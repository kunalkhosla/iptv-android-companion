package com.khouch.phone.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
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
import com.khouch.core.data.model.PosterResponse
import com.khouch.core.data.model.ProgressEntry
import com.khouch.core.data.model.SimilarRail
import com.khouch.core.data.model.Stream
import com.khouch.core.data.repo.KhouchRepository
import com.khouch.phone.ui.common.CertBadge
import com.khouch.phone.ui.common.formatPos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class MovieDetailViewModel(
    private val repo: KhouchRepository,
    private val downloads: com.khouch.core.data.downloads.DownloadsRepo,
    private val movieId: Int,
) : ViewModel() {
    private val _poster = MutableStateFlow<PosterResponse?>(null)
    val poster = _poster.asStateFlow()

    val userState = repo.userState
    val downloadItems = downloads.items

    val stream: Stream? get() = repo.lookupStream("movie", movieId)
    fun progressNow(): ProgressEntry? = repo.progressFor("movie", movieId)

    private val _favOn = MutableStateFlow(repo.isFavorite("movie", movieId))
    val favOn = _favOn.asStateFlow()
    private val _myListOn = MutableStateFlow(repo.isInMyList("movie", movieId))
    val myListOn = _myListOn.asStateFlow()
    // "More Like This" rails. Server builds the full rail list
    // (ordered, filtered, capped). Empty until load() resolves; the
    // section composable hides itself when this is empty.
    private val _similar = MutableStateFlow<List<SimilarRail>>(emptyList())
    val similar = _similar.asStateFlow()

    fun downloadStatus(): com.khouch.core.data.downloads.DownloadEntry.Status? =
        downloads.entryFor("movie", movieId)?.status

    /**
     * Queue a download for this movie. Resolves a fresh signed proxy
     * URL from the server (so the panel-CDN auth signature is fresh
     * and the URL never expires before the download starts) and then
     * hands it to DownloadManager.
     */
    fun enqueueDownload() = viewModelScope.launch {
        val s = stream ?: return@launch
        // Always request the .mp4 channel of /api/stream — the server's
        // /api/download endpoint is 720p MP4 regardless of source
        // container, so saving with ext=mp4 keeps the on-disk file
        // playable everywhere. The source's actual container (mkv etc.)
        // is irrelevant once we route through the server transcoder.
        val urls = runCatching { repo.streamUrls("movie", movieId, "mp4") }.getOrNull() ?: return@launch
        val sourceUrl = urls.download
            ?: urls.proxy   // graceful fallback for older servers
            ?: urls.direct
            ?: return@launch
        downloads.enqueue(
            mode = "movie",
            id = movieId,
            name = s.name,
            sourceUrl = sourceUrl,
            ext = "mp4",
            poster = _poster.value?.poster ?: s.icon,
            year = s.year,
        )
    }

    fun deleteDownload() = downloads.delete("movie", movieId)

    fun load() {
        viewModelScope.launch {
            // Make sure the mode index is loaded so lookupStream returns
            // a non-null Stream (rails screen only fetches /api/home).
            if (repo.lookupStream("movie", movieId) == null) {
                runCatching { repo.loadIndex("movie") }
            }
            if (userState.value.progress.isEmpty()) {
                runCatching { repo.bootstrap() }
            }
            runCatching { repo.poster("movie", movieId) }.onSuccess { _poster.value = it }
            _favOn.value = repo.isFavorite("movie", movieId)
            _myListOn.value = repo.isInMyList("movie", movieId)
            runCatching { repo.similar("movie", movieId) }
                .onSuccess { _similar.value = it.rails }
        }
    }

    fun toggleFav() = viewModelScope.launch {
        runCatching { _favOn.value = repo.toggleFavorite("movie", movieId) }
    }
    fun toggleMyList() = viewModelScope.launch {
        runCatching { _myListOn.value = repo.toggleMyList("movie", movieId) }
    }
}

