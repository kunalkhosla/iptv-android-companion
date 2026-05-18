package com.khouch.phone.di

import com.khouch.core.data.api.ApiFactory
import com.khouch.core.data.auth.PersistentCookieJar
import com.khouch.core.data.downloads.DownloadsRepo
import com.khouch.core.data.prefs.UserPrefs
import com.khouch.core.data.repo.KhouchRepository
import com.khouch.phone.ui.category.CategoryViewModel
import com.khouch.phone.ui.detail.MovieDetailViewModel
import com.khouch.phone.ui.detail.SeriesDetailViewModel
import com.khouch.phone.ui.downloads.DownloadsViewModel
import com.khouch.phone.ui.guide.TvGuideViewModel
import com.khouch.phone.ui.home.HomeViewModel
import com.khouch.phone.ui.login.LoginViewModel
import com.khouch.phone.ui.login.ServerUrlViewModel
import com.khouch.phone.ui.player.PlayerViewModel
import com.khouch.phone.ui.profile.ProfilePickerViewModel
import com.khouch.phone.ui.search.SearchViewModel
import com.khouch.phone.ui.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val phoneModule = module {
    single { PersistentCookieJar(androidContext()) }
    single { ApiFactory(get()) }
    single { UserPrefs(androidContext()) }
    single { KhouchRepository(get(), get(), get()) }
    single { DownloadsRepo(androidContext()) }

    viewModel { ServerUrlViewModel(get()) }
    viewModel { LoginViewModel(get()) }
    viewModel { ProfilePickerViewModel(get()) }
    viewModel { HomeViewModel(get()) }
    viewModel { TvGuideViewModel(get()) }
    viewModel { SearchViewModel(get()) }
    viewModel { SettingsViewModel(get(), get()) }
    viewModel { DownloadsViewModel(get()) }
    viewModel { (id: Int) -> MovieDetailViewModel(get(), get(), id) }
    viewModel { (id: Int) -> SeriesDetailViewModel(get(), get(), id) }
    viewModel { (name: String) -> com.khouch.phone.ui.detail.PersonCreditsViewModel(get(), name) }
    viewModel { (mode: String, categoryId: String) ->
        CategoryViewModel(get(), mode, categoryId)
    }
    viewModel { (mode: String, streamId: Int, ext: String) ->
        PlayerViewModel(get(), get(), mode, streamId, ext)
    }
}
