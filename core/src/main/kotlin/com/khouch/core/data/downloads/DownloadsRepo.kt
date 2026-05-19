package com.khouch.core.data.downloads

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

// Persists downloaded items to <app external files>/Movies/khouch.
//
// Why DownloadManager rather than a custom OkHttp downloader:
//   - System notification UI handled for us (paused / resumed / progress)
//   - Survives app process kill — keeps downloading in background
//   - Honors Wi-Fi-only / metered-connection prefs
//   - Auto-retries on transient failures
//
// What we maintain on top of DownloadManager:
//   - A per-item .json sidecar (name / poster / size / status) so a
//     fresh app install can re-index existing files.
//   - An in-memory StateFlow<List<DownloadEntry>> that the UI subscribes
//     to. Refreshed on file changes + on a slow poll loop while
//     downloads are running (so progress / size update on screen).
//
// Important constraint: the panel's `direct` URL is ephemeral and CDN-
// signed, so we never store it. Downloads always go through the
// server proxy (`/api/proxy?u=…&s=…`) — that signature is HMAC-bound
// to the server's PROXY_SECRET and rotates if the server rotates,
// but at least it isn't a reseller domain that vanishes next week.
class DownloadsRepo(private val context: Context) {

    private val dm: DownloadManager = context.getSystemService()!!

