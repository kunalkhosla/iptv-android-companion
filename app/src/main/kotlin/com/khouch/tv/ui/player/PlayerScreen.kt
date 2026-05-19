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

    fun resolve() {
        viewModelScope.launch {
            // Resume position from server-side userState.progress.
            // Stored as { p: positionSeconds, d: duration }; we only
            // honor it if it's > 30s in and not within 10s of the end
            // (otherwise just play from the start / treat as finished).
            if (mode != "live") {
                val p = repo.progressFor(mode, streamId)
                if (p != null && p.p > 30 && p.p < (p.d - 10).coerceAtLeast(0.0)) {
                    _resumeMs.value = (p.p * 1000.0).toLong()
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
            }
            // Push to userState.recents[mode] AND fire play-event for the
            // lastPlayed timestamp — the web client's "Recently Played"
            // rail reads from recents and the catalog sort reads from
            // lastPlayed. Both need to move for the TV play to surface
            // on the web side cleanly.
            runCatching { repo.pushRecent(mode, streamId) }
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
) {
    val vm: PlayerViewModel = koinViewModel { parametersOf(mode, streamId, ext) }
    val state by vm.state.collectAsState()
    val userForcedTranscode by vm.userForcedTranscode.collectAsState()
    val context = LocalContext.current

    BackHandler { onBack() }
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
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Capture position before any release work — it's only
            // valid while the player is still alive.
            val pos = exo.currentPosition / 1000.0
            val dur = exo.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
            vm.recordProgress(pos, dur)
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
    val coScope = rememberCoroutineScope()
    var pendingSeekDeltaMs by remember { mutableStateOf(0L) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var pillHideJob by remember { mutableStateOf<Job?>(null) }
    var isBuffering by remember { mutableStateOf(false) }
    val transcodeAnchorMs by vm.transcodeAnchorMs.collectAsState()

    fun stepForRepeat(repeatCount: Int): Long = when {
        repeatCount < 10 -> 15_000L
        repeatCount < 20 -> 30_000L
        else -> 60_000L
    }

    fun queueSeek(direction: Int, repeatCount: Int) {
        val delta = stepForRepeat(repeatCount) * direction
        pendingSeekDeltaMs += delta
        pillHideJob?.cancel()
        seekJob?.cancel()
        seekJob = coScope.launch {
            delay(250L)
            val dur = exo.duration
            val rawTarget = exo.currentPosition + pendingSeekDeltaMs
            val maxAvail = if (dur > 0) dur - 1_000L else Long.MAX_VALUE
            val isTranscoded = (state as? PlaybackState.Ready)?.isTranscode == true

            if (isTranscoded && (rawTarget < 0L || rawTarget > maxAvail)) {
                // Out of the current playlist window — restart the
                // transcode with ffmpeg anchored at the requested
                // real-time offset. The new playlist resets to 0:00
                // representing transcodeAnchorMs in real movie time;
                // ExoPlayer re-prepares via the state observer.
                val realTimeMs = (transcodeAnchorMs + rawTarget).coerceAtLeast(0L)
                vm.reanchorTranscode((realTimeMs / 1000L).toInt())
            } else {
                val target = rawTarget.coerceIn(0L, maxAvail)
                exo.seekTo(target)
                playerViewRef.value?.showController()
            }
            // Keep the pill on screen briefly so the user sees the
            // total delta that was applied, then clear.
            pillHideJob = coScope.launch {
                delay(800L)
                pendingSeekDeltaMs = 0L
            }
        }
    }

    // Buffering listener — STATE_BUFFERING fires while ExoPlayer
    // waits for the next keyframe after a seek, especially on HLS
    // transcodes. The spinner gives the user feedback that something
    // is happening when the screen would otherwise be frozen.
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                isBuffering = s == Player.STATE_BUFFERING
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .focusRequester(focusReq)
        .focusable()
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (exo.isPlaying) exo.pause() else exo.play()
                    playerViewRef.value?.showController()
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (mode != "live") {
                        queueSeek(+1, e.nativeKeyEvent.repeatCount); true
                    } else false
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (mode != "live") {
                        queueSeek(-1, e.nativeKeyEvent.repeatCount); true
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
                                        onBack(); true
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
                if (fullDurMs > 0L) {
                    // Poll the player position every 500ms while
                    // playing. Without an explicit observer the
                    // progress bar would only update when other
                    // state changes — feels frozen.
                    var playerNowMs by remember { mutableStateOf(0L) }
                    LaunchedEffect(state, transcodeAnchorMs) {
                        while (true) {
                            playerNowMs = exo.currentPosition
                            delay(500L)
                        }
                    }
                    val realPosMs = (transcodeAnchorMs + playerNowMs).coerceIn(0L, fullDurMs)
                    // Scrub preview: while the user is mashing FF/REW
                    // and the debounced seek hasn't committed yet,
                    // pendingSeekDeltaMs is non-zero. Move the thumb
                    // to where the seek will land so the user can see
                    // their target before it commits — same UX as
                    // Netflix/Prime where the scrubber thumb tracks
                    // the input live and the actual seek commits on
                    // release. realPosMs is the "actual" playback
                    // position; previewMs is "where the thumb is."
                    val previewMs = (realPosMs + pendingSeekDeltaMs).coerceIn(0L, fullDurMs)
                    val isScrubbing = pendingSeekDeltaMs != 0L
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
                            .background(Color(0xB30B0B0C))
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left time text — shows the preview position
                        // while scrubbing, snaps to the actual position
                        // when idle. Same value the thumb represents.
                        Text(
                            text = fmt(if (isScrubbing) previewMs else realPosMs),
                            color = if (isScrubbing) KhouchColors.Accent else KhouchColors.Fg,
                            fontSize = 16.sp,
                            modifier = Modifier.width(96.dp),
                        )
                        // Linear progress whose fill represents the
                        // preview thumb position — the orange end of
                        // the bar IS the thumb. Track is full movie.
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (previewMs.toFloat() / fullDurMs.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp)
                                .height(6.dp),
                            color = KhouchColors.Accent,
                            trackColor = KhouchColors.FgDim.copy(alpha = 0.35f),
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                        Text(
                            text = fmt(fullDurMs),
                            color = KhouchColors.Fg,
                            fontSize = 16.sp,
                            modifier = Modifier.width(96.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}
