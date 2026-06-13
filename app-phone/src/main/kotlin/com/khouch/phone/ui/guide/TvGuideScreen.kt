package com.khouch.phone.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.khouch.core.data.model.EpgEntry
import com.khouch.core.data.model.Stream
import com.khouch.core.data.repo.KhouchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.util.Calendar
import java.util.Locale

private const val EPG_TTL_MS = 1000L * 60 * 30
private const val GUIDE_HOURS = 3   // 3-hour window shown to the right of "now"
private val DP_PER_HOUR = 220.dp
private val ROW_HEIGHT = 56.dp
private val CHANNEL_COL_WIDTH = 96.dp
private val TIME_RULER_HEIGHT = 22.dp

class TvGuideViewModel(private val repo: KhouchRepository) : ViewModel() {
    private val _channels = MutableStateFlow<List<Stream>>(emptyList())
    val channels = _channels.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _epg = MutableStateFlow<Map<Int, EpgRow>>(emptyMap())
    val epg = _epg.asStateFlow()

    private val _activeFilters = MutableStateFlow<Set<String>>(emptySet())
    val activeFilters = _activeFilters.asStateFlow()
    fun toggleFilter(key: String) {
        val cur = _activeFilters.value
        _activeFilters.value = if (key in cur) cur - key else cur + key
    }

    // Per-profile chip list — comes from /api/home/live response so
    // we don't surface chips that have no matching items for this
    // profile (e.g. "Tamil" on Vir's profile).
    private val _chips = MutableStateFlow<List<com.khouch.core.data.model.ChipDef>>(emptyList())
    val chips = _chips.asStateFlow()

    val favorites = repo.userState
    val filterConfig = repo.filterConfig

    data class EpgRow(val fetchedAt: Long, val programs: List<EpgEntry>)

    fun load() {
        if (_channels.value.isNotEmpty()) return
        viewModelScope.launch {
            _loading.value = true
            runCatching { repo.loadIndex("live") }
                .onSuccess { r -> _channels.value = r.streams }
            // Server-side per-profile chips for the live mode.
            runCatching { repo.home("live") }
                .onSuccess { _chips.value = it.chips }
            _loading.value = false
        }
    }

    fun fetchEpg(streamId: Int) {
        val cur = _epg.value[streamId]
        if (cur != null && (System.currentTimeMillis() - cur.fetchedAt) < EPG_TTL_MS) return
        viewModelScope.launch {
            runCatching { repo.epgShort(streamId) }
                .onSuccess { r ->
                    _epg.value = _epg.value + (streamId to EpgRow(System.currentTimeMillis(), r.programs))
                }
        }
    }
}

