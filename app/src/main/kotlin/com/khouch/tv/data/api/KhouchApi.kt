package com.khouch.tv.data.api

import com.khouch.tv.data.model.BootstrapResponse
import com.khouch.tv.data.model.EpgResponse
import com.khouch.tv.data.model.HealthResponse
import com.khouch.tv.data.model.IndexResponse
import com.khouch.tv.data.model.LoginRequest
import com.khouch.tv.data.model.LoginResponse
import com.khouch.tv.data.model.MovieInfoResponse
import com.khouch.tv.data.model.OkResponse
import com.khouch.tv.data.model.PosterResponse
import com.khouch.tv.data.model.ProfilesResponse
import com.khouch.tv.data.model.ProgressUpdate
import com.khouch.tv.data.model.SelectProfileRequest
import com.khouch.tv.data.model.SeriesInfoResponse
import com.khouch.tv.data.model.StillsResponse
import com.khouch.tv.data.model.StreamUrls
import com.khouch.tv.data.model.UserStatePatch
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// Full /api/* surface consumed by the TV app. Keep in lockstep with
// iptv-webui/server.js — any added or changed endpoint over there
// must be reflected here in the same PR (per the CLAUDE.md rule).
interface KhouchApi {

    @GET("healthz")
    suspend fun health(): HealthResponse

    // --- Auth ---

    @Headers("Accept: application/json")
    @POST("api/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @Headers("Accept: application/json")
    @POST("api/logout")
    suspend fun logout(): OkResponse

    @GET("api/profiles")
    suspend fun profiles(): ProfilesResponse

    @Headers("Accept: application/json")
    @POST("api/profile/select")
    suspend fun selectProfile(@Body body: SelectProfileRequest): OkResponse

    // --- Catalog ---

    @GET("api/bootstrap")
    suspend fun bootstrap(): BootstrapResponse

    @GET("api/index/{mode}")
    suspend fun modeIndex(@Path("mode") mode: String): IndexResponse

    // Server-prebuilt home payload: hero + rails for the active
    // profile. Replaces the giant index download on Movies / Series.
    @GET("api/home/{mode}")
    suspend fun home(@Path("mode") mode: String): com.khouch.tv.data.model.HomeResponse

    @GET("api/{mode}/info/{id}")
    suspend fun movieOrSeriesInfo(
        @Path("mode") mode: String,
        @Path("id") id: Int,
    ): MovieInfoResponse

    // Single-item lookup. Cheap (~200 B) — replaces the 15 MB index
    // download for the detail screens' title/icon seed.
    @GET("api/{mode}/item/{id}")
    suspend fun item(
        @Path("mode") mode: String,
        @Path("id") id: Int,
    ): com.khouch.tv.data.model.Stream

    @GET("api/series/info/{id}")
    suspend fun seriesInfo(@Path("id") id: Int): SeriesInfoResponse

    // Server wraps the array as `{ q, count, results }` — receive the
    // raw envelope and pull the `results` field in the repo. Declaring
    // JsonArray here failed deserialization silently and made every
    // search return zero matches.
    @GET("api/search/{mode}")
    suspend fun search(
        @Path("mode") mode: String,
        @Query("q") q: String,
    ): JsonElement

    // --- TMDB enrichment ---

    @GET("api/poster/{mode}/{id}")
    suspend fun poster(@Path("mode") mode: String, @Path("id") id: Int): PosterResponse

    @GET("api/poster/series/{id}/season/{n}")
    suspend fun seasonStills(@Path("id") id: Int, @Path("n") seasonNum: Int): StillsResponse

    @GET("api/person/credits")
    suspend fun personCredits(
        @retrofit2.http.Query("name") name: String,
    ): com.khouch.tv.data.model.PersonCreditsResponse

    // "More Like This" rails for a movie/series detail screen. Server
    // returns a uniform { ready, rails: [{kind, title, items}] } shape
    // — rails are already ordered, deduped, filtered, capped. Client
    // iterates rails[] and renders each.
    @GET("api/similar/{mode}/{id}")
    suspend fun similar(
        @Path("mode") mode: String,
        @Path("id") id: Int,
    ): com.khouch.tv.data.model.SimilarResponse

    // --- Stream URLs ---

    @GET("api/stream/{mode}/{id}.{ext}")
    suspend fun streamUrls(
        @Path("mode") mode: String,
        @Path("id") id: Int,
        @Path("ext") ext: String,
        // Source-side seek anchor in seconds. When > 0 the server
        // restarts ffmpeg at this offset so a fresh playlist begins
        // there — lets the client jump past the encoded edge of the
        // current transcode without waiting for ffmpeg to catch up.
        @retrofit2.http.Query("t") t: Int? = null,
        // Audio mode the transcoder should target. "surround" tells
        // the server to keep multi-channel (E-AC3 384k); omitted /
        // "stereo" → AAC 192k. See UserPrefs.audioMode.
        @retrofit2.http.Query("a") a: String? = null,
    ): StreamUrls

    // --- EPG ---

    @GET("api/epg/short/{streamId}")
    suspend fun epgShort(@Path("streamId") streamId: Int): EpgResponse

    // --- Playback / state ---

    @Headers("Accept: application/json")
    @POST("api/play-event/{mode}/{id}")
    suspend fun playEvent(
        @Path("mode") mode: String,
        @Path("id") id: Int,
    ): OkResponse

    @Headers("Accept: application/json")
    @POST("api/progress/{mode}/{id}")
    suspend fun progress(
        @Path("mode") mode: String,
        @Path("id") id: Int,
        @Body body: ProgressUpdate,
    ): OkResponse

    @Headers("Accept: application/json")
    @PUT("api/user-state")
    suspend fun putUserState(@Body body: UserStatePatch): OkResponse

    // Remove a single item from the merged Continue Watching rail
    // (#48) — wipes both recents[mode] and progress[mode:id] in one
    // call. Idempotent.
    @Headers("Accept: application/json")
    @DELETE("api/user-state/recents/{mode}/{id}")
    suspend fun deleteRecentItem(
        @Path("mode") mode: String,
        @Path("id") id: Int,
    ): OkResponse

    // Clear the whole Continue Watching list for a mode (#48). Wipes
    // recents[mode] AND every progress entry whose key starts with mode.
    @Headers("Accept: application/json")
    @DELETE("api/user-state/recents/{mode}")
    suspend fun deleteRecentsForMode(@Path("mode") mode: String): OkResponse
}
