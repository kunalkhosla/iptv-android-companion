package com.khouch.tv.di

import com.khouch.tv.data.api.ApiFactory
import com.khouch.tv.data.auth.PersistentCookieJar
import com.khouch.tv.data.prefs.UserPrefs
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.detail.MovieDetailViewModel
import com.khouch.tv.ui.detail.SeriesDetailViewModel
import com.khouch.tv.ui.guide.TvGuideViewModel
import com.khouch.tv.ui.home.HomeRailsViewModel
import com.khouch.tv.ui.login.LoginViewModel
import com.khouch.tv.ui.login.ServerUrlViewModel
import com.khouch.tv.ui.main.LiveBrowseViewModel
import com.khouch.tv.ui.player.PlayerViewModel
import com.khouch.tv.ui.profile.ProfilePickerViewModel
import com.khouch.tv.ui.search.SearchViewModel
import com.khouch.tv.ui.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { PersistentCookieJar(androidContext()) }
    single { ApiFactory(get(), androidContext()) }
    single { UserPrefs(androidContext()) }
    single { KhouchRepository(get(), get(), get()) }

    viewModel { ServerUrlViewModel(get()) }
    viewModel { LoginViewModel(get()) }
    viewModel { ProfilePickerViewModel(get()) }
    viewModel { LiveBrowseViewModel(get()) }
    viewModel { TvGuideViewModel(get()) }
    viewModel { HomeRailsViewModel(get()) }
    viewModel { SearchViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get(), androidContext()) }
    viewModel { (id: Int) -> MovieDetailViewModel(get(), id) }
    viewModel { (id: Int) -> SeriesDetailViewModel(get(), id) }
    viewModel { (name: String) -> com.khouch.tv.ui.detail.PersonCreditsViewModel(get(), name) }
    viewModel { (mode: String, streamId: Int, ext: String, parentId: Int, fromStart: Boolean) ->
        PlayerViewModel(get(), mode, streamId, ext, parentId, fromStart)
    }
}
