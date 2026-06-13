package com.khouch.tv.ui.detail

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.khouch.tv.data.model.PersonCreditsResponse
import com.khouch.tv.data.model.PersonCreditTile
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

class PersonCreditsViewModel(
    private val repo: KhouchRepository,
    private val name: String,
) : ViewModel() {
    private val _data = MutableStateFlow<PersonCreditsResponse?>(null)
    val data = _data.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    init {
        viewModelScope.launch {
            runCatching { repo.personCredits(name) }.onSuccess { _data.value = it }
            _loading.value = false
        }
    }
}

@Composable
fun PersonCreditsScreen(
    name: String,
    onOpen: (mode: String, tile: PersonCreditTile) -> Unit,
    onBack: () -> Unit,
) {
    val vm: PersonCreditsViewModel = koinViewModel { parametersOf(name) }
    val data by vm.data.collectAsState()
    val loading by vm.loading.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = KhouchColors.Bg, contentColor = KhouchColors.Fg),
    ) {
        LazyColumn(contentPadding = PaddingValues(32.dp)) {
            item {
                Text("More from $name", color = KhouchColors.Fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
            }
            if (loading) {
                item { Text("Loading filmography…", color = KhouchColors.FgDim) }
            } else if (data == null) {
                item { Text("Couldn't load credits", color = KhouchColors.Accent) }
            } else {
                val d = data!!
                item { PersonHeader(d) }
                val movie = d.items.movie
                val series = d.items.series
                if (movie.isEmpty() && series.isEmpty()) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No titles featuring $name in your catalog.",
                            color = KhouchColors.FgDim,
                            fontSize = 14.sp,
                        )
                    }
                }
                if (movie.isNotEmpty()) {
                    item { SectionHeader("MOVIES · ${movie.size}") }
                    item { CreditStrip(movie) { onOpen("movie", it) } }
                }
                if (series.isNotEmpty()) {
                    item { SectionHeader("SERIES · ${series.size}") }
                    item { CreditStrip(series) { onOpen("series", it) } }
                }
            }
        }
    }
}

@Composable
private fun PersonHeader(d: PersonCreditsResponse) {
    val p = d.person
    val total = d.items.movie.size + d.items.series.size
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
        Box(
            modifier = Modifier.size(96.dp).clip(CircleShape).background(KhouchColors.Bg2),
            contentAlignment = Alignment.Center,
        ) {
            if (!p?.profile.isNullOrBlank()) {
                AsyncImage(
                    model = p!!.profile,
                    contentDescription = p.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(p?.name ?: d.name, color = KhouchColors.Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            p?.knownForDepartment?.let { dept ->
                Text(dept.uppercase(), color = KhouchColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "$total title${if (total != 1) "s" else ""} in your catalog",
                color = KhouchColors.FgDim,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, color = KhouchColors.Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun CreditStrip(items: List<PersonCreditTile>, onClick: (PersonCreditTile) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(end = 32.dp),
    ) {
        items(items, key = { it.id }) { tile -> CreditCard(tile) { onClick(tile) } }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CreditCard(tile: PersonCreditTile, onClick: () -> Unit) {
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
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
            ) {
                AsyncImage(
                    model = tile.poster ?: tile.icon,
                    contentDescription = tile.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                tile.name,
                color = KhouchColors.Fg,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            tile.character?.takeIf { it.isNotBlank() }?.let { ch ->
                Text(
                    "as $ch",
                    color = KhouchColors.FgDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 0.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
