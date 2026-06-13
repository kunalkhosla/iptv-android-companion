package com.khouch.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.khouch.tv.data.model.EpgEntry
import com.khouch.tv.data.repo.KhouchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.delay
import org.koin.core.context.GlobalContext
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

// Process-wide cache for short EPG lookups, mirroring TmdbPosterCache.
// One fetch per channel id; the result is the current "now playing"
// entry (best-effort — the panel doesn't always include timestamps,
// so we lean on the first listing as a fallback).
object EpgCache {
    private val flows = ConcurrentHashMap<Int, MutableStateFlow<EpgEntry?>>()
    private val inflight = ConcurrentHashMap<Int, Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun flow(streamId: Int): StateFlow<EpgEntry?> =
        flows.getOrPut(streamId) { MutableStateFlow(null) }

    fun fetch(repo: KhouchRepository, streamId: Int) {
        val flow = flows.getOrPut(streamId) { MutableStateFlow(null) }
        if (inflight.putIfAbsent(streamId, Unit) == null && flow.value == null) {
            scope.launch {
                runCatching { repo.epgShort(streamId) }
                    .onSuccess { resp ->
                        val nowSec = System.currentTimeMillis() / 1000
                        val current = resp.programs.firstOrNull {
                            val s = it.startTs ?: return@firstOrNull false
                            val e = it.stopTs ?: return@firstOrNull false
                            nowSec in s until e
                        } ?: resp.programs.firstOrNull()
                        flow.value = current
                    }
            }
        }
    }
}

@Composable
fun NowPlayingLine(streamId: Int, modifier: Modifier = Modifier) {
    val repo: KhouchRepository = remember { GlobalContext.get().get() }
    val entry by EpgCache.flow(streamId).collectAsState()
    LaunchedEffect(streamId) {
        delay(150)
        EpgCache.fetch(repo, streamId)
    }
    val title = entry?.title
    if (title.isNullOrBlank()) return
    Text(
        "● $title",
        color = Color(0xFFFBBF24),
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
