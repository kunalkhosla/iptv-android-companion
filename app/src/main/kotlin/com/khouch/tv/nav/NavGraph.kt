package com.khouch.tv.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.khouch.tv.ui.detail.MovieDetailScreen
import com.khouch.tv.ui.detail.SeriesDetailScreen
import com.khouch.tv.ui.login.LoginScreen
import com.khouch.tv.ui.login.ServerUrlScreen
import com.khouch.tv.ui.main.MainScreen
import com.khouch.tv.ui.player.PlayerScreen
import com.khouch.tv.ui.profile.ProfilePickerScreen
import com.khouch.tv.ui.search.SearchScreen
import com.khouch.tv.ui.settings.SettingsScreen

@Composable
fun KhouchNavGraph(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.ServerUrl) {
            ServerUrlScreen(
                onContinue = { navController.navigate(Routes.Login) { popUpTo(0) } },
            )
        }

        composable(Routes.Login) {
            LoginScreen(
                onSuccess = { navController.navigate(Routes.Profile) { popUpTo(0) } },
                onChangeServer = { navController.navigate(Routes.ServerUrl) },
            )
        }

        composable(Routes.Profile) {
            ProfilePickerScreen(
                onPicked = { navController.navigate(Routes.Main) { popUpTo(0) } },
            )
        }

        composable(Routes.Main) {
            MainScreen(
                onPlay = { mode, id ->
                    val ext = if (mode == "live") "m3u8" else "mp4"
                    navController.navigate(Routes.player(mode, id, ext))
                },
                onOpenDetail = { mode, id ->
                    when (mode) {
                        "movie" -> navController.navigate(Routes.movieDetail(id))
                        "series" -> navController.navigate(Routes.seriesDetail(id))
                        "live" -> {
                            val r = Routes.player("live", id, "m3u8")
                            navController.navigate(r)
                        }
                    }
                },
                onSearch = { navController.navigate(Routes.Search) },
                onSettings = { navController.navigate(Routes.Settings) },
            )
        }

        composable(
            route = Routes.MovieDetail,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            MovieDetailScreen(
                movieId = id,
                onPlay = { navController.navigate(Routes.player("movie", id, "mp4")) },
                onPerson = { name -> navController.navigate(Routes.person(name)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.SeriesDetail,
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: return@composable
            SeriesDetailScreen(
                seriesId = id,
                onPlayEpisode = { epId, ext ->
                    navController.navigate(Routes.player("series", epId, ext))
                },
                onPerson = { name -> navController.navigate(Routes.person(name)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.Person,
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { entry ->
            val name = entry.arguments?.getString("name")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return@composable
            com.khouch.tv.ui.detail.PersonCreditsScreen(
                name = name,
                onOpen = { mode, tile ->
                    when (mode) {
                        "movie" -> navController.navigate(Routes.movieDetail(tile.id))
                        "series" -> navController.navigate(Routes.seriesDetail(tile.id))
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.Search) {
            SearchScreen(
                onPlay = { mode, id ->
                    val ext = if (mode == "live") "m3u8" else "mp4"
                    navController.navigate(Routes.player(mode, id, ext))
                },
                onOpenDetail = { mode, id ->
                    when (mode) {
                        "movie" -> navController.navigate(Routes.movieDetail(id))
                        "series" -> navController.navigate(Routes.seriesDetail(id))
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.Settings) {
            SettingsScreen(
                onSwitchProfile = {
                    navController.navigate(Routes.Profile) { popUpTo(0) }
                },
                onSignOut = {
                    navController.navigate(Routes.Login) { popUpTo(0) }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.Player,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType },
                navArgument("streamId") { type = NavType.IntType },
                navArgument("ext") { type = NavType.StringType },
            ),
        ) { entry ->
            val mode = entry.arguments?.getString("mode") ?: return@composable
            val id = entry.arguments?.getInt("streamId") ?: return@composable
            val ext = entry.arguments?.getString("ext") ?: "m3u8"
            PlayerScreen(
                mode = mode,
                streamId = id,
                ext = ext,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
