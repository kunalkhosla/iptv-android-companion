package com.khouch.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.prefsStore by preferencesDataStore(name = "khouch_prefs")
private val SERVER_URL = stringPreferencesKey("server_url")

class UserPrefs(context: Context, private val defaultUrl: String = "") {
    private val store = context.applicationContext.prefsStore

    val serverUrl: Flow<String> = store.data.map { it[SERVER_URL].orEmpty().ifBlank { defaultUrl } }

    suspend fun serverUrlOnce(): String = serverUrl.first()

    suspend fun setServerUrl(url: String) {
        store.edit { it[SERVER_URL] = url.trim() }
    }
}
