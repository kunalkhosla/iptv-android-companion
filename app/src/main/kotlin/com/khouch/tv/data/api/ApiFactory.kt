package com.khouch.tv.data.api

import com.khouch.tv.BuildConfig
import com.khouch.tv.data.auth.PersistentCookieJar
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

// Builds Retrofit clients against a dynamic base URL. The Android TV
// app can repoint at any Khouch backend, so we don't hard-code the
// base URL — instead, every screen requests an api instance via the
// repository, which builds (or reuses) a Retrofit pointed at the
// currently-stored server URL.
class ApiFactory(private val cookieJar: PersistentCookieJar) {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    // Separate client used only by the 401 authenticator below. Reusing
    // the main client would risk recursive 401 → authenticate → 401
    // loops if the login itself somehow returned 401. Same cookie jar
    // though, so the Set-Cookie from /api/login lands where the main
    // client will read it on retry.
    private val loginOnlyClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Self-heal on 401: if the server rejects a request and we have
    // build-embedded credentials, re-run /api/login and retry the
    // original request once. The cookie jar picks up the new
    // khouch_session set-cookie from the login response, so the retry
    // goes out with valid auth. Without this, anything that
    // invalidates a session — server restart, expiry, manual cookie
    // wipe — surfaces a 401 to the user instead of self-recovering.
    //
    // Release builds compile with empty creds, so the authenticator
    // becomes a no-op and 401s propagate normally.
    private val reLoginAuthenticator = Authenticator { _, response ->
        // Already retried once on this chain — give up to avoid loops.
        if (response.request.header(RETRY_HEADER) != null) return@Authenticator null
        if (response.request.url.encodedPath.endsWith("/api/login")) return@Authenticator null
        val user = BuildConfig.AUTO_LOGIN_USER
        val pass = BuildConfig.AUTO_LOGIN_PASS
        if (user.isBlank() || pass.isBlank()) return@Authenticator null

        val loginUrl = response.request.url.newBuilder().encodedPath("/api/login").build()
        val body = """{"user":${jsonString(user)},"pass":${jsonString(pass)}}"""
            .toRequestBody("application/json".toMediaType())
        // Accept JSON so the server returns 200 {ok:true} instead of
        // the browser-style 302 → /. Following that redirect would
        // land on the Basic-Auth-gated home page and come back as 401,
        // masking the actually-successful login.
        val loginReq = Request.Builder()
            .url(loginUrl)
            .post(body)
            .header("Accept", "application/json")
            .build()
        val ok = runCatching { loginOnlyClient.newCall(loginReq).execute() }
            .getOrNull()
            ?.use { it.isSuccessful }
            ?: false
        if (!ok) return@Authenticator null

        // The session cookie is now fresh, but the profile cookie may
        // ALSO have gone stale (same root cause — invalid signature
        // after server-side state change). The token format is
        // `<id>.<hmac>`, so we can recover the previously-selected
        // profile id locally and ask the server to re-issue a fresh
        // cookie for it. That keeps the user on the same profile
        // they had before instead of dumping them to the picker, and
        // turns the otherwise-fatal "profile required" 401 into a
        // silent recovery.
        cookieJar.snapshotByName("khouch_profile")?.let { stale ->
            val profileId = stale.substringBefore('.', "").takeIf { it.isNotBlank() }
            if (profileId != null) {
                val selectUrl = response.request.url.newBuilder().encodedPath("/api/profile/select").build()
                val selBody = """{"id":${jsonString(profileId)}}"""
                    .toRequestBody("application/json".toMediaType())
                val selReq = Request.Builder()
                    .url(selectUrl)
                    .post(selBody)
                    .header("Accept", "application/json")
                    .build()
                runCatching { loginOnlyClient.newCall(selReq).execute() }
                    .getOrNull()
                    ?.close()
            }
        }

        android.util.Log.i("KhouchAuth", "self-healed 401 on ${response.request.url.encodedPath}")
        response.request.newBuilder().header(RETRY_HEADER, "1").build()
    }

    private val okhttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .authenticator(reLoginAuthenticator)
            // Helpful while bringing the app up; flip to NONE for release.
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            .build()
    }

    @Volatile private var cached: Pair<String, KhouchApi>? = null

    fun api(baseUrl: String): KhouchApi {
        val normalized = baseUrl.trim().trimEnd('/') + "/"
        cached?.let { (url, api) -> if (url == normalized) return api }
        val api = Retrofit.Builder()
            .baseUrl(normalized)
            .client(okhttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KhouchApi::class.java)
        cached = normalized to api
        return api
    }

    val httpClient: OkHttpClient get() = okhttp

    private companion object {
        const val RETRY_HEADER = "X-Khouch-Auth-Retry"
        // Minimal JSON-string escape — username/password are
        // build-time constants today, but escaping defensively keeps
        // the authenticator safe if creds ever come from elsewhere.
        fun jsonString(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '\\', '"' -> { append('\\'); append(c) }
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}
