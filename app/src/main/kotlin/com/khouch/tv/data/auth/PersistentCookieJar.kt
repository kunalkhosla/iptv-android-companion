package com.khouch.tv.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

private val Context.cookieStore by preferencesDataStore(name = "khouch_cookies")
private val COOKIES_KEY = stringPreferencesKey("serialized")

// DataStore-backed CookieJar. Persists session + profile cookies
// across process restarts so the TV app lands straight on the home
// screen the next time the user opens it.
//
// Cookies are serialized as one "name=value; Domain=…; Path=…;
// Expires=…; HttpOnly; Secure" line per cookie, separated by \n.
// Simple but adequate — we only ever store the two khouch_* cookies.
class PersistentCookieJar(context: Context) : CookieJar {

    private val store = context.applicationContext.cookieStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-memory mirror, keyed by cookie name. Loaded synchronously
    // once on construction (acceptable: the file is tiny).
    private val cookies: MutableMap<String, Cookie> = runBlocking {
        val blob = store.data.first()[COOKIES_KEY].orEmpty()
        parseBlob(blob).toMutableMap()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(this.cookies) {
            for (c in cookies) {
                if (c.expiresAt <= System.currentTimeMillis()) {
                    this.cookies.remove(c.name)
                } else {
                    this.cookies[c.name] = c
                }
            }
        }
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return synchronized(cookies) {
            cookies.values
                .filter { it.expiresAt > now && it.matches(url) }
                .toList()
        }
    }

    fun clear() {
        synchronized(cookies) { cookies.clear() }
        persist()
    }

    fun hasSession(): Boolean = synchronized(cookies) {
        cookies.values.any { it.name == "khouch_session" && it.expiresAt > System.currentTimeMillis() }
    }

    fun hasProfile(): Boolean = synchronized(cookies) {
        cookies.values.any { it.name == "khouch_profile" && it.expiresAt > System.currentTimeMillis() }
    }

    // Read-only access to a stored cookie's value by name. Used by the
    // OkHttp re-login authenticator to recover the user's
    // previously-selected profile id from a stale khouch_profile
    // cookie (the token is "<id>.<hmac>", so the prefix is the id
    // even when the hmac no longer verifies on the server). Returns
    // null when the cookie is absent or expired.
    fun snapshotByName(name: String): String? = synchronized(cookies) {
        cookies[name]
            ?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?.value
    }

    private fun persist() {
        val snapshot = synchronized(cookies) { cookies.values.toList() }
        scope.launch {
            store.edit { it[COOKIES_KEY] = serialize(snapshot) }
        }
    }

    private fun serialize(cookies: List<Cookie>): String =
        cookies.joinToString("\n") { it.toString() }

    private fun parseBlob(blob: String): Map<String, Cookie> {
        if (blob.isBlank()) return emptyMap()
        // Cookie.parse requires a URL; we rebuild one per line using the
        // domain encoded in the cookie itself. If the user changes
        // server URL we just clear cookies — no migration needed.
        val out = LinkedHashMap<String, Cookie>()
        for (line in blob.lineSequence()) {
            if (line.isBlank()) continue
            // Best-effort parse — split out the domain= and rebuild a
            // matching HttpUrl. Falls back to localhost when the cookie
            // string somehow omitted its domain (shouldn't happen in
            // practice — Cookie.toString() always emits it).
            val domain = Regex("domain=([^;\\s]+)", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1) ?: "localhost"
            val url = HttpUrl.Builder().scheme("https").host(domain).build()
            val parsed = Cookie.parse(url, line) ?: continue
            out[parsed.name] = parsed
        }
        return out
    }
}