@Composable
fun TvGuideScreen(onPlay: (Stream) -> Unit) {
    val vm: TvGuideViewModel = koinViewModel()
    val channels by vm.channels.collectAsState()
    val loading by vm.loading.collectAsState()
    val epg by vm.epg.collectAsState()
    val userState by vm.favorites.collectAsState()
    val activeFilters by vm.activeFilters.collectAsState()
    val serverChips by vm.chips.collectAsState()
    val filterConfig by vm.filterConfig.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    val filtered = remember(channels, activeFilters) {
        channels.filter { ch ->
            activeFilters.isEmpty() || activeFilters.all { it in ch.tags }
        }
    }

    // Window starts at "now" rounded down to the half-hour, spans
    // GUIDE_HOURS hours of programming to the right. Same model as
    // the web TV Guide. nowMs / windowStart re-tick once a minute so
    // the now-line slides.
    val nowMs = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs.value = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L)
        }
    }
    val windowStart = remember(nowMs.value) {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs.value
            set(Calendar.MINUTE, if (get(Calendar.MINUTE) < 30) 0 else 30)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        cal.timeInMillis / 1000
    }
    val windowEnd = windowStart + GUIDE_HOURS * 3600L

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Chip strip — server ships per-profile chips on /api/home/live
        // so kid profiles don't get irrelevant chips like "Tamil". Older
        // servers fall back to a coarse default; this won't show on the
        // current server build.
        val liveChips = remember(serverChips, filterConfig) {
            if (serverChips.isNotEmpty()) {
                serverChips.map { it.key to it.label }
            } else {
                buildList {
                    add("sports" to "Sports")
                    add("news" to "News")
                    add("kids" to "Kids")
                    add("music" to "Music")
                    filterConfig?.groups?.forEach { add(it.key to it.label) }
                }
            }
        }
        if (liveChips.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(liveChips) { (key, label) ->
                    val on = key in activeFilters
                    Surface(
                        onClick = { vm.toggleFilter(key) },
                        color = if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            label,
                            color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }

        if (loading && channels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@Column
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No channels match", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        // Time ruler row (sticky-ish: scrolls vertically with content
        // would be cleaner, but for now it lives above the LazyColumn
        // and shares the horizontal scroll position via a remember).
        val hScroll = rememberScrollState()
        TimeRulerRow(windowStart, hScroll)

        val listState = rememberLazyListState()
        // Lazy-fetch EPG for visible rows.
        LaunchedEffect(listState, filtered) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged()
                .collect { idxs ->
                    for (i in idxs) filtered.getOrNull(i)?.let { vm.fetchEpg(it.id) }
                }
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.id }) { ch ->
                ChannelGridRow(
                    ch = ch,
                    programs = epg[ch.id]?.programs.orEmpty(),
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    nowSec = nowMs.value / 1000,
                    isFavorite = userState.favorites["live"]?.contains(ch.id.toLong()) == true,
                    hScroll = hScroll,
                    onPlay = { onPlay(ch) },
                )
                HorizontalDivider(color = Color(0xFF1F2440), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun TimeRulerRow(windowStart: Long, hScroll: androidx.compose.foundation.ScrollState) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(TIME_RULER_HEIGHT)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Spacer(Modifier.width(CHANNEL_COL_WIDTH))
        Row(Modifier.horizontalScroll(hScroll)) {
            // Half-hour ticks across the window
            val halfHours = GUIDE_HOURS * 2
            for (i in 0 until halfHours) {
                val tickTs = windowStart + i * 1800L
                Box(
                    Modifier
                        .width(DP_PER_HOUR / 2)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        formatClock(tickTs),
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelGridRow(
    ch: Stream,
    programs: List<EpgEntry>,
    windowStart: Long,
    windowEnd: Long,
    nowSec: Long,
    isFavorite: Boolean,
    hScroll: androidx.compose.foundation.ScrollState,
    onPlay: () -> Unit,
) {
    val density = LocalDensity.current
    val pxPerSec = with(density) { DP_PER_HOUR.toPx() / 3600f }

    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable(onClick = onPlay),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Channel column (sticky-left)
        Row(
            modifier = Modifier
                .width(CHANNEL_COL_WIDTH)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (!ch.icon.isNullOrBlank()) {
                    AsyncImage(
                        model = ch.icon,
                        contentDescription = ch.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            ch.name.take(2).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                ch.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                lineHeight = 11.sp,
            )
            if (isFavorite) {
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Default.Star, null, Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }

        // Program track — horizontally scrollable, shared scroll
        // state with the time ruler so they stay in sync.
        Box(
            Modifier
                .fillMaxHeight()
                .horizontalScroll(hScroll),
        ) {
            val trackWidth = DP_PER_HOUR * GUIDE_HOURS
            Box(Modifier.width(trackWidth).fillMaxHeight()) {
                // Programs that overlap the window
                for (p in programs) {
                    val s = p.startTs ?: continue
                    val e = p.stopTs ?: continue
                    if (e <= windowStart || s >= windowEnd) continue
                    val clipS = s.coerceAtLeast(windowStart)
                    val clipE = e.coerceAtMost(windowEnd)
                    val offsetPx = (clipS - windowStart).toFloat() * pxPerSec
                    val widthPx = (clipE - clipS).toFloat() * pxPerSec
                    val offsetDp = with(density) { offsetPx.toDp() }
                    val widthDp = with(density) { widthPx.toDp() }
                    val isLive = clipS <= nowSec && clipE > nowSec
                    Box(
                        Modifier
                            .padding(start = offsetDp, top = 4.dp, bottom = 4.dp, end = 0.dp)
                            .width((widthDp - 2.dp).coerceAtLeast(0.dp))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (isLive) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                else MaterialTheme.colorScheme.surface,
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        Column {
                            Text(
                                p.title ?: "—",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isLive) Color.White
                                        else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                "${formatClock(s)}–${formatClock(e)}",
                                fontSize = 8.sp,
                                color = if (isLive) Color.White.copy(alpha = 0.85f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                // Now line — vertical orange marker across the row
                if (nowSec in windowStart..windowEnd) {
                    val nowOffsetPx = (nowSec - windowStart).toFloat() * pxPerSec
                    val nowOffsetDp = with(density) { nowOffsetPx.toDp() }
                    Box(
                        Modifier
                            .padding(start = nowOffsetDp)
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

private fun formatClock(ts: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ts * 1000 }
    val h24 = cal.get(Calendar.HOUR_OF_DAY)
    val m = cal.get(Calendar.MINUTE)
    val h = ((h24 + 11) % 12) + 1
    return String.format(Locale.US, "%d:%02d", h, m)
}
