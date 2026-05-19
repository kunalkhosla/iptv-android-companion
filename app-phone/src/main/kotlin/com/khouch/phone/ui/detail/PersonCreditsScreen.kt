package com.khouch.phone.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.khouch.core.data.model.PersonCreditsResponse
import com.khouch.core.data.model.PersonCreditTile
import com.khouch.core.data.repo.KhouchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * "More from <actor/director>" screen. Opened from a cast-card or
 * director-link tap on the detail screens. Resolves the name to a
 * TMDB person via /api/person/credits, then displays their
 * catalog filmography in two sections (Movies, Series).
 */
class PersonCreditsViewModel(
    private val repo: KhouchRepository,
    private val name: String,
) : ViewModel() {

    private val _data = MutableStateFlow<PersonCreditsResponse?>(null)
    val data = _data.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    init { fetch() }

    private fun fetch() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching { repo.personCredits(name) }
                .onSuccess { _data.value = it }
                .onFailure { _error.value = it.message ?: "Couldn't load credits" }
            _loading.value = false
        }
    }
}

@Composable
fun PersonCreditsScreen(
    name: String,
    onOpen: (mode: String, item: PersonCreditTile) -> Unit,
    onBack: () -> Unit,
) {
    val vm: PersonCreditsViewModel = koinViewModel { parametersOf(name) }
    val data by vm.data.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Header bar with back button + "More from <name>" title.
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "More from $name",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Person summary card — photo + name + department + total count.
        // Only renders once the credits fetch has resolved (skeleton
        // until then so the screen doesn't feel empty).
        when {
            loading -> {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Text(
                    error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            data != null -> {
                PersonSummaryHeader(data!!)
                Spacer(Modifier.height(8.dp))
                val movie = data!!.items.movie
                val series = data!!.items.series
                if (movie.isEmpty() && series.isEmpty()) {
                    Text(
                        "No titles featuring $name in your catalog.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    if (movie.isNotEmpty()) Section("Movies", movie) { onOpen("movie", it) }
                    if (series.isNotEmpty()) Section("Series", series) { onOpen("series", it) }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PersonSummaryHeader(d: PersonCreditsResponse) {
    val p = d.person
    val total = d.items.movie.size + d.items.series.size
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                p?.name ?: d.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            p?.knownForDepartment?.let { dept ->
                Text(
                    dept.uppercase(),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.08.sp,
                )
            }
            Text(
                "$total title${if (total != 1) "s" else ""} in your catalog",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Section(
    title: String,
    items: List<PersonCreditTile>,
    onClick: (PersonCreditTile) -> Unit,
) {
    Column {
        Text(
            title.uppercase() + " · ${items.size}",
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.08.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(items, key = { it.id }) { item -> CreditTile(item) { onClick(item) } }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CreditTile(item: PersonCreditTile, onClick: () -> Unit) {
    Column(
        Modifier
            .width(110.dp)
            .clickable(onClick = onClick),
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
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.name,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 13.sp,
        )
        item.character?.takeIf { it.isNotBlank() }?.let { ch ->
            Text(
                "as $ch",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
