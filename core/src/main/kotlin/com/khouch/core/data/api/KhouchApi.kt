package com.khouch.core.data.api

import com.khouch.core.data.model.BootstrapResponse
import com.khouch.core.data.model.EpgResponse
import com.khouch.core.data.model.HealthResponse
import com.khouch.core.data.model.IndexResponse
import com.khouch.core.data.model.LoginRequest
import com.khouch.core.data.model.LoginResponse
import com.khouch.core.data.model.MovieInfoResponse
import com.khouch.core.data.model.OkResponse
import com.khouch.core.data.model.PosterResponse
import com.khouch.core.data.model.ProfilesResponse
import com.khouch.core.data.model.ProgressUpdate
import com.khouch.core.data.model.SelectProfileRequest
import com.khouch.core.data.model.SeriesInfoResponse
import com.khouch.core.data.model.StillsResponse
import com.khouch.core.data.model.StreamUrls
import com.khouch.core.data.model.UserStatePatch
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
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
    suspend fun home(@Path("mode") mode: String): com.khouch.core.data.model.HomeResponse

    @GET("api/{mode}/info/{id}")
    suspend fun movieOrSeriesInfo(
        @Path("mode") mode: String,
        @Path("id") id: Int,
    ): MovieInfoResponse

    // Per-category stream list, used by phone's "See all" view.
    // Server already applies kid-cert + title-language filtering for
    // the active profile, so the response is safe to render directly.
    @GET("api/{mode}/streams")
    suspend fun streamsByCategory(
        @Path("mode") mode: String,
        @Query("category_id") categoryId: String,
    ): List<com.khouch.core.data.model.Stream>

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

    // Cross-mode unified search. Returns three lists of HomeItem-shaped
    // results (with TMDB poster URLs inline). Same endpoint the web
    // header search box calls.
    @GET("api/search/all")
    suspend fun searchAll(
        @Query("q") q: String,
        @Query("limit") limit: Int = 30,
    ): com.khouch.core.data.model.SearchAllResponse

    // --- TMDB enrichment ---

    @GET("api/poster/{mode}/{id}")
    suspend fun poster(@Path("mode") mode: String, @Path("id") id: Int): PosterResponse

    @GET("api/poster/series/{id}/season/{n}")
    suspend fun seasonStills(@Path("id") id: Int, @Path("n") seasonNum: Int): StillsResponse

    // "More from <actor/director>" — resolves the name to a TMDB
    // person_id and returns their filmography intersected with the
    // local catalog. Used by the cast strip on detail screens.
    @GET("api/person/credits")
    suspend fun personCredits(
        @retrofit2.http.Query("name") name: String,
    ): com.khouch.core.data.model.PersonCreditsResponse

    // --- Stream URLs ---

    @GET("api/stream/{mode}/{id}.{ext}")
    suspend fun streamUrls(
        @Path("mode") mode: String,
        @Path("id") id: Int,
        @Path("ext") ext: String,
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
}
