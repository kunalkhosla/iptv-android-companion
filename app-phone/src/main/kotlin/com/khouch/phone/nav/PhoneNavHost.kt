package com.khouch.phone.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.khouch.core.data.api.AuthEvents
import com.khouch.core.data.repo.KhouchRepository
import com.khouch.phone.ui.category.CategoryAction
import com.khouch.phone.ui.category.CategoryScreen
import com.khouch.phone.ui.detail.MovieDetailScreen
import com.khouch.phone.ui.detail.SeriesDetailScreen
import com.khouch.phone.ui.downloads.DownloadsScreen
import com.khouch.phone.ui.home.HomeAction
import com.khouch.phone.ui.home.HomeScreen
import com.khouch.phone.ui.login.LoginScreen
import com.khouch.phone.ui.login.ServerUrlScreen
import com.khouch.phone.ui.player.PlayerScreen
import com.khouch.phone.ui.profile.ProfilePickerScreen
import com.khouch.phone.ui.search.SearchScreen
import com.khouch.phone.ui.settings.SettingsScreen
import org.koin.compose.koinInject

object Routes {
    const val ServerUrl   = "server-url"
    const val Login       = "login"
    const val Profile     = "profile"
    const val Home        = "home"
    const val Search      = "search"
    const val Settings    = "settings"
    const val Downloads   = "downloads"
    const val MovieDetail = "detail/movie/{id}?name={name}"
    const val SeriesDetail = "detail/series/{id}?name={name}"
    const val Category    = "category/{mode}/{categoryId}?title={title}"
    const val Player      = "player/{mode}/{streamId}/{ext}"
    const val Person      = "person/{name}"
    fun movieDetail(id: Int, name: String) =
        "detail/movie/$id?name=${java.net.URLEncoder.encode(name, "UTF-8")}"
    fun seriesDetail(id: Int, name: String) =
        "detail/series/$id?name=${java.net.URLEncoder.encode(name, "UTF-8")}"
    fun person(name: String) = "person/${java.net.URLEncoder.encode(name, "UTF-8")}"
    fun category(mode: String, categoryId: String, title: String) =
        "category/$mode/${java.net.URLEncoder.encode(categoryId, "UTF-8")}" +
        "?title=${java.net.URLEncoder.encode(title, "UTF-8")}"
    fun player(mode: String, streamId: Int, ext: String) = "player/$mode/$streamId/$ext"
}

fun goToLogin(navController: androidx.navigation.NavController) {
    navController.navigate(Routes.Login) { popUpTo(0) { inclusive = true } }
}

