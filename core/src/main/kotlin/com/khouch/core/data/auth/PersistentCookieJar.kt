package com.khouch.core.data.auth

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

private val Context.cookieStore by preferencesDataStore(name = "khouch_cookies")
private val COOKIES_KEY = stringPreferencesKey("serialized_v2")

// DataStore-backed CookieJar. Persists session + profile cookies
// across process restarts.
//
// Stored as a JSON array of full cookie shapes so we can rebuild
// the original Cookie exactly — including host-only vs domain
// cookies, secure flag, and the origin URL we saw it on. The
// previous v1 format used `Cookie.toString() + Cookie.parse()` which
// silently dropped `host-only` cookies (Set-Cookie without Domain=
// from the panel server), so after a process restart every request
// 401'd and the auth interceptor logged the user out within a
// minute.
class PersistentCookieJar(context: Context) : CookieJar {

    private val store = context.applicationContext.cookieStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    private fun persist() {
        val snapshot = synchronized(cookies) { cookies.values.toList() }
        scope.launch {
            store.edit { it[COOKIES_KEY] = serialize(snapshot) }
        }
    }

    private fun serialize(cookies: List<Cookie>): String {
        val arr = JSONArray()
        for (c in cookies) {
            arr.put(JSONObject().apply {
                put("name", c.name)
                put("value", c.value)
                put("expiresAt", c.expiresAt)
                put("domain", c.domain)
                put("path", c.path)
                put("secure", c.secure)
                put("httpOnly", c.httpOnly)
                put("hostOnly", c.hostOnly)
                put("persistent", c.persistent)
            })
        }
        return arr.toString()
    }

    private fun parseBlob(blob: String): Map<String, Cookie> {
        if (blob.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, Cookie>()
        val arr = runCatching { JSONArray(blob) }.getOrNull() ?: return emptyMap()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val b = Cookie.Builder()
                .name(o.optString("name"))
                .value(o.optString("value"))
                .expiresAt(o.optLong("expiresAt", Long.MAX_VALUE))
                .path(o.optString("path", "/"))
            val domain = o.optString("domain")
            if (o.optBoolean("hostOnly")) b.hostOnlyDomain(domain) else b.domain(domain)
            if (o.optBoolean("secure")) b.secure()
            if (o.optBoolean("httpOnly")) b.httpOnly()
            val cookie = runCatching { b.build() }.getOrNull() ?: continue
            out[cookie.name] = cookie
        }
        return out
    }
}
