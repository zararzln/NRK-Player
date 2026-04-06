package no.nrk.player.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import no.nrk.player.data.repository.NrkRepository
import no.nrk.player.domain.model.ContentItem
import no.nrk.player.domain.model.ContentSection
import no.nrk.player.domain.model.WatchProgress

class HomeViewModel(private val repository: NrkRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadFrontpage()
        loadContinueWatching()
    }

    private fun loadFrontpage() {
        repository.getFrontpageSections()
            .onEach { result ->
                result
                    .onSuccess { sections ->
                        _uiState.update { it.copy(sections = sections, isLoading = false) }
                    }
                    .onFailure { e ->
                        _uiState.update { it.copy(error = e.message, isLoading = false) }
                    }
            }
            .launchIn(viewModelScope)
    }

    private fun loadContinueWatching() {
        viewModelScope.launch {
            val progressList = repository.getAllWatchProgress()
                .filter { !it.isFinished }
                .sortedByDescending { it.lastWatched }
                .take(10)

            // We only have IDs from the progress table — look up titles from the API.
            // In a full implementation these would be cached in Room.
            // For now we emit what we have and enrich lazily.
            _uiState.update { it.copy(continueWatching = progressList.map { p ->
                ContentItem(
                    id              = p.programmeId,
                    title           = p.programmeId,   // replaced when detail loads
                    tagline         = null,
                    imageUrl        = "",
                    durationSeconds = p.durationMs / 1000,
                    type            = no.nrk.player.domain.model.ContentType.PROGRAMME
                ) to p
            }) }
        }
    }
}

data class HomeUiState(
    val isLoading: Boolean = true,
    val sections: List<ContentSection> = emptyList(),
    val continueWatching: List<Pair<ContentItem, WatchProgress>> = emptyList(),
    val error: String? = null
)
