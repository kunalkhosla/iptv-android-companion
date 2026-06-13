package com.khouch.phone.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.khouch.core.data.model.HomeItem
import com.khouch.core.data.model.SearchAllResponse
import com.khouch.core.data.repo.KhouchRepository
import com.khouch.phone.ui.common.CertBadge
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class SearchViewModel(private val repo: KhouchRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    private val _results = MutableStateFlow<SearchAllResponse?>(null)
    val results = _results.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private var searchJob: Job? = null

    fun setQuery(q: String) {
        _query.value = q
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _results.value = null
            _loading.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(220)  // debounce
            _loading.value = true
            runCatching { repo.searchAll(q.trim(), limit = 30) }
                .onSuccess { _results.value = it; _loading.value = false }
                .onFailure { _loading.value = false }
        }
    }

    fun clear() {
        _query.value = ""
        _results.value = null
        searchJob?.cancel()
    }
}

@Composable
fun SearchScreen(
    onResult: (mode: String, item: HomeItem) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SearchViewModel = koinViewModel()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val loading by vm.loading.collectAsState()
    BackHandler { onBack() }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Edge-to-edge is enabled at the activity level, so we need to
    // pad the status-bar inset ourselves — otherwise the search row
    // sits under the system clock and bleeds off the top.
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(
                androidx.compose.foundation.layout.WindowInsets.systemBars
            ),
    ) {
        // Search bar — single combined surface holding back arrow,
        // text input, and clear icon. The previous layout used a
        // separate IconButton sitting next to an OutlinedTextField,
        // which baseline-aligned poorly because OutlinedTextField
        // reserves space for a floating label that this UI doesn't
        // use, pushing the input lower than the back arrow.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack, "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .padding(vertical = 14.dp),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                "Search movies, series, channels",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        inner()
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.clear() }) {
                        Icon(
                            Icons.Default.Clear, "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(
                    Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                results == null && query.length >= 2 -> Text(
                    "Searching…",
                    Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                results != null && results!!.movie.isEmpty() &&
                results!!.series.isEmpty() && results!!.live.isEmpty() -> Text(
                    "No matches",
                    Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                results != null -> ResultsList(results!!, onResult)
                else -> Text(
                    "Type to search",
                    Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultsList(
    res: SearchAllResponse,
    onResult: (String, HomeItem) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        if (res.movie.isNotEmpty()) {
            item { SectionHeader("Movies", res.movie.size) }
            item { ResultRow(res.movie, "movie", onResult) }
        }
        if (res.series.isNotEmpty()) {
            item { SectionHeader("Series", res.series.size) }
            item { ResultRow(res.series, "series", onResult) }
        }
        if (res.live.isNotEmpty()) {
            item { SectionHeader("Live channels", res.live.size) }
            item { LiveChannelList(res.live, onResult) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultRow(
    items: List<HomeItem>,
    mode: String,
    onResult: (String, HomeItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items) { item ->
            Column(
                Modifier
                    .width(110.dp)
                    .clickable { onResult(mode, item) },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    AsyncImage(
                        model = item.poster ?: item.icon,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    item.usCert?.let { cert ->
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                            CertBadge(cert)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun LiveChannelList(
    items: List<HomeItem>,
    onResult: (String, HomeItem) -> Unit,
) {
    Column {
        items.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onResult("live", item) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    AsyncImage(
                        model = item.icon,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // EPG-matched result — show the programme that put this
                    // channel in the results ("Now · FIFA World Cup 2026").
                    item.programme?.let { p ->
                        val startMs = p.startTs * 1000
                        val whenLabel = if (startMs <= System.currentTimeMillis()) "Now"
                        else java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date(startMs))
                        Text(
                            "$whenLabel · ${p.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
