package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.di.AppContainer
import com.example.ui.screens.home.HomeViewModel
import com.example.ui.screens.anime.AnimeViewModel

class ViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            return HomeViewModel(AppContainer.mediaRepository) as T
        }
        if (modelClass.isAssignableFrom(com.example.ui.screens.details.MovieDetailsViewModel::class.java)) {
            return com.example.ui.screens.details.MovieDetailsViewModel(AppContainer.mediaRepository) as T
        }
        if (modelClass.isAssignableFrom(com.example.ui.screens.details.SeriesDetailsViewModel::class.java)) {
            return com.example.ui.screens.details.SeriesDetailsViewModel(AppContainer.mediaRepository) as T
        }
        if (modelClass.isAssignableFrom(com.example.ui.screens.movies.MoviesViewModel::class.java)) {
            return com.example.ui.screens.movies.MoviesViewModel(AppContainer.mediaRepository) as T
        }
        if (modelClass.isAssignableFrom(com.example.ui.screens.series.SeriesViewModel::class.java)) {
            return com.example.ui.screens.series.SeriesViewModel(AppContainer.mediaRepository) as T
        }
        if (modelClass.isAssignableFrom(com.example.ui.screens.search.SearchViewModel::class.java)) {
            return com.example.ui.screens.search.SearchViewModel(AppContainer.mediaRepository) as T
        }
        if (modelClass.isAssignableFrom(com.example.ui.screens.details.PersonDetailsViewModel::class.java)) {
            return com.example.ui.screens.details.PersonDetailsViewModel(AppContainer.mediaRepository) as T
        }
        if (modelClass.isAssignableFrom(AnimeViewModel::class.java)) {
            return AnimeViewModel(AppContainer.mediaRepository) as T
        }
        if (modelClass.isAssignableFrom(com.example.ui.screens.social.SocialViewModel::class.java)) {
            return com.example.ui.screens.social.SocialViewModel() as T
        }
                if (modelClass.isAssignableFrom(com.example.ui.screens.auth.AuthViewModel::class.java)) {
            return com.example.ui.screens.auth.AuthViewModel(com.example.di.AppContainer.application) as T
        }
        if (modelClass.isAssignableFrom(com.example.ui.screens.settings.NotificationPreferencesViewModel::class.java)) {
            return com.example.ui.screens.settings.NotificationPreferencesViewModel(com.example.di.AppContainer.application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}