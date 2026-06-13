package com.khouch.tv.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import com.khouch.tv.ui.common.focusBorder
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.khouch.tv.data.model.EpgEntry
import com.khouch.tv.data.model.Stream
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.common.PanelImage
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// Time-grid TV Guide, mirroring the web client's renderGuide():
// channel rows on the left, time ruler across the top, program blocks
// positioned by their start_ts/stop_ts on each row's track, vertical
// red "now" line overlaid across the whole grid, EPG fetched lazily
// per visible row.
//
// Constants chosen so the entire 6h window fits cleanly on a 1080p TV
// without horizontal scroll: 280 dp channel column + 6 × 144 dp hour
// columns = 1144 dp, well inside a 1920 dp screen with side padding.

private const val GUIDE_HOURS = 6
private val DP_PER_HOUR = 144.dp
private val ROW_HEIGHT = 56.dp
private val CHANNEL_COL_WIDTH = 280.dp
private val TIME_RULER_HEIGHT = 32.dp

class TvGuideViewModel(private val repo: KhouchRepository) : ViewModel() {

    val categories = repo.categories
    val streamsByMode = repo.streams
    val userState = repo.userState
    val filterConfig = repo.filterConfig

    // Multi-select chip filter — empty set means "All". Mirrors the
    // web's `_guideQuickFilter` Set semantics. AND across all picked
    // keys (so Movies + Hindi narrows to Hindi-language movies).
    private val _activeFilters = MutableStateFlow<Set<String>>(emptySet())
    val activeFilters = _activeFilters.asStateFlow()
    fun toggleFilter(key: String) {
        if (key == "all") { _activeFilters.value = emptySet(); return }
        val cur = _activeFilters.value
        _activeFilters.value =
            if (key in cur) cur - key else cur + key
    }

    private val _activeTab = MutableStateFlow("with")
    val activeTab = _activeTab.asStateFlow()

    // Last channel the user opened from this guide. Re-rendering the
    // screen (e.g. after back from the player) scrolls and grants
    // focus to this id so we don't blast the user back to the top.
    private val _lastPlayedId = MutableStateFlow<Int?>(null)
    val lastPlayedId = _lastPlayedId.asStateFlow()
    fun rememberPlayed(id: Int) { _lastPlayedId.value = id }

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    // Channels we discovered to be EPG-empty (the panel said yes, but
    // the fetched listing was zero programs). They migrate to the
    // "Without" tab on the next render.
    val epgEmpty = ConcurrentHashMap.newKeySet<Int>()

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            // Independent runCatch per call: a bootstrap failure must NOT
            // skip the live-index load. They were chained in one
            // runCatching, so when bootstrap threw (e.g. a userState field
            // the model couldn't parse) loadIndex never ran and the guide
            // showed "0 channels" with a perfectly reachable index.
            runCatching { if (repo.categoriesForMode("live").isEmpty()) repo.bootstrap() }
                .onFailure { android.util.Log.w("KhouchGuide", "bootstrap failed: $it") }
            runCatching { if (repo.streamsForMode("live").isEmpty()) repo.loadIndex("live") }
                .onFailure { android.util.Log.w("KhouchGuide", "loadIndex(live) failed: $it") }
            _loading.value = false
        }
    }

    fun pickTab(tab: String) { _activeTab.value = tab }
    fun markEmpty(streamId: Int) { epgEmpty.add(streamId) }
    fun unmarkEmpty(streamId: Int) { epgEmpty.remove(streamId) }
    fun isFavorite(id: Int) = repo.isFavorite("live", id)
    val repository: KhouchRepository get() = repo
}

