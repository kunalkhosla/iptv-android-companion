package com.khouch.tv.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.prefsStore by preferencesDataStore(name = "khouch_prefs")
private val SERVER_URL = stringPreferencesKey("server_url")

// Build-variant-dependent default — debug points at 10.0.2.2 (the
// Android emulator's host alias) so a local `node server.js` is one
// keystroke away; release ships the public URL.
val DEFAULT_SERVER_URL: String = com.khouch.tv.BuildConfig.DEFAULT_SERVER_URL

class UserPrefs(context: Context) {
    private val store = context.applicationContext.prefsStore

    val serverUrl: Flow<String> = store.data.map { it[SERVER_URL].orEmpty().ifBlank { DEFAULT_SERVER_URL } }

    suspend fun serverUrlOnce(): String = serverUrl.first()

    suspend fun setServerUrl(url: String) {
        store.edit { it[SERVER_URL] = url.trim() }
    }
}
