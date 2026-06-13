package com.khouch.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.rememberNavController
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.nav.KhouchNavGraph
import com.khouch.tv.nav.Routes
import com.khouch.tv.ui.theme.KhouchTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val repo: KhouchRepository by inject()

    // Activity-level BACK handler. Compose's BackHandler runs on the
    // main thread, which can be busy with recomposition during heavy
    // screens — pressing BACK there has been seen to queue up while
    // the main thread is composing rails, then fire as a burst when
    // the thread frees up (exiting the app). Catching BACK in
    // dispatchKeyEvent lets us pop nav from the input dispatcher path
    // even when the main thread is saturated.
    //
    // We let Compose's BackHandlers handle the actual pop so navigation
    // semantics stay consistent — just ensure the event lands.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Drop repeat presses on BACK so a held button doesn't pop
        // back the whole nav stack in a single beat.
        if (event.keyCode == KeyEvent.KEYCODE_BACK
            && event.action == KeyEvent.ACTION_DOWN
            && event.repeatCount > 0) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always start at the Splash route. The Splash composable runs
        // startDestinationFor() in a LaunchedEffect (off the main thread
        // via Dispatchers.IO inside the repo calls) and navigates to
        // the real destination once it resolves. This unblocks the
        // first frame paint — previously the activity blocked on a
        // synchronous runBlocking { startDestinationFor() } which would
        // do a full /api/login network round trip when the session
        // cookie had expired, adding ~5-7 s to cold launch.
        setContent {
            KhouchTheme {
                val navController = rememberNavController()
                KhouchNavGraph(
                    navController = navController,
                    startDestination = Routes.Splash,
                )
            }
        }
    }
}
