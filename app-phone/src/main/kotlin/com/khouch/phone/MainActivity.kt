package com.khouch.phone

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import com.khouch.phone.ui.theme.KhouchPhoneTheme
import com.khouch.phone.nav.PhoneNavHost

// Exposed to Composables (player + nav) so they can trigger PiP and
// observe whether the activity is currently in PiP mode. Set by
// MainActivity via CompositionLocalProvider in setContent.
class PipController(
    val enter: () -> Unit,
    val isInPip: () -> Boolean,
)
val LocalPipController = compositionLocalOf<PipController> {
    error("PipController not provided — wrap content in MainActivity's setContent")
}

class MainActivity : ComponentActivity() {

    // The player screen flips this to true while playback is active.
    // Used to decide whether onUserLeaveHint should auto-enter PiP
    // (we never want PiP for the home / login / settings screens).
    @Volatile var pipEnabled: Boolean = false

    // Recomposes the player when PiP state flips so it can hide its
    // controls overlay (controls aren't interactive in PiP anyway).
    private val pipState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pip = PipController(
            enter = ::tryEnterPip,
            isInPip = { pipState.value },
        )
        setContent {
            KhouchPhoneTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalPipController provides pip) {
                    PhoneNavHost()
                }
            }
        }
    }

    private fun tryEnterPip() {
        if (!pipEnabled) {
            Log.d("KhouchPip", "skip enter: pipEnabled=false (not on player)")
            return
        }
        if (isInPictureInPictureMode) return
        val params = PictureInPictureParams.Builder()
            // 16:9 — clamps automatically if real video aspect differs.
            .setAspectRatio(Rational(16, 9))
            .build()
        val ok = runCatching { enterPictureInPictureMode(params) }
            .onFailure { Log.w("KhouchPip", "enterPiP failed", it) }
            .getOrDefault(false)
        Log.d("KhouchPip", "enterPiP returned $ok")
    }

    // Fires when the user dismisses the activity (home button, recent
    // apps gesture). Matches YouTube / Netflix behaviour — playback
    // keeps going in a floating window instead of pausing.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        tryEnterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipState.value = isInPictureInPictureMode
    }
}
