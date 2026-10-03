package com.example.ui.screens.movies

import android.widget.Toast
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
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
fun MoviesScreen(
    onMovieClick: (String) -> Unit,
    onPlayMovie: ((String) -> Unit)? = null,
    onNavigateToTrending: () -> Unit = {},
    onNavigateToWatching: () -> Unit = {},
    onNavigateToPopular: () -> Unit = {},
    onNavigateToNewReleases: () -> Unit = {},
    onNavigateToUpcoming: () -> Unit = {},
    viewModel: MoviesViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val libraryRepository = remember { LibraryRepository(context) }
    val historyRepository = remember { com.example.data.repository.HistoryRepository(context) }
    val downloadRepository = remember { DownloadRepository(context) }

    val historyItems by historyRepository.getHistoryItems().collectAsState(initial = emptyList())
    val movieHistoryItems = historyItems.filter { it.isMovie && !com.example.data.repository.AnimePlaybackStore.isAnime(context, it.id, it.title) }

    val scope = rememberCoroutineScope()

    val hasMovieContent = uiState.trendingMovies.isNotEmpty() ||
            uiState.movies.isNotEmpty() ||
            uiState.newReleasesMovies.isNotEmpty() ||
            uiState.upcomingMovies.isNotEmpty()

    LaunchedEffect(Unit) {
        if (!hasMovieContent) {
            viewModel.loadMovies()
        }
    }

    var showBottomSheet by remember { mutableStateOf(false) }
    var selectedMediaId by remember { mutableStateOf("") }
    var selectedMediaTitle by remember { mutableStateOf("") }
    var selectedMediaPoster by remember { mutableStateOf("") }

    var selectedCategory by remember { mutableStateOf("Movies") }

    data class CategoryItem(val id: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
    val categories = listOf(
        CategoryItem("Movies", stringResource(R.string.category_movies), Icons.Default.LocalMovies),
        CategoryItem("Genres", stringResource(R.string.category_genres), Icons.Default.Category),
        CategoryItem("New Releases", stringResource(R.string.new_releases), Icons.Default.NewReleases),
        CategoryItem("Top Rated", stringResource(R.string.category_top_rated), Icons.Default.Star)
    )
    val ptrState = rememberPullToRefreshState()
    
    PullToRefreshBox(
        isRefreshing = uiState.isLoading,
        onRefresh = { viewModel.loadMovies(forceRefresh = true) },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!hasMovieContent) {
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
                            onClick = { viewModel.loadMovies(forceRefresh = true) },
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
            val heroMovies = if (uiState.trendingMovies.isNotEmpty()) uiState.trendingMovies else uiState.movies
            if (heroMovies.isNotEmpty()) {
                HeroCarousel(items = heroMovies.take(5).map { HeroItem(it.id, it.title, it.backdropUrl, true, contentType = ContentType.MOVIE) }, onClick = onMovieClick)
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
                                    selectedCategory = "Movies"
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
            val isMoviesSelected = selectedCategory == "Movies" || selectedCategory == categories[0].id || selectedCategory == stringResource(R.string.category_movies)
            if (isMoviesSelected) {
                if (movieHistoryItems.isNotEmpty()) {
                    SectionTitleShared(stringResource(R.string.continue_watching), onSeeAllClick = onNavigateToWatching)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(movieHistoryItems, key = { it.id }) { item ->
                            ContinueWatchingCardShared(
                                item = item,
                                onClick = { onMovieClick(item.id) },
                                onPlayClick = {
                                    if (onPlayMovie != null) onPlayMovie(item.id) else onMovieClick(item.id)
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Trending Movies
                if (uiState.trendingMovies.isNotEmpty()) {
                    SectionTitleShared(stringResource(R.string.trending_movies), onSeeAllClick = onNavigateToTrending)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(uiState.trendingMovies, key = { _, movie -> movie.id }) { index, movie ->
                            MediaCard(
                                title = movie.title,
                                posterUrl = movie.posterUrl,
                                                            rating = movie.rating,
                                year = movie.releaseDate?.take(4) ?: "2024",
                                mediaId = movie.id,
                                onClick = { onMovieClick(movie.id) },
                                onLongClick = { 
                                    selectedMediaId = movie.id
                                    selectedMediaTitle = movie.title
                                    selectedMediaPoster = movie.posterUrl
                                    showBottomSheet = true
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // New Releases
                if (uiState.newReleasesMovies.isNotEmpty()) {
                    SectionTitleShared(stringResource(R.string.new_releases), onSeeAllClick = onNavigateToNewReleases)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(uiState.newReleasesMovies, key = { _, movie -> movie.id }) { index, movie ->
                            MediaCard(
                                title = movie.title,
                                posterUrl = movie.posterUrl,
                                rank = index + 1,
                                rating = movie.rating,
                                year = movie.releaseDate?.take(4) ?: "2024",
                                mediaId = movie.id,
                                onClick = { onMovieClick(movie.id) },
                                onLongClick = { 
                                    selectedMediaId = movie.id
                                    selectedMediaTitle = movie.title
                                    selectedMediaPoster = movie.posterUrl
                                    showBottomSheet = true
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Popular Movies
                if (uiState.movies.isNotEmpty()) {
                    SectionTitleShared(stringResource(R.string.popular_movies), onSeeAllClick = onNavigateToPopular)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(uiState.movies, key = { _, movie -> movie.id }) { index, movie ->
                            MediaCard(
                                title = movie.title,
                                posterUrl = movie.posterUrl,
                                rank = index + 1,
                                rating = movie.rating,
                                year = movie.releaseDate?.take(4) ?: "2024",
                                mediaId = movie.id,
                                onClick = { onMovieClick(movie.id) },
                                onLongClick = { 
                                    selectedMediaId = movie.id
                                    selectedMediaTitle = movie.title
                                    selectedMediaPoster = movie.posterUrl
                                    showBottomSheet = true
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Coming Soon Movies
                if (uiState.upcomingMovies.isNotEmpty()) {
                    SectionTitleShared(stringResource(R.string.coming_soon), onSeeAllClick = onNavigateToUpcoming)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(uiState.upcomingMovies, key = { _, movie -> movie.id }) { index, movie ->
                            MediaCard(
                                title = movie.title,
                                posterUrl = movie.posterUrl,
                                rank = index + 1,
                                rating = movie.rating,
                                year = movie.releaseDate?.take(4) ?: "2024",
                                mediaId = movie.id,
                                onClick = { onMovieClick(movie.id) },
                                onLongClick = { 
                                    selectedMediaId = movie.id
                                    selectedMediaTitle = movie.title
                                    selectedMediaPoster = movie.posterUrl
                                    showBottomSheet = true
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            } else {
                val displayItems = remember(selectedCategory, uiState.movies) {
                    when (selectedCategory) {
                        "New Releases" -> uiState.movies.reversed()
                        "Top Rated" -> uiState.movies.sortedByDescending { it.rating }
                        "Genres" -> uiState.movies.sortedBy { it.title }
                        else -> uiState.movies
                    }
                }

                com.example.ui.components.VerticalGrid(
                    items = displayItems,
                    columns = 3,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { movie ->
                    MediaCard(
                        title = movie.title,
                        posterUrl = movie.posterUrl,
                                            rating = movie.rating,
                        year = movie.year.toString(),
                        isMovie = true,
                        mediaId = movie.id,
                        onClick = { onMovieClick(movie.id) },
                        onLongClick = { 
                            selectedMediaId = movie.id
                            selectedMediaTitle = movie.title
                            selectedMediaPoster = movie.posterUrl
                            showBottomSheet = true
                        }
                    )
                }
            }

        }}
    }
if (showBottomSheet) {
            MediaActionBottomSheet(
                isMovie = true,
                mediaId = selectedMediaId,
                title = selectedMediaTitle,
                posterUrl = selectedMediaPoster,
                contentType = ContentType.MOVIE,
                onDismissRequest = { showBottomSheet = false },
                onDownloadStart = { quality ->
                    scope.launch {
                        downloadRepository.addToDownloads(DownloadItem(
                            id = selectedMediaId,
                            mediaId = selectedMediaId,
                            title = selectedMediaTitle,
                            posterUrl = selectedMediaPoster,
                            isMovie = true,
                            quality = quality
                        ))
                        Toast.makeText(context, context.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
                    }
                },
                onAddToLibrary = {
                    scope.launch {
                        val libItem = LibraryItem.create(
                            contentType = ContentType.MOVIE,
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
}
