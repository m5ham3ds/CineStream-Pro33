package com.example.ui.screens.home
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.foundation.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.draw.*
import androidx.compose.foundation.shape.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.res.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.unit.*
import com.example.ui.components.*
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.*
import com.example.data.util.ContentTypeResolver
import com.example.data.repository.*
import com.example.domain.models.*
import com.example.ui.ViewModelFactory
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.lazy.*
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import androidx.compose.material3.pulltorefresh.*
import com.example.ui.screens.home.HomeViewModel







@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onPlayMovie: ((String) -> Unit)? = null,
    onPlaySeries: ((String) -> Unit)? = null,
    onNavigateToTrending: () -> Unit = {},
    onNavigateToWatching: () -> Unit = {},
    onNavigateToPopular: () -> Unit = {},
    onNavigateToNewReleases: () -> Unit = {},
    onNavigateToUpcoming: () -> Unit = {},
    onNavigateToAnime: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val historyRepository = remember { HistoryRepository(context) }
    val historyItems by historyRepository.getHistoryItems().collectAsState(initial = emptyList())
    val libraryRepository = remember { LibraryRepository(context) }
    val downloadRepository = remember { DownloadRepository(context) }
    val scope = rememberCoroutineScope()

    val hasRemoteContent = uiState.trendingMovies.isNotEmpty() ||
            uiState.allMovies.isNotEmpty() ||
            uiState.popularMovies.isNotEmpty() ||
            uiState.trendingSeries.isNotEmpty() ||
            uiState.popularSeries.isNotEmpty() ||
            uiState.allSeries.isNotEmpty() ||
            uiState.actionMovies.isNotEmpty() ||
            uiState.upcomingMovies.isNotEmpty() ||
            uiState.newReleasesMovies.isNotEmpty() ||
            uiState.trendingAnime.isNotEmpty() ||
            uiState.animeSeries.isNotEmpty()

    LaunchedEffect(Unit) {
        if (!hasRemoteContent) {
            viewModel.loadData()
        }
    }

    var showBottomSheet by remember { mutableStateOf(false) }
    var bottomSheetIsMovie by remember { mutableStateOf(true) }
    var bottomSheetContentType by remember { mutableStateOf(ContentType.MOVIE) }
    var selectedMediaId by remember { mutableStateOf("") }
    var selectedMediaTitle by remember { mutableStateOf("") }
    var selectedMediaPoster by remember { mutableStateOf("") }

    val categories = listOf(stringResource(R.string.home), stringResource(R.string.movies), stringResource(R.string.series), stringResource(R.string.anime))
    var selectedCategory by remember { mutableStateOf(categories[0]) }
    val ptrState = rememberPullToRefreshState()
    
    val trendingMix = remember(uiState.trendingMovies, uiState.trendingSeries) {
        (uiState.trendingMovies.take(5) + uiState.trendingSeries.map { 
            Movie(id = it.id, title = it.title, overview = it.overview, posterUrl = it.posterUrl, backdropUrl = it.backdropUrl, year = it.year, rating = it.rating, genres = it.genres, runtime = 0)
        }.take(5))
    }
    val newReleasesMix = remember(uiState.newReleasesMovies, uiState.newReleasesSeries) {
        (uiState.newReleasesMovies.take(10) + uiState.newReleasesSeries.map { 
            Movie(id = it.id, title = it.title, overview = it.overview, posterUrl = it.posterUrl, backdropUrl = it.backdropUrl, year = it.year, rating = it.rating, genres = it.genres, runtime = 0)
        }.take(10))
    }
    val upcomingMix = remember(uiState.upcomingMovies) {
        uiState.upcomingMovies.take(10)
    }

    PullToRefreshBox(
        isRefreshing = uiState.isLoading,
        onRefresh = { viewModel.loadData(forceRefresh = true) },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!hasRemoteContent) {
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
                            onClick = { viewModel.loadData(forceRefresh = true) },
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
            if (uiState.trendingMovies.isNotEmpty()) {
                HeroCarousel(items = uiState.trendingMovies.take(5).map { HeroItem(it.id, it.title, it.backdropUrl, true, contentType = ContentType.MOVIE) }, onClick = onMovieClick)
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
                items(categories, key = { it }) { category ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedCategory == category) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                            .border(
                                width = 1.dp,
                                color = if (selectedCategory == category) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                if (selectedCategory == category) {
                                    selectedCategory = categories[0]
                                } else {
                                    selectedCategory = category
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = category,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            fontWeight = if (selectedCategory == category) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

        }


        item {
            Spacer(modifier = Modifier.height(24.dp))

        }
        

        item {
            if (selectedCategory == categories[0]) {
            // 1. Continue Watching
            if (historyItems.isNotEmpty()) {
                SectionTitle(stringResource(R.string.continue_watching), onSeeAllClick = onNavigateToWatching)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(historyItems, key = { it.id }) { item ->
                        ContinueWatchingCardShared(
                            item = item,
                            onClick = {
                                if (item.isMovie) onMovieClick(item.id) else onSeriesClick(item.id)
                            },
                            onPlayClick = {
                                if (item.isMovie) {
                                    if (onPlayMovie != null) onPlayMovie(item.id) else onMovieClick(item.id)
                                } else {
                                    if (onPlaySeries != null) onPlaySeries(item.id) else onSeriesClick(item.id)
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 2. Trending Now
            if (uiState.trendingMovies.isNotEmpty() || uiState.trendingSeries.isNotEmpty()) {
                SectionTitle(stringResource(R.string.trending_now), onSeeAllClick = onNavigateToTrending)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(trendingMix, key = { index, item -> "trending_${if (index < 5) "m" else "s"}_${item.id}" }) { index, item ->
                        val isMovie = index < 5
                        val resolvedType = if (isMovie) ContentType.MOVIE else ContentTypeResolver.resolveSeries(uiState.trendingSeries[index - 5])
                        MediaCard(
                            title = item.title,
                            posterUrl = item.posterUrl,
                            rank = index + 1,
                            rating = item.rating,
                            year = item.year.toString(),
                            isMovie = isMovie,
                            contentType = resolvedType,
                            mediaId = item.id,
                            onClick = { if (isMovie) onMovieClick(item.id) else onSeriesClick(item.id) },
                            onLongClick = { 
                                bottomSheetIsMovie = isMovie
                                bottomSheetContentType = resolvedType
                                selectedMediaId = item.id
                                selectedMediaTitle = item.title
                                selectedMediaPoster = item.posterUrl
                                showBottomSheet = true
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 3. New Releases
            if (uiState.newReleasesMovies.isNotEmpty() || uiState.newReleasesSeries.isNotEmpty()) {
                SectionTitle(stringResource(R.string.new_releases), onSeeAllClick = onNavigateToNewReleases)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(newReleasesMix, key = { index, item -> "new_releases_${if (index < 10) "m" else "s"}_${item.id}" }) { index, item ->
                        val isMovie = index < 10
                        val resolvedType = if (isMovie) ContentType.MOVIE else ContentTypeResolver.resolveSeries(uiState.newReleasesSeries[index - 10])
                        MediaCard(
                            title = item.title,
                            posterUrl = item.posterUrl,
                            rank = index + 1,
                            rating = item.rating,
                            year = item.year.toString(),
                            isMovie = isMovie,
                            contentType = resolvedType,
                            mediaId = item.id,
                            onClick = { if (isMovie) onMovieClick(item.id) else onSeriesClick(item.id) },
                            onLongClick = { 
                                bottomSheetIsMovie = isMovie
                                bottomSheetContentType = resolvedType
                                selectedMediaId = item.id
                                selectedMediaTitle = item.title
                                selectedMediaPoster = item.posterUrl
                                showBottomSheet = true
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 4. Trending Movies
            if (uiState.trendingMovies.isNotEmpty()) {
                SectionTitle(stringResource(R.string.trending_movies), onSeeAllClick = onNavigateToTrending)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(uiState.trendingMovies, key = { index, movie -> movie.id }) { index, movie ->
                        MediaCard(
                            title = movie.title,
                            posterUrl = movie.posterUrl,
                            rank = index + 1,
                            rating = movie.rating,
                            year = movie.year.toString(),
                            isMovie = true,
                            contentType = ContentType.MOVIE,
                            mediaId = movie.id,
                            onClick = { onMovieClick(movie.id) },
                            onLongClick = { 
                                bottomSheetIsMovie = true
                                bottomSheetContentType = ContentType.MOVIE
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

            // 5. Trending Series
            if (uiState.trendingSeries.isNotEmpty()) {
                SectionTitle(stringResource(R.string.trending_series), onSeeAllClick = onNavigateToTrending)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(uiState.trendingSeries, key = { index, series -> series.id }) { index, series ->
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
                                bottomSheetIsMovie = false
                                bottomSheetContentType = resolvedType
                                selectedMediaId = series.id
                                selectedMediaTitle = series.title
                                selectedMediaPoster = series.posterUrl
                                showBottomSheet = true
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 6. Trending Anime
            if (uiState.animeSeries.isNotEmpty()) {
                SectionTitle(stringResource(R.string.trending_anime), onSeeAllClick = onNavigateToTrending)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(uiState.animeSeries, key = { index, series -> series.id }) { index, series ->
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
                                bottomSheetIsMovie = false
                                bottomSheetContentType = resolvedType
                                selectedMediaId = series.id
                                selectedMediaTitle = series.title
                                selectedMediaPoster = series.posterUrl
                                showBottomSheet = true
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 7. Coming Soon
            if (uiState.upcomingMovies.isNotEmpty()) {
                SectionTitle(stringResource(R.string.coming_soon), onSeeAllClick = onNavigateToUpcoming)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(upcomingMix, key = { _, item -> "upcoming_${item.id}" }) { index, item ->
                        MediaCard(
                            title = item.title,
                            posterUrl = item.posterUrl,
                            rank = index + 1,
                            rating = item.rating,
                            year = item.year.toString(),
                            isMovie = true,
                            contentType = ContentType.MOVIE,
                            mediaId = item.id,
                            onClick = { onMovieClick(item.id) },
                            onLongClick = { 
                                bottomSheetIsMovie = true
                                bottomSheetContentType = ContentType.MOVIE
                                selectedMediaId = item.id
                                selectedMediaTitle = item.title
                                selectedMediaPoster = item.posterUrl
                                showBottomSheet = true
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

    } else {
                val displayItems = when (selectedCategory) {
                    "Movies" -> uiState.allMovies
                    "Series" -> uiState.allSeries
                    stringResource(R.string.anime) -> uiState.animeSeries
                    "Documentaries" -> uiState.allMovies.filter { it.genres.contains("Documentary") }
                    else -> emptyList()
                }

                com.example.ui.components.VerticalGrid(
                    items = displayItems,
                    columns = 3,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { item ->
                    // Because displayItems can be Movie or Series, we need to handle both
                    // We'll just cast check since Kotlin supports it
                    if (item is com.example.domain.models.Movie) {
                        MediaCard(
                            title = item.title,
                            posterUrl = item.posterUrl,
                                                    rating = item.rating,
                            year = item.year.toString(),
                            isMovie = true,
                            contentType = ContentType.MOVIE,
                            mediaId = item.id,
                            onClick = { onMovieClick(item.id) },
                            onLongClick = { 
                                bottomSheetIsMovie = true
                                bottomSheetContentType = ContentType.MOVIE
                                selectedMediaId = item.id
                                selectedMediaTitle = item.title
                                selectedMediaPoster = item.posterUrl
                                showBottomSheet = true
                            }
                        )
                    } else if (item is com.example.domain.models.Series) {
                        val resolvedType = ContentTypeResolver.resolveSeries(item)
                        MediaCard(
                            title = item.title,
                            posterUrl = item.posterUrl,
                            rank = null,
                            rating = item.rating,
                            year = item.year.toString(),
                            isMovie = false,
                            contentType = resolvedType,
                            mediaId = item.id,
                            onClick = { onSeriesClick(item.id) },
                            onLongClick = { 
                                bottomSheetIsMovie = false
                                bottomSheetContentType = resolvedType
                                selectedMediaId = item.id
                                selectedMediaTitle = item.title
                                selectedMediaPoster = item.posterUrl
                                showBottomSheet = true
                            }
                        )
                    }
                }
            }

        }
    }
}
if (showBottomSheet) {
            MediaActionBottomSheet(
                isMovie = bottomSheetIsMovie,
                mediaId = selectedMediaId,
                title = selectedMediaTitle,
                posterUrl = selectedMediaPoster,
                contentType = bottomSheetContentType,
                onDismissRequest = { showBottomSheet = false },
                onDownloadStart = { quality ->
                    scope.launch {
                        downloadRepository.addToDownloads(DownloadItem(
                            id = selectedMediaId,
                            mediaId = selectedMediaId,
                            title = selectedMediaTitle,
                            posterUrl = selectedMediaPoster,
                            isMovie = bottomSheetIsMovie,
                            quality = quality
                        ))
                        Toast.makeText(context, context.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
                    }
                },
                onAddToLibrary = {
                    scope.launch {
                        val libItem = LibraryItem.create(
                            contentType = bottomSheetContentType,
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



@Composable
fun SectionTitle(title: String, onSeeAllClick: (() -> Unit)? = null) {
    val parts = title.split(" ", limit = 2)
    val firstWord = parts.getOrNull(0) ?: ""
    val rest = parts.getOrNull(1) ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row {
                Text(
                    text = firstWord,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (rest.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = rest,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        
        if (onSeeAllClick != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSeeAllClick() }
            ) {
                Text(
                    text = stringResource(R.string.see_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.see_all),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    

}
}
