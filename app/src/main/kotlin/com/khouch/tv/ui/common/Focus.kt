package com.khouch.tv.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardDefaults
import com.khouch.tv.ui.theme.KhouchColors

// App-wide focus visibility: a 3dp accent-red border when a focusable
// surface is focused. Earlier draft also pulsed a 1.04x scale, but
// the scale modifier triggered a layout pass on every focus change
// which contributed to the rail-scroll jank — keeping just the
// border, which is a draw-only effect, is materially cheaper.

private val FOCUS_BORDER_WIDTH = 3.dp
private val DEFAULT_CORNER = 8.dp

// Hoisted constants — allocated ONCE at class init, not per recompose.
// Before this, each Card invocation rebuilt the BorderStroke +
// RoundedCornerShape + Border + CardBorder objects, which on the
// home rails (~36 tiles visible at first paint) added up.
private val ACCENT_BORDER_STROKE = BorderStroke(FOCUS_BORDER_WIDTH, KhouchColors.Accent)
private val DEFAULT_SHAPE = RoundedCornerShape(DEFAULT_CORNER)
private val DEFAULT_FOCUS_BORDER = Border(border = ACCENT_BORDER_STROKE, shape = DEFAULT_SHAPE)
private val ROUND_18_SHAPE = RoundedCornerShape(18.dp)
private val ROUND_18_FOCUS_BORDER = Border(border = ACCENT_BORDER_STROKE, shape = ROUND_18_SHAPE)

@Composable
fun KhouchCardBorder(corner: Dp = DEFAULT_CORNER) =
    CardDefaults.border(
        focusedBorder = when (corner) {
            DEFAULT_CORNER -> DEFAULT_FOCUS_BORDER
            18.dp -> ROUND_18_FOCUS_BORDER
            else -> Border(
                border = ACCENT_BORDER_STROKE,
                shape = RoundedCornerShape(corner),
            )
        },
    )

// For non-Card surfaces (Box + clickable, etc) — drop this Modifier
// in. Border-only (no scale), and the shape lookup uses the same
// cached constants where possible.
fun Modifier.focusBorder(corner: Dp = DEFAULT_CORNER): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .border(
            width = if (focused) FOCUS_BORDER_WIDTH else 0.dp,
            color = if (focused) KhouchColors.Accent else Color.Transparent,
            shape = when (corner) {
                DEFAULT_CORNER -> DEFAULT_SHAPE
                18.dp -> ROUND_18_SHAPE
                else -> RoundedCornerShape(corner)
            },
        )
}