@Composable
fun TvGuideScreen(onPlay: (Int) -> Unit) {
    val vm: TvGuideViewModel = koinViewModel()
    val cats by vm.categories.collectAsState()
    val streams by vm.streamsByMode.collectAsState()
    val loading by vm.loading.collectAsState()
    val activeFilters by vm.activeFilters.collectAsState()
    val activeTab by vm.activeTab.collectAsState()
    val userState by vm.userState.collectAsState()
    val filterConfig by vm.filterConfig.collectAsState()
    val lastPlayedId by vm.lastPlayedId.collectAsState()
    val handlePlay: (Int) -> Unit = remember(onPlay) {
        { id -> vm.rememberPlayed(id); onPlay(id) }
    }
    LaunchedEffect(Unit) { vm.load() }

    val allLive = streams["live"].orEmpty()
    val liveCats = cats["live"].orEmpty()
    val catById = remember(liveCats) { liveCats.associateBy { it.categoryId } }

    // Apply the user's region/language filter FIRST so the with/without
    // EPG counts (and the chip filter below) operate on the same
    // narrowed pool the web client uses. Without this, an onboarded
    // user sees "11,453 channels" instead of just the ~1,500 that
    // match their picked language buckets.
    val liveChannels: List<Stream> = remember(allLive, userState.filter, liveCats, filterConfig) {
        val onboarded = userState.filter?.onboarded == true
        val groups = userState.filter?.groups?.get("live").orEmpty().toSet()
        if (!onboarded || groups.isEmpty()) return@remember allLive
        // Tag-driven: a channel passes if any of its server-attached
        // tags is one the user onboarded for. Falls back to category
        // regex when a stream has no tags (older index pre-tag-system).
        val catNameById = liveCats.associate { it.categoryId to it.categoryName }
        val lookup: (String?) -> String = { id -> catNameById[id].orEmpty() }
        allLive.filter { s ->
            // Fast path: use the server-attached tag list directly when present
            // to skip the per-call Set allocation in effectiveTagsFor.
            val tags: Collection<String> = if (s.tags.isNotEmpty()) s.tags
                else com.khouch.tv.ui.common.effectiveTagsFor(s, lookup)
            tags.any { it in groups }
        }
    }

    if (loading && liveChannels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading TV Guide…", color = KhouchColors.FgDim)
        }
        return
    }

    // Anchor the time ruler 5 min before now, snapped to a 30-min boundary
    // so labels fall on clean :00 / :30 marks. Mirrors the web client.
    val anchorMs = remember {
        val now = System.currentTimeMillis()
        ((now - 5 * 60_000L) / (30 * 60_000L)) * (30 * 60_000L)
    }

    // Quick-filter chips — mirrors the web exactly. Universal genre
    // chips (All / 4K / Movies / Sports / News / Music / Kids /
    // Entertainment) always shown. Additional chips come from the
    // user's onboarded group filter (state.filter.groups.live on the
    // server) — NOT from every group that happens to have channels in
    // the catalog. Otherwise a US-only viewer would see Malayalam /
    // Telugu / etc. chips that don't apply to them.
    val chips: List<Pair<String, String>> = remember(userState.filter, filterConfig) {
        val genreChips = listOf(
            "all" to "All",
            "4k" to "4K",
            "movies" to "Movies",
            "sports" to "Sports",
            "news" to "News",
            "music" to "Music",
            "kids" to "Kids",
            "entertainment" to "Entertainment",
        )
        val genreKeys = genreChips.map { it.first }.toSet()
        val onboarded = userState.filter?.onboarded == true
        val onboardedGroups = userState.filter?.groups?.get("live").orEmpty().toSet()
        val groupChips: List<Pair<String, String>> = if (onboarded) {
            com.khouch.tv.ui.common.chipCatalog(filterConfig)
                .filter { it.key in onboardedGroups && it.key !in genreKeys }
                .map { it.key to it.label }
        } else emptyList()
        genreChips + groupChips
    }

    // Apply chip filter. Server attaches a pre-computed `tags` array
    // to every stream, so chip matching is a Set.contains check —
    // O(1) per chip per channel. Falls back to category-regex when
    // tags are missing (e.g. cached index from before the tagging
    // system shipped). Multi-select AND semantics; empty set = "All".
    val filtered: List<Stream> = remember(liveChannels, activeFilters, liveCats) {
        if (activeFilters.isEmpty()) liveChannels
        else {
            val catNameById = liveCats.associate { it.categoryId to it.categoryName }
            val lookup: (String?) -> String = { id -> catNameById[id].orEmpty() }
            liveChannels.filter { ch ->
                // Compute tags once per channel rather than once per
                // (channel, active chip) pair. effectiveTagsFor allocates
                // a fresh Set even when stream.tags is present; the fast
                // path here uses the List directly.
                val tags: Collection<String> = if (ch.tags.isNotEmpty()) ch.tags
                    else com.khouch.tv.ui.common.effectiveTagsFor(ch, lookup)
                activeFilters.all { key ->
                    if (key == "all") true else key in tags
                }
            }
        }
    }

    // Split into with/without EPG. Memoized — the two .filter() passes
    // over `filtered` (5000+ channels) used to re-run on every recompose
    // (e.g. typing in the search field, toggling a chip), which was a
    // significant CPU spike on the Chromecast.
    val haveField = remember(filtered) { filtered.any { it.epgChannelId != null } }
    val (withEpg, withoutEpg) = remember(filtered, vm.epgEmpty, haveField) {
        if (haveField) {
            val wEpg = filtered.filter { !it.epgChannelId.isNullOrBlank() && it.id !in vm.epgEmpty }
            val woEpg = filtered.filter { it.epgChannelId.isNullOrBlank() } +
                    filtered.filter { !it.epgChannelId.isNullOrBlank() && it.id in vm.epgEmpty }
            wEpg to woEpg
        } else {
            filtered to emptyList<Stream>()
        }
    }

    val visible = if (activeTab == "without") withoutEpg else withEpg

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {

        // Title + channel count
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TV Guide", color = KhouchColors.Fg, fontSize = 22.sp)
            Spacer(Modifier.width(16.dp))
            val totalLabel = "${liveChannels.size.toLocaleString()}" +
                if (liveChannels.size != allLive.size) " of ${allLive.size.toLocaleString()}" else ""
            Text(
                "$totalLabel channels · next ${GUIDE_HOURS}h",
                color = KhouchColors.FgDim,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(8.dp))

        // With / Without tabs (only show "Without" if non-empty)
        if (haveField && withoutEpg.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabChip("With program data · ${withEpg.size.toLocaleString()}",
                    selected = activeTab == "with",
                    onClick = { vm.pickTab("with") })
                TabChip("Without · ${withoutEpg.size.toLocaleString()}",
                    selected = activeTab == "without",
                    onClick = { vm.pickTab("without") })
            }
            Spacer(Modifier.height(8.dp))
        }

        // Quick-filter chips — multi-select with AND semantics.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chips, key = { it.first }) { (key, label) ->
                val selected = if (key == "all") activeFilters.isEmpty()
                    else key in activeFilters
                FilterChip(label,
                    selected = selected,
                    onClick = { vm.toggleFilter(key) })
            }
        }
        Spacer(Modifier.height(12.dp))

        // Time ruler
        TimeRuler(anchorMs)
        Spacer(Modifier.height(4.dp))

        // Channel rows + their tracks, with a vertical now-line overlay
        // spanning the whole grid.
        if (visible.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No channels match this filter.", color = KhouchColors.FgDim, fontSize = 13.sp)
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().focusGroup()) {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                val backScope = androidx.compose.runtime.rememberCoroutineScope()
                // BACK while scrolled down → snap to top instead of
                // exiting the app. Lets the user reset the view with
                // one click; a second BACK at the top falls through to
                // the activity-level handler (which does exit).
                // PERF: see HomeRails for the same anti-pattern. Wrap
                // in derivedStateOf so we don't recompose the whole
                // TV Guide on every pixel of scroll offset.
                val scrolled by remember(listState) {
                    derivedStateOf {
                        listState.firstVisibleItemIndex > 0 ||
                            listState.firstVisibleItemScrollOffset > 0
                    }
                }
                BackHandler(enabled = scrolled) {
                    backScope.launch { listState.scrollToItem(0) }
                }
                // After back-nav from the player, scroll the row that
                // was last opened into view + grant it focus so the
                // user's pointer doesn't snap to row 0.
                val rowFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(visible.size, lastPlayedId) {
                    val target = lastPlayedId ?: return@LaunchedEffect
                    val idx = visible.indexOfFirst { it.id == target }
                    if (idx >= 0) {
                        listState.scrollToItem(idx)
                        runCatching { rowFocus.requestFocus() }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(visible, key = { it.id }) { ch ->
                        val focusMod = if (ch.id == lastPlayedId)
                            Modifier.focusRequester(rowFocus) else Modifier
                        GuideRow(
                            channel = ch,
                            anchorMs = anchorMs,
                            noEpg = activeTab == "without",
                            isFavorite = userState.favorites["live"]?.contains(ch.id.toLong()) == true,
                            onPlay = { handlePlay(ch.id) },
                            onEmpty = { vm.markEmpty(ch.id) },
                            onPopulated = { vm.unmarkEmpty(ch.id) },
                            repo = vm.repository,
                            modifier = focusMod,
                        )
                    }
                }

                // The now-line — a 2dp red vertical bar at the current-time
                // x-offset. Recomputes itself once a minute.
                NowLine(anchorMs)
            }
        }
    }
}

