package com.khouch.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.khouch.tv.data.model.PosterResponse
import com.khouch.tv.data.model.ProgressEntry
import com.khouch.tv.data.model.Stream
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.common.PanelImage
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class MovieDetailViewModel(
    private val repo: KhouchRepository,
    private val movieId: Int,
) : ViewModel() {
    private val _poster = MutableStateFlow<PosterResponse?>(null)
    val poster = _poster.asStateFlow()

    // Expose userState as a flow so the detail screen recomposes when
    // progress / favorites / my-list arrive from bootstrap. Using a
    // simple `get()` property bound the read to first composition;
    // when userState hadn't loaded yet, the Resume button stayed
    // labeled "Play" forever.
    val userState = repo.userState

    val stream: Stream? get() = repo.lookupStream("movie", movieId)
    val isFav get() = repo.isFavorite("movie", movieId)
    val inMyList get() = repo.isInMyList("movie", movieId)
    fun progressNow(): ProgressEntry? = repo.progressFor("movie", movieId)

    private val _favOn = MutableStateFlow(repo.isFavorite("movie", movieId))
    val favOn = _favOn.asStateFlow()
    private val _myListOn = MutableStateFlow(repo.isInMyList("movie", movieId))
    val myListOn = _myListOn.asStateFlow()
    // "More Like This" rails. Server builds the full rail list
    // (ordered, filtered, capped). Empty until load() resolves; the
    // section composable hides itself when this is empty.
    private val _similar = MutableStateFlow<List<com.khouch.tv.data.model.SimilarRail>>(emptyList())
    val similar = _similar.asStateFlow()

    fun load() {
        viewModelScope.launch {
            // Without this, opening a deep-link / tile click before
            // /api/index/movie has run returns a null stream and the
            // detail title stays "Loading…" forever. Matches the
            // phone VM's behavior.
            if (repo.lookupStream("movie", movieId) == null) {
                runCatching { repo.loadIndex("movie") }
            }
            runCatching { repo.poster("movie", movieId) }
                .onSuccess { _poster.value = it }
            // Make sure bootstrap has run so userState.progress is
            // populated before we read it. Cheap when already cached.
            if (userState.value.progress.isEmpty()) {
                runCatching { repo.bootstrap() }
            }
            runCatching { repo.similar("movie", movieId) }
                .onSuccess { _similar.value = it.rails }
        }
    }

    fun toggleFav() {
        viewModelScope.launch {
            runCatching { _favOn.value = repo.toggleFavorite("movie", movieId) }
        }
    }
    fun toggleMyList() {
        viewModelScope.launch {
            runCatching { _myListOn.value = repo.toggleMyList("movie", movieId) }
        }
    }
}

@Composable
fun MovieDetailScreen(
    movieId: Int,
    onPlay: () -> Unit,
    onPerson: (name: String) -> Unit = {},
    onOpenSimilar: (mode: String, id: Int, name: String) -> Unit = { _, _, _ -> },
    onBack: () -> Unit,
) {
    val vm: MovieDetailViewModel = koinViewModel { parametersOf(movieId) }
    val poster by vm.poster.collectAsState()
    val favOn by vm.favOn.collectAsState()
    val myListOn by vm.myListOn.collectAsState()
    // Subscribe to userState so the Resume button reflects the saved
    // position as soon as bootstrap lands (and updates in real time
    // when the user finishes a play session and progress is written
    // through).
    val userState by vm.userState.collectAsState()
    val similar by vm.similar.collectAsState()
    val stream = vm.stream
    LaunchedEffect(movieId) { vm.load() }

    val playFocus = remember { FocusRequester() }
    LaunchedEffect(stream) {
        if (stream != null) runCatching { playFocus.requestFocus() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(
            containerColor = KhouchColors.Bg,
            contentColor = KhouchColors.Fg,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // Backdrop fills the entire screen, faded into the bg at
            // bottom so the text content is readable.
            BackdropLayer(poster = poster, stream = stream)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 64.dp, end = 64.dp, top = 64.dp, bottom = 32.dp)
                    // Vertical scroll so D-pad DOWN past the cast row
                    // reveals the More Like This rails. Without this
                    // they're laid out below the viewport with no way
                    // to focus into them.
                    .verticalScroll(rememberScrollState()),
            ) {
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
                        Text(
                            stream?.name ?: "Loading…",
                            color = KhouchColors.Fg,
                            fontSize = 36.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        MetaRow(poster = poster, stream = stream)
                        Spacer(Modifier.height(16.dp))
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
                            // Read progress derived from the subscribed
                            // userState so the label updates the moment
                            // bootstrap lands (or after the user comes
                            // back from a play session that wrote a
                            // new progress entry through).
                            val prog = remember(userState, movieId) { vm.progressNow() }
                            val resume = prog != null && prog.p > 30 && prog.p < (prog.d - 10).coerceAtLeast(0.0)
                            Button(
                                onClick = onPlay,
                                colors = ButtonDefaults.colors(
                                    containerColor = KhouchColors.Accent,
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier.focusRequester(playFocus),
                            ) {
                                Text(
                                    if (resume) "▸ Resume at ${formatPos(prog!!.p)}" else "▸ Play",
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                )
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
                        // TMDB enrichment sits AFTER the action row so
                        // Play / My List / Favorite always stay visible
                        // above the fold. Cast strip + keywords can
                        // scroll into view via D-pad DOWN.
                        TmdbExtrasRow(mode = "movie", poster = poster, onPerson = onPerson)
                        if (similar.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            MoreLikeThisSection(
                                mode = "movie",
                                rails = similar,
                                onItemClick = onOpenSimilar,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BackdropLayer(poster: PosterResponse?, stream: Stream?) {
    val url = poster?.backdrop ?: poster?.poster ?: stream?.icon
    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(KhouchColors.Bg),
        )
    }
    // Always overlay a vertical gradient so text on top is readable
    // regardless of whether the backdrop was light or dark.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xCC0B0B0C),
                        Color(0xAA0B0B0C),
                        Color(0xFF0B0B0C),
                    )
                )
            ),
    )
}

// "1:23:45" / "12:34" — same shape the web's formatPos produces.
private fun formatPos(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%d:%02d", m, s)
}

@Composable
private fun MetaRow(poster: PosterResponse?, stream: Stream?) {
    val bits = mutableListOf<Pair<String, Color>>()
    val year = poster?.year?.takeIf { it.isNotBlank() }
        ?: stream?.year?.take(4)?.takeIf { it.isNotBlank() }
    if (year != null) bits += year to KhouchColors.FgDim
    poster?.usCert?.takeIf { it.isNotBlank() }?.let { bits += it to KhouchColors.Fg }
    poster?.runtime?.let { bits += "$it min" to KhouchColors.FgDim }
    val rating = poster?.rating ?: stream?.rating?.toDoubleOrNull()
    if (rating != null) bits += "★ ${"%.1f".format(rating)}" to Color(0xFFFBBF24)
    poster?.genres?.firstOrNull()?.let { bits += it to KhouchColors.FgDim }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        bits.forEach { (text, color) ->
            if (text == poster?.usCert && poster.usCert != null) {
                // Render the cert as a chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(KhouchColors.Bg3)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(text, color = color, fontSize = 12.sp)
                }
            } else {
                Text(text, color = color, fontSize = 14.sp)
            }
        }
    }
}
