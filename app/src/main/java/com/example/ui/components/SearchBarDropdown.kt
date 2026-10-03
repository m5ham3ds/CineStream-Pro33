package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ui.ViewModelFactory
import com.example.ui.screens.search.SearchViewModel
import kotlinx.coroutines.delay

@Composable
fun ExpandableSearchBar(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    viewModel: SearchViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            focusRequester.requestFocus()
        }
    }
    
    // We want the search bar to overlay the UI when it shows results.
    // So it should be in a Box.
    
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        if (!isExpanded) {
            Icon(
                Icons.Default.Search, 
                contentDescription = stringResource(R.string.cd_search), 
                tint = MaterialTheme.colorScheme.onBackground, 
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onExpandedChange(true) }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                // Search Input Field
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = uiState.query,
                        onValueChange = { viewModel.onQueryChange(it) },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        decorationBox = { innerTextField ->
                            if (uiState.query.isEmpty()) {
                                Text(stringResource(R.string.search_dots), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                            }
                            innerTextField()
                        }
                    )
                    IconButton(
                        onClick = { 
                            if (uiState.query.isNotEmpty()) {
                                viewModel.onQueryChange("")
                            } else {
                                onExpandedChange(false) 
                            }
                        }, 
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Dropdown Results
                if (uiState.query.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    
                    if (uiState.isSearching) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (uiState.movieResults.isEmpty() && uiState.seriesResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp) // Limit height so it doesn't take up the whole screen
                        ) {
                            val allResults = (uiState.movieResults.map { it to true } + uiState.seriesResults.map { it to false }).sortedByDescending { it.first.hashCode() } // just a random mix
                            
                            items(allResults) { (item, isMovie) ->
                                // Cast Media (Movie/Series) to a common shape
                                val id = if (isMovie) (item as com.example.domain.models.Movie).id else (item as com.example.domain.models.Series).id
                                val title = if (isMovie) (item as com.example.domain.models.Movie).title else (item as com.example.domain.models.Series).title
                                val poster = if (isMovie) (item as com.example.domain.models.Movie).posterUrl else (item as com.example.domain.models.Series).posterUrl
                                val year = if (isMovie) (item as com.example.domain.models.Movie).year.toString() else (item as com.example.domain.models.Series).year.toString()
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isMovie) onMovieClick(id) else onSeriesClick(id)
                                            onExpandedChange(false)
                                            viewModel.onQueryChange("")
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = poster,
                                        contentDescription = title,
                                        modifier = Modifier
                                            .width(40.dp)
                                            .height(60.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                                        Text("$year • ${if(isMovie) stringResource(R.string.movie_singular) else stringResource(R.string.series_singular)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}