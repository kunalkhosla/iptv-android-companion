package com.khouch.tv.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.khouch.tv.data.model.Category
import com.khouch.tv.data.model.Stream
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class LiveBrowseViewModel(private val repo: KhouchRepository) : ViewModel() {
    private val _activeCatId = MutableStateFlow<String?>(null)
    val activeCatId = _activeCatId.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    val categories = repo.categories
    val streamsByMode = repo.streams
    val byCategory = repo.byCategory

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            runCatching {
                if (repo.categoriesForMode("live").isEmpty()) repo.bootstrap()
                if (repo.streamsForMode("live").isEmpty()) repo.loadIndex("live")
                if (_activeCatId.value == null) {
                    _activeCatId.value = repo.categoriesForMode("live").firstOrNull()?.categoryId
                }
            }.onSuccess { _error.value = null }
                .onFailure { _error.value = "Couldn't load Live — ${it.message}" }
            _loading.value = false
        }
    }

    fun pickCategory(id: String) { _activeCatId.value = id }

    fun streamsForCategory(catId: String): List<Stream> =
        repo.byCategory.value["live"]?.get(catId).orEmpty()
}

@Composable
fun LiveBrowseInline(onPlay: (Int) -> Unit) {
    val vm: LiveBrowseViewModel = koinViewModel()
    val activeCatId by vm.activeCatId.collectAsState()
    val error by vm.error.collectAsState()
    val loading by vm.loading.collectAsState()
    val categoriesByMode by vm.categories.collectAsState()
    val byCategoryByMode by vm.byCategory.collectAsState()
    val categories = categoriesByMode["live"].orEmpty()
    LaunchedEffect(Unit) { vm.load() }

    val sidebarFocus = remember { FocusRequester() }
    LaunchedEffect(categories.size) {
        if (categories.isNotEmpty()) sidebarFocus.requestFocus()
    }

    Row(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(280.dp)
                .background(KhouchColors.Bg2)
                .padding(vertical = 24.dp)
                .focusGroup(),
        ) {
            Text(
                "Live · ${categories.size} categories",
                color = KhouchColors.FgDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .focusRequester(sidebarFocus)
                    .focusRestorer(),
            ) {
                items(categories, key = { it.categoryId }) { cat ->
                    CategoryRow(
                        cat = cat,
                        selected = cat.categoryId == activeCatId,
                        onClick = { vm.pickCategory(cat.categoryId) },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .focusGroup(),
        ) {
            when {
                loading -> Text("Loading channels…", color = KhouchColors.FgDim)
                error != null -> Text(error.orEmpty(), color = KhouchColors.Accent)
                activeCatId == null -> Text("No categories.", color = KhouchColors.FgDim)
                else -> {
                    val list = byCategoryByMode["live"]?.get(activeCatId).orEmpty()
                    if (list.isEmpty()) {
                        Text("Empty category.", color = KhouchColors.FgDim)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 180.dp),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(list, key = { it.id }) { s ->
                                ChannelTile(stream = s, onClick = { onPlay(s.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(cat: Category, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (selected) KhouchColors.Bg3 else KhouchColors.Bg2,
            contentColor = KhouchColors.Fg,
            focusedContainerColor = KhouchColors.Accent,
            focusedContentColor = androidx.compose.ui.graphics.Color.White,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            cat.categoryName,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ChannelTile(stream: Stream, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = KhouchColors.Bg2,
            contentColor = KhouchColors.Fg,
            focusedContainerColor = KhouchColors.Bg3,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(KhouchColors.Bg3),
                contentAlignment = Alignment.Center,
            ) {
                com.khouch.tv.ui.common.PanelImage(
                    url = stream.icon,
                    fallbackText = stream.name.take(2).uppercase(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                stream.name,
                color = KhouchColors.Fg,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            com.khouch.tv.ui.common.NowPlayingLine(
                streamId = stream.id,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
