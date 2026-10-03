package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Bookmark

import kotlinx.coroutines.launch
import com.example.data.model.LibraryItem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.ui.platform.LocalContext
import com.example.data.repository.LibraryRepository
import androidx.compose.runtime.collectAsState

import coil.compose.AsyncImage
import androidx.compose.ui.graphics.painter.ColorPainter
import coil.request.ImageRequest
import coil.size.Scale

@Composable
fun MediaCard(
    title: String,
    posterUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    rank: Int? = null,
    rating: Double = 8.7,
    year: String = "2024",
    isMovie: Boolean = true,
    contentType: String = if (isMovie) com.example.data.model.ContentType.MOVIE else com.example.data.model.ContentType.TV,
    mediaId: String? = null
) {
    
    val context = LocalContext.current
    val libraryRepository = remember { LibraryRepository(context) }
    
    val isBookmarked by if (mediaId != null) {
        libraryRepository.isItemInLibrary(mediaId, contentType).collectAsState(initial = false)
    } else {
        remember { mutableStateOf(false) }
    }

    var showRemoveDialog by remember { mutableStateOf(false) }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.remove_from_favorites), color = MaterialTheme.colorScheme.onSurface) },
            text = { Text(stringResource(R.string.remove_from_favorites_confirm), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                val scope = rememberCoroutineScope()
                TextButton(onClick = {
                    if (mediaId != null) {
                        scope.launch {
                            val libItem = LibraryItem.create(
                                contentType = contentType,
                                tmdbId = mediaId,
                                title = title,
                                posterUrl = posterUrl
                            )
                            libraryRepository.removeFromLibrary(libItem)
                        }
                    }
                    showRemoveDialog = false
                }) {
                    Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurface)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    Box(
        modifier = modifier
            .width(140.dp)
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick?.invoke() }
                )
            }
    ) {
        // Shimmer skeleton background inside the card
        Box(modifier = Modifier.fillMaxSize().shimmerEffect())
        
        if (posterUrl.isNotBlank()) {
            val context = LocalContext.current
            val imageRequest = remember(posterUrl, mediaId) {
                val model = if (!mediaId.isNullOrBlank()) {
                    com.example.utils.DownloadedPostersManager.getPosterModel(context, mediaId, posterUrl)
                } else {
                    posterUrl
                }
                ImageRequest.Builder(context)
                    .data(model)
                    .memoryCacheKey(posterUrl)
                    .diskCacheKey(posterUrl)
                    .crossfade(true)
                    .scale(Scale.FILL)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // Gradient overlay for text readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.9f)),
                        startY = 150f
                    )
                )
        )
        
        // Top Badges
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            if (rank != null) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = rank.toString(), color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Spacer(modifier = Modifier.width(4.dp))
            }
            
            if (title.isNotBlank()) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder, 
                    contentDescription = stringResource(R.string.cd_bookmark), 
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        
        // Bottom Text (Title, Rating, Year)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, contentDescription = stringResource(R.string.cd_rating), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = rating.toString(), color = Color.LightGray, fontSize = 11.sp)
                    }
                    Text(text = year, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
            }
        }
    }
}