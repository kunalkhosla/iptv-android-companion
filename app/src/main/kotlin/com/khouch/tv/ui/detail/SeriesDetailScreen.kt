package com.khouch.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.khouch.tv.data.model.Episode
import com.khouch.tv.data.model.PosterResponse
import com.khouch.tv.data.model.SeriesInfoResponse
import com.khouch.tv.data.model.Stream
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.common.PanelImage
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class SeriesDetailViewModel(
    private val repo: KhouchRepository,
    private val seriesId: Int,
) : ViewModel() {
    private val _poster = MutableStateFlow<PosterResponse?>(null)
    val poster = _poster.asStateFlow()
    private val _info = MutableStateFlow<SeriesInfoResponse?>(null)
    val info = _info.asStateFlow()
    private val _stills = MutableStateFlow<Map<String, String>>(emptyMap())
    val stills = _stills.asStateFlow()
    private val _activeSeason = MutableStateFlow<Int?>(null)
    val activeSeason = _activeSeason.asStateFlow()
    private val _myListOn = MutableStateFlow(repo.isInMyList("series", seriesId))
    val myListOn = _myListOn.asStateFlow()
    private val _favOn = MutableStateFlow(repo.isFavorite("series", seriesId))
    val favOn = _favOn.asStateFlow()
    // "More Like This" rails — see MovieDetailViewModel.similar.
    private val _similar = MutableStateFlow<List<com.khouch.tv.data.model.SimilarRail>>(emptyList())
    val similar = _similar.asStateFlow()

    // StateFlow rather than a plain get() property so Compose can
    // observe the index-load completion — see MovieDetailViewModel
    // for the full story.
    private val _stream = MutableStateFlow(repo.lookupStream("series", seriesId))
    val stream: StateFlow<Stream?> = _stream.asStateFlow()

    // Surface the server's userState so the episode list recomposes
    // when a played episode lands a new resume timestamp on the
    // userState.progress map.
    val userState = repo.userState
    fun progressForEpisode(episodeId: Int): com.khouch.tv.data.model.ProgressEntry? =
        repo.progressFor("series", episodeId)

    // True until the user has manually picked a season. While true,
    // load() and lastEpisode arrivals can auto-jump to the season
    // containing the last-watched episode. Once flipped, the user's
    // choice sticks even if a later play of a different season lands
    // on userState.
    private var seasonPickedByUser = false

    /**
     * Continue-watching descriptor: the last episode the user played
     * for this series + (if any) the saved resume position. Built from
     * `userState.lastEpisode[seriesId]` and the info episodes table.
     * Null until both `info` and `userState` have loaded, or when the
     * user has never played an episode of this series.
     */
    data class ContinueInfo(
        val episode: com.khouch.tv.data.model.Episode,
        val seasonNum: Int,
        val episodeIdInt: Int,
        val resumeSeconds: Double?,
    )

    fun continueInfo(): ContinueInfo? {
        val infoSnap = _info.value ?: return null
        val state = userState.value
        // Server shape: lastEpisode["<seriesId>"] = { "episode_id": "<id>" }
        val entry = state.lastEpisode[seriesId.toString()] ?: return null
        val episodeId: String = runCatching {
            val obj = entry as? kotlinx.serialization.json.JsonObject ?: return null
            val v = obj["episode_id"] ?: return null
            (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        }.getOrNull() ?: return null
        val epIdInt = episodeId.toIntOrNull()
        // Find the episode + which season it's in. Match by string id
        // OR int id — the panel has been seen to return episode ids
        // with leading zeros / extra padding in some catalogs, so a
        // strict `it.id == episodeId` lookup misses. Numeric equality
        // is the canonical comparison.
        for ((seasonKey, episodes) in infoSnap.episodes) {
            val seasonNum = seasonKey.toIntOrNull() ?: continue
            val ep = episodes.firstOrNull { e ->
                e.id == episodeId || (epIdInt != null && e.id.toIntOrNull() == epIdInt)
            } ?: continue
            val resolvedIdInt = epIdInt ?: ep.id.toIntOrNull() ?: return null
            val resume = repo.progressFor("series", resolvedIdInt)?.p?.takeIf { it > 5 }
            return ContinueInfo(
                episode = ep,
                seasonNum = seasonNum,
                episodeIdInt = resolvedIdInt,
                resumeSeconds = resume,
            )
        }
        return null
    }

    /**
     * Best-effort heuristic for "has userState been loaded from the
     * server yet". A truly fresh launch lands here with everything
     * empty; once bootstrap returns, at least one of these collections
     * will be populated for any active household. Used to defer the
     * "Play S1E1" fallback so a user with a saved lastEpisode doesn't
     * briefly see Play-from-start before Resume appears.
     */
    fun isUserStateProbablyLoaded(state: com.khouch.tv.data.model.UserState): Boolean =
        state.lastEpisode.isNotEmpty() ||
            state.progress.isNotEmpty() ||
            state.recents.values.any { it.isNotEmpty() } ||
            state.favorites.values.any { it.isNotEmpty() }

    fun load() {
        viewModelScope.launch {
            // Cheap single-item endpoint replaces the 15 MB index download.
            // See MovieDetailViewModel.load() for the same pattern.
            if (_stream.value == null) {
                _stream.value = repo.getItem("series", seriesId)
            }
            // Bootstrap if userState looks empty so continueInfo can
            // resolve lastEpisode → Resume button instead of falling
            // through to the "Play S1E1" placeholder. Mirrors the same
            // guard in MovieDetailViewModel.load(). Without this, a
            // user who opens a series detail immediately after a fresh
            // install would see Play-from-start until bootstrap landed
            // (a few hundred ms but enough to actually be clicked).
            if (!isUserStateProbablyLoaded(userState.value)) {
                runCatching { repo.bootstrap() }
            }
            runCatching { repo.poster("series", seriesId) }.onSuccess { _poster.value = it }
            runCatching { repo.seriesInfo(seriesId) }.onSuccess { i ->
                _info.value = i
                if (!seasonPickedByUser) {
                    // Auto-pick the season containing the last-watched
                    // episode, when known. Falls through to the first
                    // season if the user has never played anything in
                    // this series (or lastEpisode hasn't loaded yet —
                    // a later userState arrival will re-trigger the
                    // pick via the observer below).
                    val target = continueInfo()?.seasonNum
                        ?: i.episodes.keys.mapNotNull(String::toIntOrNull).sorted().firstOrNull()
                    if (target != null) {
                        _activeSeason.value = target
                        loadStills(target)
                    }
                }
            }
            runCatching { repo.similar("series", seriesId) }
                .onSuccess { _similar.value = it.rails }
        }
    }

    /**
     * When `userState.lastEpisode` lands AFTER `info` (the usual order
     * on a cold open — bootstrap is in-flight when info already came
     * from cache), re-pick the season once. No-op after the user picks
     * one manually.
     */
    fun ensureSeasonReflectsLastEpisode() {
        if (seasonPickedByUser) return
        val target = continueInfo()?.seasonNum ?: return
        if (_activeSeason.value != target) {
            _activeSeason.value = target
            loadStills(target)
        }
    }

    /**
     * Returns the very first episode of the series (lowest season number,
     * first episode in that season) — the target for the "Play S1E1"
     * fallback button when the user has never opened this series before.
     * Null while seriesInfo is still loading.
     */
    data class FirstEpisode(val episodeIdInt: Int, val ext: String, val seasonNum: Int, val episodeNum: String)

    fun firstEpisode(): FirstEpisode? {
        val infoSnap = _info.value ?: return null
        val firstSeasonKey = infoSnap.episodes.keys
            .mapNotNull { it.toIntOrNull()?.let { n -> n to it } }
            .sortedBy { it.first }
            .firstOrNull()?.second ?: return null
        val seasonNum = firstSeasonKey.toIntOrNull() ?: return null
        val firstEp = infoSnap.episodes[firstSeasonKey]?.firstOrNull() ?: return null
        val idInt = firstEp.id.toIntOrNull() ?: return null
        val epNum = firstEp.episodeNum
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            ?: "1"
        return FirstEpisode(idInt, firstEp.container ?: "mp4", seasonNum, epNum)
    }

    fun selectSeason(n: Int) {
        seasonPickedByUser = true
        _activeSeason.value = n
        loadStills(n)
    }

    private fun loadStills(n: Int) {
        viewModelScope.launch {
            runCatching { repo.seasonStills(seriesId, n) }
                .onSuccess { _stills.value = it.stills }
        }
    }

    fun toggleFav() = viewModelScope.launch {
        runCatching { _favOn.value = repo.toggleFavorite("series", seriesId) }
    }
    fun toggleMyList() = viewModelScope.launch {
        runCatching { _myListOn.value = repo.toggleMyList("series", seriesId) }
    }
    fun recordLastEpisode(episodeId: Int) = viewModelScope.launch {
        runCatching { repo.setLastEpisode(seriesId, episodeId) }
    }
}

@Composable
fun SeriesDetailScreen(
    seriesId: Int,
    onPlayEpisode: (episodeId: Int, ext: String, fromStart: Boolean) -> Unit,
    onPerson: (name: String) -> Unit = {},
    onOpenSimilar: (mode: String, id: Int, name: String) -> Unit = { _, _, _ -> },
    onBack: () -> Unit,
) {
    val vm: SeriesDetailViewModel = koinViewModel { parametersOf(seriesId) }
    val poster by vm.poster.collectAsState()
    val info by vm.info.collectAsState()
    val stills by vm.stills.collectAsState()
    val activeSeason by vm.activeSeason.collectAsState()
    val myListOn by vm.myListOn.collectAsState()
    val favOn by vm.favOn.collectAsState()
    val similar by vm.similar.collectAsState()
    val stream by vm.stream.collectAsState()
    val userState by vm.userState.collectAsState()
    LaunchedEffect(seriesId) { vm.load() }

    // When userState lands after info (cold open from cache before
    // bootstrap finishes), re-pick the season to match the last-watched
    // episode. No-op once the user has tapped a season chip.
    LaunchedEffect(userState, info) { vm.ensureSeasonReflectsLastEpisode() }

    val continueInfo = remember(userState, info) { vm.continueInfo() }
    val firstEp = remember(info) { vm.firstEpisode() }
    val playFocus = remember { FocusRequester() }
    // Auto-focus the Resume / Play button once info is loaded so the
    // primary action is selected by default — user can hit OK
    // immediately without D-padping around.
    LaunchedEffect(info) {
        if (info != null) runCatching { playFocus.requestFocus() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(
            containerColor = KhouchColors.Bg,
            contentColor = KhouchColors.Fg,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            BackdropLayer(poster = poster, stream = stream)

            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 64.dp,
                    vertical = 48.dp,
                ),
            ) {
                // Hero row — compact. Only poster + title/plot + action
                // buttons. TmdbExtrasRow (cast, director, keywords) and
                // MoreLikeThisSection moved OUT of the right column so
                // the hero stays a fixed short height; cast and rails
                // appear as their own full-width items further down.
                item {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .width(180.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(8.dp)),
                        ) {
                            PanelImage(
                                url = poster?.poster ?: stream?.icon,
                                fallbackText = (stream?.name ?: "?").take(2).uppercase(),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.width(28.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stream?.name ?: "Loading…", color = KhouchColors.Fg, fontSize = 32.sp)
                            Spacer(Modifier.height(8.dp))
                            val plot = poster?.plot ?: stream?.plot
                            if (!plot.isNullOrBlank()) {
                                Text(
                                    plot,
                                    color = KhouchColors.Fg,
                                    fontSize = 14.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.height(20.dp))
                            // Action row: primary action FIRST and
                            // auto-focused. If userState.lastEpisode is
                            // set we offer Resume (with timestamp if a
                            // progress entry exists); otherwise the
                            // fallback is Play S1E1 so the screen always
                            // exposes a one-tap path to start watching.
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val ci = continueInfo
                                if (ci != null) {
                                    val epNum = ci.episode.episodeNum
                                        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                                        ?: "?"
                                    val label = ci.resumeSeconds
                                        ?.let { "▸ Resume S${ci.seasonNum}E$epNum · ${com.khouch.tv.ui.common.formatPlaybackPos(it)}" }
                                        ?: "▸ Play S${ci.seasonNum}E$epNum"
                                    Button(
                                        onClick = {
                                            vm.recordLastEpisode(ci.episodeIdInt)
                                            val ext = ci.episode.container ?: "mp4"
                                            onPlayEpisode(ci.episodeIdInt, ext, ci.resumeSeconds == null)
                                        },
                                        colors = ButtonDefaults.colors(
                                            containerColor = KhouchColors.Accent,
                                            contentColor = Color.White,
                                        ),
                                        modifier = Modifier.focusRequester(playFocus),
                                    ) {
                                        Text(
                                            label,
                                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                                        )
                                    }
                                    // If resume is set, also offer Start over
                                    if (ci.resumeSeconds != null) {
                                        Button(
                                            onClick = {
                                                vm.recordLastEpisode(ci.episodeIdInt)
                                                val ext = ci.episode.container ?: "mp4"
                                                onPlayEpisode(ci.episodeIdInt, ext, true)
                                            },
                                            colors = ButtonDefaults.colors(
                                                containerColor = KhouchColors.Bg2,
                                                contentColor = KhouchColors.Fg,
                                            ),
                                        ) {
                                            Text(
                                                "Start over",
                                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                            )
                                        }
                                    }
                                } else if (firstEp != null && vm.isUserStateProbablyLoaded(userState)) {
                                    // Never played AND userState confirmed loaded →
                                    // safe to show "Play S1E1" fallback. Without
                                    // the userState gate, a user with a real
                                    // saved lastEpisode could briefly see (and
                                    // click) Play-from-start before bootstrap
                                    // finished and Resume appeared.
                                    Button(
                                        onClick = {
                                            vm.recordLastEpisode(firstEp.episodeIdInt)
                                            onPlayEpisode(firstEp.episodeIdInt, firstEp.ext, true)
                                        },
                                        colors = ButtonDefaults.colors(
                                            containerColor = KhouchColors.Accent,
                                            contentColor = Color.White,
                                        ),
                                        modifier = Modifier.focusRequester(playFocus),
                                    ) {
                                        Text(
                                            "▸ Play S${firstEp.seasonNum}E${firstEp.episodeNum}",
                                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                                        )
                                    }
                                } else {
                                    // info still loading — disabled placeholder
                                    Button(
                                        onClick = {},
                                        colors = ButtonDefaults.colors(
                                            containerColor = KhouchColors.Bg2,
                                            contentColor = KhouchColors.FgDim,
                                        ),
                                        modifier = Modifier.focusRequester(playFocus),
                                    ) {
                                        Text(
                                            "Loading…",
                                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                                        )
                                    }
                                }
                                Button(
                                    onClick = { vm.toggleMyList() },
                                    colors = ButtonDefaults.colors(
                                        containerColor = KhouchColors.Bg2,
                                        contentColor = KhouchColors.Fg,
                                    ),
                                ) {
                                    Text(
                                        if (myListOn) "✓ My List" else "+ My List",
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                    )
                                }
                                Button(
                                    onClick = { vm.toggleFav() },
                                    colors = ButtonDefaults.colors(
                                        containerColor = KhouchColors.Bg2,
                                        contentColor = if (favOn) KhouchColors.Accent else KhouchColors.Fg,
                                    ),
                                ) {
                                    Text(
                                        if (favOn) "★ Favorited" else "☆ Favorite",
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                    )
                                }
                                Button(
                                    onClick = onBack,
                                    colors = ButtonDefaults.colors(
                                        containerColor = KhouchColors.Bg2,
                                        contentColor = KhouchColors.FgDim,
                                    ),
                                ) {
                                    Text("Back", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }

                // Season picker
                item {
                    val seasonNums = info?.episodes?.keys?.mapNotNull(String::toIntOrNull)?.sorted().orEmpty()
                    if (seasonNums.size > 1) {
                        Text("Seasons", color = KhouchColors.FgDim, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(seasonNums, key = { it }) { n ->
                                val isActive = n == activeSeason
                                Card(
                                    onClick = { vm.selectSeason(n) },
                                    colors = CardDefaults.colors(
                                        containerColor = if (isActive) KhouchColors.Accent else KhouchColors.Bg2,
                                        contentColor = if (isActive) Color.White else KhouchColors.Fg,
                                        focusedContainerColor = KhouchColors.Accent,
                                        focusedContentColor = Color.White,
                                    ),
                                ) {
                                    Text(
                                        "Season $n",
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // Episode list for the active season
                val episodes = activeSeason
                    ?.let { info?.episodes?.get(it.toString()).orEmpty() }
                    .orEmpty()
                items(episodes, key = { it.id }) { ep ->
                    val epIdInt = ep.id.toIntOrNull()
                    val prog = remember(userState, ep.id) {
                        epIdInt?.let { vm.progressForEpisode(it) }
                    }
                    EpisodeRow(
                        episode = ep,
                        stillUrl = stills[ep.id],
                        resumeSeconds = prog?.p?.takeIf { it > 5 },
                        onPlay = {
                            val id = epIdInt ?: return@EpisodeRow
                            vm.recordLastEpisode(id)
                            val ext = ep.container ?: "mp4"
                            // Default action resumes if there's a saved
                            // position, otherwise plays from start.
                            onPlayEpisode(id, ext, prog == null || prog.p <= 5)
                        },
                        onStartOver = {
                            val id = epIdInt ?: return@EpisodeRow
                            vm.recordLastEpisode(id)
                            val ext = ep.container ?: "mp4"
                            onPlayEpisode(id, ext, true)
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Cast + director + tagline + keywords. Used to live
                // inside the hero row's right column which made the
                // column unreasonably tall; now full-width below the
                // episodes so cast cards can spread out naturally and
                // scroll horizontally without competing for hero space.
                if (poster != null) {
                    item {
                        Spacer(Modifier.height(32.dp))
                        TmdbExtrasRow(mode = "series", poster = poster, onPerson = onPerson)
                    }
                }

                if (similar.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(32.dp))
                        MoreLikeThisSection(
                            mode = "series",
                            rails = similar,
                            onItemClick = onOpenSimilar,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    stillUrl: String?,
    resumeSeconds: Double?,
    onPlay: () -> Unit,
    onStartOver: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            onClick = onPlay,
            colors = CardDefaults.colors(
                containerColor = KhouchColors.Bg2,
                contentColor = KhouchColors.Fg,
                focusedContainerColor = KhouchColors.Bg3,
            ),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(4.dp)),
                ) {
                    if (!stillUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = stillUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().background(KhouchColors.Bg3),
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize().background(KhouchColors.Bg3),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Ep ${episode.episodeNum?.jsonPrimitive?.contentOrNull ?: "?"}",
                                color = KhouchColors.FgDim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${episode.episodeNum?.jsonPrimitive?.contentOrNull ?: "?"} · ${episode.title ?: "Untitled"}",
                        color = KhouchColors.Fg,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (resumeSeconds != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "▸ Resume at ${com.khouch.tv.ui.common.formatPlaybackPos(resumeSeconds)}",
                            color = KhouchColors.Accent,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        if (resumeSeconds != null) {
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onStartOver,
                colors = ButtonDefaults.colors(
                    containerColor = KhouchColors.Bg2,
                    contentColor = KhouchColors.Fg,
                ),
            ) {
                Text(
                    "Start over",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                )
            }
        }
    }
}
