package com.khouch.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.khouch.tv.data.model.PosterResponse
import com.khouch.tv.data.repo.KhouchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.concurrent.ConcurrentHashMap

// Process-wide cache for TMDB poster lookups. We don't want every
// tile in every rail firing its own /api/poster request when it
// scrolls into view — the server caches anyway, but cutting the
// round-trip is worth the in-memory hop.
//
// One StateFlow per (mode, id) so multiple tiles for the same item
// all subscribe to the same fetch.
object TmdbPosterCache {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<PosterResponse?>>()
    private val inflight = ConcurrentHashMap<String, Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Bumped each time a fetch result lands. Kids-mode home views
    // collect it as state so the rails recompose when new certs
    // arrive and previously-hidden items now pass the filter.
    val cacheTick = MutableStateFlow(0)

    fun get(repo: KhouchRepository, mode: String, id: Int): StateFlow<PosterResponse?> {
        val key = "$mode:$id"
        val flow = flows.getOrPut(key) { MutableStateFlow(null) }
        // Kick off the fetch once per key. Live channels have no TMDB
        // equivalent, so we skip them entirely.
        if (mode != "live" && inflight.putIfAbsent(key, Unit) == null && flow.value == null) {
            scope.launch {
                runCatching { repo.poster(mode, id) }
                    .onSuccess { flow.value = it; cacheTick.value = cacheTick.value + 1 }
                    .onFailure { /* swallow — UI gracefully falls back to panel art */ }
            }
        }
        return flow
    }

    // Synchronous peek — returns the cached value if it has already
    // landed, null otherwise. Used by the kids filter to decide on
    // first render whether an item is allowed; items not yet cached
    // are filtered out (conservative) and surface as their fetch
    // completes (which re-triggers Compose via the StateFlow).
    fun peek(mode: String, id: Int): PosterResponse? =
        flows["$mode:$id"]?.value

    // Prewarm a batch — used by kids mode so the filter has enough
    // cached certs to actually show items. Capped per call.
    fun prewarm(repo: KhouchRepository, mode: String, ids: List<Int>, cap: Int = 60) {
        if (mode == "live") return
        var count = 0
        for (id in ids) {
            if (count >= cap) break
            val key = "$mode:$id"
            if (flows[key]?.value != null) continue
            if (inflight.putIfAbsent(key, Unit) != null) continue
            val flow = flows.getOrPut(key) { MutableStateFlow(null) }
            count++
            scope.launch {
                runCatching { repo.poster(mode, id) }
                    .onSuccess { flow.value = it; cacheTick.value = cacheTick.value + 1 }
                    .onFailure { /* swallow */ }
            }
        }
    }
}

@Composable
fun rememberTmdbPoster(mode: String, id: Int): StateFlow<PosterResponse?> {
    val repo: KhouchRepository = remember { GlobalContext.get().get() }
    return remember(mode, id) { TmdbPosterCache.get(repo, mode, id) }
}
