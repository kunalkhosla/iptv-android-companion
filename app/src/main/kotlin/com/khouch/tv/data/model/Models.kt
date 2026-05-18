package com.khouch.tv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LoginRequest(val user: String, val pass: String)

@Serializable
data class LoginResponse(val ok: Boolean, val next: String? = null, val error: String? = null)

@Serializable
data class ProfilesResponse(
    val profiles: List<Profile>,
    val avatars: List<String> = emptyList(),
)

@Serializable
data class Profile(
    val id: String,
    val nick: String,
    val avatar: String? = null,
    val kidsBirthYear: Int? = null,
)

@Serializable
data class SelectProfileRequest(val id: String)

@Serializable
data class OkResponse(val ok: Boolean)

@Serializable
data class Category(
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String,
    @SerialName("parent_id") val parentId: Int? = null,
)

@Serializable
data class IndexStatus(
    val total: Int = 0,
    val done: Int = 0,
    val ready: Boolean = false,
)

@Serializable
data class UserState(
    val favorites: Map<String, List<Int>> = emptyMap(),
    val myList: Map<String, List<Int>> = emptyMap(),
    val recents: Map<String, List<Int>> = emptyMap(),
    val watched: List<String> = emptyList(),
    // Server's lastEpisode is `{ seriesId: { episode_id, season, … } }`,
    // not a flat int. Held as raw JSON; readers parse on demand.
    val lastEpisode: Map<String, JsonElement> = emptyMap(),
    // Progress is `{ "mode:id": { p, d, t } }`. We hold it as raw JSON
    // because the server's `t` field is sometimes a long, sometimes a
    // string, and kotlinx-serialization with strict types breaks the
    // whole bootstrap deser otherwise. ProgressEntry is the projected
    // view that downstream consumers should use via `progressFor`.
    val progress: Map<String, JsonElement> = emptyMap(),
    val filter: FilterState? = null,
    val theme: String = "netflix",
    val remoteEnabled: Boolean = false,
)

@Serializable
data class ProgressEntry(
    val p: Double = 0.0,       // position in seconds
    val d: Double = 0.0,       // duration in seconds
    val t: Long = 0,           // updated-at ms
)

