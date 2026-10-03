package com.example.utils

import android.content.Context
import com.example.data.model.DownloadItem

data class SavedPlaybackInfo(
    val mediaId: String,
    val episodeId: String? = null,
    val url: String,
    val quality: String = "Auto",
    val serverName: String? = null,
    val website: String? = null,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val playbackPageUrl: String? = null,
    val scraperKey: String? = null
)

object LastPlaybackStore {
    private const val PREFS_NAME = "last_playback_store"

    fun savePlayback(
        context: Context,
        mediaId: String,
        url: String,
        quality: String = "Auto",
        serverName: String? = null,
        website: String? = null,
        episodeId: String? = null,
        positionMillis: Long = 0L,
        durationMillis: Long = 0L,
        playbackPageUrl: String? = null,
        scraperKey: String? = null
    ) {
        if (mediaId.isBlank() || url.isBlank()) return
        val cleanQ = DownloadItem.cleanQualityName(quality)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = if (episodeId.isNullOrBlank()) mediaId else "${mediaId}_$episodeId"
        val editor = sp.edit()
            .putString("url_$key", url)
            .putString("quality_$key", cleanQ)
            .putString("server_$key", serverName ?: "")
            .putString("website_$key", website ?: "")

        if (!playbackPageUrl.isNullOrBlank()) {
            editor.putString("page_$key", playbackPageUrl)
        }
        if (!scraperKey.isNullOrBlank()) {
            editor.putString("scraper_$key", scraperKey)
        }

        if (positionMillis > 0L) {
            editor.putLong("pos_$key", positionMillis)
        }
        if (durationMillis > 0L) {
            editor.putLong("dur_$key", durationMillis)
        }

        if (!episodeId.isNullOrBlank()) {
            editor.putString("last_ep_$mediaId", episodeId)
        }
        editor.apply()
    }

    fun saveLastPlayback(
        context: Context,
        mediaId: String,
        url: String,
        serverName: String? = null,
        website: String? = null,
        quality: String = "Auto",
        episodeId: String? = null,
        positionMillis: Long = 0L,
        durationMillis: Long = 0L,
        playbackPageUrl: String? = null,
        scraperKey: String? = null
    ) {
        savePlayback(context, mediaId, url, quality, serverName, website, episodeId, positionMillis, durationMillis, playbackPageUrl, scraperKey)
    }

    fun updatePosition(
        context: Context,
        mediaId: String,
        episodeId: String? = null,
        positionMillis: Long,
        durationMillis: Long
    ) {
        if (mediaId.isBlank()) return
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = if (episodeId.isNullOrBlank()) mediaId else "${mediaId}_$episodeId"
        val editor = sp.edit()
        if (positionMillis > 0L) {
            editor.putLong("pos_$key", positionMillis)
        }
        if (durationMillis > 0L) {
            editor.putLong("dur_$key", durationMillis)
        }
        editor.apply()
    }

    fun updateQuality(
        context: Context,
        mediaId: String,
        episodeId: String? = null,
        quality: String
    ) {
        if (mediaId.isBlank()) return
        val cleanQ = DownloadItem.cleanQualityName(quality)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = if (episodeId.isNullOrBlank()) mediaId else "${mediaId}_$episodeId"
        sp.edit().putString("quality_$key", cleanQ).apply()
    }

    fun getLastPlayback(context: Context, mediaId: String, episodeId: String? = null): SavedPlaybackInfo? {
        if (mediaId.isBlank()) return null
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val epId = if (episodeId.isNullOrBlank()) sp.getString("last_ep_$mediaId", null) else episodeId
        var key = if (epId.isNullOrBlank()) mediaId else "${mediaId}_$epId"
        var url = sp.getString("url_$key", null)
        if (url.isNullOrBlank() && mediaId.contains("_")) {
            // Check direct mediaId if it already had episode appended
            val directUrl = sp.getString("url_$mediaId", null)
            if (!directUrl.isNullOrBlank()) {
                key = mediaId
                url = directUrl
            }
        }
        if (url.isNullOrBlank()) return null
        val quality = sp.getString("quality_$key", "Auto") ?: "Auto"
        val server = sp.getString("server_$key", null)?.takeIf { it.isNotBlank() }
        val website = sp.getString("website_$key", null)?.takeIf { it.isNotBlank() }
        var pos = sp.getLong("pos_$key", 0L)
        if (pos <= 0L) {
            val inMem = com.example.ui.screens.player.PlaybackSyncStore.getPosition(key)
            if (inMem > 0L) pos = inMem
        }
        val dur = sp.getLong("dur_$key", 0L)
        val page = sp.getString("page_$key", null)?.takeIf { it.isNotBlank() }
        val scraper = sp.getString("scraper_$key", null)?.takeIf { it.isNotBlank() }
        return SavedPlaybackInfo(
            mediaId = mediaId,
            episodeId = epId,
            url = url,
            quality = quality,
            serverName = server,
            website = website,
            positionMillis = pos,
            durationMillis = dur,
            playbackPageUrl = page,
            scraperKey = scraper
        )
    }

    fun getLastEpisodeId(context: Context, seriesId: String): String? {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString("last_ep_$seriesId", null)?.takeIf { it.isNotBlank() }
    }

    fun clearPlayback(context: Context, mediaId: String, episodeId: String? = null) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = if (episodeId.isNullOrBlank()) mediaId else "${mediaId}_$episodeId"
        sp.edit()
            .remove("url_$key")
            .remove("quality_$key")
            .remove("server_$key")
            .remove("website_$key")
            .remove("pos_$key")
            .remove("dur_$key")
            .apply()
    }
}
