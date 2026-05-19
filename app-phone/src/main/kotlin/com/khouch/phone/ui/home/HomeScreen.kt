package com.khouch.phone.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.khouch.core.data.model.FilterConfig
import com.khouch.core.data.model.HeroItem
import com.khouch.core.data.model.HomeItem
import com.khouch.core.data.model.HomeRail
import com.khouch.core.data.model.HomeResponse
import com.khouch.core.data.model.Stream
import com.khouch.core.data.repo.KhouchRepository
import com.khouch.phone.ui.common.CertBadge
import com.khouch.phone.ui.guide.TvGuideScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

// Navigation contract: from a tile, the home screen can either open a
// detail screen (movie/series) or play directly (live). The phone
// nav host decides which based on `mode`.
sealed class HomeAction {
    data class OpenDetail(val mode: String, val id: Int, val name: String) : HomeAction()
    data class PlayLive(val item: HomeItem) : HomeAction()
    data class SeeAll(val mode: String, val categoryId: String, val title: String) : HomeAction()
}

class HomeViewModel(private val repo: KhouchRepository) : ViewModel() {
    private val _home = MutableStateFlow<Map<String, HomeResponse>>(emptyMap())
    val home = _home.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    val filterConfig = repo.filterConfig

    // Active chip filter per mode. Set<String> of tag keys; AND logic.
    private val _filters = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val filters = _filters.asStateFlow()

    fun toggleFilter(mode: String, key: String) {
        val cur = _filters.value[mode].orEmpty()
        val next = if (key in cur) cur - key else cur + key
        _filters.value = _filters.value + (mode to next)
    }

    fun load(mode: String) {
        if (_home.value.containsKey(mode)) return
        viewModelScope.launch {
            _loading.value = true
            runCatching { repo.home(mode) }
                .onSuccess { _home.value = _home.value + (mode to it) }
            _loading.value = false
        }
    }
}