    // App-scoped external storage. No permissions needed, survives
    // app updates, gets wiped on uninstall — sensible for sideloaded
    // media (no media-store leakage to the photos app etc.).
    val rootDir: File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
        "khouch",
    ).apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _items = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val items: StateFlow<List<DownloadEntry>> = _items.asStateFlow()

    // Aggregate disk usage across all on-disk media files (NOT just
    // the sidecars). Surfaces the "12.4 GB used" line on the Downloads
    // screen and in Settings.
    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Initial scan of disk + start the poll loop.
        scope.launch { reindex() }
        scope.launch { pollActive() }
    }

    // ---- Sidecar persistence ----

    private fun sidecarFor(mode: String, id: Int): File =
        File(rootDir, "${mode}_${id}.json")

    private fun mediaFor(mode: String, id: Int, ext: String): File =
        File(rootDir, "${mode}_${id}.${ext}")

    private fun writeSidecar(e: DownloadEntry) {
        val tmp = File(rootDir, "${e.mode}_${e.id}.json.tmp")
        tmp.writeText(json.encodeToString(DownloadEntry.serializer(), e))
        tmp.renameTo(sidecarFor(e.mode, e.id))
    }

    private fun readSidecar(f: File): DownloadEntry? = try {
        json.decodeFromString(DownloadEntry.serializer(), f.readText())
    } catch (_: Throwable) { null }

    suspend fun reindex() {
        val sidecars = rootDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        val parsed = sidecars.mapNotNull(::readSidecar)
        // Re-stat the media files on each scan so size + status stay
        // honest even if DownloadManager flushed its row.
        val refreshed = parsed.map { e ->
            val media = File(e.localPath)
            if (media.exists() && media.length() > 0) {
                e.copy(
                    status = DownloadEntry.Status.COMPLETED,
                    sizeBytes = media.length(),
                )
            } else e
        }
        _items.value = refreshed.sortedByDescending { it.downloadedAt }
        _totalBytes.value = refreshed
            .map { runCatching { File(it.localPath).length() }.getOrDefault(0L) }
            .sum()
    }

    // While anything is RUNNING, poll DownloadManager every 1.5s for
    // size updates. Stop polling when everything is settled to avoid
    // burning battery.
    private suspend fun pollActive() {
        while (true) {
            val active = _items.value.any {
                it.status == DownloadEntry.Status.RUNNING ||
                it.status == DownloadEntry.Status.PENDING
            }
            if (active) {
                refreshActive()
                delay(1500)
            } else {
                delay(8000)
            }
        }
    }

    private fun refreshActive() {
        val list = _items.value.toMutableList()
        var changed = false
        for ((i, e) in list.withIndex()) {
            if (e.downloadId < 0) continue
            val q = DownloadManager.Query().setFilterById(e.downloadId)
            val c = runCatching { dm.query(q) }.getOrNull() ?: continue
            c.use {
                if (!it.moveToFirst()) return@use
                val statusCol  = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val totalCol   = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val soFarCol   = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val st         = it.getInt(statusCol)
                // COLUMN_TOTAL_SIZE_BYTES is the expected total from the
                // response's Content-Length. The server's ffmpeg pipe is
                // a streaming response with no upfront size, so this
                // stays -1 for the whole download and the UI would show
                // "0 B" forever. Use COLUMN_BYTES_DOWNLOADED_SO_FAR for
                // running progress, then settle to the on-disk size on
                // completion (already handled below).
                val total = it.getLong(soFarCol)
                    .takeIf { v -> v > 0 }
                    ?: it.getLong(totalCol).coerceAtLeast(0L)
                // DownloadManager reports SUCCESSFUL even when the
                // response was 200 OK with a 0-byte body — which is
                // exactly what the panel returns for movies whose
                // file is no longer on the reseller's CDN (text/html
                // + Content-Length: 0). Detect that here so the UI
                // can surface it as a failure instead of a "Saved"
                // 0-byte placeholder.
                val onDiskSize = runCatching { File(e.localPath).length() }.getOrDefault(0L)
                val newStatus = when (st) {
                    DownloadManager.STATUS_SUCCESSFUL ->
                        if (onDiskSize <= 0L) DownloadEntry.Status.FAILED
                        else DownloadEntry.Status.COMPLETED
                    DownloadManager.STATUS_RUNNING    -> DownloadEntry.Status.RUNNING
                    DownloadManager.STATUS_PENDING    -> DownloadEntry.Status.PENDING
                    DownloadManager.STATUS_PAUSED     -> DownloadEntry.Status.RUNNING
                    DownloadManager.STATUS_FAILED     -> DownloadEntry.Status.FAILED
                    else -> e.status
                }
                // When we detect the 0-byte-success case, also wipe
                // the empty placeholder so the user's "X items"
                // count and "free up space" math stay honest.
                if (newStatus == DownloadEntry.Status.FAILED && onDiskSize == 0L) {
                    runCatching { File(e.localPath).delete() }
                }
                if (newStatus != e.status || total != e.sizeBytes) {
                    val updated = e.copy(status = newStatus, sizeBytes = total)
                    list[i] = updated
                    writeSidecar(updated)
                    changed = true
                }
            }
        }
        if (changed) {
            _items.value = list.sortedByDescending { it.downloadedAt }
            _totalBytes.value = list.map {
                runCatching { File(it.localPath).length() }.getOrDefault(0L)
            }.sum()
        }
    }

    // ---- Public ops ----

    fun isDownloaded(mode: String, id: Int): Boolean =
        _items.value.any {
            it.mode == mode && it.id == id &&
            it.status == DownloadEntry.Status.COMPLETED
        }

    fun entryFor(mode: String, id: Int): DownloadEntry? =
        _items.value.firstOrNull { it.mode == mode && it.id == id }

    /**
     * Queue a new download. `sourceUrl` should be the SERVER PROXY url
     * (e.g. https://khouch.example.com/api/proxy?u=…&s=…), NOT the
     * panel's direct URL — see class-level note on URL ephemerality.
     */
    fun enqueue(
        mode: String,
        id: Int,
        name: String,
        sourceUrl: String,
        ext: String,
        poster: String? = null,
        year: String? = null,
        seriesId: Int? = null,
        seasonNum: Int? = null,
        episodeNum: String? = null,
    ): DownloadEntry {
        // Skip if already queued / completed for this id.
        entryFor(mode, id)?.let { return it }

        val media = mediaFor(mode, id, ext)
        val req = DownloadManager.Request(Uri.parse(sourceUrl))
            .setTitle(name)
            .setDescription(if (seriesId != null) "Series $seriesId · S${seasonNum}E${episodeNum}" else "Khouch download")
            .setDestinationUri(Uri.fromFile(media))
            .setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or
                DownloadManager.Request.NETWORK_MOBILE,
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setMimeType(if (ext == "mkv") "video/x-matroska" else "video/mp4")

        val downloadId = dm.enqueue(req)

        val entry = DownloadEntry(
            mode = mode,
            id = id,
            name = name,
            poster = poster,
            year = year,
            ext = ext,
            sourceUrl = sourceUrl,
            localPath = media.absolutePath,
            sizeBytes = 0L,
            downloadedAt = System.currentTimeMillis(),
            downloadId = downloadId,
            status = DownloadEntry.Status.PENDING,
            seriesId = seriesId,
            seasonNum = seasonNum,
            episodeNum = episodeNum,
        )
        writeSidecar(entry)
        _items.value = (_items.value + entry).sortedByDescending { it.downloadedAt }
        return entry
    }

    fun delete(mode: String, id: Int) {
        val e = entryFor(mode, id) ?: return
        // Cancel any in-flight DownloadManager row first.
        if (e.downloadId > 0) runCatching { dm.remove(e.downloadId) }
        runCatching { File(e.localPath).delete() }
        runCatching { sidecarFor(mode, id).delete() }
        scope.launch { reindex() }
    }

    fun deleteAll() {
        val snapshot = _items.value
        for (e in snapshot) {
            if (e.downloadId > 0) runCatching { dm.remove(e.downloadId) }
            runCatching { File(e.localPath).delete() }
            runCatching { sidecarFor(e.mode, e.id).delete() }
        }
        scope.launch { reindex() }
    }
}
