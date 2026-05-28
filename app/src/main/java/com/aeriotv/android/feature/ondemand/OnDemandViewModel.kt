package com.aeriotv.android.feature.ondemand

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.network.DispatcharrAuthBroker
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrVODEpisode
import com.aeriotv.android.core.network.DispatcharrVODMovie
import com.aeriotv.android.core.network.DispatcharrVODProviderInfo
import com.aeriotv.android.core.network.DispatcharrVODSeries
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
    private val dispatcharrAuth: DispatcharrAuthBroker,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val error: String? = null,
        val movies: List<DispatcharrVODMovie> = emptyList(),
        val totalCount: Int = 0,
        val searchQuery: String = "",
        val unsupportedSource: Boolean = false,
        // Series state — separate from movies so each sub-tab's search /
        // loading flow is independent. Both share the same playlist context.
        val isLoadingSeries: Boolean = false,
        val seriesError: String? = null,
        val series: List<DispatcharrVODSeries> = emptyList(),
        val seriesTotalCount: Int = 0,
        val seriesSearchQuery: String = "",
        // Episode cache: each series gets a lazy-loaded slot. The detail
        // screen reads its slot and shows a spinner while episodesLoadingFor
        // contains the seriesId.
        val episodesBySeries: Map<Int, List<DispatcharrVODEpisode>> = emptyMap(),
        val episodesLoadingFor: Set<Int> = emptySet(),
        val episodesErrorFor: Map<Int, String> = emptyMap(),
        // Provider-info cache. Movie + series detail pages call into this for
        // backdrop / cast / director / country / trailer enrichment. Keyed by
        // Dispatcharr's int `id` (NOT uuid) — matches the URL shape of
        // `/api/vod/{movies,series}/<id>/provider-info/`.
        val movieProviderInfo: Map<Int, DispatcharrVODProviderInfo> = emptyMap(),
        val seriesProviderInfo: Map<Int, DispatcharrVODProviderInfo> = emptyMap(),
        val movieProviderInfoLoading: Set<Int> = emptySet(),
        val seriesProviderInfoLoading: Set<Int> = emptySet(),
    ) {
        val visible: List<DispatcharrVODMovie> get() {
            val q = searchQuery.trim()
            if (q.isEmpty()) return movies
            return movies.filter { it.displayName.contains(q, ignoreCase = true) }
        }
        val visibleSeries: List<DispatcharrVODSeries> get() {
            val q = seriesSearchQuery.trim()
            if (q.isEmpty()) return series
            return series.filter { it.displayName.contains(q, ignoreCase = true) }
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
        refreshSeries()
    }

    fun setSearchQuery(value: String) {
        _state.update { it.copy(searchQuery = value) }
    }

    fun setSeriesSearchQuery(value: String) {
        _state.update { it.copy(seriesSearchQuery = value) }
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
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODMoviesFirstPage(playlist.urlString, key)
                }
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
                    // Audit task #42: walk the `next` cursor in the background
                    // so the user gets the full library appended progressively
                    // instead of just the first 100. First-page paint already
                    // landed above so the grid is interactive; subsequent
                    // pages append as they arrive. De-dup on uuid in case two
                    // pages share a row.
                    var nextUrl = page.next
                    while (nextUrl != null) {
                        val captured = nextUrl
                        val nextResult = runCatching {
                            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                                dispatcharrClient.getVODMoviesPage(captured, key)
                            }
                        }
                        nextUrl = nextResult.getOrNull()?.next
                        nextResult.getOrNull()?.let { p ->
                            _state.update { st ->
                                val merged = st.movies.toMutableList()
                                val seen = merged.mapTo(HashSet()) { it.uuid }
                                p.results.forEach { m ->
                                    if (m.uuid !in seen) {
                                        merged += m
                                        seen += m.uuid
                                    }
                                }
                                st.copy(movies = merged, totalCount = p.count)
                            }
                        }
                        nextResult.exceptionOrNull()?.let { t ->
                            Log.w(TAG, "VOD movies next-page fetch failed; stopping", t)
                            nextUrl = null
                        }
                    }
                },
                onFailure = { t ->
                    Log.w(TAG, "getVODMovies failed", t)
                    _state.update { it.copy(isLoading = false, error = t.message ?: t::class.simpleName) }
                },
            )
        }
    }

    fun refreshSeries() {
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist()
            val sourceType = playlist?.sourceType?.let { SourceType.entries.firstOrNull { st -> st.name == it } }
            val isDispatcharr = sourceType == SourceType.DispatcharrApiKey ||
                    sourceType == SourceType.DispatcharrUserPass
            if (playlist == null || !isDispatcharr || playlist.apiKey.isNullOrBlank()) {
                _state.update { it.copy(unsupportedSource = true, series = emptyList(), isLoadingSeries = false, seriesError = null) }
                return@launch
            }
            _state.update { it.copy(isLoadingSeries = true, seriesError = null) }
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getVODSeriesFirstPage(playlist.urlString, key)
                }
            }.fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            isLoadingSeries = false,
                            series = page.results,
                            seriesTotalCount = page.count,
                            seriesError = null,
                        )
                    }
                    // Audit task #42: same next-cursor walk as movies above.
                    // Series use `id` as the de-dup key. Stops on first error.
                    var nextUrl = page.next
                    while (nextUrl != null) {
                        val captured = nextUrl
                        val nextResult = runCatching {
                            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                                dispatcharrClient.getVODSeriesPage(captured, key)
                            }
                        }
                        nextUrl = nextResult.getOrNull()?.next
                        nextResult.getOrNull()?.let { p ->
                            _state.update { st ->
                                val merged = st.series.toMutableList()
                                val seen = merged.mapTo(HashSet()) { it.id }
                                p.results.forEach { s ->
                                    if (s.id !in seen) {
                                        merged += s
                                        seen += s.id
                                    }
                                }
                                st.copy(series = merged, seriesTotalCount = p.count)
                            }
                        }
                        nextResult.exceptionOrNull()?.let { t ->
                            Log.w(TAG, "VOD series next-page fetch failed; stopping", t)
                            nextUrl = null
                        }
                    }
                },
                onFailure = { t ->
                    Log.w(TAG, "getVODSeries failed", t)
                    _state.update { it.copy(isLoadingSeries = false, seriesError = t.message ?: t::class.simpleName) }
                },
            )
        }
    }

    fun seriesById(id: Int): DispatcharrVODSeries? =
        _state.value.series.firstOrNull { it.id == id }

    fun movieById(id: Int): DispatcharrVODMovie? =
        _state.value.movies.firstOrNull { it.id == id }

    fun movieByUuid(uuid: String): DispatcharrVODMovie? =
        _state.value.movies.firstOrNull { it.uuid == uuid }

    /**
     * Lazy-fetch provider-info for a movie — backdrop, cast, director,
     * country, trailer URL. Idempotent within session: a second call for the
     * same id no-ops if either the data is already cached or a fetch is
     * already in flight. Mirrors iOS VODService.enrichMovie which runs this
     * the first time the detail page opens.
     */
    fun loadMovieProviderInfo(movieId: Int) {
        val current = _state.value
        if (current.movieProviderInfo.containsKey(movieId) ||
            current.movieProviderInfoLoading.contains(movieId)) return
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist() ?: return@launch
            if (playlist.apiKey.isNullOrBlank()) return@launch
            _state.update { it.copy(movieProviderInfoLoading = it.movieProviderInfoLoading + movieId) }
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getMovieProviderInfo(playlist.urlString, key, movieId)
                }
            }.fold(
                onSuccess = { info ->
                    _state.update { st ->
                        st.copy(
                            movieProviderInfo = st.movieProviderInfo + (movieId to info),
                            movieProviderInfoLoading = st.movieProviderInfoLoading - movieId,
                        )
                    }
                },
                onFailure = { t ->
                    Log.w(TAG, "getMovieProviderInfo($movieId) failed", t)
                    _state.update { it.copy(movieProviderInfoLoading = it.movieProviderInfoLoading - movieId) }
                },
            )
        }
    }

    /** Series equivalent of [loadMovieProviderInfo]. Same throttling +
     *  idempotency rules. */
    fun loadSeriesProviderInfo(seriesId: Int) {
        val current = _state.value
        if (current.seriesProviderInfo.containsKey(seriesId) ||
            current.seriesProviderInfoLoading.contains(seriesId)) return
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist() ?: return@launch
            if (playlist.apiKey.isNullOrBlank()) return@launch
            _state.update { it.copy(seriesProviderInfoLoading = it.seriesProviderInfoLoading + seriesId) }
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getSeriesProviderInfo(playlist.urlString, key, seriesId)
                }
            }.fold(
                onSuccess = { info ->
                    _state.update { st ->
                        st.copy(
                            seriesProviderInfo = st.seriesProviderInfo + (seriesId to info),
                            seriesProviderInfoLoading = st.seriesProviderInfoLoading - seriesId,
                        )
                    }
                },
                onFailure = { t ->
                    Log.w(TAG, "getSeriesProviderInfo($seriesId) failed", t)
                    _state.update { it.copy(seriesProviderInfoLoading = it.seriesProviderInfoLoading - seriesId) }
                },
            )
        }
    }

    /** Lazy-load (idempotent within session) episodes for a series. */
    fun loadEpisodes(seriesId: Int) {
        val current = _state.value
        if (current.episodesBySeries.containsKey(seriesId) ||
            current.episodesLoadingFor.contains(seriesId)) {
            return
        }
        viewModelScope.launch {
            val playlist = playlistRepository.activePlaylist()
                ?: return@launch
            if (playlist.apiKey.isNullOrBlank()) return@launch
            _state.update { it.copy(episodesLoadingFor = it.episodesLoadingFor + seriesId) }
            runCatching {
                dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                    dispatcharrClient.getSeriesEpisodesFirstPage(playlist.urlString, key, seriesId)
                }
            }.fold(
                onSuccess = { page ->
                    _state.update { st ->
                        st.copy(
                            episodesBySeries = st.episodesBySeries + (seriesId to page.results),
                            episodesLoadingFor = st.episodesLoadingFor - seriesId,
                            episodesErrorFor = st.episodesErrorFor - seriesId,
                        )
                    }
                },
                onFailure = { t ->
                    Log.w(TAG, "getSeriesEpisodes($seriesId) failed", t)
                    _state.update { st ->
                        st.copy(
                            episodesLoadingFor = st.episodesLoadingFor - seriesId,
                            episodesErrorFor = st.episodesErrorFor +
                                    (seriesId to (t.message ?: t::class.simpleName.orEmpty())),
                        )
                    }
                },
            )
        }
    }

    /** Same pattern as resolveMovieUrl but for an episode proxy URL. */
    suspend fun resolveEpisodeUrl(episodeUuid: String, streamId: Int?): Result<String> {
        val playlist = playlistRepository.activePlaylist()
            ?: return Result.failure(IllegalStateException("No playlist loaded."))
        if (playlist.apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Active source is not Dispatcharr-backed."))
        }
        return runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.resolveVODEpisodeStreamUrl(
                    baseUrl = playlist.urlString,
                    apiKey = key,
                    episodeUuid = episodeUuid,
                    streamId = streamId,
                )
            }
        }
    }

    /**
     * Resolves the redirect-bound proxy URL to the session-bound playback URL
     * for a movie. Returns a Result so the caller can surface failures via
     * Toast / inline error rather than landing on a half-loaded player.
     */
    suspend fun resolveMovieUrl(movieUuid: String): Result<String> {
        val playlist = playlistRepository.activePlaylist()
            ?: return Result.failure(IllegalStateException("No playlist loaded."))
        if (playlist.apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Active source is not Dispatcharr-backed."))
        }
        val movie = _state.value.movies.firstOrNull { it.uuid == movieUuid }
        return runCatching {
            dispatcharrAuth.withApiKeyRetry(playlist.id) { key ->
                dispatcharrClient.resolveVODStreamUrl(
                    baseUrl = playlist.urlString,
                    apiKey = key,
                    movieUuid = movieUuid,
                    streamId = movie?.firstStreamId,
                )
            }
        }
    }

    private companion object {
        const val TAG = "OnDemandViewModel"
    }
}
