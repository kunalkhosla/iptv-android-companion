package com.khouch.phone.ui.category

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.khouch.core.data.model.Stream
import com.khouch.core.data.repo.KhouchRepository
import com.khouch.phone.ui.common.CertBadge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class CategoryViewModel(
    private val repo: KhouchRepository,
    val mode: String,
    val categoryId: String,
) : ViewModel() {
    private val _items = MutableStateFlow<List<Stream>>(emptyList())
    val items = _items.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    fun load() {
        if (_items.value.isNotEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            runCatching { repo.streamsByCategory(mode, categoryId) }
                .onSuccess { _items.value = it }
            _loading.value = false
        }
    }
}

sealed class CategoryAction {
    data class OpenDetail(val mode: String, val id: Int, val name: String) : CategoryAction()
    data class PlayLive(val item: Stream) : CategoryAction()
}

@Composable
fun CategoryScreen(
    mode: String,
    categoryId: String,
    title: String,
    onAction: (CategoryAction) -> Unit,
    onBack: () -> Unit,
) {
    val vm: CategoryViewModel = koinViewModel { parametersOf(mode, categoryId) }
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    LaunchedEffect(mode, categoryId) { vm.load() }
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back",
                            tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (items.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${items.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pv ->
        Box(Modifier.fillMaxSize().padding(pv)) {
            when {
                loading && items.isEmpty() -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
                items.isEmpty() -> Text(
                    "Empty",
                    Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                mode == "live" -> ChannelList(items) { s -> onAction(CategoryAction.PlayLive(s)) }
                else -> PosterGrid(items, mode) { s ->
                    onAction(CategoryAction.OpenDetail(mode, s.id, s.name))
                }
            }
        }
    }
}

@Composable
private fun PosterGrid(items: List<Stream>, mode: String, onClick: (Stream) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { s ->
            PosterCard(s) { onClick(s) }
        }
    }
}

@Composable
private fun PosterCard(s: Stream, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (!s.icon.isNullOrBlank()) {
                AsyncImage(
                    model = s.icon,
                    contentDescription = s.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        s.name.take(2).uppercase(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                }
            }
            s.rating?.toDoubleOrNull()?.let { r ->
                Text(
                    "★ %.1f".format(r),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFD4A544),
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color(0x99000000), RoundedCornerShape(topStart = 4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            s.year?.takeIf { it.isNotBlank() }?.let { y ->
                Text(
                    y.take(4),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color(0x99000000), RoundedCornerShape(topEnd = 4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            s.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun ChannelList(items: List<Stream>, onClick: (Stream) -> Unit) {
    LazyColumn {
        items(items, key = { it.id }) { ch ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick(ch) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    if (!ch.icon.isNullOrBlank()) {
                        AsyncImage(
                            model = ch.icon,
                            contentDescription = ch.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(4.dp),
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(ch.name.take(2).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    ch.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(color = Color(0xFF1F2440))
        }
    }
}
