package com.example.utils

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object DownloadedPostersManager {
    private const val POSTERS_DIR = "downloaded_posters"

    private fun getPostersDir(context: Context): File {
        val dir = File(context.filesDir, POSTERS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getLocalPosterFile(context: Context, mediaId: String): File {
        val safe = mediaId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(getPostersDir(context), "${safe}.jpg")
    }

    fun getPosterModel(context: Context, mediaId: String, remoteUrl: String): Any {
        if (mediaId.isNotBlank()) {
            val local = getLocalPosterFile(context, mediaId)
            if (local.exists() && local.length() > 0L) {
                return local
            }
        }
        return remoteUrl
    }

    suspend fun savePosterLocally(context: Context, mediaId: String, posterUrl: String) = withContext(Dispatchers.IO) {
        if (posterUrl.isBlank() || mediaId.isBlank()) return@withContext
        val local = getLocalPosterFile(context, mediaId)
        if (local.exists() && local.length() > 0L) return@withContext
        try {
            val url = URL(posterUrl)
            val connection = url.openConnection()
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.getInputStream().use { input ->
                FileOutputStream(local).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: Exception) {
            try {
                val req = ImageRequest.Builder(context)
                    .data(posterUrl)
                    .build()
                context.imageLoader.enqueue(req)
            } catch (_: Exception) {}
        }
    }

    fun removePoster(context: Context, mediaId: String) {
        try {
            val local = getLocalPosterFile(context, mediaId)
            if (local.exists()) {
                local.delete()
            }
        } catch (_: Exception) {}
    }
}
