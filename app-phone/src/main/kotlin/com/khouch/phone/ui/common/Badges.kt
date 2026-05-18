package com.khouch.phone.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CertBadge(cert: String, modifier: Modifier = Modifier) {
    val bg = when (cert) {
        "G", "TV-G", "TV-Y" -> Color(0xFF2E7D32)
        "PG", "TV-PG", "TV-Y7" -> Color(0xFF1565C0)
        "PG-13", "TV-14" -> Color(0xFFF57F17)
        "R", "NC-17", "TV-MA" -> Color(0xFFC62828)
        else -> Color(0xFF424242)
    }
    Text(
        cert,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        fontSize = 8.sp,
        modifier = modifier
            .background(bg, RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}

fun formatPos(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%d:%02d", m, s)
}
