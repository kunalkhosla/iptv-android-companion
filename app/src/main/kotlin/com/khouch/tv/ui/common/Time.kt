package com.khouch.tv.ui.common

import java.util.Locale

/**
 * Format a playback position in seconds as `H:MM:SS` (when ≥ 1 hour)
 * or `M:SS` otherwise. Same shape the web client's `formatPos`
 * produces so resume timestamps read identically across surfaces.
 */
fun formatPlaybackPos(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}
