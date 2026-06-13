package com.khouch.tv.data.repo

import com.khouch.tv.data.api.ApiFactory
import com.khouch.tv.data.api.KhouchApi
import com.khouch.tv.data.auth.PersistentCookieJar
import com.khouch.tv.data.model.BootstrapResponse
import com.khouch.tv.data.model.Category
import com.khouch.tv.data.model.EpgResponse
import com.khouch.tv.data.model.IndexResponse
import com.khouch.tv.data.model.LoginRequest
import com.khouch.tv.data.model.LoginResponse
import com.khouch.tv.data.model.MovieInfoResponse
import com.khouch.tv.data.model.OkResponse
import com.khouch.tv.data.model.PosterResponse
import com.khouch.tv.data.model.Profile
import com.khouch.tv.data.model.ProfilesResponse
import com.khouch.tv.data.model.ProgressUpdate
import com.khouch.tv.data.model.SelectProfileRequest
import com.khouch.tv.data.model.SeriesInfoResponse
import com.khouch.tv.data.model.StillsResponse
import com.khouch.tv.data.model.Stream
import com.khouch.tv.data.model.StreamUrls
import com.khouch.tv.data.model.UserState
import com.khouch.tv.data.model.UserStatePatch
import com.khouch.tv.data.prefs.UserPrefs
import java.util.concurrent.ConcurrentHashMap
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

    // --- Response caches (survive navigation, cleared on profile switch / logout) ---

    private data class CacheEntry<T>(val value: T, val ts: Long = System.currentTimeMillis())

    private val HOME_TTL = 5 * 60 * 1000L
    private val DETAIL_TTL = 10 * 60 * 1000L

    private val homeCache = ConcurrentHashMap<String, CacheEntry<com.khouch.tv.data.model.HomeResponse>>()
    private val posterCache = ConcurrentHashMap<String, CacheEntry<com.khouch.tv.data.model.PosterResponse>>()
    private val similarCache = ConcurrentHashMap<String, CacheEntry<com.khouch.tv.data.model.SimilarResponse>>()
    private val seriesInfoCache = ConcurrentHashMap<Int, CacheEntry<com.khouch.tv.data.model.SeriesInfoResponse>>()
    private val itemCache = ConcurrentHashMap<String, CacheEntry<Stream>>()

    private fun clearResponseCaches() {
        homeCache.clear()
        posterCache.clear()
        similarCache.clear()
        seriesInfoCache.clear()
        itemCache.clear()
    }

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
    private val _filterConfig = MutableStateFlow<com.khouch.tv.data.model.FilterConfig?>(null)
    val filterConfig: StateFlow<com.khouch.tv.data.model.FilterConfig?> = _filterConfig.asStateFlow()

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
        clearResponseCaches()
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
        clearResponseCaches()
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

    /**
     * Fire-and-forget bootstrap on the repo's process-lifetime ioScope.
     * Lets the Splash screen kick off userState/profile/categories
     * hydration without blocking the navigation to Main. Survives the
     * caller's composition / lifecycle going away.
     */
    fun bootstrapAsync() {
        ioScope.launch { runCatching { bootstrap() } }
    }

    // Fetch the server-prebuilt home payload (rails + hero, with
    // pre-sized TMDB poster URLs inline). Cached 5 min so navigating
    // Movies → detail → back doesn't re-fetch the whole rail set.
    suspend fun home(mode: String): com.khouch.tv.data.model.HomeResponse {
        homeCache[mode]?.let { if (System.currentTimeMillis() - it.ts < HOME_TTL) return it.value }
        val r = api().home(mode)
        homeCache[mode] = CacheEntry(r)
        return r
    }

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

    // Single-item lookup. Backed by the new /api/{mode}/item/{id} endpoint
    // (cheap, ~200 B) so the detail screen no longer has to download the
    // 15 MB movie index just to seed a title. Live mode still consults
    // the loaded index first since the live index is small (~5-8 MB) and
    // already in memory whenever the Guide / Live Browse is open.
    suspend fun getItem(mode: String, id: Int): Stream? {
        _byId.value[mode]?.get(id)?.let { return it }
        val key = "$mode:$id"
        itemCache[key]?.let { if (System.currentTimeMillis() - it.ts < DETAIL_TTL) return it.value }
        return runCatching { api().item(mode, id) }
            .onSuccess { itemCache[key] = CacheEntry(it) }
            .getOrNull()
    }

    fun categoriesForMode(mode: String): List<Category> = _categories.value[mode].orEmpty()

    // --- Detail ---

    suspend fun movieInfo(id: Int): MovieInfoResponse =
        api().movieOrSeriesInfo("movie", id)

    suspend fun seriesInfo(id: Int): SeriesInfoResponse {
        seriesInfoCache[id]?.let { if (System.currentTimeMillis() - it.ts < DETAIL_TTL) return it.value }
        val r = api().seriesInfo(id)
        seriesInfoCache[id] = CacheEntry(r)
        return r
    }

    // Extract the full source-side duration in seconds from a
    // movie's info response. Used by the player to render a
    // progress bar that represents the entire movie, not just the
    // already-transcoded HLS playlist (which grows over time and
    // would otherwise look like "the movie is 14 minutes long").
    // Returns null when the panel didn't provide a duration_secs
    // field (some catalogs omit it; live has no concept).
    suspend fun movieDurationSecs(id: Int): Int? = runCatching {
        val resp = api().movieOrSeriesInfo("movie", id)
        val info = resp.info as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
        info["duration_secs"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
        }
    }.getOrNull()

    // --- Stream URLs ---

    suspend fun streamUrls(mode: String, id: Int, ext: String = "m3u8", anchorSecs: Int = 0): StreamUrls {
        // Per-device audio mode — UserPrefs default is "surround" so AVR
        // owners get 5.1 by default; Settings has a toggle to flip back.
        // Live mode skips this: the live transcoder is only invoked
        // for codec-incompatibility fallback and surround on a live
        // sports feed has no benefit (panel audio is rarely > stereo).
        val audio = if (mode == "live") null else userPrefs.audioModeOnce().takeIf { it == "surround" }
        val raw = api().streamUrls(mode, id, ext, anchorSecs.takeIf { it > 0 }, audio)
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
        )
    }

    // --- TMDB ---

    suspend fun poster(mode: String, id: Int): PosterResponse {
        val key = "$mode:$id"
        posterCache[key]?.let { if (System.currentTimeMillis() - it.ts < DETAIL_TTL) return it.value }
        val r = api().poster(mode, id)
        posterCache[key] = CacheEntry(r)
        return r
    }

    // "More from <actor/director>" — see KhouchApi.personCredits for
    // the wire shape. Surfaces the person's catalog filmography.
    suspend fun personCredits(name: String) = api().personCredits(name)

    // "More Like This" rails for a detail screen. Server returns the
    // fully-built rail list (ordered, filtered, capped). UI iterates
    // rails[] without any local logic.
    suspend fun similar(mode: String, id: Int): com.khouch.tv.data.model.SimilarResponse {
        val key = "$mode:$id"
        similarCache[key]?.let { if (System.currentTimeMillis() - it.ts < DETAIL_TTL) return it.value }
        val r = api().similar(mode, id)
        similarCache[key] = CacheEntry(r)
        return r
    }

    suspend fun seasonStills(seriesId: Int, seasonNum: Int): StillsResponse =
        api().seasonStills(seriesId, seasonNum)

    // --- EPG ---

    suspend fun epgShort(streamId: Int): EpgResponse = api().epgShort(streamId)

    // --- Search ---

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
        homeCache.remove(mode)
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
        val idL = id.toLong()
        val cur = _userState.value
        val list = cur.favorites[mode].orEmpty()
        val nextList = if (idL in list) list - idL else list + idL
        val nextFavs = cur.favorites.toMutableMap().apply { this[mode] = nextList }
        _userState.value = cur.copy(favorites = nextFavs)
        homeCache.remove(mode)
        api().putUserState(UserStatePatch(favorites = nextFavs))
        return idL in nextList
    }

    suspend fun toggleMyList(mode: String, id: Int): Boolean {
        val idL = id.toLong()
        val cur = _userState.value
        val list = cur.myList[mode].orEmpty()
        val nextList = if (idL in list) list - idL else list + idL
        val nextMyList = cur.myList.toMutableMap().apply { this[mode] = nextList }
        _userState.value = cur.copy(myList = nextMyList)
        homeCache.remove(mode)
        api().putUserState(UserStatePatch(myList = nextMyList))
        return idL in nextList
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
        val idL = id.toLong()
        val cur = _userState.value
        val list = cur.recents[mode].orEmpty()
        val deduped = (listOf(idL) + list.filter { it != idL }).take(100)
        val nextRecents = cur.recents.toMutableMap().apply { this[mode] = deduped }
        _userState.value = cur.copy(recents = nextRecents)
        homeCache.remove(mode)
        api().putUserState(UserStatePatch(recents = nextRecents))
    }

    /**
     * Reset every mode's Continue Watching rail for this profile (#48).
     * Hits the new per-mode DELETE endpoint which wipes BOTH the
     * recents list AND every progress entry in that mode — same
     * thing the per-item ✕ does on the web. Server is the source of
     * truth; we mirror the empty state locally so the next render
     * doesn't briefly show stale tiles.
     */
    suspend fun clearAllRecents() {
        val empty = mapOf("live" to emptyList<Long>(), "movie" to emptyList(), "series" to emptyList())
        _userState.value = _userState.value.copy(
            recents = empty,
            progress = emptyMap(),
        )
        homeCache.clear()
        // Per-mode loop because the server endpoint is scoped to a
        // single mode (lets a future "clear just Movies" UI hit it
        // directly). Three round-trips is fine — same total bytes as
        // the old PUT.
        for (mode in listOf("live", "movie", "series")) {
            runCatching { api().deleteRecentsForMode(mode) }
        }
    }

    /**
     * Remove a single title from the merged Continue Watching rail
     * (#48). Wipes both the recents entry and the saved playback
     * position in one round-trip; the rail re-renders without the
     * deleted item on the next /api/home call.
     */
    suspend fun removeRecent(mode: String, id: Int) {
        val idL = id.toLong()
        val cur = _userState.value
        val nextRecents = cur.recents.toMutableMap().apply {
            this[mode] = (this[mode].orEmpty()).filter { it != idL }
        }
        val nextProgress = cur.progress.toMutableMap().apply { remove("$mode:$id") }
        _userState.value = cur.copy(recents = nextRecents, progress = nextProgress)
        homeCache.remove(mode)
        runCatching { api().deleteRecentItem(mode, id) }
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
        _userState.value.favorites[mode]?.contains(id.toLong()) == true

    fun isInMyList(mode: String, id: Int): Boolean =
        _userState.value.myList[mode]?.contains(id.toLong()) == true

    fun progressFor(mode: String, id: Int): com.khouch.tv.data.model.ProgressEntry? {
        val raw = _userState.value.progress["$mode:$id"] ?: return null
        return runCatching {
            val o = raw.jsonObject
            com.khouch.tv.data.model.ProgressEntry(
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
