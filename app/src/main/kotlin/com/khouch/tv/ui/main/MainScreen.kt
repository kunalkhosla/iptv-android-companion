package com.khouch.tv.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.khouch.tv.ui.guide.TvGuideScreen
import com.khouch.tv.ui.home.HomeRails
import com.khouch.tv.ui.theme.KhouchColors

// Top-level scaffold for the browsing UI. Holds the mode tabs (Live /
// Movies / Series) + search/settings icons across the top, and swaps
// the content below based on the active mode.
//
// Live mode keeps the sidebar+grid layout (no TMDB posters to hang
// rails on, and the 146 panel categories deserve a fast filter).
// Movies / Series get the Netflix-style rails layout via HomeRails.
@Composable
fun MainScreen(
    onPlay: (mode: String, id: Int) -> Unit,
    onOpenDetail: (mode: String, id: Int) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    // Default landing tab is Movies. TV Guide (Live) is the heaviest
    // screen we render (~5 k channel rows × programme strips) and was
    // the worst cold-launch experience on Chromecast hardware. The
    // phone app already lands on Movies; this matches. rememberSaveable
    // means a user who switches to Live stays there across config
    // changes.
    var mode by rememberSaveable { mutableStateOf("movie") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(
            containerColor = KhouchColors.Bg,
            contentColor = KhouchColors.Fg,
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            TopBar(
                activeMode = mode,
                onModeChange = { mode = it },
                onSearch = onSearch,
                onSettings = onSettings,
            )

            // Content area below the top bar.
            Box(modifier = Modifier.fillMaxSize()) {
                when (mode) {
                    "live" -> TvGuideScreen(
                        onPlay = { id -> onPlay("live", id) },
                    )
                    "movie", "series" -> HomeRails(
                        mode = mode,
                        onPlay = { id -> onPlay(mode, id) },
                        onOpenDetail = { id -> onOpenDetail(mode, id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    activeMode: String,
    onModeChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KhouchColors.Bg2)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Brand: just the antenna-potato mark. Wordmark was redundant
        // — users already know what app they opened, and the empty
        // horizontal space lets the mode chips breathe.
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(
                id = com.khouch.tv.R.drawable.brand_mark,
            ),
            contentDescription = "Khouch Potato",
            modifier = Modifier.size(36.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(20.dp))

        ModeChip("movie",  "Movies",  activeMode, onModeChange)
        ModeChip("series", "Series",  activeMode, onModeChange)
        ModeChip("live",   "Live",    activeMode, onModeChange)

        Spacer(Modifier.weight(1f))

        IconChip(onClick = onSearch, content = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = "Search",
                tint = KhouchColors.Fg,
                modifier = Modifier.size(20.dp),
            )
        })
        Spacer(Modifier.width(8.dp))
        IconChip(onClick = onSettings, content = {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = KhouchColors.Fg,
                modifier = Modifier.size(20.dp),
            )
        })
    }
}

@Composable
private fun ModeChip(
    id: String,
    label: String,
    activeMode: String,
    onModeChange: (String) -> Unit,
) {
    val isActive = id == activeMode
    Card(
        onClick = { onModeChange(id) },
        colors = CardDefaults.colors(
            containerColor = if (isActive) KhouchColors.Accent else Color.Transparent,
            contentColor = if (isActive) Color.White else KhouchColors.FgDim,
            focusedContainerColor = if (isActive) KhouchColors.Accent else KhouchColors.Bg3,
            focusedContentColor = Color.White,
        ),
        border = com.khouch.tv.ui.common.KhouchCardBorder(corner = 18.dp),
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Text(
            label,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun IconChip(onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = KhouchColors.Fg,
            focusedContainerColor = KhouchColors.Bg3,
            focusedContentColor = KhouchColors.Fg,
        ),
        border = com.khouch.tv.ui.common.KhouchCardBorder(corner = 18.dp),
    ) {
        Box(
            modifier = Modifier.padding(8.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
