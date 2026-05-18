package com.khouch.phone.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.khouch.core.data.model.Episode
import com.khouch.core.data.model.PosterResponse
import com.khouch.core.data.model.SeriesInfoResponse
import com.khouch.core.data.model.Stream
import com.khouch.core.data.repo.KhouchRepository
import com.khouch.phone.ui.common.CertBadge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class SeriesDetailViewModel(
    private val repo: KhouchRepository,
    private val downloads: com.khouch.core.data.downloads.DownloadsRepo,
    private val seriesId: Int,
) : ViewModel() {

    val downloadItems = downloads.items

    fun isEpisodeDownloaded(episodeId: Int): com.khouch.core.data.downloads.DownloadEntry.Status? =
        downloads.entryFor("series", episodeId)?.status

    fun enqueueEpisode(episode: Episode, season: Int?) = viewModelScope.launch {
        val epIdInt = episode.id.toIntOrNull() ?: return@launch
        // Always mp4 — server transcodes to 720p H.264 + AAC in MP4
        // regardless of the source episode's container (mkv etc.).
        val urls = runCatching { repo.streamUrls("series", epIdInt, "mp4") }.getOrNull() ?: return@launch
        val sourceUrl = urls.download
            ?: urls.proxy
            ?: urls.direct
            ?: return@launch
        val showName = stream?.name ?: "Series $seriesId"
        val epNum = episode.episodeNum?.let { runCatching {
            it.jsonPrimitive.contentOrNull
        }.getOrNull() } ?: ""
        val label = if (episode.title.isNullOrBlank())
            "$showName · S${season ?: "?"}E$epNum"
        else "$showName · $epNum · ${episode.title}"
        downloads.enqueue(
            mode = "series",
            id = epIdInt,
            name = label,
            sourceUrl = sourceUrl,
            ext = "mp4",
            poster = _poster.value?.poster,
            seriesId = seriesId,
            seasonNum = season,
            episodeNum = epNum,
        )
    }

    fun enqueueSeason(season: Int) = viewModelScope.launch {
        val eps = _info.value?.episodes?.get(season.toString()).orEmpty()
        for (ep in eps) {
            if (downloads.entryFor("series", ep.id.toIntOrNull() ?: continue) != null) continue
            enqueueEpisode(ep, season)
        }
    }

    fun deleteEpisode(episodeId: Int) = downloads.delete("series", episodeId)
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

    val stream: Stream? get() = repo.lookupStream("series", seriesId)

    fun load() {
        viewModelScope.launch {
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
            _favOn.value = repo.isFavorite("series", seriesId)
            _myListOn.value = repo.isInMyList("series", seriesId)
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
    fallbackName: String = "",
    onPlayEpisode: (episodeId: Int, ext: String) -> Unit,
    onPerson: (name: String) -> Unit = {},
    onBack: () -> Unit,
) {
    val vm: SeriesDetailViewModel = koinViewModel { parametersOf(seriesId) }
    val poster by vm.poster.collectAsState()
    val info by vm.info.collectAsState()
    val stills by vm.stills.collectAsState()
    val activeSeason by vm.activeSeason.collectAsState()
    val myListOn by vm.myListOn.collectAsState()
    val favOn by vm.favOn.collectAsState()
    val stream = vm.stream

    LaunchedEffect(seriesId) { vm.load() }
    BackHandler { onBack() }

    // collectAsState must be inside the @Composable scope, not the
    // LazyColumn DSL — so it lives here at the top, and we pass the
    // snapshot down to the per-row composable.
    val dlItems by vm.downloadItems.collectAsState()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    val backdrop = poster?.backdrop ?: poster?.poster ?: stream?.icon
                    if (backdrop != null) {
                        AsyncImage(
                            model = backdrop,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.6f to Color(0x88000000),
                                1f to MaterialTheme.colorScheme.background,
                            ),
                        ),
                    )
                    // No back button — system back gesture handles
                    // it (see BackHandler above). A floating button
                    // on the backdrop just adds visual noise.
                }
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .offset(y = (-40).dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    val posterUrl = poster?.poster ?: stream?.icon
                    Box(
                        Modifier
                            .width(110.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!posterUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = posterUrl,
                                contentDescription = stream?.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            val initials = (stream?.name ?: fallbackName)
                                .take(2).uppercase().ifBlank { "?" }
                            Text(
                                initials,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                        val title = stream?.name?.takeIf { it.isNotBlank() }
                            ?: poster?.tmdbTitle?.takeIf { it.isNotBlank() }
                            ?: fallbackName.takeIf { it.isNotBlank() }
                            ?: "…"
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            (poster?.year ?: stream?.year?.take(4))?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            poster?.usCert?.let { CertBadge(it) }
                            (poster?.rating ?: stream?.rating?.toDoubleOrNull())?.let { r ->
                                Icon(Icons.Default.Star, null, Modifier.size(12.dp),
                                    tint = Color(0xFFD4A544))
                                Text(
                                    "%.1f".format(r),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFD4A544),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp).offset(y = (-24).dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = { vm.toggleMyList() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onBackground,
                            ),
                        ) {
                            Icon(
                                if (myListOn) Icons.Default.Check else Icons.Outlined.Add,
                                null, Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("My List", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = { vm.toggleFav() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (favOn) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onBackground,
                            ),
                        ) {
                            Icon(
                                if (favOn) Icons.Default.Star else Icons.Outlined.StarBorder,
                                null, Modifier.size(16.dp),
                                tint = if (favOn) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onBackground,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (favOn) "Favorited" else "Favorite", fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    val plot = poster?.plot ?: stream?.plot
                    if (!plot.isNullOrBlank()) {
                        Text(
                            plot,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
            // TMDB enrichment block — same shape as movies, just with
            // "CREATED BY" instead of "DIRECTED BY".
            item {
                TmdbExtrasSection(
                    mode = "series",
                    poster = poster,
                    onPerson = onPerson,
                )
                Spacer(Modifier.height(16.dp))
            }

            // Season picker
            val seasonNums = info?.episodes?.keys?.mapNotNull(String::toIntOrNull)?.sorted().orEmpty()
            if (seasonNums.size > 1) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        items(seasonNums, key = { it }) { n ->
                            val isActive = n == activeSeason
                            Surface(
                                onClick = { vm.selectSeason(n) },
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(
                                    "Season $n",
                                    color = if (isActive) Color.White
                                            else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            // "Download Season N" button — visible whenever there's
            // an active season with episodes. Calls enqueueSeason
            // which iterates and skips already-queued episodes.
            val episodes = activeSeason
                ?.let { info?.episodes?.get(it.toString()).orEmpty() }
                .orEmpty()
            if (episodes.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { activeSeason?.let { vm.enqueueSeason(it) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            androidx.compose.material.icons.Icons.Default.Download,
                            null, Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Download all of Season ${activeSeason ?: "?"}", fontSize = 13.sp)
                    }
                }
            }

            // Episodes — dlItems is hoisted above the LazyColumn.
            items(episodes, key = { it.id }) { ep ->
                val epIdInt = ep.id.toIntOrNull()
                val dlStatus = remember(dlItems, epIdInt) {
                    epIdInt?.let { vm.isEpisodeDownloaded(it) }
                }
                EpisodeRow(
                    episode = ep,
                    stillUrl = stills[ep.id],
                    downloadStatus = dlStatus,
                    onPlay = {
                        vm.recordLastEpisode(epIdInt ?: 0)
                        val ext = ep.container ?: "mp4"
                        onPlayEpisode(epIdInt ?: return@EpisodeRow, ext)
                    },
                    onDownloadToggle = {
                        if (dlStatus == null || dlStatus == com.khouch.core.data.downloads.DownloadEntry.Status.FAILED) {
                            vm.enqueueEpisode(ep, activeSeason)
                        } else {
                            epIdInt?.let { vm.deleteEpisode(it) }
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    stillUrl: String?,
    downloadStatus: com.khouch.core.data.downloads.DownloadEntry.Status?,
    onPlay: () -> Unit,
    onDownloadToggle: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            Box(
                Modifier
                    .width(140.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF222742)),
                contentAlignment = Alignment.Center,
            ) {
                if (!stillUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = stillUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow, null,
                        Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val epNum = episode.episodeNum?.jsonPrimitive?.contentOrNull ?: "?"
                Text(
                    "$epNum · ${episode.title ?: "Untitled"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Tap-target on the right edge of the row, separate from
            // the play-row click. Tapping toggles download (queue /
            // delete) — same UX as the movie detail's third button
            // but compressed into an icon.
            IconButton(
                onClick = onDownloadToggle,
                modifier = Modifier.size(40.dp),
            ) {
                val icon = when (downloadStatus) {
                    com.khouch.core.data.downloads.DownloadEntry.Status.COMPLETED -> Icons.Default.DownloadDone
                    com.khouch.core.data.downloads.DownloadEntry.Status.RUNNING,
                    com.khouch.core.data.downloads.DownloadEntry.Status.PENDING -> Icons.Default.Downloading
                    com.khouch.core.data.downloads.DownloadEntry.Status.FAILED -> Icons.Default.ErrorOutline
                    else -> Icons.Default.Download
                }
                val tint = when (downloadStatus) {
                    com.khouch.core.data.downloads.DownloadEntry.Status.COMPLETED,
                    com.khouch.core.data.downloads.DownloadEntry.Status.RUNNING,
                    com.khouch.core.data.downloads.DownloadEntry.Status.PENDING -> MaterialTheme.colorScheme.primary
                    com.khouch.core.data.downloads.DownloadEntry.Status.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(icon, "Download", tint = tint, modifier = Modifier.size(20.dp))
            }
        }
    }
}
