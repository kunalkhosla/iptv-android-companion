package com.khouch.tv.nav

import com.khouch.tv.BuildConfig
import com.khouch.tv.data.repo.KhouchRepository

object Routes {
    // Splash is the initial route — renders an empty themed background
    // while a LaunchedEffect runs startDestinationFor() off the main
    // thread. Avoids blocking the activity start on a network /api/login
    // round trip (which used to add ~5-7 seconds to cold-launch on a
    // first-install device whose session had expired).
    const val Splash = "splash"
    const val ServerUrl = "server-url"
    const val Login = "login"
    const val Profile = "profile"
    const val Main = "main"
    const val Player = "player/{mode}/{streamId}/{ext}?parentId={parentId}&fromStart={fromStart}"
    fun player(mode: String, streamId: Int, ext: String, parentId: Int = 0, fromStart: Boolean = false) =
        "player/$mode/$streamId/$ext?parentId=$parentId&fromStart=$fromStart"
    const val MovieDetail = "movie/{id}"
    fun movieDetail(id: Int) = "movie/$id"
    const val SeriesDetail = "series/{id}"
    fun seriesDetail(id: Int) = "series/$id"
    const val Search = "search"
    const val Settings = "settings"
    const val Person = "person/{name}"
    fun person(name: String) = "person/${java.net.URLEncoder.encode(name, "UTF-8")}"
}

suspend fun startDestinationFor(repo: KhouchRepository): String {
    // Debug-build convenience: if the build embedded auto-login
    // credentials, run /api/login transparently so the family TV
    // install never sees the username/password screen. Release
    // builds compile with empty creds and fall through to the
    // normal flow.
    if (!repo.hasSession()
        && BuildConfig.AUTO_LOGIN_USER.isNotBlank()
        && BuildConfig.AUTO_LOGIN_PASS.isNotBlank()
    ) {
        runCatching { repo.login(BuildConfig.AUTO_LOGIN_USER, BuildConfig.AUTO_LOGIN_PASS) }
    }
    val serverConfigured = repo.currentServerUrl().isNotBlank()
    if (!serverConfigured) return Routes.ServerUrl
    if (!repo.hasSession()) return Routes.Login
    if (!repo.hasProfile()) return Routes.Profile
    return Routes.Main
}
