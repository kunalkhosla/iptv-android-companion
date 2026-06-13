package com.khouch.tv.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.khouch.tv.data.model.PosterResponse
import com.khouch.tv.data.model.TmdbCastMember
import com.khouch.tv.ui.theme.KhouchColors

/**
 * TV detail-screen enrichment block — tagline, trailer button,
 * director(s), interactive cast strip (D-pad focusable), keywords.
 *
 * Cast and director taps both invoke `onPerson(name)`, which the nav
 * graph routes to the PersonCreditsScreen for the "More from <X>"
 * filmography view.
 */
@Composable
fun TmdbExtrasRow(
    mode: String,
    poster: PosterResponse?,
    onPerson: (name: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (poster == null) return
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        poster.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
            Text(
                tagline,
                color = KhouchColors.Accent,
                fontSize = 14.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.width(720.dp),
            )
        }
        poster.trailerKey?.takeIf { it.isNotBlank() }?.let { key ->
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key"))
                        )
                    }
                },
                colors = ButtonDefaults.colors(
                    containerColor = KhouchColors.Bg2,
                    contentColor = KhouchColors.Fg,
                    focusedContainerColor = KhouchColors.Bg3,
                ),
            ) { Text("▸ Trailer", fontSize = 14.sp) }
        }
        if (poster.directors.isNotEmpty()) {
            Column {
                Text(
                    if (mode == "series") "CREATED BY" else "DIRECTED BY",
                    color = KhouchColors.FgDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                // One Card per director name. Focusable so the D-pad
                // can land on it and CENTER opens the person view.
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 32.dp),
                ) {
                    items(poster.directors, key = { it }) { name ->
                        Card(
                            onClick = { onPerson(name) },
                            colors = CardDefaults.colors(
                                containerColor = KhouchColors.Bg2,
                                contentColor = KhouchColors.Fg,
                                focusedContainerColor = KhouchColors.Bg3,
                            ),
                        ) {
                            Text(
                                name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        if (poster.cast.isNotEmpty()) {
            Column {
                Text(
                    "CAST",
                    color = KhouchColors.FgDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 32.dp),
                ) {
                    items(poster.cast, key = { it.name }) { c ->
                        CastCard(c, onClick = { onPerson(c.name) })
                    }
                }
            }
        }
        if (poster.keywords.isNotEmpty()) {
            Text(
                poster.keywords.take(10).joinToString(" · "),
                color = KhouchColors.FgDim,
                fontSize = 12.sp,
                modifier = Modifier.width(720.dp),
            )
        }
    }
}

@Composable
private fun CastCard(c: TmdbCastMember, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = KhouchColors.Bg2,
            contentColor = KhouchColors.Fg,
            focusedContainerColor = KhouchColors.Bg3,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(8.dp).width(100.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(KhouchColors.Bg3),
                contentAlignment = Alignment.Center,
            ) {
                if (!c.profile.isNullOrBlank()) {
                    AsyncImage(
                        model = c.profile,
                        contentDescription = c.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(c.name.take(1).uppercase(), color = KhouchColors.FgDim, fontSize = 24.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                c.name,
                color = KhouchColors.Fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 13.sp,
            )
            c.character?.takeIf { it.isNotBlank() }?.let { ch ->
                Text(
                    ch,
                    color = KhouchColors.FgDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