// Time labels at each hour boundary across the track.
@Composable
private fun TimeRuler(anchorMs: Long) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .height(TIME_RULER_HEIGHT)
    ) {
        Spacer(Modifier.width(CHANNEL_COL_WIDTH))
        for (h in 0 until GUIDE_HOURS) {
            Box(
                modifier = Modifier
                    .width(DP_PER_HOUR)
                    .fillMaxHeight()
                    .background(KhouchColors.Bg2),
                contentAlignment = Alignment.CenterStart,
            ) {
                val labelMs = anchorMs + h * 3600_000L
                Text(
                    formatHour(labelMs),
                    color = KhouchColors.FgDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

// One channel row: fixed channel chip on the left, program track on
// the right with absolutely-positioned program blocks.
@Composable
private fun GuideRow(
    channel: Stream,
    anchorMs: Long,
    noEpg: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onEmpty: () -> Unit,
    onPopulated: () -> Unit,
    repo: KhouchRepository,
    modifier: Modifier = Modifier,
) {
    val programs by remember(channel.id) {
        if (noEpg) MutableStateFlow<List<EpgEntry>>(emptyList())
        else GuideEpgCache.flow(channel.id)
    }.collectAsState()

    // Debounce: wait 150ms before firing the fetch so rows that scroll
    // past quickly during a fast D-pad swipe don't all fire in parallel.
    // LaunchedEffect is cancelled when the row leaves composition, so
    // only rows that stay visible long enough actually trigger a request.
    LaunchedEffect(channel.id, noEpg) {
        if (!noEpg && !GuideEpgCache.hasFetched(channel.id)) {
            delay(150)
            GuideEpgCache.fetch(repo, channel.id)
        }
    }

    LaunchedEffect(channel.id, programs.size, noEpg) {
        if (!noEpg) {
            if (programs.isEmpty() && GuideEpgCache.hasFetched(channel.id)) onEmpty()
            if (programs.isNotEmpty()) onPopulated()
        }
    }

    Row(modifier = modifier
        .fillMaxWidth()
        .height(ROW_HEIGHT)
        .padding(vertical = 2.dp)
    ) {
        // Left: channel chip
        Card(
            onClick = onPlay,
            colors = CardDefaults.colors(
                containerColor = KhouchColors.Bg2,
                contentColor = KhouchColors.Fg,
                focusedContainerColor = KhouchColors.Bg3,
            ),
            border = com.khouch.tv.ui.common.KhouchCardBorder(),
            modifier = Modifier
                .width(CHANNEL_COL_WIDTH)
                .fillMaxHeight()
                .padding(end = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                ) {
                    PanelImage(
                        url = channel.icon,
                        fallbackText = channel.name.take(2).uppercase(),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    channel.name,
                    color = KhouchColors.Fg,
                    style = com.khouch.tv.ui.theme.KhouchChannelNameStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isFavorite) Text("★", color = KhouchColors.Accent, fontSize = 14.sp)
            }
        }

        // Right: program track
        Box(modifier = Modifier
            .fillMaxHeight()
            .width(DP_PER_HOUR * GUIDE_HOURS)
            .background(KhouchColors.Bg2)
        ) {
            if (noEpg) {
                Text(
                    "(no schedule — press OK to play)",
                    color = KhouchColors.FgDim,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp),
                )
                return@Box
            }
            if (programs.isEmpty()) {
                Text(
                    "Schedule loading…",
                    color = KhouchColors.FgDim,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp),
                )
                return@Box
            }

            val nowMs = System.currentTimeMillis()
            val endMs = anchorMs + GUIDE_HOURS * 3600_000L
            // Sort + clip overlapping windows + cap to a sane number
            // of blocks per row. 12 blocks max keeps the composition
            // tree small even on slow TV silicon (no real EPG dump
            // gives more than ~10 distinct programs in a 6h window
            // anyway).
            val blocks = remember(programs, anchorMs) {
                programs
                    .asSequence()
                    .filter { it.startTs != null && it.stopTs != null }
                    .filter { (it.stopTs!! * 1000L) > anchorMs && (it.startTs!! * 1000L) < endMs }
                    .sortedBy { it.startTs!! }
                    .toList()
                    .take(12)
            }
            for (i in blocks.indices) {
                val p = blocks[i]
                val s = p.startTs!! * 1000L
                var e = p.stopTs!! * 1000L
                val next = blocks.getOrNull(i + 1)
                if (next != null) e = minOf(e, next.startTs!! * 1000L)
                if (e <= s) continue
                val leftMin = ((s - anchorMs).coerceAtLeast(0L)) / 60_000.0
                val rightMin = ((e - anchorMs).coerceAtMost(endMs - anchorMs)) / 60_000.0
                val widthMin = (rightMin - leftMin).coerceAtLeast(0.0)
                if (widthMin * (DP_PER_HOUR.value / 60.0) < 4) continue
                val isNow = s <= nowMs && e > nowMs
                ProgramBlock(
                    title = p.title,
                    startMs = s,
                    stopMs = p.stopTs * 1000L,
                    isNow = isNow,
                    leftDp = (leftMin * (DP_PER_HOUR.value / 60.0)).dp,
                    widthDp = (widthMin * (DP_PER_HOUR.value / 60.0)).dp,
                )
            }
        }
    }
}

// Plain Box (NO clickable / focusable) — the comment originally said
// "the track is the only focus target" but the implementation was
// adding .clickable(onClick) to every block, which silently made each
// programme block focusable. With ~12 blocks/row × ~10 visible rows,
// that's ~120 extra focus targets per viewport — Compose for TV's
// focus traversal is O(focusables) on every D-pad event. Removing
// click + focus border collapses traversal cost to 1 focusable per
// row (the channel chip on the left).
@Composable
private fun ProgramBlock(
    title: String?,
    startMs: Long,
    stopMs: Long,
    isNow: Boolean,
    leftDp: androidx.compose.ui.unit.Dp,
    widthDp: androidx.compose.ui.unit.Dp,
) {
    val bg = if (isNow) Color(0xFF2A1719) else KhouchColors.Bg3
    Box(
        modifier = Modifier
            .offset(x = leftDp)
            .width(widthDp)
            .height(ROW_HEIGHT - 8.dp)
            .padding(end = 2.dp)
            .background(bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                title?.takeIf { it.isNotBlank() } ?: "Untitled",
                color = KhouchColors.Fg,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatTime(startMs)}–${formatTime(stopMs)}",
                color = KhouchColors.FgDim,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

// 2dp vertical red bar at the current-time x-offset across the rows.
// Recomputes itself once a minute via a state-driven LaunchedEffect.
@Composable
private fun NowLine(anchorMs: Long) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val offsetMin = (nowMs - anchorMs) / 60_000.0
    val maxMin = GUIDE_HOURS * 60.0
    if (offsetMin < 0 || offsetMin > maxMin) return
    val leftDp = CHANNEL_COL_WIDTH + (offsetMin * (DP_PER_HOUR.value / 60.0)).dp
    Box(modifier = Modifier
        .offset(x = leftDp)
        .fillMaxHeight()
        .width(2.dp)
        .background(KhouchColors.Accent)
    )
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (selected) KhouchColors.Bg3 else KhouchColors.Bg2,
            contentColor = KhouchColors.Fg,
            focusedContainerColor = KhouchColors.Accent,
            focusedContentColor = Color.White,
        ),
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

// Three visually distinct states so the user can always tell at a
// glance which chips are picked AND which one the cursor is on:
//   - Unselected, idle:    Bg2 fill, FgDim text
//   - Unselected, focused: Bg3 fill + white border outline
//   - Selected, idle:      Accent fill, white text
//   - Selected, focused:   Accent fill + white border outline
// The KhouchCardBorder draws a 2dp white ring only when focused, so
// selected-and-focused stays accent-filled while still showing the
// cursor location.
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (selected) KhouchColors.Accent else KhouchColors.Bg2,
            contentColor = if (selected) Color.White else KhouchColors.FgDim,
            focusedContainerColor = if (selected) KhouchColors.Accent else KhouchColors.Bg3,
            focusedContentColor = Color.White,
        ),
        border = com.khouch.tv.ui.common.KhouchCardBorder(corner = 14.dp),
    ) {
        Text(label, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

private fun formatHour(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    val h12 = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
    val am = cal.get(Calendar.AM_PM) == Calendar.AM
    val min = cal.get(Calendar.MINUTE)
    return if (min == 0) String.format(Locale.US, "%d %s", h12, if (am) "AM" else "PM")
    else String.format(Locale.US, "%d:%02d %s", h12, min, if (am) "AM" else "PM")
}

private fun formatTime(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    val h12 = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
    val min = cal.get(Calendar.MINUTE)
    return String.format(Locale.US, "%d:%02d", h12, min)
}

private fun Int.toLocaleString(): String = "%,d".format(this)

// Per-channel EPG cache for the guide. Same `programs` payload as the
// channel-tile NowPlayingLine consumes, but we cache the whole list
// (the tile only kept the current entry). Hot-path lookup must be
// O(1) so rendering scrolls smoothly on TV silicon.
private object GuideEpgCache {
    private val flows = ConcurrentHashMap<Int, MutableStateFlow<List<EpgEntry>>>()
    private val inflight = ConcurrentHashMap<Int, Unit>()
    private val fetched = ConcurrentHashMap.newKeySet<Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun flow(streamId: Int): StateFlow<List<EpgEntry>> =
        flows.getOrPut(streamId) { MutableStateFlow(emptyList()) }

    fun fetch(repo: KhouchRepository, streamId: Int) {
        val flow = flows.getOrPut(streamId) { MutableStateFlow(emptyList()) }
        if (inflight.putIfAbsent(streamId, Unit) == null && flow.value.isEmpty()) {
            scope.launch {
                runCatching { repo.epgShort(streamId) }
                    .onSuccess { resp ->
                        flow.value = resp.programs
                        fetched.add(streamId)
                    }
                    .onFailure { fetched.add(streamId) }
            }
        }
    }

    fun hasFetched(streamId: Int): Boolean = streamId in fetched
}
