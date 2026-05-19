package com.khouch.core.data.api

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

// Singleton event bus for "the server returned 401 — bounce the user
// back to login". Fired from the OkHttp interceptor (worker thread)
// and collected by the nav layer to redirect into the Login screen
// regardless of which composable was on top. Replay 0 + drop-oldest
// so a long-idle nav doesn't drain a backlog of stale 401s on resume.
object AuthEvents {
    val needsLogin = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}
