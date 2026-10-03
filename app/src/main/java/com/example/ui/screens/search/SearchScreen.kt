package com.example.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh

import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.LocalMovies
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ui.ViewModelFactory
import com.example.ui.components.MediaCard
import com.example.ui.components.SectionTitleShared

data class UnifiedMediaResult(val id: String, val title: String, val posterUrl: String, val isMovie: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onMediaClick: (String, Boolean) -> Unit,
    onNavigateToTrending: () -> Unit = {},
    viewModel: SearchViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val ptrState = rememberPullToRefreshState()

    LaunchedEffect(Unit) {
        if (uiState.trendingNow.isEmpty()) {
            viewModel.refresh()
        }
    }

    val searchResults = uiState.movieResults.map { UnifiedMediaResult(it.id, it.title, it.posterUrl, true) } +
                        uiState.seriesResults.map { UnifiedMediaResult(it.id, it.title, it.posterUrl, false) }

    PullToRefreshBox(
        isRefreshing = uiState.isSearching,
        onRefresh = { viewModel.refresh() },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 100.dp)
        ) {
        // Search Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .clip(RoundedCornerShape(percent = 50))
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it; viewModel.onQueryChange(it) },
                placeholder = { Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp) },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                singleLine = true
            )
            Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.surfaceVariant))
            Spacer(modifier = Modifier.width(12.dp))
            Icon(Icons.Default.FilterAlt, contentDescription = stringResource(R.string.filter), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp).clickable { /* Filter */ })
        }

        if (searchQuery.isEmpty()) {
            if (uiState.trendingNow.isEmpty()) {
                if (uiState.isSearching) {
                    com.example.ui.components.SearchScreenSkeleton()
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp, start = 24.dp, end = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
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
                                onClick = { viewModel.refresh() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text(stringResource(R.string.retry), color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            } else {
            // Popular Searches
            SectionTitleSharedWithAction(stringResource(R.string.popular_searches), stringResource(R.string.clear_all))
            
            @Composable
            fun SearchChip(text: String) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(percent = 50))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { searchQuery = text; viewModel.onQueryChange(text) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchChip("Game of Thrones")
                SearchChip("Stranger Things")
                SearchChip("The Last of Us")
                SearchChip("Interstellar")
                SearchChip("Money Heist")
                SearchChip("Breaking Bad")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Browse by Category
            Text(text = stringResource(R.string.browse_by_category),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            
            val cats = listOf(
                Pair("Movies", Icons.Outlined.LocalMovies),
                Pair("Series", Icons.Outlined.LiveTv),
                Pair(stringResource(R.string.anime), Icons.Outlined.TheaterComedy),
                Pair("Documentaries", Icons.Outlined.Description),
                Pair("Kids", Icons.Outlined.ChildCare)
            )
            
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(cats) { cat ->
                    Column(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .clickable { /* Select category */ },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(cat.second, contentDescription = cat.first, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(cat.first, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // Trending Now
            SectionTitleShared(stringResource(R.string.trending_now), onSeeAllClick = onNavigateToTrending)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (uiState.trendingNow.isEmpty()) {
                    items(4) { index ->
                        MediaCard(
                            title = stringResource(R.string.loading),
                            posterUrl = "",
                            rank = index + 1,
                            rating = 0.0,
                            year = "",
                            isMovie = true,
                            onClick = { }
                        )
                    }
                } else {
                    itemsIndexed(uiState.trendingNow) { index, movie ->
                        MediaCard(
                            title = movie.title,
                            posterUrl = movie.posterUrl,
                            rank = index + 1,
                            rating = movie.rating,
                            year = movie.year.toString(),
                            isMovie = true,
                            onClick = { onMediaClick(movie.id, true) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Recent Searches
            SectionTitleSharedWithAction(stringResource(R.string.recent_searches), stringResource(R.string.edit_action))
            
            val recents = listOf(
                Triple("Interstellar", "Movie • Sci-Fi", "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"),
                Triple("Money Heist", "Series • Crime", "https://image.tmdb.org/t/p/w500/reEMJA1uzscCbkpeRJeTT2bjqUp.jpg"),
                Triple("Breaking Bad", "Series • Drama", "https://image.tmdb.org/t/p/w500/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"),
                Triple("The Dark Knight", "Movie • Action", "https://image.tmdb.org/t/p/w500/qJ2tW6WMUDux911r6m7haRef0WH.jpg")
            )
            
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                recents.forEach { recent ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { searchQuery = recent.first; viewModel.onQueryChange(recent.first) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = recent.third,
                            contentDescription = recent.first,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(50.dp, 50.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(recent.first, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(recent.second, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp).clickable { /* Remove */ })
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            // Can't find what you're looking for?
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(80.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                         Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(stringResource(R.string.cant_find), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.try_different_keyword), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(percent = 50))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable { /* Explore */ }
                        ) {
                            Text(stringResource(R.string.explore_all_content), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            }
        } else {
            // Search Results
            if (uiState.isSearching) {
                com.example.ui.components.SearchScreenSkeleton()
            } else {
                Text(text = stringResource(R.string.results_for, searchQuery),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    searchResults.forEach { media ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onMediaClick(media.id, media.isMovie) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = media.posterUrl,
                                contentDescription = media.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(60.dp, 80.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(media.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(if (media.isMovie) stringResource(R.string.movie_singular) else stringResource(R.string.series_singular), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
fun SectionTitleSharedWithAction(title: String, actionText: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = actionText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { /* action */ }
        )
    }
}