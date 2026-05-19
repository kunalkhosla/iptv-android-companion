package com.khouch.tv.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.khouch.tv.data.model.SimilarRail
import com.khouch.tv.ui.home.PosterTile
import com.khouch.tv.ui.theme.KhouchColors

/**
 * "More Like This" rails on a movie/series detail screen. Renders the
 * server-built rail list as a vertical stack of focusable horizontal
 * LazyRows (one per rail: Collection / Recommendations / Director /
 * Similar).
 *
 * Server-side already applies ordering, dedup, kid-cert / language
 * filtering, and the ≥5-items-per-rail threshold — this composable
 * just iterates. Hides itself when the list is empty.
 *
 * D-pad navigation: UP/DOWN moves between rails (and between rails
 * and the action row above), LEFT/RIGHT moves within a rail. PosterTile
 * is a TV Material Card that handles focus visuals.
 */
@Composable
fun MoreLikeThisSection(
    mode: String,
    rails: List<SimilarRail>,
    onItemClick: (mode: String, id: Int, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rails.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        rails.forEach { rail ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    rail.title,
                    color = KhouchColors.Fg,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(rail.items, key = { it.id }) { item ->
                        PosterTile(item = item, onClick = {
                            onItemClick(mode, item.id, item.name)
                        })
                    }
                }
            }
        }
    }
}
