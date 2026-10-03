package com.example.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ViewModelFactory
import com.example.ui.components.MediaCard
import com.example.ui.components.CustomTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PopularScreen(
    onItemClick: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val allStr = stringResource(R.string.all)
    val moviesStr = stringResource(R.string.movies)
    val seriesStr = stringResource(R.string.series)
    val animeStr = stringResource(R.string.anime)
    val tabsList = listOf(allStr, moviesStr, seriesStr, animeStr)
    val pagerState = rememberPagerState(pageCount = { tabsList.size })
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = tabsList[pagerState.currentPage]
    
    
    val getItemsForTab = { tab: String -> 
        val raw = when (tab) {
            moviesStr -> uiState.popularMovies.map { it to true }
            seriesStr -> uiState.popularSeries.map { it to false }
            animeStr -> uiState.popularAnime.map { it to false }
            else -> (uiState.popularMovies.map { it to true } + uiState.popularSeries.map { it to false } + uiState.popularAnime.map { it to false })
        }
        raw.distinctBy { (media, isMovie) ->
            val id = if (isMovie) (media as com.example.domain.models.Movie).id else (media as com.example.domain.models.Series).id
            "${if(isMovie) "m" else "s"}_$id"
        }
    }
    
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CustomTopBar(
            titleFirst = stringResource(R.string.popular_title_first), titleSecond = stringResource(R.string.popular_title_second), subtitle = stringResource(R.string.popular_subtitle), onBack = onBack, showFilter = true
        )
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            tabsList.forEach { tab ->
                val isSelected = selectedTab == tab
                Box(
                    modifier = Modifier.weight(1f).clickable { coroutineScope.launch { pagerState.animateScrollToPage(tabsList.indexOf(tab)) } }
                        .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .border(if (isSelected) 1.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tab, color = if (isSelected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        val ptrState = rememberPullToRefreshState()
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val currentTab = tabsList[page]
            val items = getItemsForTab(currentTab)
            PullToRefreshBox(isRefreshing = uiState.isLoading, onRefresh = { viewModel.loadData(forceRefresh = true) }, state = ptrState, modifier = Modifier.fillMaxSize()) {
                if (uiState.isLoading && items.isEmpty()) {
                    com.example.ui.components.GridScreenSkeleton()
                } else {
                    LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(items, key = { _, (media, isMovie) ->
                            val id = if (isMovie) (media as com.example.domain.models.Movie).id else (media as com.example.domain.models.Series).id
                            "${if(isMovie) "m" else "s"}_$id"
                        }) { index, (media, isMovie) ->
                            val title = if (isMovie) (media as com.example.domain.models.Movie).title else (media as com.example.domain.models.Series).title
                            val poster = if (isMovie) (media as com.example.domain.models.Movie).posterUrl else (media as com.example.domain.models.Series).posterUrl
                            val id = if (isMovie) (media as com.example.domain.models.Movie).id else (media as com.example.domain.models.Series).id
                            MediaCard(
                                title = title,
                                posterUrl = poster,
                                isMovie = isMovie,
                                rank = index + 1,
                                mediaId = id,
                                onClick = { onItemClick(id, isMovie) }
                            )
                        }
                    }
                }
            }
        }
    }
}