package com.khouch.core.data.repo

import com.khouch.core.data.api.ApiFactory
import com.khouch.core.data.api.KhouchApi
import com.khouch.core.data.auth.PersistentCookieJar
import com.khouch.core.data.model.BootstrapResponse
import com.khouch.core.data.model.Category
import com.khouch.core.data.model.EpgResponse
import com.khouch.core.data.model.IndexResponse
import com.khouch.core.data.model.LoginRequest
import com.khouch.core.data.model.LoginResponse
import com.khouch.core.data.model.MovieInfoResponse
import com.khouch.core.data.model.OkResponse
import com.khouch.core.data.model.PosterResponse
import com.khouch.core.data.model.Profile
import com.khouch.core.data.model.ProfilesResponse
import com.khouch.core.data.model.ProgressUpdate
import com.khouch.core.data.model.SelectProfileRequest
import com.khouch.core.data.model.SeriesInfoResponse
import com.khouch.core.data.model.StillsResponse
import com.khouch.core.data.model.Stream
import com.khouch.core.data.model.StreamUrls
import com.khouch.core.data.model.UserState
import com.khouch.core.data.model.UserStatePatch
import com.khouch.core.data.prefs.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KhouchRepository(
    private val apiFactory: ApiFactory,
    private val cookieJar: PersistentCookieJar,
    private val userPrefs: UserPrefs,
) {

    private suspend fun api(): KhouchApi = apiFactory.api(userPrefs.serverUrlOnce())

    // Process-lifetime scope for fire-and-forget writes (progress,
    // recents) that need to outlive the ViewModel that triggered
    // them. Otherwise BACK from the player cancels the in-flight
    // POST /api/progress before it lands and the resume position
    // is lost.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- In-memory catalog state (loaded lazily, shared across screens) ---

    private val _categories = MutableStateFlow<Map<String, List<Category>>>(emptyMap())
    val categories: StateFlow<Map<String, List<Category>>> = _categories.asStateFlow()

    private val _streams = MutableStateFlow<Map<String, List<Stream>>>(emptyMap())
    val streams: StateFlow<Map<String, List<Stream>>> = _streams.asStateFlow()

    // Per-mode {streamId → Stream} lookup, derived from streams map.
    private val _byId = MutableStateFlow<Map<String, Map<Int, Stream>>>(emptyMap())
    val byId: StateFlow<Map<String, Map<Int, Stream>>> = _byId.asStateFlow()

    // Per-mode {category_id → List<Stream>} bucket. Built once when
    // the index loads so home rails don't have to scan all 58k movies
    // every time the user toggles a favorite. Without this the rebuild
    // cost is O(categories × streams) on every userState change, which
    // is what was making the TV browsing feel choppy.
    private val _byCategory = MutableStateFlow<Map<String, Map<String, List<Stream>>>>(emptyMap())
    val byCategory: StateFlow<Map<String, Map<String, List<Stream>>>> = _byCategory.asStateFlow()

    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    private val _userState = MutableStateFlow(UserState())
    val userState: StateFlow<UserState> = _userState.asStateFlow()

    // Server-supplied chip catalog + kids-cert thresholds. Null until
    // bootstrap lands; null on older servers that don't emit the field.
    // UI code prefers this when present, falls back to the hardcoded
    // tables in GroupFilters.kt / KidsFilter.kt otherwise — so the app
    // keeps working even when the deployed server is older than the APK.
    private val _filterConfig = MutableStateFlow<com.khouch.core.data.model.FilterConfig?>(null)
    val filterConfig: StateFlow<com.khouch.core.data.model.FilterConfig?> = _filterConfig.asStateFlow()

    // --- Auth ---

    suspend fun probeHealth() = api().health()

    suspend fun login(user: String, pass: String): LoginResponse =
        api().login(LoginRequest(user, pass))

    suspend fun logout(): OkResponse = api().logout().also {
        cookieJar.clear()
        _categories.value = emptyMap()
        _streams.value = emptyMap()
        _byId.value = emptyMap()
        _profile.value = null
        _userState.value = UserState()
        _filterConfig.value = null
    }

    suspend fun listProfiles(): ProfilesResponse = api().profiles()

    suspend fun selectProfile(id: String): OkResponse {
        val r = api().selectProfile(SelectProfileRequest(id))
        // Profile cookie just changed on the server. Drop every piece of
        // in-memory state from the previous profile (favorites, recents,
        // progress, byCategory snapshot, …) and re-fetch bootstrap so
        // the next render reflects the new profile. Without this the
        // ViewModel layer would keep serving the previous profile's
        // "Continue Watching" after switching to a kids profile, even
        // though the cookie did switch correctly.
        _categories.value = emptyMap()
        _streams.value = emptyMap()
        _byId.value = emptyMap()
        _byCategory.value = emptyMap()
        _profile.value = null
        _userState.value = UserState()
        _filterConfig.value = null
        runCatching { bootstrap() }
        return r
    }

    // --- Catalog hydration ---

    suspend fun bootstrap(): BootstrapResponse {
        val b = api().bootstrap()
        _categories.value = b.categories
        _profile.value = b.profile
        b.userState?.let { _userState.value = it }
        _filterConfig.value = b.filterConfig
        return b
    }

    // Fetch the server-prebuilt home payload (rails + hero, with
    // pre-sized TMDB poster URLs inline). Cheap — ~180 KB vs 19.7 MB
    // for the equivalent client-side rail computation.
    suspend fun home(mode: String): com.khouch.core.data.model.HomeResponse =
        api().home(mode)

    suspend fun loadIndex(mode: String): IndexResponse {
        val r = api().modeIndex(mode)
        // Update all three derived structures atomically. Each is
        // O(N) over the streams once; downstream consumers do O(1)
        // lookups thereafter.
        val byIdMap = HashMap<Int, Stream>(r.streams.size)
        val byCatMap = HashMap<String, ArrayList<Stream>>()
        for (s in r.streams) {
            byIdMap[s.id] = s
            val cid = s.categoryId.orEmpty()
            byCatMap.getOrPut(cid) { ArrayList() }.add(s)
        }
        _streams.value = _streams.value + (mode to r.streams)
        _byId.value = _byId.value + (mode to byIdMap)
        _byCategory.value = _byCategory.value + (mode to byCatMap)
        return r
    }

    fun streamsForMode(mode: String): List<Stream> = _streams.value[mode].orEmpty()

    fun lookupStream(mode: String, id: Int): Stream? = _byId.value[mode]?.get(id)

    fun categoriesForMode(mode: String): List<Category> = _categories.value[mode].orEmpty()

    // --- Detail ---

    suspend fun movieInfo(id: Int): MovieInfoResponse =
        api().movieOrSeriesInfo("movie", id)

    suspend fun streamsByCategory(mode: String, categoryId: String): List<Stream> =
        api().streamsByCategory(mode, categoryId)

    suspend fun seriesInfo(id: Int): SeriesInfoResponse = api().seriesInfo(id)

    // "More from <actor/director>" — resolves a name to TMDB
    // person, returns their filmography intersected with our local
    // catalog. Powers the cast-card click and director-link flows
    // on detail screens.
    suspend fun personCredits(name: String) =
        api().personCredits(name)

    // "More Like This" rails for a detail screen. Server returns the
    // fully-built rail list (ordered, filtered, capped). UI iterates
    // rails[] without any local logic.
    suspend fun similar(mode: String, id: Int) =
        api().similar(mode, id)

    // Source-side full duration in seconds. Used by the player to
    // render a scrubber spanning the entire movie even when the
    // server is still transcoding (HLS playlist would otherwise
    // report a much shorter duration). Returns null when the panel
    // didn't supply duration_secs.
    suspend fun movieDurationSecs(id: Int): Int? = runCatching {
        val resp = api().movieOrSeriesInfo("movie", id)
        val info = resp.info as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
        info["duration_secs"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        }
    }.getOrNull()

    // --- Stream URLs ---

    suspend fun streamUrls(mode: String, id: Int, ext: String = "m3u8"): StreamUrls {
        val raw = api().streamUrls(mode, id, ext)
        val base = userPrefs.serverUrlOnce().trimEnd('/')
        fun abs(u: String?) = when {
            u.isNullOrBlank() -> null
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("/") -> "$base$u"
            else -> "$base/$u"
        }
        return StreamUrls(
            direct = abs(raw.direct),
            proxy = abs(raw.proxy),
            transcode = abs(raw.transcode),
            download = abs(raw.download),
        )
    }

    // --- TMDB ---

    suspend fun poster(mode: String, id: Int): PosterResponse = api().poster(mode, id)

    suspend fun seasonStills(seriesId: Int, seasonNum: Int): StillsResponse =
        api().seasonStills(seriesId, seasonNum)

    // --- EPG ---

    suspend fun epgShort(streamId: Int): EpgResponse = api().epgShort(streamId)

    // --- Search ---

    suspend fun searchAll(q: String, limit: Int = 30): com.khouch.core.data.model.SearchAllResponse =
        api().searchAll(q, limit)

    suspend fun search(mode: String, q: String): List<Stream> {
        // Server returns { q, count, results } — pull the `results`
        // array out of the envelope. Declaring JsonArray on the
        // Retrofit interface failed deserialization silently and made
        // every search return zero matches.
        val env = api().search(mode, q)
        val arr: JsonArray = env.jsonObject["results"]?.jsonArray
            ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                Stream(
                    id = o["id"]?.jsonPrimitive?.intOrNull
                        ?: o["stream_id"]?.jsonPrimitive?.intOrNull
                        ?: o["series_id"]?.jsonPrimitive?.intOrNull
                        ?: return@runCatching null,
                    name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    icon = o["icon"]?.jsonPrimitive?.contentOrNull
                        ?: o["stream_icon"]?.jsonPrimitive?.contentOrNull
                        ?: o["cover"]?.jsonPrimitive?.contentOrNull,
                    categoryId = o["category_id"]?.jsonPrimitive?.contentOrNull,
                    year = o["year"]?.jsonPrimitive?.contentOrNull,
                    rating = o["rating"]?.jsonPrimitive?.contentOrNull,
                )
            }.getOrNull()
        }
    }

    // --- Playback events ---

    suspend fun playEvent(mode: String, id: Int): OkResponse = api().playEvent(mode, id)

    suspend fun postProgress(mode: String, id: Int, position: Double, duration: Double): OkResponse =
        api().progress(mode, id, ProgressUpdate(position, duration))

    // Fire-and-forget variant: launches on the process-lifetime
    // scope so a navigating-away caller doesn't cancel the request.
    // Also writes the entry into the local userState so the Resume
    // button updates immediately without waiting for the round-trip.
    fun postProgressDetached(mode: String, id: Int, position: Double, duration: Double) {
        if (mode == "live" || position < 5) return
        // Local mirror so the UI sees the new resume timestamp on next
        // composition without waiting for the server round-trip.
        val cur = _userState.value
        val nextProgress = cur.progress.toMutableMap().apply {
            this["$mode:$id"] = kotlinx.serialization.json.JsonObject(
                mapOf(
                    "p" to kotlinx.serialization.json.JsonPrimitive(position),
                    "d" to kotlinx.serialization.json.JsonPrimitive(duration),
                    "t" to kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()),
                )
            )
        }
        _userState.value = cur.copy(progress = nextProgress)
        ioScope.launch {
            runCatching { api().progress(mode, id, ProgressUpdate(position, duration)) }
        }
    }

    // --- User-state mutations ---

    suspend fun toggleFavorite(mode: String, id: Int): Boolean {
        val cur = _userState.value
        val list = cur.favorites[mode].orEmpty()
        val nextList = if (id in list) list - id else list + id
        val nextFavs = cur.favorites.toMutableMap().apply { this[mode] = nextList }
        _userState.value = cur.copy(favorites = nextFavs)
        api().putUserState(UserStatePatch(favorites = nextFavs))
        return id in nextList
    }

    suspend fun toggleMyList(mode: String, id: Int): Boolean {
        val cur = _userState.value
        val list = cur.myList[mode].orEmpty()
        val nextList = if (id in list) list - id else list + id
        val nextMyList = cur.myList.toMutableMap().apply { this[mode] = nextList }
        _userState.value = cur.copy(myList = nextMyList)
        api().putUserState(UserStatePatch(myList = nextMyList))
        return id in nextList
    }

    suspend fun setTheme(theme: String) {
        _userState.value = _userState.value.copy(theme = theme)
        api().putUserState(UserStatePatch(theme = theme))
    }

    // Push an id onto the front of userState.recents[mode] (capped at
    // 100, deduped). Mirrors what the web client does inside its
    // `pushRecent(mode, id)` helper so playing on the TV surfaces in
    // the "Recently Played" rail on every other device for this
    // profile. Without this, only `lastPlayed` (the timestamp map)
    // moves; the recents list itself stays stale.
    suspend fun pushRecent(mode: String, id: Int) {
        val cur = _userState.value
        val list = cur.recents[mode].orEmpty()
        val deduped = (listOf(id) + list.filter { it != id }).take(100)
        val nextRecents = cur.recents.toMutableMap().apply { this[mode] = deduped }
        _userState.value = cur.copy(recents = nextRecents)
        api().putUserState(UserStatePatch(recents = nextRecents))
    }

    suspend fun setLastEpisode(seriesId: Int, episodeId: Int) {
        val cur = _userState.value
        // Match the server's shape: `{ episode_id, season }` per series.
        val entry: kotlinx.serialization.json.JsonElement =
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "episode_id" to kotlinx.serialization.json.JsonPrimitive(episodeId.toString()),
                )
            )
        val nextLE = cur.lastEpisode.toMutableMap().apply { this[seriesId.toString()] = entry }
        _userState.value = cur.copy(lastEpisode = nextLE)
        api().putUserState(UserStatePatch(lastEpisode = nextLE))
    }

    fun isFavorite(mode: String, id: Int): Boolean =
        _userState.value.favorites[mode]?.contains(id) == true

    fun isInMyList(mode: String, id: Int): Boolean =
        _userState.value.myList[mode]?.contains(id) == true

    fun progressFor(mode: String, id: Int): com.khouch.core.data.model.ProgressEntry? {
        val raw = _userState.value.progress["$mode:$id"] ?: return null
        return runCatching {
            val o = raw.jsonObject
            com.khouch.core.data.model.ProgressEntry(
                p = o["p"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                d = o["d"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                t = o["t"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        }.getOrNull()
    }

    fun progressTimestamp(mode: String, id: Int): Long =
        progressFor(mode, id)?.t ?: 0L

    // --- Misc ---

    fun hasSession(): Boolean = cookieJar.hasSession()
    fun hasProfile(): Boolean = cookieJar.hasProfile()

    suspend fun currentServerUrl(): String = userPrefs.serverUrlOnce()

    suspend fun setServerUrl(url: String) = userPrefs.setServerUrl(url)
}
