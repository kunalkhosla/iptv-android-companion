package com.khouch.phone.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.khouch.phone.LocalPipController
import com.khouch.phone.MainActivity
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PlayerScreen(
    mode: String,
    streamId: Int,
    ext: String,
    onBack: () -> Unit,
) {
    val vm: PlayerViewModel = koinViewModel { parametersOf(mode, streamId, ext) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val pip = LocalPipController.current

    // BACK pops the player so the user lands back on the detail
    // screen (where they can switch episodes / pick another title).
    // PiP is still available via the system home-gesture —
    // MainActivity.onUserLeaveHint fires it while `pipEnabled` is
    // true (set in the DisposableEffect below).
    BackHandler { onBack() }

    LaunchedEffect(streamId) { vm.resolve() }

    var buffering by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }

    // Tell MainActivity that PiP is meaningful right now so its
    // onUserLeaveHint can fire it. Reset on dispose so closing the
    // player doesn't accidentally PiP the home screen.
    DisposableEffect(Unit) {
        (activity as? MainActivity)?.pipEnabled = true
        onDispose { (activity as? MainActivity)?.pipEnabled = false }
    }

    val exo = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    buffering = (playbackState == Player.STATE_BUFFERING)
                }
                override fun onPlayerError(error: PlaybackException) {
                    vm.fallbackToTranscode()
                }
                override fun onTracksChanged(tracks: Tracks) {
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (audioGroups.isNotEmpty()) {
                        val anyPlayable = audioGroups.any { g ->
                            (0 until g.length).any { i -> g.isTrackSupported(i) }
                        }
                        if (!anyPlayable && state is PlaybackState.Ready
                            && !(state as PlaybackState.Ready).isTranscode) {
                            vm.fallbackToTranscode()
                        }
                    }
                }
            })
        }
    }

    // Periodic position save while the user is actively watching.
    // Without this, progress only persisted in the DisposableEffect
    // below — which never fires when the OS kills the Activity
    // mid-watch (locked phone, incoming call, task-switcher swipe,
    // OOM background kill). Real-world result: user watched 30 min
    // of Lucy on the phone, came back on the laptop, no resume bar.
    // 30-second cadence matches the TV app; server-side de-dupes via
    // its own debounce so the network cost is minimal.
    LaunchedEffect(state, mode) {
        if (mode == "live" || state !is PlaybackState.Ready) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            if (exo.isPlaying) {
                val pos = exo.currentPosition / 1000.0
                val dur = exo.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
                vm.recordProgress(pos, dur)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val pos = exo.currentPosition / 1000.0
            val dur = exo.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
            vm.recordProgress(pos, dur)
            val player = exo
            Handler(Looper.getMainLooper()).post {
                runCatching { player.stop(); player.clearMediaItems(); player.release() }
            }
            // Always restore upright + system bars on leave so the
            // next screen isn't stuck in landscape.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w ->
                WindowInsetsControllerCompat(w, w.decorView).show(
                    WindowInsetsCompat.Type.systemBars()
                )
            }
        }
    }

    // Cast session glue. When the user picks a Chromecast via the
    // route button: pause local ExoPlayer + hand the current URL to
    // RemoteMediaClient.load() so playback continues on the TV. When
    // the cast session ends: resume local. Cast URLs must be
    // PUBLICLY reachable from the Chromecast — a server fronted by
    // public DNS + HTTPS works (Basic Auth is disabled at the proxy
    // endpoint via HMAC signing). Local dev URLs won't work unless
    // the Chromecast is on the same LAN.
    DisposableEffect(Unit) {
        val castContext = runCatching {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(context)
        }.getOrNull() ?: return@DisposableEffect onDispose { }
        val listener = object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
            override fun onSessionStarted(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {
                val s = state
                val url = (s as? PlaybackState.Ready)?.url ?: return
                val info = com.google.android.gms.cast.MediaInfo.Builder(url)
                    .setStreamType(com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(if (url.endsWith(".m3u8")) "application/x-mpegURL" else "video/mp4")
                    .build()
                val req = com.google.android.gms.cast.MediaLoadRequestData.Builder()
                    .setMediaInfo(info)
                    .setCurrentTime(exo.currentPosition)
                    .setAutoplay(true)
                    .build()
                session.remoteMediaClient?.load(req)
                exo.pause()
            }
            override fun onSessionEnded(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
                exo.play()
            }
            override fun onSessionStarting(session: com.google.android.gms.cast.framework.CastSession) {}
            override fun onSessionStartFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
            override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {}
            override fun onSessionResuming(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {}
            override fun onSessionResumed(session: com.google.android.gms.cast.framework.CastSession, wasSuspended: Boolean) {
                onSessionStarted(session, "")
            }
            override fun onSessionResumeFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
            override fun onSessionSuspended(session: com.google.android.gms.cast.framework.CastSession, reason: Int) {}
        }
        castContext.sessionManager.addSessionManagerListener(listener, com.google.android.gms.cast.framework.CastSession::class.java)
        onDispose {
            castContext.sessionManager.removeSessionManagerListener(listener, com.google.android.gms.cast.framework.CastSession::class.java)
        }
    }

    // Wire fullscreen toggle to activity orientation + system bars.
    LaunchedEffect(fullscreen) {
        val a = activity ?: return@LaunchedEffect
        if (fullscreen) {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            a.window?.let { w ->
                WindowInsetsControllerCompat(w, w.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        } else {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            a.window?.let { w ->
                WindowInsetsControllerCompat(w, w.decorView).show(
                    WindowInsetsCompat.Type.systemBars()
                )
            }
        }
    }

    LaunchedEffect(state) {
        val s = state
        if (s is PlaybackState.Ready) {
            exo.setMediaItem(MediaItem.fromUri(s.url))
            exo.prepare()
            val seekTo = vm.consumeResumeMs()
            if (seekTo > 0) exo.seekTo(seekTo)
            exo.play()
        }
    }

    val isPip = pip.isInPip()
    // Holder for PlayerView so a side coroutine can reach into its
    // native scrubber + position/duration text views and overwrite
    // them with "real movie time" (full source duration, not the
    // encoded-HLS edge that ExoPlayer reports).
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (val s = state) {
            is PlaybackState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is PlaybackState.Error   -> Text(
                s.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
            is PlaybackState.Ready   -> {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exo
                            useController = true
                            controllerAutoShow = true
                            controllerHideOnTouch = true
                            controllerShowTimeoutMs = 4000
                            setShowSubtitleButton(true)
                            setShowFastForwardButton(true)
                            setShowRewindButton(true)
                            setBackgroundColor(android.graphics.Color.BLACK)
                            keepScreenOn = true
                            setFullscreenButtonClickListener { isFullScreen ->
                                fullscreen = isFullScreen
                            }
                            playerViewRef.value = this
                        }
                    },
                    update = { view ->
                        view.useController = !isPip
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Cast button is deferred — MediaRouteButton's
                // constructor crashes against this app's non-
                // appcompat theme (calculateContrast on a
                // translucent colorPrimary). The SessionManagerListener
                // below is wired so cast WILL work once we have a
                // launch surface for the picker. See iptv-android-
                // companion issue for the proper Cast UI plan.
                if (buffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // PlayerView ships its own scrubber + position /
                // duration text. ExoPlayer's notion of duration is
                // the encoded-HLS edge, which for a transcoded MKV
                // says "14 minutes" while the movie is 2h38m. Reach
                // into the native scrubber views and overwrite them
                // every 500ms with real movie time, sourced from
                // the panel's reported duration. PlayerView's own
                // bottom row (CC / settings / fullscreen) stays in
                // its natural layout slot — no overlay required.
                val fullDurMs = vm.fullDurationMs.collectAsState().value
                val pv = playerViewRef.value
                LaunchedEffect(pv, fullDurMs) {
                    val view = pv ?: return@LaunchedEffect
                    if (fullDurMs <= 0L) return@LaunchedEffect
                    fun fmt(ms: Long): String {
                        val s = (ms / 1000L).toInt().coerceAtLeast(0)
                        val h = s / 3600
                        val m = (s % 3600) / 60
                        val ss = s % 60
                        return if (h > 0) "%d:%02d:%02d".format(h, m, ss)
                               else "%d:%02d".format(m, ss)
                    }
                    val timeBar = view.findViewById<androidx.media3.ui.DefaultTimeBar?>(
                        androidx.media3.ui.R.id.exo_progress
                    )
                    val posText = view.findViewById<android.widget.TextView?>(
                        androidx.media3.ui.R.id.exo_position
                    )
                    val durText = view.findViewById<android.widget.TextView?>(
                        androidx.media3.ui.R.id.exo_duration
                    )

                    // While the user is dragging the scrubber, suspend
                    // the 500 ms override loop — otherwise every tick
                    // re-stamps the bar's position back to the player's
                    // current position and a slow drag feels frozen.
                    var scrubbing = false
                    val scrubListener = object : androidx.media3.ui.TimeBar.OnScrubListener {
                        override fun onScrubStart(bar: androidx.media3.ui.TimeBar, position: Long) {
                            scrubbing = true
                        }
                        override fun onScrubMove(bar: androidx.media3.ui.TimeBar, position: Long) {
                            posText?.text = fmt(position)
                        }
                        override fun onScrubStop(bar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                            scrubbing = false
                        }
                    }
                    timeBar?.addListener(scrubListener)

                    // Reserve the PlayerView's bottom band from
                    // Android's gesture nav. In landscape fullscreen
                    // the system bars are hidden but the bottom-edge
                    // gesture zone still belongs to the system, which
                    // is why the scrubber feels dead to touch even
                    // though DefaultTimeBar is wired up correctly.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val density = view.resources.displayMetrics.density
                        val bandPx = (160 * density).toInt()
                        fun applyExclusion() {
                            if (view.height <= 0 || view.width <= 0) return
                            val top = (view.height - bandPx).coerceAtLeast(0)
                            view.systemGestureExclusionRects = listOf(
                                android.graphics.Rect(0, top, view.width, view.height)
                            )
                        }
                        applyExclusion()
                        val layoutListener = android.view.View.OnLayoutChangeListener {
                            _, _, _, _, _, _, _, _, _ -> applyExclusion()
                        }
                        view.addOnLayoutChangeListener(layoutListener)
                        try {
                            while (true) {
                                if (!scrubbing) {
                                    val realMs = exo.currentPosition.coerceIn(0L, fullDurMs)
                                    timeBar?.setDuration(fullDurMs)
                                    timeBar?.setPosition(realMs)
                                    posText?.text = fmt(realMs)
                                    durText?.text = fmt(fullDurMs)
                                }
                                kotlinx.coroutines.delay(500L)
                            }
                        } finally {
                            view.removeOnLayoutChangeListener(layoutListener)
                            timeBar?.removeListener(scrubListener)
                        }
                    } else {
                        try {
                            while (true) {
                                if (!scrubbing) {
                                    val realMs = exo.currentPosition.coerceIn(0L, fullDurMs)
                                    timeBar?.setDuration(fullDurMs)
                                    timeBar?.setPosition(realMs)
                                    posText?.text = fmt(realMs)
                                    durText?.text = fmt(fullDurMs)
                                }
                                kotlinx.coroutines.delay(500L)
                            }
                        } finally {
                            timeBar?.removeListener(scrubListener)
                        }
                    }
                }
            }
        }
    }
}
