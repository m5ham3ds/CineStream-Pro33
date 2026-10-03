package com.example.utils

import android.content.Context
import java.io.File

object MediaStorageUtils {

    /**
     * Get the dedicated internal, app-private directory for downloaded movies/series/anime.
     * Stored in context.filesDir ("movies"), keeping them hidden from system gallery / file scanners.
     * Includes a .nomedia file as additional protection.
     */
    fun getMediaDirectory(context: Context): File {
        val dir = File(context.filesDir, "movies")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) {
            try {
                noMedia.createNewFile()
            } catch (e: Exception) {
                // ignore
            }
        }
        return dir
    }

    /**
     * Find existing downloaded media file for a given item id across supported extensions or legacy paths.
     */
    fun findMediaFile(context: Context, id: String): File? {
        val internalDir = getMediaDirectory(context)
        
        // 1. Check in internal app directory first
        val matchingInternal = internalDir.listFiles { file ->
            file.isFile && (file.name == id || file.name.startsWith("${id}."))
        }
        if (!matchingInternal.isNullOrEmpty()) {
            return matchingInternal.first()
        }

        // 2. Fallback for series/episode naming combinations (e.g. id="1234_5678" or id="1234")
        if (id.contains("_")) {
            val epPart = id.substringAfter("_")
            val matchingEp = internalDir.listFiles { file ->
                file.isFile && (file.name == epPart || file.name.startsWith("${epPart}."))
            }
            if (!matchingEp.isNullOrEmpty()) {
                return matchingEp.first()
            }
        } else {
            val matchingSeriesPart = internalDir.listFiles { file ->
                file.isFile && file.name.startsWith("${id}_")
            }
            if (!matchingSeriesPart.isNullOrEmpty()) {
                return matchingSeriesPart.first()
            }
        }

        // 3. Check legacy external files dir (Android/data/.../files/Movies)
        val legacyExternal = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
        if (legacyExternal != null && legacyExternal.exists()) {
            val matchingExternal = legacyExternal.listFiles { file ->
                file.isFile && (file.name == id || file.name.startsWith("${id}.") || (id.contains("_") && file.name.startsWith("${id.substringAfter("_")}.")) || (!id.contains("_") && file.name.startsWith("${id}_")))
            }
            if (!matchingExternal.isNullOrEmpty()) {
                return matchingExternal.first()
            }
        }

        // 4. Check legacy downloads folder in filesDir
        val legacyDownloads = File(context.filesDir, "downloads")
        if (legacyDownloads.exists()) {
            val matchingDownloads = legacyDownloads.listFiles { file ->
                file.isFile && (file.name == id || file.name.startsWith("${id}.") || (id.contains("_") && file.name.startsWith("${id.substringAfter("_")}.")) || (!id.contains("_") && file.name.startsWith("${id}_")))
            }
            if (!matchingDownloads.isNullOrEmpty()) {
                return matchingDownloads.first()
            }
        }

        return null
    }

    /**
     * Check if a media file exists and has content for a given id.
     */
    fun hasDownloadedMedia(context: Context, id: String): Boolean {
        val file = findMediaFile(context, id)
        return file != null && file.exists() && file.length() > 0L
    }

    /**
     * Create the destination file for a new download or transfer in internal storage,
     * preserving its original extension.
     */
    fun getDestinationFile(context: Context, id: String, extension: String? = null): File {
        val dir = getMediaDirectory(context)
        val cleanExt = extension?.trim()?.removePrefix(".")?.ifEmpty { null }
        val fileName = if (cleanExt != null) "${id}.${cleanExt}" else "${id}.mp4"
        return File(dir, fileName)
    }

    /**
     * Get the real file size on disk in bytes for a given item id.
     */
    fun getActualFileSize(context: Context, id: String): Long {
        val file = findMediaFile(context, id)
        return if (file != null && file.exists()) file.length() else 0L
    }

    /**
     * Format byte count into human-readable size (e.g., 450 MB, 1.25 GB).
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            else -> String.format(java.util.Locale.US, "%.0f KB", kb)
        }
    }
}
