package com.example.ui.screens.player

import androidx.compose.runtime.Composable

/**
 * ServerSelectionDialog:
 * Interface maintained for architectural backwards compatibility.
 * Playback is handled directly inside InlineDetailVideoPlayer and PlayerScreen without popup dialogs.
 * Download requests are immediately delegated to SmartDownloadQualityDialog without intermediate dialogs.
 */
@Composable
fun ServerSelectionDialog(
    title: String,
    year: String = "",
    isMovie: Boolean,
    season: Int = 1,
    episode: Int = 1,
    isAnime: Boolean = false,
    posterUrl: String? = null,
    mediaId: String = "",
    isDownloadMode: Boolean = false,
    onDismiss: () -> Unit,
    onPlay: (url: String, serverName: String, website: String) -> Unit = { _, _, _ -> },
    onNavigateToExtensions: () -> Unit = {}
) {
    if (isDownloadMode) {
        SmartDownloadQualityDialog(
            title = title,
            year = year,
            isMovie = isMovie,
            season = season,
            episode = episode,
            isAnime = isAnime,
            posterUrl = posterUrl,
            mediaId = mediaId,
            onDismiss = onDismiss,
            onNavigateToExtensions = onNavigateToExtensions
        )
    }
}
