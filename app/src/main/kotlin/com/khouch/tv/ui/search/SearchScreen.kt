package com.khouch.tv.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.khouch.tv.data.model.Stream
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.common.PanelImage
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class SearchViewModel(private val repo: KhouchRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    private val _results = MutableStateFlow<Map<String, List<Stream>>>(emptyMap())
    val results = _results.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private var debounce: Job? = null

    fun setQuery(q: String) {
        _query.value = q
        debounce?.cancel()
        if (q.isBlank()) { _results.value = emptyMap(); return }
        debounce = viewModelScope.launch {
            delay(600)
            run(q)
        }
    }

    private fun run(q: String) {
        if (q.isBlank()) {
            _results.value = emptyMap()
            return
        }
        _busy.value = true
        viewModelScope.launch {
            val out = mutableMapOf<String, List<Stream>>()
            for (mode in listOf("live", "movie", "series")) {
                runCatching { repo.search(mode, q) }
                    .onSuccess { out[mode] = it.take(120) }
            }
            _results.value = out
            _busy.value = false
        }
    }
}

@Composable
fun SearchScreen(
    onPlay: (mode: String, id: Int) -> Unit,
    onOpenDetail: (mode: String, id: Int) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SearchViewModel = koinViewModel()
    // Local text state — decoupled from the ViewModel so every keystroke
    // only recomposes the text field, not the results grid below it.
    var localQuery by remember { mutableStateOf("") }
    val results by vm.results.collectAsState()
    val busy by vm.busy.collectAsState()
    val fieldFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }

    fun jumpToResults() {
        keyboard?.hide()
        runCatching { resultsFocus.requestFocus() }
    }
    BackHandler { onBack() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(
            containerColor = KhouchColors.Bg,
            contentColor = KhouchColors.Fg,
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(48.dp)) {
            Text("Search", color = KhouchColors.Fg, fontSize = 32.sp)
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = localQuery,
                onValueChange = { localQuery = it; vm.setQuery(it) },
                singleLine = true,
                textStyle = TextStyle(color = KhouchColors.Fg, fontSize = 22.sp),
                cursorBrush = SolidColor(KhouchColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { jumpToResults() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KhouchColors.Bg3)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .focusRequester(fieldFocus)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                            jumpToResults(); true
                        } else false
                    },
            )
            Spacer(Modifier.height(24.dp))

            when {
                busy && results.isEmpty() -> Text("Searching…", color = KhouchColors.FgDim)
                localQuery.isBlank() -> Text(
                    "Type to search live channels, movies, and series.",
                    color = KhouchColors.FgDim, fontSize = 13.sp,
                )
                results.values.all { it.isEmpty() } -> Text(
                    "No matches for \"$localQuery\".", color = KhouchColors.FgDim, fontSize = 13.sp,
                )
                else -> {
                    val expanded = remember { mutableStateMapOf<String, Boolean>() }
                    val modeOrder = listOf("live", "movie", "series")
                    val modeLabel = mapOf("live" to "Live", "movie" to "Movies", "series" to "Series")
                    val previewCount = 8
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(resultsFocus)
                            .focusGroup(),
                    ) {
                        modeOrder.forEach { mode ->
                            val items = results[mode].orEmpty()
                            val isExpanded = expanded[mode] == true
                            val label = modeLabel[mode] ?: mode
                            val tileH = if (mode == "live") 110.dp else 230.dp
                            item(key = "header_$mode") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        label,
                                        color = KhouchColors.Fg,
                                        fontSize = 18.sp,
                                    )
                                    if (items.isNotEmpty()) {
                                        Text(
                                            "  ·  ${items.size}",
                                            color = KhouchColors.FgDim,
                                            fontSize = 14.sp,
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (items.size > previewCount) {
                                        androidx.tv.material3.Button(
                                            onClick = { expanded[mode] = !isExpanded },
                                            colors = androidx.tv.material3.ButtonDefaults.colors(
                                                containerColor = KhouchColors.Bg3,
                                                contentColor = KhouchColors.FgDim,
                                                focusedContainerColor = KhouchColors.Accent,
                                                focusedContentColor = androidx.compose.ui.graphics.Color.White,
                                            ),
                                        ) {
                                            Text(
                                                if (isExpanded) "Show less" else "See all ${items.size}",
                                                fontSize = 11.sp,
                                            )
                                        }
                                    }
                                }
                            }
                            item(key = "results_$mode") {
                                when {
                                    items.isEmpty() -> Text(
                                        "No $label results.",
                                        color = KhouchColors.FgDim,
                                        fontSize = 13.sp,
                                    )
                                    isExpanded -> {
                                        val expandRows = if (mode == "live") 3 else 2
                                        LazyHorizontalGrid(
                                            rows = GridCells.Fixed(expandRows),
                                            contentPadding = PaddingValues(end = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(tileH * expandRows + 12.dp * (expandRows - 1)),
                                        ) {
                                            gridItems(items, key = { it.id }) { s ->
                                                ResultTile(s, mode) {
                                                    if (mode == "live") onPlay(mode, s.id)
                                                    else onOpenDetail(mode, s.id)
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        LazyRow(
                                            contentPadding = PaddingValues(end = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(tileH),
                                        ) {
                                            items(
                                                items.take(previewCount),
                                                key = { it.id },
                                            ) { s ->
                                                ResultTile(s, mode) {
                                                    if (mode == "live") onPlay(mode, s.id)
                                                    else onOpenDetail(mode, s.id)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultTile(stream: Stream, mode: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = KhouchColors.Bg2,
            contentColor = KhouchColors.Fg,
            focusedContainerColor = KhouchColors.Bg3,
        ),
        modifier = Modifier.width(140.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (mode == "live") 16f / 9f else 2f / 3f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
            ) {
                PanelImage(
                    url = stream.icon,
                    fallbackText = stream.name.take(2).uppercase(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                stream.name,
                color = KhouchColors.Fg,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