@Serializable
data class FilterState(
    val onboarded: Boolean = false,
    val groups: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class BootstrapResponse(
    val account: JsonElement? = null,
    val categories: Map<String, List<Category>> = emptyMap(),
    val index: Map<String, IndexStatus> = emptyMap(),
    val profile: Profile? = null,
    val userState: UserState? = null,
    // Server's lastPlayed has mixed-type leaves — kept as raw JSON.
    val lastPlayed: JsonElement? = null,
    // Authoritative chip-filter + kids-cert configuration. When
    // present, this is the source of truth — the client's hardcoded
    // GROUPS / cert thresholds become fallback-only. Adding a new
    // language / region / genre or shifting an age threshold is then
    // a server-only change.
    val filterConfig: FilterConfig? = null,
)

@Serializable
data class FilterConfig(
    val groups: List<FilterGroup> = emptyList(),
    val syntheticTags: List<String> = emptyList(),
    val nonEntertainmentTags: List<String> = emptyList(),
    val kidsCertTiers: List<KidsCertTier> = emptyList(),
)

@Serializable
data class FilterGroup(
    val key: String,
    val label: String,
    // "language" | "country" | "genre" | "other"
    val kind: String = "other",
)

@Serializable
data class KidsCertTier(
    val minAge: Int = 0,
    val add: List<String> = emptyList(),
)

@Serializable
data class Stream(
    val id: Int,
    val name: String,
    val icon: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val plot: String? = null,
    val container: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    val added: String? = null,
    // Pre-computed by the server-side tagger (CHANNEL_GROUPS + regional
    // defaults + XX:-prefix layer + pre-pipe genre). Chip filtering on
    // this client reduces to a Set.contains(key) lookup against this
    // array; no client-side regex needed when populated.
    val tags: List<String> = emptyList(),
)

@Serializable
data class IndexResponse(
    val total: Int = 0,
    val done: Int = 0,
    val ready: Boolean = false,
    val streams: List<Stream> = emptyList(),
)

@Serializable
data class StreamUrls(
    val direct: String? = null,
    val proxy: String? = null,
    val transcode: String? = null,
)

@Serializable
data class HealthResponse(val ok: Boolean = true)

// --- Server-prebuilt home (Movies/Series rails + hero) ---
//
// Replaces the per-mode 19.7 MB index download + client-side rail
// composition with a ~180 KB payload from /api/home/{mode}. Each
// item already carries a pre-sized TMDB poster URL so the home
// view doesn't fan out one /api/poster fetch per visible tile.

@Serializable
data class HomeResponse(
    val mode: String = "",
    val ready: Boolean = true,
    val hero: List<HeroItem> = emptyList(),
    val rails: List<HomeRail> = emptyList(),
)

@Serializable
data class HomeRail(
    val title: String = "",
    val items: List<HomeItem> = emptyList(),
)

@Serializable
data class HomeItem(
    val id: Int,
    val name: String,
    val icon: String? = null,
    val year: String? = null,
    val poster: String? = null,
    @SerialName("us_cert") val usCert: String? = null,
    val tags: List<String> = emptyList(),
    // Container ext (mp4 / mkv / avi). Used to render the small
    // "MKV" badge on tiles that will go through the server's
    // transcoder so the user knows to expect a slower start.
    val container: String? = null,
)

@Serializable
data class HeroItem(
    val id: Int,
    val name: String,
    val icon: String? = null,
    val year: String? = null,
    val plot: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: Double? = null,
    val runtime: Int? = null,
    @SerialName("us_cert") val usCert: String? = null,
)

// --- Movie / Series detail endpoints ---

@Serializable
data class MovieInfoResponse(
    val info: JsonElement? = null,
    @SerialName("movie_data") val movieData: JsonElement? = null,
)

@Serializable
data class SeriesInfoResponse(
    val info: JsonElement? = null,
    val episodes: Map<String, List<Episode>> = emptyMap(),
    val seasons: List<Season>? = null,
)

@Serializable
data class Episode(
    val id: String,
    val title: String? = null,
    @SerialName("episode_num") val episodeNum: JsonElement? = null,
    val season: Int? = null,
    val info: JsonElement? = null,
    @SerialName("container_extension") val container: String? = null,
)

@Serializable
data class Season(
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)

// --- TMDB enrichment ---

@Serializable
data class PosterResponse(
    @SerialName("tmdb_id") val tmdbId: Long? = null,
    @SerialName("tmdb_title") val tmdbTitle: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val plot: String? = null,
    val year: String? = null,
    val rating: Double? = null,
    @SerialName("vote_count") val voteCount: Long? = null,
    val popularity: Double? = null,
    val runtime: Int? = null,
    val genres: List<String> = emptyList(),
    val tagline: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("us_cert") val usCert: String? = null,
    val collection: TmdbCollection? = null,
    val cast: List<TmdbCastMember> = emptyList(),
    val directors: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    @SerialName("trailer_key") val trailerKey: String? = null,
    val reviews: List<TmdbReview> = emptyList(),
    val recommendations: List<Long> = emptyList(),
    val similar: List<Long> = emptyList(),
)

@Serializable
data class TmdbCollection(
    val id: Long,
    val name: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
)

@Serializable
data class TmdbCastMember(
    val name: String,
    val character: String? = null,
    val profile: String? = null,
)

@Serializable
data class TmdbReview(
    val author: String,
    val rating: Double? = null,
    val excerpt: String,
)

@Serializable
data class PersonCreditsResponse(
    val name: String,
    val person: TmdbPerson? = null,
    val items: PersonCreditsItems = PersonCreditsItems(),
)

@Serializable
data class TmdbPerson(
    val id: Long,
    val name: String,
    val profile: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
)

@Serializable
data class PersonCreditsItems(
    val movie: List<PersonCreditTile> = emptyList(),
    val series: List<PersonCreditTile> = emptyList(),
)

@Serializable
data class PersonCreditTile(
    val id: Int,
    val name: String,
    val icon: String? = null,
    val poster: String? = null,
    val year: String? = null,
    @SerialName("us_cert") val usCert: String? = null,
    val rating: Double? = null,
    val tags: List<String> = emptyList(),
    val container: String? = null,
    @SerialName("tmdb_id") val tmdbId: Long? = null,
    val character: String? = null,
)

@Serializable
data class StillsResponse(
    val stills: Map<String, String> = emptyMap(),
)

// --- EPG ---

// The Khouch server normalizes the panel's raw EPG into its own
// shape: `{ stream_id, programs: [ {title, description, start_ts,
// stop_ts} ] }`. Titles arrive already base64-decoded, so we don't
// need to handle that on the client.
@Serializable
data class EpgResponse(
    @SerialName("stream_id") val streamId: String? = null,
    val programs: List<EpgEntry> = emptyList(),
)

@Serializable
data class EpgEntry(
    val title: String? = null,
    val description: String? = null,
    @SerialName("start_ts") val startTs: Long? = null,
    @SerialName("stop_ts") val stopTs: Long? = null,
)

// --- Mutations ---

@Serializable
data class ProgressUpdate(
    val p: Double,
    val d: Double,
)

@Serializable
data class UserStatePatch(
    val favorites: Map<String, List<Int>>? = null,
    val myList: Map<String, List<Int>>? = null,
    val recents: Map<String, List<Int>>? = null,
    val watched: List<String>? = null,
    val lastEpisode: Map<String, JsonElement>? = null,
    val theme: String? = null,
    val filter: FilterState? = null,
)
