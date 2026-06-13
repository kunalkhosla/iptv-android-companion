package com.khouch.phone.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khouch.core.data.model.SimilarRail
import com.khouch.phone.ui.home.StreamCard

/**
 * "More Like This" rails on a movie/series detail screen. Renders the
 * server-built rail list as a vertical stack of horizontal LazyRows
 * (one per rail: Collection / Recommendations / Director / Similar).
 *
 * The server already applies ordering, dedup, kid-cert / language
 * filtering, and the ≥5-items-per-rail threshold — this composable
 * just iterates. Hides itself when the list is empty (movie has no
 * TMDB match, or no rail crossed the threshold).
 *
 * `mode` is the seed item's mode ("movie" or "series"). Every rail
 * item is the same mode as the seed, so onItemClick can pass it
 * straight through to navigation.
 */
@Composable
fun MoreLikeThisSection(
    mode: String,
    rails: List<SimilarRail>,
    onItemClick: (mode: String, id: Int, name: String) -> Unit,
) {
    if (rails.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rails.forEach { rail ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    rail.title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rail.items) { item ->
                        StreamCard(item) {
                            onItemClick(mode, item.id, item.name)
                        }
                    }
                }
            }
        }
    }
}
