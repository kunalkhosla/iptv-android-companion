package com.khouch.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.em
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
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.khouch.tv.data.model.HeroItem
import com.khouch.tv.data.model.HomeItem
import com.khouch.tv.data.model.HomeRail
import com.khouch.tv.data.model.HomeResponse
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.common.PanelImage
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class HomeRailsViewModel(internal val repo: KhouchRepository) : ViewModel() {
    val userState = repo.userState
    val filterConfig = repo.filterConfig
    private val _home = MutableStateFlow<HomeResponse?>(null)
    val home = _home.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    // Multi-select chip filter. Same semantics as the TV Guide: empty
    // set = "All", multi-select narrows with AND across chips. Reset on
    // mode switch so toggling Movies→Series doesn't carry stale picks.
    private val _activeFilters = MutableStateFlow<Set<String>>(emptySet())
    val activeFilters = _activeFilters.asStateFlow()
    fun toggleFilter(key: String) {
        if (key == "all") { _activeFilters.value = emptySet(); return }
        val cur = _activeFilters.value
        _activeFilters.value = if (key in cur) cur - key else cur + key
    }
    fun clearFilters() { _activeFilters.value = emptySet() }

    fun load(mode: String) {
        viewModelScope.launch {
            _loading.value = true
            runCatching { repo.home(mode) }
                .onSuccess { _home.value = it; _error.value = null }
                .onFailure { _error.value = "Couldn't load — ${it.message}" }
            _loading.value = false
        }
    }
}

@Composable
fun HomeRails(
    mode: String,
    onPlay: (Int) -> Unit,
    onOpenDetail: (Int) -> Unit,
) {
    val vm: HomeRailsViewModel = koinViewModel()
    val home by vm.home.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val userState by vm.userState.collectAsState()
    val filterConfig by vm.filterConfig.collectAsState()
    val activeFilters by vm.activeFilters.collectAsState()
    // Reset chip picks when the mode changes — "4K Movies" shouldn't
    // silently apply on Series. Repeat the same load() trigger.
    LaunchedEffect(mode) {
        vm.clearFilters()
        vm.load(mode)
    }

    val railFocus = remember { FocusRequester() }
    LaunchedEffect(home?.rails?.size) {
        if ((home?.rails?.size ?: 0) > 0) runCatching { railFocus.requestFocus() }
    }

    if (home == null && loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading ${mode}s…", color = KhouchColors.FgDim)
        }
        return
    }
    if (error != null && home == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error.orEmpty(), color = KhouchColors.Accent)
        }
        return
    }

    val rawPayload = home ?: return

    // Chip strip: 4K + Movies + a curated language set drawn from the
    // user's onboarded filter so the strip stays specific to what they
    // care about (no surprise Telugu chip on a US-only profile).
    // "Movies" and "Series" chips don't make sense here (we're already
    // in those sections) — they're TV-Guide only.
    val chips: List<Pair<String, String>> = remember(userState.filter, mode, filterConfig) {
        val genreChips = listOf(
            "all" to "All",
            "4k" to "4K",
        )
        val onboarded = userState.filter?.onboarded == true
        val onboardedGroups = userState.filter?.groups?.get(mode).orEmpty().toSet()
        val groupChips: List<Pair<String, String>> = if (onboarded) {
            com.khouch.tv.ui.common.chipCatalog(filterConfig)
                .filter { it.key in onboardedGroups }
                .map { it.key to it.label }
        } else emptyList()
        genreChips + groupChips
    }

    // Filter rails by active chips — AND across chips. Drop any rail
    // that ends up empty after filtering so the user doesn't see
    // labeled gaps. Keep the rail order from the server.
    val payload = remember(rawPayload, activeFilters) {
        if (activeFilters.isEmpty()) rawPayload
        else rawPayload.copy(
            rails = rawPayload.rails.mapNotNull { rail ->
                val items = rail.items.filter { item ->
                    activeFilters.all { key -> item.tags.contains(key) }
                }
                if (items.isEmpty()) null else rail.copy(items = items)
            },
        )
    }

    // Progressive rail reveal — show only the first 5 rails on first
    // paint so the LazyColumn measure+layout pass is cheap, then
    // expand to the full set 250ms later when the user's eye has
    // already started focusing on the top of the screen. On a slow
    // Chromecast this turns a 3-second mode-switch freeze into two
    // smaller bursts that fit between vsync windows.
    var revealedRails by remember(payload) { mutableIntStateOf(5) }
    LaunchedEffect(payload) {
        delay(250)
        revealedRails = payload.rails.size
    }
    val visibleRails = remember(payload, revealedRails) {
        payload.rails.take(revealedRails)
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val backScope = androidx.compose.runtime.rememberCoroutineScope()
    // BACK while scrolled down → snap to top of the rails. Same UX as
    // the TV Guide — first BACK resets the view, a second BACK at the
    // top falls through to the activity which exits to the launcher.
    val scrolled = listState.firstVisibleItemIndex > 0 ||
        listState.firstVisibleItemScrollOffset > 0
    BackHandler(enabled = scrolled) {
        backScope.launch { listState.animateScrollToItem(0) }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        // No focusRestorer here — Compose Foundation 1.7 throws
        // "Release should only be called once" on LazyLayoutPinnableItem
        // when the parent LazyColumn is detached during a mode switch
        // (Movies → Series). Lose nothing by dropping it; focus
        // naturally lands on the first item on re-entry.
        modifier = Modifier.fillMaxSize(),
    ) {
        if (payload.hero.isNotEmpty()) {
            item("hero") { HeroBillboard(payload.hero, mode = mode, onOpenDetail = onOpenDetail) }
        }
        item("chips") {
            FilterChipStrip(
                chips = chips,
                active = activeFilters,
                onToggle = { vm.toggleFilter(it) },
            )
        }
        if (visibleRails.isEmpty()) {
            item("empty") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No ${mode}s match this filter.",
                        color = KhouchColors.FgDim,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            itemsIndexed(visibleRails) { idx, rail ->
                RailView(
                    rail = rail,
                    focusRequester = if (idx == 0 && payload.hero.isEmpty()) railFocus else null,
                    onClick = { onOpenDetail(it.id) },
                )
            }
        }
    }
}

