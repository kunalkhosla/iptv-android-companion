package com.khouch.tv.ui.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.nav.startDestinationFor
import com.khouch.tv.ui.theme.KhouchColors
import org.koin.compose.koinInject

/**
 * Cheap splash that paints immediately on cold launch while
 * `startDestinationFor()` resolves in the background. Previously
 * MainActivity.onCreate did `runBlocking { startDestinationFor(...) }`
 * which made the activity start block on a synchronous /api/login
 * round trip (~5–7 s of cold-launch lag when the session cookie had
 * expired). Now the first frame paints with a single CircularProgressIndicator
 * and we navigate to the resolved destination as soon as we have it.
 */
@Composable
fun SplashScreen(onResolved: (String) -> Unit) {
    val repo: KhouchRepository = koinInject()
    LaunchedEffect(Unit) {
        val dest = startDestinationFor(repo)
        // Fire-and-forget userState bootstrap on the repo's own
        // process-lifetime ioScope so it survives Splash leaving
        // composition. Without this, /api/bootstrap was only called
        // lazily inside detail screens — Settings, the Resume button
        // on series detail, and anything else reading userState saw
        // an empty default until a detail screen was opened.
        if (dest == com.khouch.tv.nav.Routes.Main) {
            repo.bootstrapAsync()
        }
        onResolved(dest)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KhouchColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = KhouchColors.Accent,
            modifier = Modifier.padding(16.dp),
        )
    }
}
