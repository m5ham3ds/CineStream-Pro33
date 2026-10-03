package com.example.ui.screens.series

import android.widget.Toast
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.ContentType
import com.example.data.util.ContentTypeResolver
import com.example.data.model.DownloadItem
import com.example.data.model.LibraryItem
import com.example.data.repository.DownloadRepository
import com.example.data.repository.LibraryRepository
import com.example.ui.ViewModelFactory
import com.example.ui.components.ContinueWatchingCardShared
import com.example.ui.components.HeroSectionShared
import com.example.ui.components.MediaActionBottomSheet
import com.example.ui.components.MediaCard
import com.example.ui.components.VerticalGrid
import com.example.ui.components.MediaScreenSkeleton
import com.example.ui.components.HeroCarousel
import com.example.ui.components.HeroItem
import com.example.ui.components.SectionTitleShared
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    onSeriesClick: (String) -> Unit,
    onPlaySeries: ((String) -> Unit)? = null,
    onNavigateToTrending: () -> Unit = {},
    onNavigateToWatching: () -> Unit = {},
    onNavigateToPopular: () -> Unit = {},
    onNavigateToNewReleases: () -> Unit = {},
    onNavigateToUpcoming: () -> Unit = {},
    viewModel: SeriesViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val libraryRepository = remember { LibraryRepository(context) }
    val historyRepository = remember { com.example.data.repository.HistoryRepository(context) }
    val downloadRepository = remember { DownloadRepository(context) }

    val historyItems by historyRepository.getHistoryItems().collectAsState(initial = emptyList())
    val seriesHistoryItems = remember(historyItems, uiState) {
        val knownSeriesIds = (uiState.trendingSeries + uiState.series + uiState.newEpisodes + uiState.upcomingSeries).map { it.id }.toSet()
        val knownSeriesTitles = (uiState.trendingSeries + uiState.series + uiState.newEpisodes + uiState.upcomingSeries).map { it.title.lowercase().trim() }

        historyItems.filter { item ->
            val isAnime = com.example.data.repository.AnimePlaybackStore.isAnime(context, item.id, item.title)
            if (isAnime) return@filter false

            val baseId = if (item.id.contains("_")) item.id.substringBeforeLast("_") else item.id
            val lower = item.title.lowercase()

            val isSeriesItem = !item.isMovie ||
                    item.id.contains("_") ||
                    item.title.contains(" - S") ||
                    item.title.contains(" S") ||
                    item.title.contains("حلقة") ||
                    item.title.contains("الموسم") ||
                    item.title.contains("الحلقة") ||
                    knownSeriesIds.contains(item.id) ||
                    knownSeriesIds.contains(baseId) ||
                    knownSeriesTitles.any { lower.contains(it) || it.contains(lower) }

            isSeriesItem
        }
    }

    val scope = rememberCoroutineScope()

    val hasSeriesContent = uiState.trendingSeries.isNotEmpty() ||
            uiState.series.isNotEmpty() ||
            uiState.newEpisodes.isNotEmpty() ||
            uiState.upcomingSeries.isNotEmpty()

    LaunchedEffect(Unit) {
        if (!hasSeriesContent) {
            viewModel.loadSeries()
        }
    }

    var showBottomSheet by remember { mutableStateOf(false) }
    var selectedMediaContentType by remember { mutableStateOf(ContentType.TV) }
    var selectedMediaId by remember { mutableStateOf("") }
    var selectedMediaTitle by remember { mutableStateOf("") }
    var selectedMediaPoster by remember { mutableStateOf("") }

    var selectedCategory by remember { mutableStateOf("Series") }
    data class CategoryItem(val id: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
    val categories = listOf(
        CategoryItem("Series", stringResource(R.string.category_series), Icons.Default.LocalMovies),
        CategoryItem("Genres", stringResource(R.string.category_genres), Icons.Default.Category),
        CategoryItem("New Releases", stringResource(R.string.new_releases), Icons.Default.NewReleases),
        CategoryItem("Top Rated", stringResource(R.string.category_top_rated), Icons.Default.Star)
    )
    val ptrState = rememberPullToRefreshState()
    
    PullToRefreshBox(
        isRefreshing = uiState.isLoading,
        onRefresh = { viewModel.loadSeries(forceRefresh = true) },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!hasSeriesContent) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    MediaScreenSkeleton()
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.check_net_retry),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.loadSeries(forceRefresh = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(stringResource(R.string.retry), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {

        // Hero Section
        item {
            val heroSeries = if (uiState.trendingSeries.isNotEmpty()) uiState.trendingSeries else uiState.newEpisodes
            if (heroSeries.isNotEmpty()) {
                HeroCarousel(items = heroSeries.take(5).map { HeroItem(it.id, it.title, it.backdropUrl, false, contentType = ContentTypeResolver.resolveSeries(it)) }, onClick = onSeriesClick)
            }
        }


        item {
            Spacer(modifier = Modifier.height(16.dp))

        }        // Categories Tab Row

        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                            items(categories, key = { it.id }) { category ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedCategory == category.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                            .clickable {
                                if (selectedCategory == category.id) {
                                    selectedCategory = "Series"
                                } else {
                                    selectedCategory = category.id
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = category.icon, 
                                contentDescription = category.label, 
                                tint = if (selectedCategory == category.id) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = category.label,
                                color = if (selectedCategory == category.id) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                                fontWeight = if (selectedCategory == category.id) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

        }
        item {
            Spacer(modifier = Modifier.height(24.dp))

        }
        item {
            val isSeriesSelected = selectedCategory == "Series" || selectedCategory == categories[0].id || selectedCategory == categories[0].label || selectedCategory == stringResource(R.string.category_series) || selectedCategory == stringResource(R.string.series)
            if (isSeriesSelected) {
                if (seriesHistoryItems.isNotEmpty()) {
                    SectionTitleShared(stringResource(R.string.continue_watching), onSeeAllClick = onNavigateToWatching)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(seriesHistoryItems, key = { it.id }) { item ->
                            val cleanId = if (item.id.contains("_")) item.id.substringBeforeLast("_") else item.id
                            ContinueWatchingCardShared(
                                item = item,
                                onClick = { onSeriesClick(cleanId) },
                                onPlayClick = {
                                    if (onPlaySeries != null) onPlaySeries(cleanId) else onSeriesClick(cleanId)
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Trending Series
                if (uiState.trendingSeries.isNotEmpty()) {
                    SectionTitleShared(stringResource(R.string.trending_series), onSeeAllClick = onNavigateToTrending)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(uiState.trendingSeries, key = { _, series -> series.id }) { index, series ->
                            val resolvedType = ContentTypeResolver.resolveSeries(series)
                            MediaCard(
                                title = series.title,
                                posterUrl = series.posterUrl,
                                                            rating = series.rating,
                                year = com.example.utils.SeasonFormatter.getSeasonString(androidx.compose.ui.platform.LocalContext.current, series.seasons.size),
                                isMovie = false,
                                contentType = resolvedType,
                                mediaId = series.id,
                                onClick = { onSeriesClick(series.id) },
                                onLongClick = { 
                                    selectedMediaId = series.id
                                    selectedMediaTitle = series.title
                                    selectedMediaPoster = series.posterUrl
                                    selectedMediaContentType = resolvedType
                                    showBottomSheet = true
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // New Releases
                if (uiState.newEpisodes.isNotEmpty()) {
                    SectionTitleShared(stringResource(R.string.new_releases), onSeeAllClick = onNavigateToNewReleases)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(uiState.newEpisodes, key = { _, series -> series.id }) { index, series ->
                            val resolvedType = ContentTypeResolver.resolveSeries(series)
                            MediaCard(
                                title = series.title,
                                posterUrl = series.posterUrl,
                                rank = index + 1,
                                rating = series.rating,
                                year = com.example.utils.SeasonFormatter.getSeasonString(androidx.compose.ui.platform.LocalContext.current, series.seasons.size),
                                isMovie = false,
                                contentType = resolvedType,
                                mediaId = series.id,
                                onClick = { onSeriesClick(series.id) },
                                onLongClick = { 
                                    selectedMediaId = series.id
                                    selectedMediaTitle = series.title
                                    selectedMediaPoster = series.posterUrl
                                    selectedMediaContentType = resolvedType
                                    showBottomSheet = true
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Popular Series
                if (uiState.series.isNotEmpty()) {
                    SectionTitleShared(stringResource(R.string.popular_series), onSeeAllClick = onNavigateToPopular)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(uiState.series, key = { _, series -> series.id }) { index, series ->
                            val resolvedType = ContentTypeResolver.resolveSeries(series)
                            MediaCard(
                                title = series.title,
                                posterUrl = series.posterUrl,
                                rank = index + 1,
                                rating = series.rating,
                                year = com.example.utils.SeasonFormatter.getSeasonString(androidx.compose.ui.platform.LocalContext.current, series.seasons.size),
                                isMovie = false,
                                contentType = resolvedType,
                                mediaId = series.id,
                                onClick = { onSeriesClick(series.id) },
                                onLongClick = { 
                                    selectedMediaId = series.id
                                    selectedMediaTitle = series.title
                                    selectedMediaPoster = series.posterUrl
                                    selectedMediaContentType = resolvedType
                                    showBottomSheet = true
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Coming Soon Series
                if (uiState.upcomingSeries.isNotEmpty()) {
                    SectionTitleShared(stringResource(R.string.coming_soon), onSeeAllClick = onNavigateToUpcoming)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(uiState.upcomingSeries, key = { _, series -> series.id }) { index, series ->
                            val resolvedType = ContentTypeResolver.resolveSeries(series)
                            MediaCard(
                                title = series.title,
                                posterUrl = series.posterUrl,
                                rank = index + 1,
                                rating = series.rating,
                                year = com.example.utils.SeasonFormatter.getSeasonString(androidx.compose.ui.platform.LocalContext.current, series.seasons.size),
                                isMovie = false,
                                contentType = resolvedType,
                                mediaId = series.id,
                                onClick = { onSeriesClick(series.id) },
                                onLongClick = { 
                                    selectedMediaId = series.id
                                    selectedMediaTitle = series.title
                                    selectedMediaPoster = series.posterUrl
                                    selectedMediaContentType = resolvedType
                                    showBottomSheet = true
                                },
                                modifier = Modifier.width(140.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            } else {
                val displayItems = remember(selectedCategory, uiState.series) {
                    when (selectedCategory) {
                        "New Releases" -> uiState.series.reversed()
                        "Top Rated" -> uiState.series.sortedByDescending { it.rating }
                        "Genres" -> uiState.series.sortedBy { it.title }
                        else -> uiState.series
                    }
                }

                com.example.ui.components.VerticalGrid(
                    items = displayItems,
                    columns = 3,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { series ->
                    val resolvedType = ContentTypeResolver.resolveSeries(series)
                    MediaCard(
                        title = series.title,
                        posterUrl = series.posterUrl,
                                            rating = series.rating,
                        year = com.example.utils.SeasonFormatter.getSeasonString(androidx.compose.ui.platform.LocalContext.current, series.seasons.size),
                        isMovie = false,
                        contentType = resolvedType,
                        mediaId = series.id,
                        onClick = { onSeriesClick(series.id) },
                        onLongClick = { 
                            selectedMediaId = series.id
                            selectedMediaTitle = series.title
                            selectedMediaPoster = series.posterUrl
                            selectedMediaContentType = resolvedType
                            showBottomSheet = true
                        }
                    )
                }
            }

        }}
    }
    }
if (showBottomSheet) {
            MediaActionBottomSheet(
                isMovie = false,
                mediaId = selectedMediaId,
                title = selectedMediaTitle,
                posterUrl = selectedMediaPoster,
                contentType = selectedMediaContentType,
                onDismissRequest = { showBottomSheet = false },
                onDownloadStart = { quality ->
                    scope.launch {
                        downloadRepository.addToDownloads(DownloadItem(
                            id = selectedMediaId,
                            mediaId = selectedMediaId,
                            title = selectedMediaTitle,
                            posterUrl = selectedMediaPoster,
                            isMovie = false,
                            quality = quality
                        ))
                        Toast.makeText(context, context.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
                    }
                },
                onAddToLibrary = {
                    scope.launch {
                        val libItem = LibraryItem.create(
                            contentType = selectedMediaContentType,
                            tmdbId = selectedMediaId,
                            title = selectedMediaTitle,
                            posterUrl = selectedMediaPoster
                        )
                        libraryRepository.addToLibrary(libItem)
                        Toast.makeText(context, context.getString(R.string.added_to_library), Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    

        }
    
