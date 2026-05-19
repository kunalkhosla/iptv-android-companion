package com.khouch.phone.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.khouch.core.data.model.PosterResponse
import com.khouch.core.data.model.TmdbCastMember

/**
 * Renders the TMDB-driven enrichment block on a detail screen:
 * tagline, ▸ Trailer button, director(s) with clickable links, cast
 * strip with portraits (each tappable to open "More from <person>"),
 * keywords as text chips. Each block self-hides when its underlying
 * field is null/empty so titles without rich metadata still render a
 * clean page.
 *
 * Same shape as the web client's renderTmdbExtras — keeps the UX
 * consistent across browsers and the phone app.
 */
@Composable
fun TmdbExtrasSection(
    mode: String,
    poster: PosterResponse?,
    onPerson: (name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (poster == null) return
    val context = LocalContext.current

    Column(modifier = modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Tagline — italic, in the brand accent so it doesn't compete
        // with the plot below.
        poster.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
            Text(
                tagline,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Trailer button. Opens YouTube via implicit intent (system
        // chooser handles routing to YouTube app vs browser). Studio
        // uploads frequently disable embedded playback, so an in-app
        // WebView iframe is unreliable; the system handler always works.
        poster.trailerKey?.takeIf { it.isNotBlank() }?.let { key ->
            OutlinedButton(
                onClick = {
                    val url = "https://www.youtube.com/watch?v=$key"
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ),
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Trailer", fontSize = 13.sp)
            }
        }

        // Director(s) / Creator(s). Names are clickable to launch the
        // person-credits screen — same UX as tapping a cast portrait.
        if (poster.directors.isNotEmpty()) {
            Column {
                Text(
                    if (mode == "series") "CREATED BY" else "DIRECTED BY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.06.sp,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    poster.directors.forEachIndexed { i, name ->
                        Text(
                            name,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { onPerson(name) },
                        )
                        if (i < poster.directors.lastIndex) {
                            Text(", ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Cast strip — circular portraits + name + character. Each
        // card is tappable; goes to PersonCreditsScreen so the user
        // can browse the rest of the actor's filmography in the
        // catalog.
        if (poster.cast.isNotEmpty()) {
            Column {
                Text(
                    "CAST",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.06.sp,
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 16.dp),
                ) {
                    items(poster.cast) { c ->
                        CastCard(c, onClick = { onPerson(c.name) })
                    }
                }
            }
        }

        // Keyword text chips. Read-only on phone for now (no
        // click-to-search wiring like on web) — keeps the surface
        // simpler. TMDB keywords are tag-shaped phrases like
        // "haunted house" or "post-apocalyptic future".
        if (poster.keywords.isNotEmpty()) {
            Column {
                Text(
                    "KEYWORDS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.06.sp,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    poster.keywords.forEach { kw ->
                        Text(
                            kw,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(50),
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CastCard(c: TmdbCastMember, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
                Text(
                    c.name.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            c.name,
            color = MaterialTheme.colorScheme.onBackground,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 12.sp,
            )
        }
    }
}
