package com.khouch.tv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

sealed class PlaybackState {
    object Loading : PlaybackState()
    data class Ready(val url: String, val isTranscode: Boolean) : PlaybackState()
    data class Error(val message: String) : PlaybackState()
}

class PlayerViewModel(
    private val repo: KhouchRepository,
    private val mode: String,
    initialStreamId: Int,
    private val ext: String,
    private val parentId: Int = 0,
    private val fromStart: Boolean = false,
) : ViewModel() {
    // streamId is mutable so the CH UP / CH DOWN remote keys can walk
    // through the live channel list without recreating the ViewModel
    // or popping / pushing nav entries.
    private var streamId: Int = initialStreamId
        private set
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Loading)
    val state = _state.asStateFlow()

    // Resume position read from server-side userState.progress on
    // first resolve. ExoPlayer seeks here once the media is prepared.
    // Live mode skips this (no concept of position).
    private val _resumeMs = MutableStateFlow(0L)
    val resumeMs = _resumeMs.asStateFlow()
    private var resumeApplied = false
    fun consumeResumeMs(): Long {
        if (resumeApplied) return 0L
        resumeApplied = true
        return _resumeMs.value
    }

    // True when the user has explicitly forced the transcoded variant
    // via the quality picker. Different from the auto-fallback flag —
    // we still want auto-fallback to fire if the direct URL also fails.
    private val _userForcedTranscode = MutableStateFlow(false)
    val userForcedTranscode = _userForcedTranscode.asStateFlow()

    private var triedTranscode = false

    // Source-side anchor of the current transcode (in ms of real
    // movie time). 0 means the playlist started from the beginning;
    // non-zero after the client has re-anchored to fast-forward past
    // the encoded edge. The PlayerScreen adds this to exo.currentPosition
    // to compute the "true" position the user is at in the movie.
    private val _transcodeAnchorMs = MutableStateFlow(0L)
    val transcodeAnchorMs = _transcodeAnchorMs.asStateFlow()

    // Full source-side duration of the title, fetched from
    // /api/movie/info. exo.duration only reflects the encoded HLS
    // playlist length (which grows linearly as ffmpeg catches up to
    // the source), so without this the scrubber would say "this
    // 2h38m movie is 14 minutes long" — and the user wouldn't know
    // they can jump ahead via the re-anchor path. 0 means unknown
    // (live mode, or panel didn't provide it).
    private val _fullDurationMs = MutableStateFlow(0L)
    val fullDurationMs = _fullDurationMs.asStateFlow()

    // Next episode after the currently-playing one, in playback order.
    // Populated on resolve() when mode=="series" by walking seriesInfo's
    // (season, episode) list. Null on last-episode-of-last-season, on
    // movies/live, or until seriesInfo lands. PlayerScreen reads this
    // when ExoPlayer fires STATE_ENDED and navigates accordingly —
    // see #11 (auto-play next episode). `label` is the "S<n>E<m>"
    // string surfaced by the near-end overlay so users can fast-skip.
    data class NextEpisode(val id: Int, val ext: String, val label: String)
    private val _nextEpisode = MutableStateFlow<NextEpisode?>(null)
    val nextEpisode = _nextEpisode.asStateFlow()

    private suspend fun computeNextEpisode() {
        if (mode != "series" || parentId <= 0) return
        val info = runCatching { repo.seriesInfo(parentId) }.getOrNull() ?: return
        val seasonsSorted = info.episodes.keys
            .mapNotNull { it.toIntOrNull()?.let { n -> n to it } }
            .sortedBy { it.first }
        // Walk seasons in order, then episodes; when we hit the current
        // streamId, return the next one (rolling over to the next
        // season's first episode if we're at the season finale).
        var found = false
        for ((seasonNum, seasonKey) in seasonsSorted) {
            for (ep in info.episodes[seasonKey].orEmpty()) {
                if (found) {
                    val nextId = ep.id.toIntOrNull() ?: return
                    val epNum = (ep.episodeNum as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content ?: "?"
                    _nextEpisode.value = NextEpisode(
                        id = nextId,
                        ext = ep.container ?: "mp4",
                        label = "S${seasonNum}E$epNum",
                    )
                    return
                }
                if (ep.id.toIntOrNull() == streamId) found = true
            }
        }
    }

    fun resolve() {
        viewModelScope.launch {
            // Resume position from server-side userState.progress.
            // Stored as { p: positionSeconds, d: duration, t: ms-since-epoch }.
            // The detail screen has already shown the timestamp and let
            // the user pick — fromStart=true means "start fresh, ignore
            // the saved position." Otherwise honor any p > 5s (we no
            // longer gate on duration because some titles save with d=0
            // when the encoded HLS playlist hadn't reported a duration
            // yet, which was silently hiding the resume point).
            if (mode != "live") {
                if (fromStart) {
                    // Mark resume as already consumed so the seek-to-resume
                    // pass in PlayerScreen is a no-op. Leaves _resumeMs at 0.
                    resumeApplied = true
                } else {
                    val p = repo.progressFor(mode, streamId)
                    if (p != null && p.p > 5) {
                        _resumeMs.value = (p.p * 1000.0).toLong()
                    }
                }
                // Fetch the real source duration so the player can
                // render a scrubber spanning the whole movie. Failure
                // (panel didn't return it) just leaves _fullDurationMs
                // at 0 and the player falls back to exo.duration —
                // same as before this feature existed.
                if (mode == "movie") {
                    repo.movieDurationSecs(streamId)?.let { secs ->
                        _fullDurationMs.value = secs * 1000L
                    }
                }
                // Pre-compute the next episode so auto-play on STATE_ENDED
                // doesn't need to wait on a network call when the current
                // episode ends. seriesInfoCache makes the second visit
                // essentially free.
                if (mode == "series") {
                    _nextEpisode.value = null
                    computeNextEpisode()
                }
            }
            // Push to userState.recents[mode] AND fire play-event for the
            // lastPlayed timestamp — the web client's "Recently Played"
            // rail reads from recents and the catalog sort reads from
            // lastPlayed. Both need to move for the TV play to surface
            // on the web side cleanly.
            val recentId = if (parentId > 0) parentId else streamId
            runCatching { repo.pushRecent(mode, recentId) }
            runCatching { repo.playEvent(mode, streamId) }
            runCatching { repo.streamUrls(mode, streamId, ext) }
                .onSuccess { urls ->
                    val direct = urls.direct
                    if (!direct.isNullOrBlank()) {
                        _state.value = PlaybackState.Ready(direct, isTranscode = false)
                    } else if (!urls.transcode.isNullOrBlank()) {
                        triedTranscode = true
                        _state.value = PlaybackState.Ready(urls.transcode, isTranscode = true)
                    } else {
                        _state.value = PlaybackState.Error("No playable URL returned")
                    }
                }
                .onFailure { _state.value = PlaybackState.Error("Couldn't get stream URL — ${it.message}") }
        }
    }

    fun fallbackToTranscode() {
        if (triedTranscode) {
            _state.value = PlaybackState.Error("Playback failed on both direct and transcode URLs")
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

    // User-driven switch between the panel-direct stream (best quality,
    // browser-decode required) and the server-transcoded HLS (always
    // works, slightly more buffer). Mirrors the web's quality button.
    fun useTranscode(forced: Boolean) {
        _userForcedTranscode.value = forced
        viewModelScope.launch {
            runCatching { repo.streamUrls(mode, streamId, ext) }
                .onSuccess { urls ->
                    val pick = if (forced) urls.transcode else urls.direct
                    if (!pick.isNullOrBlank()) {
                        _state.value = PlaybackState.Ready(pick, isTranscode = forced)
                    }
                }
        }
    }

    // Restart the transcode with ffmpeg anchored at `targetSecs` of
    // the source. Triggered when the user seeks past the encoded edge
    // of the current playlist — without this the player can only
    // forward-seek as far as ffmpeg has caught up to. After re-anchor
    // the new playlist starts at 0:00 representing `targetSecs` in
    // real movie time; we stash the offset so PlayerScreen can
    // translate exo.currentPosition back to real time for display.
    fun reanchorTranscode(targetSecs: Int) {
        if (mode == "live") return
        val secs = targetSecs.coerceAtLeast(0)
        viewModelScope.launch {
            runCatching { repo.streamUrls(mode, streamId, ext, secs) }
                .onSuccess { urls ->
                    val t = urls.transcode
                    if (!t.isNullOrBlank()) {
                        triedTranscode = true
                        // Skip the resume-from-userState seek on the new
                        // URL — user just told us where they want to be.
                        resumeApplied = true
                        _transcodeAnchorMs.value = secs * 1000L
                        _state.value = PlaybackState.Ready(t, isTranscode = true)
                    }
                }
        }
    }

    // Step through the live channel list by ±1 with wraparound. No-op
    // for movie / series modes — those don't have a linear "next
    // channel" concept (the rail order is curated, not sequential).
    // Resets transcode state because each channel resolves its own
    // URL bucket independently.
    fun changeChannel(direction: Int) {
        if (mode != "live") return
        val list = repo.streamsForMode("live")
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == streamId }
        if (idx < 0) return
        val next = list[((idx + direction) % list.size + list.size) % list.size]
        if (next.id == streamId) return
        streamId = next.id
        triedTranscode = false
        resumeApplied = true   // skip resume seek on the new channel
        _state.value = PlaybackState.Loading
        resolve()
    }

    fun recordProgress(positionSec: Double, durationSec: Double) {
        if (positionSec < 0 || !positionSec.isFinite()) return
        // Detached so the navigation pop doesn't cancel the in-flight
        // request (the ViewModel's scope dies the moment the player
        // composable disposes — viewModelScope.launch would lose the
        // post). repo.postProgressDetached uses a process-lifetime
        // CoroutineScope and also writes the entry into the local
        // userState immediately so the Resume button on the detail
        // screen reflects the new position on next composition.
        repo.postProgressDetached(mode, streamId, positionSec, durationSec)
    }
}

@Composable
fun PlayerScreen(
    mode: String,
    streamId: Int,
    ext: String,
    onBack: () -> Unit,
    parentId: Int = 0,
    fromStart: Boolean = false,
    onAutoNext: ((nextId: Int, ext: String) -> Unit)? = null,
) {
    val vm: PlayerViewModel = koinViewModel { parametersOf(mode, streamId, ext, parentId, fromStart) }
    val state by vm.state.collectAsState()
    val userForcedTranscode by vm.userForcedTranscode.collectAsState()
    val context = LocalContext.current

    // Idempotent back: any of BackHandler / onPreviewKeyEvent /
    // PlayerView.setOnKeyListener may fire depending on focus, but
    // only the first one within a 500ms window actually pops.
    var backFired by remember { mutableStateOf(false) }
    val doBack: () -> Unit = {
        if (!backFired) {
            backFired = true
            onBack()
        }
    }
    BackHandler { doBack() }
    LaunchedEffect(streamId) { vm.resolve() }

    val exo = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            // Request audio focus from the system. Without this the
            // Google TV launcher (which itself holds focus to play
            // promo audio over tiles) can silence our stream entirely
            // — the symptom was "no audio while playing, brief audio
            // when the app is killed" because focus only released
            // when our process died. handleAudioFocus=true tells
            // ExoPlayer to grab MEDIA usage focus on prepare() and
            // duck/pause on focus loss the way a video app should.
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Hold a wake lock for the duration of playback so the TV
            // screensaver doesn't kick in mid-stream. WAKE_MODE_NETWORK
            // also covers the case where the underlying socket would
            // otherwise drop on suspend.
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // BehindLiveWindowException is recoverable — the
                    // HLS playlist moved past the segment we were
                    // playing (server-side delete_segments + a long
                    // pause / rewind). Re-prepare and seek to the
                    // default position lets the player jump back into
                    // the current window instead of giving up.
                    val cause = generateSequence(error.cause as Throwable?) { it.cause }
                        .firstOrNull { it::class.java.simpleName == "BehindLiveWindowException" }
                    if (cause != null) {
                        seekToDefaultPosition()
                        prepare()
                        return
                    }
                    vm.fallbackToTranscode()
                }
            })
            // Auto-play next episode (#11) — when a series episode
            // reaches its natural end, navigate to the next one. Live
            // and movie modes never reach STATE_ENDED in practice
            // (live is endless, movies fall through to the credits and
            // the user typically backs out before STATE_ENDED) and
            // even if they did, vm.nextEpisode is null so onAutoNext
            // would be a no-op. Listener is separate from the error
            // listener above to keep concerns isolated.
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_ENDED) return
                    val next = vm.nextEpisode.value ?: return
                    onAutoNext?.invoke(next.id, next.ext)
                }
            })
        }
    }

    // Keep the screen on for the entire lifetime of the player screen.
    // The view-level PlayerView.keepScreenOn flag isn't honored by
    // every Android TV firmware — basement Sony Bravias and some Fire
    // TV builds tripped the launcher's daydream / ambient screensaver
    // mid-movie. Setting FLAG_KEEP_SCREEN_ON on the *Activity window*
    // is the recommended Android pattern and works on every TV
    // firmware we've tested.
    val activity = (context as? android.app.Activity)
    DisposableEffect(activity) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Real position = transcode anchor + exoPlayer position. The anchor
    // is non-zero only after the user has re-anchored a transcode by
    // seeking past the encoded edge; without including it, the saved
    // progress would say "you were at 1:23" when the user was actually
    // at 1:23:00 + anchor (e.g. 1:46:23 of a 2h movie).
    fun realPositionSec(): Double {
        val anchor = vm.transcodeAnchorMs.value
        return (anchor + exo.currentPosition).coerceAtLeast(0L) / 1000.0
    }
    // Prefer the full source duration (movie info from the panel) over
    // exo.duration. exo.duration on a transcoded HLS playlist reflects
    // only the encoded-so-far length, which would otherwise persist a
    // bogus "you watched 1:23 of a 5-min movie" duration that fails the
    // detail screen's resume sanity check.
    fun bestDurationSec(): Double {
        val full = vm.fullDurationMs.value
        if (full > 0L) return full / 1000.0
        return exo.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
    }

    // Periodic progress posts while playback is alive. The onDispose
    // version fires only on player exit, which means a VPS restart
    // (CI auto-deploy) mid-movie used to drop the position entirely
    // — user came back, opened the same title, and it played from
    // 0:00. Every 30 s we now push the current position; the server
    // de-dupes via timestamp so the cost is minimal.
    LaunchedEffect(state, mode) {
        if (mode == "live" || state !is PlaybackState.Ready) return@LaunchedEffect
        while (true) {
            delay(30_000L)
            if (exo.isPlaying) {
                vm.recordProgress(realPositionSec(), bestDurationSec())
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Capture position before any release work — it's only
            // valid while the player is still alive.
            vm.recordProgress(realPositionSec(), bestDurationSec())
            // ExoPlayer.release() must be called from the same thread
            // that owns the player (main), but it does ~200-500ms of
            // codec / surface teardown that — if run synchronously
            // here — stutters the nav exit animation and makes the
            // BACK press feel slow. Defer the whole tear-down to the
            // next main-thread frame so navigation pops first and the
            // user gets immediate "back" feedback. Audio cuts ~16 ms
            // later, well under the perception threshold.
            val player = exo
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    player.stop()
                    player.clearMediaItems()
                    player.release()
                }
            }
        }
    }

    LaunchedEffect(state) {
        val s = state
        if (s is PlaybackState.Ready) {
            exo.setMediaItem(MediaItem.fromUri(s.url))
            exo.prepare()
            // Seek to the resume position read from userState.progress
            // on first prepare. consumeResumeMs returns 0 on subsequent
            // calls so a transcode-fallback re-prepare doesn't yank us
            // back to the resume point after the user has already
            // scrubbed away.
            val seekTo = vm.consumeResumeMs()
            if (seekTo > 0) exo.seekTo(seekTo)
            exo.play()
        }
    }

    // CC: ExoPlayer surfaces available text tracks via Tracks; we
    // auto-enable them when present. UI toggle deferred — wiring an
    // overlay was breaking BACK navigation by grabbing focus, will
    // re-add via a remote-button handler instead.
    //
    // Audio codec sanity: many Indian-panel channels broadcast AC3 /
    // E-AC3 / MP1 audio that the Chromecast's hardware decoder doesn't
    // support. Video plays fine and ExoPlayer doesn't raise a
    // PlaybackException, but the audio track is silently dropped — the
    // symptom is "video plays, no sound, on a subset of channels". If
    // the manifest advertises audio tracks but ExoPlayer can't select a
    // supported one, fall back to the server's transcoder which always
    // re-encodes to AAC 192k.
    LaunchedEffect(exo) {
        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val hasText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !hasText)
                    .build()

                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audioGroups.isNotEmpty()) {
                    val anyAudioPlayable = audioGroups.any { g ->
                        (0 until g.length).any { i -> g.isTrackSupported(i) }
                    }
                    if (!anyAudioPlayable && state is PlaybackState.Ready
                        && !(state as PlaybackState.Ready).isTranscode) {
                        vm.fallbackToTranscode()
                    }
                }
            }
        })
    }

    // Compose-level key handling so DPAD_CENTER catches regardless of
    // which view inside PlayerView holds focus. The PlayerView's own
    // setOnKeyListener only fires when the View itself (not its
    // controller children) has focus, which isn't reliable after the
    // controller auto-hides at 5s. onPreviewKeyEvent fires first, so
    // we own the CENTER behavior end-to-end: toggle play/pause and
    // surface the controller overlay together.
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }
    val focusReq = remember { FocusRequester() }
    // Auto-focused "Up Next" card (#11). When visible during the last
    // ~20 s of a series episode, the card steals focus so OK skips
    // straight to the next episode. UP / LEFT / RIGHT defocus the card
    // back to the player without skipping. BACK still exits the player.
    val nextOverlayFocus = remember { FocusRequester() }
    var nextOverlayFocused by remember { mutableStateOf(false) }
    val nextEpState by vm.nextEpisode.collectAsState()
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    val showNextOverlay = mode == "series" &&
        nextEpState != null &&
        durationMs > 0 &&
        (durationMs - positionMs) in 1..20_000L
    LaunchedEffect(state, mode, nextEpState) {
        if (mode != "series" || nextEpState == null) return@LaunchedEffect
        if (state !is PlaybackState.Ready) return@LaunchedEffect
        while (true) {
            positionMs = exo.currentPosition
            durationMs = exo.duration.coerceAtLeast(0L)
            delay(1000L)
        }
    }
    LaunchedEffect(showNextOverlay) {
        if (showNextOverlay) {
            // Tiny delay so the card actually exists in the tree
            // before we try to focus it (composition order race).
            delay(50L)
            runCatching { nextOverlayFocus.requestFocus() }
        }
    }
    LaunchedEffect(state) {
        if (state is PlaybackState.Ready) runCatching { focusReq.requestFocus() }
    }

    // Seek by a fixed step on D-pad LEFT/RIGHT and the dedicated
    // media FF/REW keys. PlayerView's default time-bar handler scrubs
    // by 5% of duration per press (≈9 min on a 3-hour movie), which
    // makes precise seek-back impossible — onPreviewKeyEvent runs
    // before PlayerView's child views, so intercepting here keeps the
    // scrubber out of the picture entirely.
    //
    // Behavior:
    //  - Rapid presses are coalesced: each press adds to
    //    pendingSeekDeltaMs and resets a 250 ms debounce; only one
    //    real exo.seekTo() runs per burst, so HLS doesn't rebuffer
    //    once per tap.
    //  - Holding a key accelerates: KeyEvent.repeatCount grows on
    //    each repeat, and stepForRepeat picks bigger jumps the
    //    longer you hold (15s → 30s → 60s).
    //  - A pill overlay shows the running pending delta and the
    //    final delta after the seek commits; it fades after 800 ms.
    //  - A spinner overlays the player while ExoPlayer reports
    //    STATE_BUFFERING after the seek (HLS keyframe wait) so the
    //    screen doesn't look frozen.
    // ───── Seek + scrubber state (PlayerSeekController-equivalent, inline) ─────
    // See iptv-android-companion-private issue #8 for the design notes.
    // Key correctness rules:
    //   - pendingSeekDeltaMs is CLAMPED so an extra mash can never command
    //     a target past the source bounds (the old "20 mashes → crash" path)
    //   - acceleration tier = max(holdRepeatCount, mashFrequency) so both
    //     hold-the-key and mash-the-key escalate to +30 s / +60 s tiers
    //   - single in-flight reanchor: drop any reanchor call within 3 s of
    //     the previous one or while state is Loading
    //   - singleton watchdog: cancel the previous before starting a new one
    //   - lastGoodRealPosMs is captured on each successful Ready transition
    //     so a failed reanchor can fall back instead of restarting from 0
    //   - actionable scrubber: UP focuses it, LEFT/RIGHT preview, OK commits,
    //     BACK cancels
    val coScope = rememberCoroutineScope()
    var pendingSeekDeltaMs by remember { mutableStateOf(0L) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var pillHideJob by remember { mutableStateOf<Job?>(null) }
    var isBuffering by remember { mutableStateOf(false) }
    var scrubberVisible by remember { mutableStateOf(false) }
    var scrubberHideJob by remember { mutableStateOf<Job?>(null) }
    var lastReanchorAtMs by remember { mutableStateOf(0L) }
    var watchdogJob by remember { mutableStateOf<Job?>(null) }
    var lastGoodRealPosMs by remember { mutableStateOf(0L) }
    val pressHistory = remember { mutableListOf<Long>() }
    // Scrubber focus mode — separate from the brief auto-show timer. While
    // scrubFocused is true, the player's main key handler routes everything
    // to the scrubber state instead of queueing seeks directly.
    var scrubFocused by remember { mutableStateOf(false) }
    var scrubPreviewMs by remember { mutableStateOf(0L) }
    fun showScrubberBriefly() {
        scrubberVisible = true
        scrubberHideJob?.cancel()
        scrubberHideJob = coScope.launch { delay(3000L); scrubberVisible = false }
    }
    val transcodeAnchorMs by vm.transcodeAnchorMs.collectAsState()
    val fullDurMsState by vm.fullDurationMs.collectAsState()

    // Real position = transcode anchor + ExoPlayer position. Used for
    // both the scrubber and the resume-progress recording path.
    fun realPosMsNow(): Long =
        (transcodeAnchorMs + exo.currentPosition.coerceAtLeast(0L)).coerceAtLeast(0L)

    // Prefer the panel-supplied full duration; fall back to exo.duration
    // (which on a transcoded HLS playlist only reflects the encoded-so-far
    // length and would clamp seeks wrongly to 14 min on a 2 h movie).
    fun bestDurMsNow(): Long {
        if (fullDurMsState > 0L) return fullDurMsState
        return exo.duration.takeIf { it > 0 } ?: 0L
    }

    // Acceleration tier from press history. Holding a key increments the
    // OS-emitted repeatCount; mashing the key keeps repeatCount=0 but
    // produces many press events in quick succession. Take the worse of
    // the two — both UX patterns should produce the same +30/+60 ladder.
    fun stepForBurst(repeatCount: Int): Long {
        val now = System.currentTimeMillis()
        pressHistory.removeAll { now - it > 1_500L }
        pressHistory.add(now)
        val tier = maxOf(repeatCount, pressHistory.size)
        return when {
            tier < 4 -> 15_000L
            tier < 10 -> 30_000L
            else -> 60_000L
        }
    }

    // Commit any seek (from the press-mash debounce OR the scrubber's OK)
    // through one path so the anti-hang rules apply uniformly.
    fun commitSeekToRealMs(targetRealMs: Long) {
        val isTranscoded = (state as? PlaybackState.Ready)?.isTranscode == true
        val curAnchor = transcodeAnchorMs
        val exoTarget = targetRealMs - curAnchor
        val exoDur = exo.duration
        val outOfWindow = isTranscoded &&
            (exoTarget < 0L || (exoDur > 0 && exoTarget > exoDur - 1_000L))
        val now = System.currentTimeMillis()
        val recentReanchor = now - lastReanchorAtMs < 3_000L
        val isLoading = state is PlaybackState.Loading

        if (outOfWindow) {
            // Single in-flight reanchor. Drop if one is already in flight
            // or fired within the last 3 s; the user's last delta will be
            // captured on the next non-coalesced press.
            if (recentReanchor || isLoading) return
            lastReanchorAtMs = now
            vm.reanchorTranscode((targetRealMs / 1000L).toInt())
            return
        }
        val clampedExoTarget = if (exoDur > 0)
            exoTarget.coerceIn(0L, exoDur - 1_000L)
        else exoTarget.coerceAtLeast(0L)
        exo.seekTo(clampedExoTarget)
        playerViewRef.value?.showController()

        // Singleton "stuck buffering" watchdog. Replaces the per-seek
        // launch that used to fire 10 watchdogs after 10 mashes and
        // produce a reanchor stampede.
        watchdogJob?.cancel()
        if (!isTranscoded) {
            watchdogJob = coScope.launch {
                delay(8_000L)
                if (exo.playbackState == Player.STATE_BUFFERING) {
                    if (System.currentTimeMillis() - lastReanchorAtMs >= 3_000L) {
                        lastReanchorAtMs = System.currentTimeMillis()
                        vm.reanchorTranscode((targetRealMs / 1000L).toInt())
                    }
                }
            }
        }
    }

    // Press-mash entry point. Clamps the pending delta to source bounds
    // BEFORE the 250 ms debounce so the user can mash forever without
    // ever commanding a target past the end of the file.
    fun queueSeek(direction: Int, repeatCount: Int) {
        val step = stepForBurst(repeatCount)
        val proposed = pendingSeekDeltaMs + step * direction
        val cur = realPosMsNow()
        val dur = bestDurMsNow()
        val minDelta = -cur
        val maxDelta = if (dur > 0) (dur - 5_000L - cur) else Long.MAX_VALUE
        pendingSeekDeltaMs = proposed.coerceIn(minDelta, maxDelta)
        pillHideJob?.cancel()
        seekJob?.cancel()
        seekJob = coScope.launch {
            delay(250L)
            val target = (realPosMsNow() + pendingSeekDeltaMs).let {
                if (dur > 0) it.coerceIn(0L, dur - 5_000L) else it.coerceAtLeast(0L)
            }
            commitSeekToRealMs(target)
            pillHideJob = coScope.launch {
                delay(800L)
                pendingSeekDeltaMs = 0L
            }
        }
    }

    // Scrubber focus mode helpers. Enter via D-pad UP on the main surface.
    // Exit via BACK (cancel) or OK (commit).
    fun enterScrubMode() {
        scrubPreviewMs = realPosMsNow()
        scrubFocused = true
        scrubberVisible = true
        scrubberHideJob?.cancel() // don't auto-hide while in scrub mode
    }
    fun exitScrubMode(commit: Boolean) {
        if (commit) {
            val dur = bestDurMsNow()
            val target = if (dur > 0) scrubPreviewMs.coerceIn(0L, dur - 5_000L) else scrubPreviewMs
            commitSeekToRealMs(target)
        }
        scrubFocused = false
        showScrubberBriefly() // re-arm the auto-hide
    }
    fun scrubMove(direction: Int, repeatCount: Int) {
        val step = stepForBurst(repeatCount)
        val dur = bestDurMsNow()
        val target = (scrubPreviewMs + step * direction).let {
            if (dur > 0) it.coerceIn(0L, dur - 5_000L) else it.coerceAtLeast(0L)
        }
        scrubPreviewMs = target
    }

    // Buffering listener — STATE_BUFFERING fires while ExoPlayer
    // waits for the next keyframe after a seek, especially on HLS
    // transcodes. The spinner gives the user feedback that something
    // is happening when the screen would otherwise be frozen. We
    // also snapshot the real position into lastGoodRealPosMs on each
    // STATE_READY so a failing reanchor can fall back to the last
    // known-good moment instead of restarting from 0.
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                isBuffering = s == Player.STATE_BUFFERING
                if (s == Player.STATE_READY) {
                    val cur = realPosMsNow()
                    if (cur > 0) lastGoodRealPosMs = cur
                }
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }
    // Cancel the singleton watchdog when the screen disposes so it
    // doesn't fire a reanchor against a destroyed player.
    DisposableEffect(Unit) {
        onDispose { watchdogJob?.cancel(); seekJob?.cancel(); pillHideJob?.cancel() }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .focusRequester(focusReq)
        .focusable()
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // While the scrubber is the focused surface, LEFT/RIGHT move
            // a preview thumb without committing; OK commits; BACK cancels
            // and returns focus to the main surface (does NOT exit the
            // player itself). DOWN also exits scrub mode without committing.
            if (scrubFocused && mode != "live") {
                when (e.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
                    android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        scrubMove(-1, e.nativeKeyEvent.repeatCount); return@onPreviewKeyEvent true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        scrubMove(+1, e.nativeKeyEvent.repeatCount); return@onPreviewKeyEvent true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        exitScrubMode(commit = true); return@onPreviewKeyEvent true
                    }
                    android.view.KeyEvent.KEYCODE_BACK,
                    android.view.KeyEvent.KEYCODE_ESCAPE,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        exitScrubMode(commit = false); return@onPreviewKeyEvent true
                    }
                }
            }
            // While the auto-focused Up-Next card has focus, route key
            // events to its actions: OK skips to the next episode; any
            // navigation key defocuses back to the player so the user
            // can resume normal controls.
            if (nextOverlayFocused) {
                val ne = nextEpState
                when (e.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        if (ne != null) onAutoNext?.invoke(ne.id, ne.ext)
                        return@onPreviewKeyEvent true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        runCatching { focusReq.requestFocus() }
                        return@onPreviewKeyEvent true
                    }
                    // BACK falls through to the existing handler (exits player).
                }
            }
            when (e.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_BACK,
                android.view.KeyEvent.KEYCODE_ESCAPE -> { doBack(); return@onPreviewKeyEvent true }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    // D-pad UP focuses the scrubber so the user can
                    // drag a preview thumb to an exact position.
                    if (mode != "live") {
                        enterScrubMode(); true
                    } else false
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    when {
                        exo.isPlaying -> exo.pause()
                        exo.playbackState == Player.STATE_IDLE ||
                        exo.playerError != null -> {
                            exo.prepare()
                            exo.play()
                        }
                        else -> exo.play()
                    }
                    playerViewRef.value?.showController()
                    showScrubberBriefly()
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (mode != "live") {
                        queueSeek(+1, e.nativeKeyEvent.repeatCount)
                        showScrubberBriefly()
                        true
                    } else false
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (mode != "live") {
                        queueSeek(-1, e.nativeKeyEvent.repeatCount)
                        showScrubberBriefly()
                        true
                    } else false
                }
                android.view.KeyEvent.KEYCODE_CHANNEL_UP -> {
                    vm.changeChannel(+1); true
                }
                android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    vm.changeChannel(-1); true
                }
                else -> false
            }
        }
    ) {
        when (val s = state) {
            is PlaybackState.Loading -> Text(
                "Loading…",
                color = KhouchColors.FgDim,
                modifier = Modifier.padding(32.dp),
            )
            is PlaybackState.Error -> Text(
                s.message,
                color = KhouchColors.Accent,
                fontSize = 18.sp,
                modifier = Modifier.padding(32.dp).align(Alignment.Center),
            )
            is PlaybackState.Ready -> {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            playerViewRef.value = this
                            player = exo
                            // PlayerView swallows the first BACK to hide
                            // its controller, then needs a second BACK
                            // to actually exit — that was the "back is
                            // slow" feeling. Hide-on-back off so the
                            // first BACK reaches our BackHandler and
                            // pops navigation immediately. Controls
                            // still surface on D-pad CENTER.
                            useController = true
                            // Keep the controller bar pinned so the user
                            // can always see CC + play/pause + scrubber
                            // without needing to wake it via D-pad first.
                            // It auto-hides after 5s of true inactivity.
                            controllerAutoShow = true
                            controllerHideOnTouch = false
                            controllerShowTimeoutMs = 5000
                            setShowSubtitleButton(true)
                            // No on-screen FF/REW buttons — the D-pad
                            // arrows and dedicated FF/REW remote keys
                            // already handle ±15s seek, and the
                            // circular ⏪5 / 15⏩ buttons just confused
                            // people about whether they should be
                            // clicked or were live indicators.
                            setShowFastForwardButton(false)
                            setShowRewindButton(false)
                            // Hide PlayerView's built-in time bar and
                            // position/duration text. They reflect the
                            // encoded HLS playlist length (e.g. "0:14 /
                            // 0:40" for a still-transcoding 2h38m mkv),
                            // which is misleading next to our Compose
                            // overlay that shows the real movie
                            // duration. Findviewbyid is the only way
                            // — PlayerView has no setShowTimeBar API
                            // and Media3's XML can't be tweaked per
                            // instance without a custom layout file.
                            post {
                                listOf(
                                    androidx.media3.ui.R.id.exo_progress,
                                    androidx.media3.ui.R.id.exo_position,
                                    androidx.media3.ui.R.id.exo_duration,
                                    androidx.media3.ui.R.id.exo_progress_placeholder,
                                ).forEach { findViewById<android.view.View?>(it)?.visibility = android.view.View.GONE }
                            }
                            // Hide |◁ / ▷| — there's no playlist concept
                            // for movies, and series episode-walk isn't
                            // wired through ExoPlayer's queue (we navigate
                            // out of the player and back in instead). The
                            // buttons just looked like dead controls.
                            setShowPreviousButton(false)
                            setShowNextButton(false)
                            setBackgroundColor(android.graphics.Color.BLACK)
                            // Pair with ExoPlayer's wake mode so the
                            // Android TV screensaver doesn't kick in
                            // mid-playback.
                            keepScreenOn = true
                            // Intercept BACK so it pops navigation on
                            // the first press (otherwise PlayerView
                            // swallows it to hide its controller).
                            // Everything else — play/pause, seek, channel
                            // step — is handled by the outer Compose
                            // onPreviewKeyEvent which runs first; if it
                            // didn't claim the event, falling through to
                            // PlayerView's default time-bar would scrub
                            // in huge 5%-of-duration jumps (≈9 min on a
                            // 3-hour movie), so swallow the leftover
                            // D-pad keys here too.
                            setOnKeyListener { _, keyCode, event ->
                                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                                when (keyCode) {
                                    android.view.KeyEvent.KEYCODE_BACK -> {
                                        // PlayerView would otherwise swallow BACK to hide its
                                        // controller. doBack() is idempotent so it's safe even
                                        // if other handlers also see the event.
                                        doBack(); true
                                    }
                                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
                                    android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                                    android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
                                    else -> false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Seek-delta pill — appears while a burst of FF/REW
                // presses is being accumulated, and lingers for 800 ms
                // after the seek commits so the user sees the total
                // jump that was applied. Hidden at zero so normal
                // playback stays clean.
                if (pendingSeekDeltaMs != 0L) {
                    val sec = (kotlin.math.abs(pendingSeekDeltaMs) / 1000L).toInt()
                    val sign = if (pendingSeekDeltaMs > 0) "+" else "−"
                    val pretty = when {
                        sec < 60 -> "$sign${sec}s"
                        sec % 60 == 0 -> "$sign${sec / 60}m"
                        else -> "$sign${sec / 60}m ${sec % 60}s"
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(24.dp)
                            .background(
                                Color(0xCC0B0B0C),
                                shape = RoundedCornerShape(20.dp),
                            )
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = pretty,
                            color = KhouchColors.Accent,
                            fontSize = 22.sp,
                        )
                    }
                }
                // Buffering spinner — ExoPlayer goes to STATE_BUFFERING
                // after a seek while it waits for the next HLS
                // keyframe. Without this the screen looks frozen
                // mid-jump.
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(64.dp),
                        color = KhouchColors.Accent,
                    )
                }
                // "Up Next" overlay (#11) — Netflix-style hint that the
                // next series episode is about to begin. Appears during
                // the final ~20 s of an episode and auto-focuses, so a
                // single OK press skips ahead. UP/LEFT/RIGHT defocus
                // back to the player; if the user ignores it entirely,
                // STATE_ENDED auto-progresses to the same destination.
                val ne = nextEpState
                if (showNextOverlay && ne != null) {
                    val remainingMs = durationMs - positionMs
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 48.dp, bottom = 96.dp)
                            .background(
                                if (nextOverlayFocused) KhouchColors.Accent
                                else Color(0xE6111114),
                                shape = RoundedCornerShape(12.dp),
                            )
                            .focusRequester(nextOverlayFocus)
                            .onFocusChanged { focus ->
                                nextOverlayFocused = focus.isFocused
                            }
                            .focusable()
                            .clickable {
                                onAutoNext?.invoke(ne.id, ne.ext)
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Column {
                            Text(
                                "UP NEXT · in ${(remainingMs / 1000L).coerceAtLeast(0L)}s",
                                color = if (nextOverlayFocused) Color.White
                                        else KhouchColors.FgDim,
                                fontSize = 11.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "▸ ${ne.label}",
                                color = if (nextOverlayFocused) Color.White
                                        else KhouchColors.Fg,
                                fontSize = 18.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "OK to skip · UP to keep watching",
                                color = if (nextOverlayFocused) Color.White
                                        else KhouchColors.FgDim,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
                // Real-progress overlay — replaces what would
                // otherwise be PlayerView's misleading "00:00 / 14:00"
                // (the encoded-so-far length) with the actual
                // "<real time> / <full movie duration>". Renders the
                // re-anchor offset (`transcodeAnchorMs`) + the
                // player's current position over the full movie
                // length so the user can see how far into the movie
                // they really are, and that they have room to fast-
                // forward via the burst-RIGHT path even when the
                // encoded playlist ends much earlier.
                //
                // Hidden when fullDurationMs == 0 (live mode, or the
                // panel didn't supply a duration — falls back to the
                // PlayerView default).
                val fullDurMs = vm.fullDurationMs.collectAsState().value
                // Show scrubber for all non-live modes. fullDurMs comes from
                // the panel's duration_secs for movies; series episodes don't
                // have a dedicated duration endpoint so we fall back to
                // exo.duration (accurate for direct MP4/MKV files).
                if (mode != "live") {
                    var playerNowMs by remember { mutableStateOf(0L) }
                    var exoDurMs by remember { mutableStateOf(0L) }
                    // 1 Hz is the lowest cadence the eye can't tell apart
                    // from continuous on a scrubber. Half the recompositions
                    // of the previous 500ms loop. Also gated on
                    // scrubberVisible — no point burning cycles updating
                    // a Row that's currently invisible (which is the
                    // common case once playback is steady).
                    LaunchedEffect(state, transcodeAnchorMs, scrubberVisible) {
                        if (!scrubberVisible) return@LaunchedEffect
                        while (true) {
                            playerNowMs = exo.currentPosition
                            if (fullDurMs == 0L) exoDurMs = exo.duration.coerceAtLeast(0L)
                            delay(1000L)
                        }
                    }
                    val effectiveDurMs = if (fullDurMs > 0L) fullDurMs else exoDurMs
                    if (effectiveDurMs > 0L && scrubberVisible) {
                    val realPosMs = (transcodeAnchorMs + playerNowMs).coerceIn(0L, effectiveDurMs)
                    // Three sources of "what the thumb represents":
                    //  - scrubFocused (D-pad UP, user drives it): scrubPreviewMs
                    //  - pendingSeekDeltaMs != 0 (rapid press-mash): realPos + delta
                    //  - otherwise: actual playback position
                    val previewMs = when {
                        scrubFocused -> scrubPreviewMs.coerceIn(0L, effectiveDurMs)
                        pendingSeekDeltaMs != 0L ->
                            (realPosMs + pendingSeekDeltaMs).coerceIn(0L, effectiveDurMs)
                        else -> realPosMs
                    }
                    val isScrubbing = scrubFocused || pendingSeekDeltaMs != 0L
                    fun fmt(ms: Long): String {
                        val s = (ms / 1000L).toInt()
                        val h = s / 3600
                        val m = (s % 3600) / 60
                        val ss = s % 60
                        return if (h > 0) "%d:%02d:%02d".format(h, m, ss)
                               else "%d:%02d".format(m, ss)
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                if (scrubFocused) Color(0xE60B0B0C) else Color(0xB30B0B0C)
                            )
                            .padding(horizontal = 24.dp, vertical = if (scrubFocused) 18.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = fmt(if (isScrubbing) previewMs else realPosMs),
                            color = if (isScrubbing) KhouchColors.Accent else KhouchColors.Fg,
                            fontSize = if (scrubFocused) 18.sp else 16.sp,
                            modifier = Modifier.width(96.dp),
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (previewMs.toFloat() / effectiveDurMs.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp)
                                .height(if (scrubFocused) 10.dp else 6.dp),
                            color = KhouchColors.Accent,
                            trackColor = KhouchColors.FgDim.copy(
                                alpha = if (scrubFocused) 0.55f else 0.35f,
                            ),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                        Text(
                            text = fmt(effectiveDurMs),
                            color = KhouchColors.Fg,
                            fontSize = if (scrubFocused) 18.sp else 16.sp,
                            modifier = Modifier.width(96.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    }
                    if (scrubFocused) {
                        // Hint pill above the scrubber — surfaces the new
                        // affordance for users who don't know UP entered
                        // a scrub mode.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 80.dp)
                                .background(Color(0xCC0B0B0C), RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text(
                                "◀ ▶ scrub · OK to seek · BACK to cancel",
                                color = KhouchColors.FgDim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    } // end effectiveDurMs > 0
                } // end mode != "live"
            }
        }
    }
}