@Composable
fun PhoneNavHost() {
    val repo: KhouchRepository = koinInject()
    val navController = rememberNavController()
    var startDest by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        AuthEvents.needsLogin.collect { goToLogin(navController) }
    }

    LaunchedEffect(Unit) {
        val serverUrl = repo.currentServerUrl()
        android.util.Log.d("KhouchNav",
            "serverUrl='$serverUrl' hasSession=${repo.hasSession()} hasProfile=${repo.hasProfile()}")
        startDest = when {
            serverUrl.isBlank()    -> Routes.ServerUrl
            !repo.hasSession()     -> Routes.Login
            else -> {
                val result = runCatching { repo.bootstrap() }
                when {
                    result.isFailure   -> Routes.Login
                    !repo.hasProfile() -> Routes.Profile
                    else               -> Routes.Home
                }
            }
        }
    }

    val start = startDest ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.ServerUrl) {
            ServerUrlScreen(onContinue = {
                navController.navigate(Routes.Login) {
                    popUpTo(Routes.ServerUrl) { inclusive = true }
                }
            })
        }
        composable(Routes.Login) {
            LoginScreen(
                onSuccess = {
                    navController.navigate(Routes.Profile) {
                        popUpTo(Routes.Login) { inclusive = true }
                    }
                },
                onChangeServer = {
                    navController.navigate(Routes.ServerUrl) {
                        popUpTo(Routes.Login) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.Profile) {
            ProfilePickerScreen(onPicked = {
                navController.navigate(Routes.Home) {
                    popUpTo(Routes.Profile) { inclusive = true }
                }
            })
        }
        composable(Routes.Home) {
            HomeScreen(
                onAction = { action ->
                    when (action) {
                        is HomeAction.OpenDetail -> {
                            val route = if (action.mode == "series")
                                Routes.seriesDetail(action.id, action.name)
                            else
                                Routes.movieDetail(action.id, action.name)
                            navController.navigate(route)
                        }
                        is HomeAction.PlayLive -> {
                            val ext = action.item.container ?: "m3u8"
                            navController.navigate(Routes.player("live", action.item.id, ext))
                        }
                        is HomeAction.SeeAll -> {
                            navController.navigate(
                                Routes.category(action.mode, action.categoryId, action.title)
                            )
                        }
                    }
                },
                onSearch = { navController.navigate(Routes.Search) },
                onSettings = { navController.navigate(Routes.Settings) },
            )
        }
        composable(Routes.Search) {
            SearchScreen(
                onResult = { mode, item ->
                    val route = when (mode) {
                        "movie" -> Routes.movieDetail(item.id, item.name)
                        "series" -> Routes.seriesDetail(item.id, item.name)
                        else -> Routes.player("live", item.id, item.container ?: "m3u8")
                    }
                    navController.navigate(route)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                onSwitchProfile = {
                    navController.navigate(Routes.Profile) {
                        popUpTo(0) { inclusive = false }
                    }
                },
                onChangeServer = {
                    navController.navigate(Routes.ServerUrl)
                },
                onDownloads = { navController.navigate(Routes.Downloads) },
                onSignedOut = {
                    navController.navigate(Routes.Login) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.Downloads) {
            DownloadsScreen(
                onPlay = { e ->
                    navController.navigate(
                        Routes.player(e.mode, e.id, e.ext)
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.MovieDetail,
            arguments = listOf(
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            val id = back.arguments?.getString("id")?.toIntOrNull() ?: return@composable
            val name = back.arguments?.getString("name")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
            MovieDetailScreen(
                movieId = id,
                fallbackName = name,
                onPlay = { ext ->
                    navController.navigate(Routes.player("movie", id, ext))
                },
                onPerson = { personName ->
                    navController.navigate(Routes.person(personName))
                },
                onOpenSimilar = { mode, similarId, similarName ->
                    val route = if (mode == "series")
                        Routes.seriesDetail(similarId, similarName)
                    else Routes.movieDetail(similarId, similarName)
                    navController.navigate(route)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.SeriesDetail,
            arguments = listOf(
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            val id = back.arguments?.getString("id")?.toIntOrNull() ?: return@composable
            val name = back.arguments?.getString("name")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
            SeriesDetailScreen(
                seriesId = id,
                fallbackName = name,
                onPlayEpisode = { episodeId, ext ->
                    navController.navigate(Routes.player("series", episodeId, ext))
                },
                onPerson = { personName ->
                    navController.navigate(Routes.person(personName))
                },
                onOpenSimilar = { mode, similarId, similarName ->
                    val route = if (mode == "movie")
                        Routes.movieDetail(similarId, similarName)
                    else Routes.seriesDetail(similarId, similarName)
                    navController.navigate(route)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.Category,
            arguments = listOf(
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            val mode       = back.arguments?.getString("mode") ?: return@composable
            val categoryId = back.arguments?.getString("categoryId")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return@composable
            val title      = back.arguments?.getString("title")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
            CategoryScreen(
                mode = mode,
                categoryId = categoryId,
                title = title,
                onAction = { a ->
                    when (a) {
                        is CategoryAction.OpenDetail -> navController.navigate(
                            if (a.mode == "series") Routes.seriesDetail(a.id, a.name)
                            else Routes.movieDetail(a.id, a.name)
                        )
                        is CategoryAction.PlayLive -> navController.navigate(
                            Routes.player("live", a.item.id, a.item.container ?: "m3u8")
                        )
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.Player) { back ->
            val mode     = back.arguments?.getString("mode") ?: return@composable
            val streamId = back.arguments?.getString("streamId")?.toIntOrNull() ?: return@composable
            val ext      = back.arguments?.getString("ext") ?: "mp4"
            PlayerScreen(
                mode = mode,
                streamId = streamId,
                ext = ext,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.Person) { back ->
            val name = back.arguments?.getString("name")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return@composable
            com.khouch.phone.ui.detail.PersonCreditsScreen(
                name = name,
                onOpen = { mode, item ->
                    val route = if (mode == "series")
                        Routes.seriesDetail(item.id, item.name)
                    else
                        Routes.movieDetail(item.id, item.name)
                    navController.navigate(route)
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
