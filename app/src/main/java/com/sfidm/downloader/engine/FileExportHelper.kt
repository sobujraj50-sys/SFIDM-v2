package com.sfidm.downloader.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream

object FileExportHelper {

    fun getWorkingDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SFIDM")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun exportToPublicDownloads(context: Context, sourceFile: File, fileName: String = sourceFile.name) {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return
        try {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SFIDM")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                @Suppress("DEPRECATION")
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "SFIDM"
                )
                if (!publicDir.exists()) publicDir.mkdirs()
                val dest = File(publicDir, fileName)
                sourceFile.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            }
        } catch (_: Exception) {
            // File remains safely in app's external Downloads/SFIDM directory
        }
    }

    fun sanitizeFileName(name: String): String {
        val invalidChars = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
        return name.map { if (it in invalidChars) '_' else it }
            .joinToString("")
            .trim()
            .ifBlank { "SFIDM_${System.currentTimeMillis()}.bin" }
    }
}