@Composable
private fun RailView(
    rail: HomeRail,
    focusRequester: FocusRequester?,
    onClick: (HomeItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().focusGroup()) {
        Text(
            rail.title,
            color = KhouchColors.Fg,
            fontSize = 18.sp,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
        ) {
            items(rail.items, key = { it.id }) { item ->
                PosterTile(item = item, onClick = { onClick(item) })
            }
        }
    }
}

// Internal (not private) so MoreLikeThisSection on the detail screen
// can reuse the same focusable poster tile instead of duplicating it.
@Composable
internal fun PosterTile(item: HomeItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = KhouchColors.Bg2,
            contentColor = KhouchColors.Fg,
            focusedContainerColor = KhouchColors.Bg3,
        ),
        border = com.khouch.tv.ui.common.KhouchCardBorder(),
        modifier = Modifier.width(140.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
            ) {
                // TMDB poster pre-included in the response — no per-tile
                // fetch fan-out. PanelImage falls back to initials if
                // both the TMDB poster and panel icon are missing.
                PanelImage(
                    url = item.poster ?: item.icon,
                    fallbackText = item.name.take(2).uppercase(),
                    modifier = Modifier.fillMaxSize(),
                )
                // MKV titles always go through the server transcoder —
                // helps the viewer set expectations for the longer
                // first-segment wait. ExoPlayer plays many mkv files
                // direct, but 4K HEVC + AC3 audio variants (common in
                // panel catalogs) hit the auto-fallback path anyway.
                if ((item.container ?: "").equals("mkv", ignoreCase = true)) {
                    androidx.compose.material3.Text(
                        "MKV",
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 9.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        letterSpacing = 0.08.em,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(
                                androidx.compose.ui.graphics.Color(0xB3000000),
                                RoundedCornerShape(3.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                item.name,
                color = KhouchColors.Fg,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

// Netflix-style rotating billboard. Auto-advances every 6s.
@Composable
private fun HeroBillboard(
    heroItems: List<HeroItem>,
    mode: String,
    onOpenDetail: (Int) -> Unit,
) {
    var index by remember(heroItems) { mutableIntStateOf(0) }
    LaunchedEffect(heroItems.size) {
        if (heroItems.size <= 1) return@LaunchedEffect
        while (true) {
            delay(6000)
            index = (index + 1) % heroItems.size
        }
    }
    val pick = heroItems.getOrNull(index) ?: return
    val backdrop = pick.backdrop ?: pick.poster ?: pick.icon

    // Defer the backdrop image fetch until 200ms after first paint
    // so the heavyweight Coil/AsyncImage init isn't on the critical
    // path of the home screen's first frame. Text/buttons render
    // instantly; the backdrop fades in once everything else is up.
    var showBackdrop by remember(pick.id) { mutableStateOf(false) }
    LaunchedEffect(pick.id) {
        delay(200)
        showBackdrop = true
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(440.dp),
    ) {
        if (showBackdrop && !backdrop.isNullOrBlank()) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF0B0B0C),
                            Color(0xCC0B0B0C),
                            Color(0x880B0B0C),
                            Color(0x44000000),
                        )
                    )
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color(0xCC0B0B0C),
                            Color(0xFF0B0B0C),
                        )
                    )
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 64.dp, top = 48.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                if (mode == "movie") "Featured movie" else "Featured series",
                color = KhouchColors.FgDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                pick.name,
                color = KhouchColors.Fg,
                fontSize = 36.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(700.dp),
            )
            val plot = pick.plot
            if (!plot.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    plot,
                    color = KhouchColors.Fg,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(700.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.tv.material3.Button(
                    onClick = { onOpenDetail(pick.id) },
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = KhouchColors.Accent,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("▸ View", modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                }
                if (heroItems.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        heroItems.forEachIndexed { i, _ ->
                            Box(
                                modifier = Modifier
                                    .size(width = if (i == index) 18.dp else 6.dp, height = 4.dp)
                                    .background(
                                        if (i == index) KhouchColors.Accent
                                        else KhouchColors.Line,
                                        shape = RoundedCornerShape(2.dp),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

// Chip strip above the rails — mirrors the TV Guide's chip layout so
// the same key bindings (selected vs focused, 4K/Movies/lang chips)
// feel familiar across the two surfaces. Sits as a regular LazyColumn
// item so it scrolls off-screen with the content; D-pad UP from the
// first rail lands back on it.
@Composable
private fun FilterChipStrip(
    chips: List<Pair<String, String>>,
    active: Set<String>,
    onToggle: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(chips) { (key, label) ->
            val selected = if (key == "all") active.isEmpty() else key in active
            FilterChip(label = label, selected = selected, onClick = { onToggle(key) })
        }
    }
}

// Same visual hierarchy as TvGuideScreen's FilterChip — three states
// (idle / focused / selected) all distinguishable. White focus border
// rides on top of either fill so the cursor is always findable, even
// when sitting on a selected chip.
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

// Local LazyListScope.itemsIndexed for our Rail type.
private inline fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    items: List<HomeRail>,
    crossinline content: @Composable (Int, HomeRail) -> Unit,
) {
    items(items.size, key = { i -> "rail:" + items[i].title }) { i ->
        content(i, items[i])
    }
}
