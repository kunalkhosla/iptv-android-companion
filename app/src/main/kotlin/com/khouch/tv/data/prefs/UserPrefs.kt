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
// Per-device audio mode: "surround" → server transcodes to E-AC3 384 k
// so AVR-connected TVs get 5.1/7.1 native; "stereo" → AAC 192 k stereo
// (max compatibility, lower CPU on the server). Defaults to "stereo"
// because some MKVs with exotic source codecs (DTS-HD MA, FLAC) ended
// up silent through the E-AC3 transcode path on certain AVR setups.
// Opt into surround via Settings → Audio once you've confirmed your
// AVR / TV chain accepts E-AC3.
private val AUDIO_MODE = stringPreferencesKey("audio_mode")

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

    val audioMode: Flow<String> = store.data.map {
        when (val v = it[AUDIO_MODE]) {
            "stereo", "surround" -> v
            else -> "stereo"
        }
    }

    suspend fun audioModeOnce(): String = audioMode.first()

    suspend fun setAudioMode(mode: String) {
        val v = if (mode == "stereo") "stereo" else "surround"
        store.edit { it[AUDIO_MODE] = v }
    }
}
