package com.khouch.core.data.downloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// One downloaded item on disk. Serialized to a JSON sidecar next to
// the media file (.json beside .mp4) so deletes can clean both, and
// so a fresh app install can re-index existing downloads.
//
// `downloadId` is Android DownloadManager's row id while the
// download is active — used to query progress and to cancel. Goes
// to -1 once the download is complete and DownloadManager has
// flushed its row.
//
// `localPath` is the absolute file path on the device. Mode/id
// pair uniquely identifies the source on the server.
@Serializable
data class DownloadEntry(
    val mode: String,          // "movie" | "series" (no "live" support)
    val id: Int,               // streamId on the server
    val name: String,          // display label
    val poster: String? = null,
    val year: String? = null,
    val ext: String = "mp4",   // container extension
    @SerialName("source_url")  val sourceUrl: String,
    @SerialName("local_path")  val localPath: String,
    @SerialName("size_bytes")  val sizeBytes: Long = 0L,
    @SerialName("downloaded_at") val downloadedAt: Long = 0L,
    @SerialName("download_id") val downloadId: Long = -1L,
    val status: Status = Status.PENDING,
    // Series episodes carry their parent series id so the
    // DownloadsScreen can group by show.
    @SerialName("series_id")   val seriesId: Int? = null,
    @SerialName("season_num")  val seasonNum: Int? = null,
    @SerialName("episode_num") val episodeNum: String? = null,
) {
    @Serializable
    enum class Status { PENDING, RUNNING, COMPLETED, FAILED }
}
