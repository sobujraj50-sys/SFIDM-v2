package com.sfidm.downloader.model

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

enum class DownloadMode {
    MEDIA_VIDEO,   // YouTube, Facebook, TikTok, Instagram, HLS/M3U8, etc. (yt-dlp + FFmpeg)
    DIRECT_FILE    // Direct HTTP multi-thread downloader (4x/8x/16x)
}

enum class MediaQuality(val id: String, val labelEn: String, val labelBn: String) {
    BEST("best", "Best MP4", "সর্বোচ্চ MP4"),
    HD_1080("1080p", "1080p HD", "1080p HD"),
    HD_720("720p", "720p HD", "720p HD"),
    SD_480("480p", "480p SD", "480p SD"),
    AUDIO_MP3("mp3", "Audio MP3", "অডিও MP3");

    companion object {
        fun fromId(id: String?): MediaQuality = entries.find { it.id == id } ?: BEST
    }
}

data class DownloadItem(
    val id: String,
    val fileName: String,
    val url: String,
    val mode: DownloadMode = DownloadMode.DIRECT_FILE,
    val quality: MediaQuality = MediaQuality.BEST,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val progressPercent: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val statusDetail: String = "",
    val threads: Int = 8,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val filePath: String = "",
    val errorMessage: String = ""
) {
    val progress: Float
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            mode == DownloadMode.MEDIA_VIDEO -> (progressPercent / 100f).coerceIn(0f, 1f)
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> (progressPercent / 100f).coerceIn(0f, 1f)
        }

    val etaSeconds: Long
        get() = if (speedBytesPerSec > 0L && totalBytes > downloadedBytes) {
            (totalBytes - downloadedBytes) / speedBytesPerSec
        } else {
            -1L
        }
}
