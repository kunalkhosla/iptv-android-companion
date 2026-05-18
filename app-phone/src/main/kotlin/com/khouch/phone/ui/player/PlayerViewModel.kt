package com.khouch.phone.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khouch.core.data.downloads.DownloadEntry
import com.khouch.core.data.downloads.DownloadsRepo
import com.khouch.core.data.repo.KhouchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class PlaybackState {
    object Loading : PlaybackState()
    data class Ready(val url: String, val isTranscode: Boolean) : PlaybackState()
    data class Error(val message: String) : PlaybackState()
}

class PlayerViewModel(
    private val repo: KhouchRepository,
    private val downloads: DownloadsRepo,
    val mode: String,
    val streamId: Int,
    private val ext: String,
) : ViewModel() {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Loading)
    val state = _state.asStateFlow()

    private val _resumeMs = MutableStateFlow(0L)
    val resumeMs = _resumeMs.asStateFlow()
    private var resumeApplied = false

    // Full source-side duration in ms. ExoPlayer's `duration` only
    // reflects the encoded HLS playlist length while transcoding is
    // still in progress, which can look like "this 2h38m movie is 14
    // minutes long". The player overlays this for a correct scrubber.
    // 0 means unknown (live mode, or panel didn't supply it).
    private val _fullDurationMs = MutableStateFlow(0L)
    val fullDurationMs = _fullDurationMs.asStateFlow()

    private var triedTranscode = false

    fun consumeResumeMs(): Long {
        if (resumeApplied) return 0L
        resumeApplied = true
        return _resumeMs.value
    }

    fun resolve() {
        viewModelScope.launch {
            if (mode != "live") {
                val p = repo.progressFor(mode, streamId)
                if (p != null && p.p > 30 && p.p < (p.d - 10).coerceAtLeast(0.0)) {
                    _resumeMs.value = (p.p * 1000.0).toLong()
                }
                if (mode == "movie") {
                    repo.movieDurationSecs(streamId)?.let { secs ->
                        _fullDurationMs.value = secs * 1000L
                    }
                }
            }
            runCatching { repo.pushRecent(mode, streamId) }
            runCatching { repo.playEvent(mode, streamId) }

            // If this item is already on disk, play the local file — no
            // panel hop, works offline, doesn't burn the panel's
            // max_connections=1 slot. Only for completed downloads with
            // a non-empty file (DownloadManager occasionally reports
            // "SUCCESSFUL" on a 0-byte response — see DownloadsRepo).
            val local = downloads.entryFor(mode, streamId)
            if (local != null && local.status == DownloadEntry.Status.COMPLETED) {
                val f = File(local.localPath)
                if (f.exists() && f.length() > 0) {
                    _state.value = PlaybackState.Ready(
                        "file://${f.absolutePath}", isTranscode = false
                    )
                    return@launch
                }
            }

            runCatching { repo.streamUrls(mode, streamId, ext) }
                .onSuccess { urls ->
                    val url = urls.direct?.takeIf { it.isNotBlank() }
                        ?: urls.transcode?.takeIf { it.isNotBlank() }
                    if (url != null) {
                        _state.value = PlaybackState.Ready(url, isTranscode = urls.direct.isNullOrBlank())
                    } else {
                        _state.value = PlaybackState.Error("No playable URL returned")
                    }
                }
                .onFailure { _state.value = PlaybackState.Error("Couldn't get stream URL — ${it.message}") }
        }
    }

    fun fallbackToTranscode() {
        if (triedTranscode) {
            _state.value = PlaybackState.Error("Playback failed — try another stream")
            return
        }
        triedTranscode = true
        viewModelScope.launch {
            runCatching { repo.streamUrls(mode, streamId, ext) }
                .onSuccess { urls ->
                    val t = urls.transcode
                    if (!t.isNullOrBlank()) _state.value = PlaybackState.Ready(t, isTranscode = true)
                    else _state.value = PlaybackState.Error("No transcode URL available")
                }
                .onFailure { _state.value = PlaybackState.Error("Transcode fallback failed — ${it.message}") }
        }
    }

    fun recordProgress(positionSec: Double, durationSec: Double) {
        repo.postProgressDetached(mode, streamId, positionSec, durationSec)
    }
}
