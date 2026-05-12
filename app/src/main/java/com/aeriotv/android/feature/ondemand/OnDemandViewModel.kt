package com.aeriotv.android.feature.ondemand

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrVODMovie
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * On Demand tab state. Phase 10a is Movies first-page only; Phase 10b adds
 * Series + the detail / episode picker; Phase 10c wires WatchProgress + the
 * "Continue Watching" CTA.
 *
 * Pagination via Dispatcharr's `next` cursor is wired but not consumed by the
 * UI yet — the first 100 movies render immediately, full library walk lands
 * with the "Load more" affordance.
 */
@HiltViewModel
class OnDemandViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val dispatcharrClient: DispatcharrClient,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val movies: List<DispatcharrVODMovie> = emptyList(),
        val totalCount: Int = 0,
        val searchQuery: String = "",
        val unsupportedSource: Boolean = false,
    ) {
        val visible: List<DispatcharrVODMovie> get() {
            val q = searchQuery.trim()
            if (q.isEmpty()) return movies
            return movies.filter { it.displayName.contains(q, ignoreCase = true) }
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun setSearchQuery(value: String) {
        _state.update { it.copy(searchQuery = value) }
    }

    fun refresh() {
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist()
            val sourceType = playlist?.sourceType?.let { SourceType.entries.firstOrNull { st -> st.name == it } }
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                    sourceType == SourceType.DispatcharrUserPass
            if (playlist == null || !isDispatcharr || playlist.apiKey.isNullOrBlank()) {
                _state.update { it.copy(unsupportedSource = true, movies = emptyList(), isLoading = false, error = null) }
                return@launch
            }
            _state.update { it.copy(isLoading = true, error = null, unsupportedSource = false) }
            runCatching {
                dispatcharrClient.getVODMoviesFirstPage(playlist.urlString, playlist.apiKey!!)
            }.fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            movies = page.results,
                            totalCount = page.count,
                            error = null,
                        )
                    }
                },
                onFailure = { t ->
                    Log.w(TAG, "getVODMovies failed", t)
                    _state.update { it.copy(isLoading = false, error = t.message ?: t::class.simpleName) }
                },
            )
        }
    }

    private companion object {
        const val TAG = "OnDemandViewModel"
    }
}