@Composable
fun HomeScreen(
    onAction: (HomeAction) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm: HomeViewModel = koinViewModel()
    // rememberSaveable so the selected tab survives leaving and
    // returning (e.g. via the player) — without it, every return
    // from PlayerScreen reset to Movies even when the user was on Live.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Movies" to "movie", "Series" to "series", "Live" to "live")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Just the antenna-potato mark — no wordmark.
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(
                            id = com.khouch.phone.R.drawable.brand_mark,
                        ),
                        contentDescription = "Khouch Potato",
                        modifier = androidx.compose.ui.Modifier.size(32.dp),
                    )
                },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, (label, _) ->
                    NavigationBarItem(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        icon = {
                            Icon(
                                when (label) {
                                    "Movies" -> Icons.Default.Movie
                                    "Series" -> Icons.Default.VideoLibrary
                                    else     -> Icons.Default.Tv
                                },
                                contentDescription = label,
                            )
                        },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pv ->
        val (_, mode) = tabs[selectedTab]

        Box(
            Modifier
                .fillMaxSize()
                .padding(pv),
        ) {
            if (mode == "live") {
                // Phone live mode shows a dedicated EPG channel list
                // (not the home-rails layout). Matches the web's TV
                // Guide view.
                TvGuideScreen(onPlay = { ch ->
                    onAction(HomeAction.PlayLive(streamToHomeItem(ch)))
                })
            } else {
                LaunchedEffect(mode) { vm.load(mode) }
                val homeData by vm.home.collectAsState()
                val loading by vm.loading.collectAsState()
                val filters by vm.filters.collectAsState()
                val filterConfig by vm.filterConfig.collectAsState()
                val data = homeData[mode]
                val activeFilter = filters[mode].orEmpty()

                when {
                    loading && data == null -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    data != null -> HomeContent(
                        hero = data.hero,
                        rails = data.rails,
                        chips = data.chips,
                        mode = mode,
                        filterConfig = filterConfig,
                        activeFilter = activeFilter,
                        onToggleFilter = { key -> vm.toggleFilter(mode, key) },
                        onAction = onAction,
                    )
                    else -> Text(
                        "Nothing to show",
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun streamToHomeItem(s: Stream): HomeItem = HomeItem(
    id = s.id,
    name = s.name,
    icon = s.icon,
    year = s.year,
    poster = null,
    usCert = null,
    container = s.container,
    tags = s.tags,
)

@Composable
private fun HomeContent(
    hero: List<HeroItem>,
    rails: List<HomeRail>,
    chips: List<com.khouch.core.data.model.ChipDef>,
    mode: String,
    filterConfig: FilterConfig?,
    activeFilter: Set<String>,
    onToggleFilter: (String) -> Unit,
    onAction: (HomeAction) -> Unit,
) {
    val filteredRails = remember(rails, activeFilter) {
        if (activeFilter.isEmpty()) rails
        else rails.map { rail ->
            rail.copy(items = rail.items.filter { item ->
                activeFilter.all { key -> item.tags.contains(key) }
            })
        }.filter { it.items.isNotEmpty() }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        if (hero.isNotEmpty() && activeFilter.isEmpty()) {
            item { HeroPager(hero, mode, onAction) }
        }
        item {
            ChipStrip(
                chips = chips,
                fallbackConfig = filterConfig,
                active = activeFilter,
                onToggle = onToggleFilter,
            )
        }
        if (filteredRails.isEmpty() && activeFilter.isNotEmpty()) {
            item {
                Text(
                    "No content matches your filters",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(filteredRails) { rail ->
            Spacer(Modifier.height(8.dp))
            // Rail header: title + total + optional "See all" link.
            // "See all" only fires for rails that have a server-set
            // category_id (per-category rails). User-curated rails
            // (Continue Watching / My List / Favorites / Recently
            // Played) don't drill in this way today.
            val canSeeAll = rail.categoryId != null && rail.total > rail.items.size
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (canSeeAll) Modifier.clickable {
                            onAction(HomeAction.SeeAll(mode, rail.categoryId!!, rail.title))
                        } else Modifier
                    )
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    rail.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (rail.total > rail.items.size) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${rail.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canSeeAll) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "See all ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rail.items) { item ->
                    StreamCard(item) {
                        onAction(HomeAction.OpenDetail(mode, item.id, item.name))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipStrip(
    chips: List<com.khouch.core.data.model.ChipDef>,
    fallbackConfig: FilterConfig?,
    active: Set<String>,
    onToggle: (String) -> Unit,
) {
    // The server ships `chips` in /api/home — already filtered to the
    // keys that appear in this profile's rails. On older servers the
    // list is empty, in which case we fall back to filterConfig's
    // master catalog (less precise but at least functional).
    val resolved = remember(chips, fallbackConfig) {
        if (chips.isNotEmpty()) chips.map { it.key to it.label }
        else buildList {
            add("4k" to "4K")
            add("movies" to "Movies")
            fallbackConfig?.groups?.forEach { add(it.key to it.label) }
        }
    }
    if (resolved.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(resolved) { (key, label) ->
            val on = key in active
            Surface(
                onClick = { onToggle(key) },
                color = if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    label,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// HeroPager — swipeable carousel of all server-picked heroes with
// a slow auto-advance. Replaces the old "render hero.first() and
// stop" path that meant the phone effectively showed one item ever.
//
// Auto-advance uses the pager's animateScrollToPage so a finger
// swipe smoothly hands off to the next auto-tick. The timer
// restarts every time currentPage changes (i.e. any swipe), so
// users get a full window on whichever slide they landed on.
@Composable
private fun HeroPager(
    hero: List<HeroItem>,
    mode: String,
    onAction: (HomeAction) -> Unit,
) {
    if (hero.size == 1) {
        // No pager chrome for a single item — would just confuse.
        HeroBanner(hero[0], mode, onAction)
        return
    }
    val pagerState = rememberPagerState(pageCount = { hero.size })
    LaunchedEffect(pagerState.currentPage, hero.size) {
        kotlinx.coroutines.delay(6_000L)
        val next = (pagerState.currentPage + 1) % hero.size
        pagerState.animateScrollToPage(next)
    }
    Box {
        HorizontalPager(state = pagerState) { page ->
            HeroBanner(hero[page], mode, onAction)
        }
        // Page indicator dots, bottom-center. Tight pill on the
        // active page, dim circles for the rest — same visual
        // grammar as the web hero so the two surfaces feel
        // related.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(hero.size) { i ->
                val active = i == pagerState.currentPage
                Box(
                    Modifier
                        .size(width = if (active) 18.dp else 6.dp, height = 6.dp)
                        .clip(if (active) RoundedCornerShape(3.dp) else CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else Color(0x99FFFFFF),
                        ),
                )
            }
        }
    }
}

@Composable
private fun HeroBanner(
    hero: HeroItem,
    mode: String,
    onAction: (HomeAction) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clickable { onAction(HomeAction.OpenDetail(mode, hero.id, hero.name)) },
    ) {
        AsyncImage(
            model = hero.backdrop ?: hero.poster ?: hero.icon,
            contentDescription = hero.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color(0x66000000),
                        1f to Color(0xEE0D1124),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                hero.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                hero.year?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                    Spacer(Modifier.width(8.dp))
                }
                hero.rating?.let { r ->
                    Icon(Icons.Default.Star, null, Modifier.size(12.dp), tint = Color(0xFFD4A544))
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "%.1f".format(r),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD4A544),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                hero.usCert?.let {
                    CertBadge(it)
                    Spacer(Modifier.width(8.dp))
                }
                hero.runtime?.let { rt ->
                    val h = rt / 60; val m = rt % 60
                    val label = if (h > 0) "${h}h ${m}m" else "${m}m"
                    Text(label, style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                }
            }
            hero.plot?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCCCCCC),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onAction(HomeAction.OpenDetail(mode, hero.id, hero.name)) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Details", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// Internal (not private) so MoreLikeThisSection on the detail screen
// can reuse the same poster-tile layout instead of duplicating it.
@Composable
internal fun StreamCard(item: HomeItem, onClick: () -> Unit) {
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
            item.usCert?.let { cert ->
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    CertBadge(cert)
                }
            }
            // MKV titles always go through the server transcoder
            // (Chrome can't decode mkv natively; ExoPlayer can but
            // many of these are also HEVC and trip our audio-codec
            // auto-fallback). Surfacing the container on the tile
            // sets expectations for the slower first-segment wait.
            if ((item.container ?: "").equals("mkv", ignoreCase = true)) {
                Text(
                    "MKV",
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    letterSpacing = 0.06.em,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            item.year?.let { y ->
                Text(
                    y,
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
            item.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
        )
    }
}
