package com.example.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.models.PersonDetails
import com.example.domain.repository.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonDetailsUiState(
    val isLoading: Boolean = false,
    val person: PersonDetails? = null,
    val error: String? = null
)

class PersonDetailsViewModel(
    private val repository: MediaRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(PersonDetailsUiState())
    val uiState: StateFlow<PersonDetailsUiState> = _uiState.asStateFlow()

    fun loadPerson(personId: String) {
        if (_uiState.value.person?.id == personId && !_uiState.value.isLoading) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val person = repository.getPersonDetails(personId)
                if (person != null) {
                    _uiState.update { it.copy(person = person, isLoading = false) }
                } else {
                    val app = try { com.example.di.AppContainer.application } catch (_: Exception) { null }
                    val errorMsg = app?.getString(com.example.R.string.person_not_found) ?: "Person not found"
                    _uiState.update { it.copy(error = errorMsg, isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }
}