@Composable
fun MovieDetailScreen(
    movieId: Int,
    fallbackName: String = "",
    onPlay: (ext: String) -> Unit,
    onPerson: (name: String) -> Unit = {},
    onOpenSimilar: (mode: String, id: Int, name: String) -> Unit = { _, _, _ -> },
    onBack: () -> Unit,
) {
    val vm: MovieDetailViewModel = koinViewModel { parametersOf(movieId) }
    val poster by vm.poster.collectAsState()
    val favOn by vm.favOn.collectAsState()
    val myListOn by vm.myListOn.collectAsState()
    val userState by vm.userState.collectAsState()
    val similar by vm.similar.collectAsState()
    val stream = vm.stream
    LaunchedEffect(movieId) { vm.load() }
    BackHandler { onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // Backdrop hero
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            ) {
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

            // Poster + title block, overlapping the backdrop slightly
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
                    MetaRow(poster = poster, stream = stream)
                }
            }

            // Buttons row
            Spacer(Modifier.height(0.dp))
            Column(Modifier.padding(horizontal = 16.dp).offset(y = (-24).dp)) {
                val prog = remember(userState, movieId) { vm.progressNow() }
                val resume = prog != null && prog.p > 30 && prog.p < (prog.d - 10).coerceAtLeast(0.0)
                Button(
                    onClick = { onPlay(stream?.container ?: "mp4") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    androidx.compose.material3.Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (resume) "Resume at ${formatPos(prog!!.p)}" else "Play",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(8.dp))
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
                        androidx.compose.material3.Icon(
                            if (myListOn) Icons.Default.Check else Icons.Outlined.Add,
                            null,
                            Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (myListOn) "My List" else "My List", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { vm.toggleFav() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (favOn) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onBackground,
                        ),
                    ) {
                        androidx.compose.material3.Icon(
                            if (favOn) Icons.Default.Star else Icons.Outlined.StarBorder,
                            null,
                            Modifier.size(16.dp),
                            tint = if (favOn) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (favOn) "Favorited" else "Favorite", fontSize = 13.sp)
                    }
                    // Download — third button in the action row.
                    // Recomputes label on every emission of the
                    // shared downloads list so progress text stays
                    // honest while the DownloadManager runs.
                    val dlItems by vm.downloadItems.collectAsState()
                    val dlStatus = remember(dlItems) { vm.downloadStatus() }
                    OutlinedButton(
                        onClick = {
                            if (dlStatus == null || dlStatus == com.khouch.core.data.downloads.DownloadEntry.Status.FAILED) {
                                vm.enqueueDownload()
                            } else {
                                vm.deleteDownload()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = when (dlStatus) {
                                com.khouch.core.data.downloads.DownloadEntry.Status.COMPLETED ->
                                    MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onBackground
                            },
                        ),
                    ) {
                        val icon = when (dlStatus) {
                            com.khouch.core.data.downloads.DownloadEntry.Status.COMPLETED -> Icons.Default.DownloadDone
                            com.khouch.core.data.downloads.DownloadEntry.Status.RUNNING,
                            com.khouch.core.data.downloads.DownloadEntry.Status.PENDING -> Icons.Default.Downloading
                            else -> Icons.Default.Download
                        }
                        val label = when (dlStatus) {
                            com.khouch.core.data.downloads.DownloadEntry.Status.COMPLETED -> "Saved"
                            com.khouch.core.data.downloads.DownloadEntry.Status.RUNNING,
                            com.khouch.core.data.downloads.DownloadEntry.Status.PENDING -> "Saving…"
                            // "720p" suffix sets expectations: downloads are
                            // server-side ffmpeg transcodes (CRF22, 720p) and
                            // take ~movie runtime to finish — not a native copy.
                            else -> "Download 720p"
                        }
                        androidx.compose.material3.Icon(icon, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(label, fontSize = 13.sp)
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
                }
                Spacer(Modifier.height(16.dp))
            }
            // TMDB enrichment block — tagline, trailer, director,
            // cast strip, keywords. Sits outside the action-column's
            // 16-dp horizontal padding because it has its own (so
            // the cast LazyRow can bleed into the right margin for
            // a nicer "scroll to see more" affordance).
            TmdbExtrasSection(
                mode = "movie",
                poster = poster,
                onPerson = onPerson,
            )
            if (similar.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                MoreLikeThisSection(
                    mode = "movie",
                    rails = similar,
                    onItemClick = onOpenSimilar,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetaRow(poster: PosterResponse?, stream: Stream?) {
    val year = poster?.year?.takeIf { it.isNotBlank() }
        ?: stream?.year?.take(4)?.takeIf { it.isNotBlank() }
    val rating = poster?.rating ?: stream?.rating?.toDoubleOrNull()
    val runtime = poster?.runtime

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        year?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        poster?.usCert?.let { CertBadge(it) }
        runtime?.let {
            val h = it / 60; val m = it % 60
            Text(
                if (h > 0) "${h}h ${m}m" else "${m}m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rating != null) {
            androidx.compose.material3.Icon(
                Icons.Default.Star, null, Modifier.size(12.dp),
                tint = Color(0xFFD4A544),
            )
            Text(
                "%.1f".format(rating),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD4A544),
            )
        }
    }
}

