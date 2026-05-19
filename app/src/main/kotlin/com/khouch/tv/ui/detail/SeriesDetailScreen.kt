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

    val stream: Stream? get() = repo.lookupStream("series", seriesId)

    fun load() {
        viewModelScope.launch {
            // Same as MovieDetailViewModel — index must be loaded
            // before lookupStream returns non-null, otherwise the
            // title stays "Loading…".
            if (repo.lookupStream("series", seriesId) == null) {
                runCatching { repo.loadIndex("series") }
            }
            runCatching { repo.poster("series", seriesId) }.onSuccess { _poster.value = it }
            runCatching { repo.seriesInfo(seriesId) }.onSuccess {
                _info.value = it
                if (_activeSeason.value == null) {
                    val firstSeason = it.episodes.keys.mapNotNull(String::toIntOrNull).sorted().firstOrNull()
                    _activeSeason.value = firstSeason
                    firstSeason?.let { n -> loadStills(n) }
                }
            }
            runCatching { repo.similar("series", seriesId) }
                .onSuccess { _similar.value = it.rails }
        }
    }

    fun selectSeason(n: Int) {
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
    onPlayEpisode: (episodeId: Int, ext: String) -> Unit,
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
    val stream = vm.stream
    LaunchedEffect(seriesId) { vm.load() }

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
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(220.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(8.dp)),
                        ) {
                            PanelImage(
                                url = poster?.poster ?: stream?.icon,
                                fallbackText = (stream?.name ?: "?").take(2).uppercase(),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.width(32.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stream?.name ?: "Loading…", color = KhouchColors.Fg, fontSize = 36.sp)
                            Spacer(Modifier.height(8.dp))
                            val plot = poster?.plot ?: stream?.plot
                            if (!plot.isNullOrBlank()) {
                                Text(
                                    plot,
                                    color = KhouchColors.Fg,
                                    fontSize = 14.sp,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(720.dp),
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { vm.toggleMyList() },
                                    colors = ButtonDefaults.colors(
                                        containerColor = KhouchColors.Bg2,
                                        contentColor = KhouchColors.Fg,
                                    ),
                                ) {
                                    Text(
                                        if (myListOn) "✓ My List" else "+ My List",
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
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
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                    )
                                }
                                Button(
                                    onClick = onBack,
                                    colors = ButtonDefaults.colors(
                                        containerColor = KhouchColors.Bg2,
                                        contentColor = KhouchColors.FgDim,
                                    ),
                                ) {
                                    Text("Back", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                            // Same as MovieDetailScreen: TMDB enrichment
                            // below the action row so Play stays above
                            // the fold.
                            TmdbExtrasRow(mode = "series", poster = poster, onPerson = onPerson)
                            if (similar.isNotEmpty()) {
                                Spacer(Modifier.height(24.dp))
                                MoreLikeThisSection(
                                    mode = "series",
                                    rails = similar,
                                    onItemClick = onOpenSimilar,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
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
                    EpisodeRow(
                        episode = ep,
                        stillUrl = stills[ep.id],
                        onPlay = {
                            vm.recordLastEpisode(ep.id.toIntOrNull() ?: 0)
                            val ext = ep.container ?: "mp4"
                            onPlayEpisode(ep.id.toIntOrNull() ?: return@EpisodeRow, ext)
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, stillUrl: String?, onPlay: () -> Unit) {
    Card(
        onClick = onPlay,
        colors = CardDefaults.colors(
            containerColor = KhouchColors.Bg2,
            contentColor = KhouchColors.Fg,
            focusedContainerColor = KhouchColors.Bg3,
        ),
        modifier = Modifier.fillMaxWidth(),
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
            }
        }
    }
}
