package com.sfidm.downloader.engine

import android.content.Context
import com.sfidm.downloader.data.PrefsManager
import com.sfidm.downloader.model.DownloadItem
import com.sfidm.downloader.model.DownloadMode
import com.sfidm.downloader.model.DownloadStatus
import com.sfidm.downloader.model.MediaQuality
import com.sfidm.downloader.service.DownloadService
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var prefs: PrefsManager
    private lateinit var directDownloader: MultiThreadDownloader
    private lateinit var mediaDownloader: MediaVideoDownloader
    private var lastNotifUpdate = 0L

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = PrefsManager(appContext)
        directDownloader = MultiThreadDownloader(appContext)
        mediaDownloader = MediaVideoDownloader(appContext)
        _downloads.value = prefs.loadDownloads()
        initialized = true

        // Warm up yt-dlp & FFmpeg in background
        scope.launch {
            runCatching { mediaDownloader.ensureInitialized() }
        }
    }

    suspend fun updateVideoEngine(): YoutubeDL.UpdateStatus? {
        return mediaDownloader.updateEngine()
    }

    fun addDownload(
        url: String,
        customName: String,
        mode: DownloadMode,
        quality: MediaQuality,
        threads: Int
    ) {
        val cleanUrl = url.trim()
        val displayTitle = customName.ifBlank {
            if (mode == DownloadMode.MEDIA_VIDEO) {
                guessHostLabel(cleanUrl)
            } else {
                cleanUrl.substringBefore("?").substringAfterLast("/").ifBlank { "SFIDM_File" }
            }
        }
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            fileName = displayTitle,
            url = cleanUrl,
            mode = mode,
            quality = quality,
            threads = threads.coerceIn(1, 16),
            status = DownloadStatus.QUEUED
        )
        _downloads.update { listOf(item) + it }
        prefs.saveDownloads(_downloads.value)
        startDownload(item.id)
    }

    fun startDownload(id: String) {
        val item = _downloads.value.find { it.id == id } ?: return
        activeJobs[id]?.cancel()

        updateItem(id) {
            it.copy(
                status = DownloadStatus.DOWNLOADING,
                errorMessage = "",
                speedBytesPerSec = 0L,
                statusDetail = if (it.mode == DownloadMode.MEDIA_VIDEO) "Analyzing..." else ""
            )
        }
        updateForegroundNotification(force = true)

        val job = scope.launch {
            try {
                if (item.mode == DownloadMode.MEDIA_VIDEO) {
                    val (savedPath, fileSize) = mediaDownloader.downloadMedia(
                        processId = id,
                        url = item.url,
                        customName = if (item.fileName.startsWith("Video (")) "" else item.fileName,
                        quality = item.quality
                    ) { percent, detail, resolvedTitle ->
                        updateItem(id) { current ->
                            current.copy(
                                fileName = resolvedTitle ?: current.fileName,
                                progressPercent = percent,
                                statusDetail = detail,
                                status = DownloadStatus.DOWNLOADING
                            )
                        }
                        updateForegroundNotification(force = false)
                    }

                    val finalName = File(savedPath).name
                    updateItem(id) {
                        it.copy(
                            fileName = finalName,
                            downloadedBytes = fileSize,
                            totalBytes = fileSize,
                            progressPercent = 100f,
                            speedBytesPerSec = 0L,
                            statusDetail = "",
                            status = DownloadStatus.COMPLETED,
                            filePath = savedPath,
                            errorMessage = ""
                        )
                    }
                } else {
                    val savedPath = directDownloader.download(
                        url = item.url,
                        requestedFileName = item.fileName,
                        threads = item.threads
                    ) { downloaded, total, speed, resolvedName ->
                        val pct = if (total > 0L) (downloaded.toFloat() / total.toFloat()) * 100f else 0f
                        updateItem(id) {
                            it.copy(
                                fileName = resolvedName,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                progressPercent = pct,
                                speedBytesPerSec = speed,
                                status = DownloadStatus.DOWNLOADING
                            )
                        }
                        updateForegroundNotification(force = false)
                    }

                    updateItem(id) {
                        val finalTotal = if (it.totalBytes > 0L) it.totalBytes else it.downloadedBytes
                        it.copy(
                            downloadedBytes = finalTotal,
                            totalBytes = finalTotal,
                            progressPercent = 100f,
                            speedBytesPerSec = 0L,
                            status = DownloadStatus.COMPLETED,
                            filePath = savedPath,
                            errorMessage = ""
                        )
                    }
                }
            } catch (_: CancellationException) {
                updateItem(id) {
                    if (it.status == DownloadStatus.DOWNLOADING) {
                        it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
                    } else {
                        it
                    }
                }
            } catch (e: Exception) {
                updateItem(id) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        speedBytesPerSec = 0L,
                        errorMessage = (e.message ?: "Download error").take(140)
                    )
                }
            } finally {
                activeJobs.remove(id)
                prefs.saveDownloads(_downloads.value)
                updateForegroundNotification(force = true)
            }
        }
        activeJobs[id] = job
    }

    fun pauseDownload(id: String) {
        val item = _downloads.value.find { it.id == id }
        if (item?.mode == DownloadMode.MEDIA_VIDEO) {
            mediaDownloader.cancelProcess(id)
        }
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        updateItem(id) { it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) }
        prefs.saveDownloads(_downloads.value)
        updateForegroundNotification(force = true)
    }

    fun deleteDownload(id: String) {
        val item = _downloads.value.find { it.id == id }
        if (item?.mode == DownloadMode.MEDIA_VIDEO) {
            mediaDownloader.cancelProcess(id)
        }
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        if (item != null && item.filePath.isNotBlank()) {
            runCatching { File(item.filePath).delete() }
        }
        _downloads.update { list -> list.filterNot { it.id == id } }
        prefs.saveDownloads(_downloads.value)
        updateForegroundNotification(force = true)
    }

    private fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) {
        _downloads.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun updateForegroundNotification(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifUpdate < 700L) return
        lastNotifUpdate = now

        val active = _downloads.value.filter { it.status == DownloadStatus.DOWNLOADING }
        if (active.isEmpty()) {
            DownloadService.stop(appContext)
            return
        }
        val totalSpeed = active.sumOf { it.speedBytesPerSec }
        val avgProgress = (active.map { it.progress }.average() * 100).toInt()
        val title = if (active.size == 1) active.first().fileName else "SFIDM (${active.size} active)"
        val text = if (totalSpeed > 0L) "${formatSpeed(totalSpeed)} • $avgProgress%" else "$avgProgress%"
        DownloadService.startOrUpdate(appContext, title, text, avgProgress)
    }

    private fun guessHostLabel(url: String): String {
        val host = url.substringAfter("://").substringBefore("/").removePrefix("www.").removePrefix("m.")
        return "Video (${host.ifBlank { "Media" }})"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.1f MB", bytes / mb)
            bytes >= kb -> String.format("%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        val kb = 1024.0
        val mb = kb * 1024.0
        return when {
            bytesPerSec >= mb -> String.format("%.2f MB/s", bytesPerSec / mb)
            else -> String.format("%.0f KB/s", bytesPerSec / kb)
        }
    }
}
