package com.khouch.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.khouch.tv.ui.theme.KhouchColors

// Renders a panel icon URL (cover_big / stream_icon) with a 2-letter
// fallback while loading or on error. Used by channel tiles and any
// other place we need a "panel icon, gracefully degrading" surface.
@Composable
fun PanelImage(
    url: String?,
    fallbackText: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var failed by remember(url) { mutableStateOf(url.isNullOrBlank()) }
    Box(
        modifier = modifier.background(KhouchColors.Bg3),
        contentAlignment = Alignment.Center,
    ) {
        if (!failed && !url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
            )
        }
        if (failed || url.isNullOrBlank()) {
            Text(
                fallbackText,
                color = KhouchColors.FgDim,
                fontSize = 18.sp,
            )
        }
    }
}
