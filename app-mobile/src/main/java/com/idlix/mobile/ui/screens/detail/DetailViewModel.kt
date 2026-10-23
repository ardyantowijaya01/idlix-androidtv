package com.idlix.mobile.ui.screens.detail

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.idlix.data.local.WatchHistoryStore
import com.idlix.data.model.EpisodeItem
import com.idlix.data.model.MovieDetail
import com.idlix.data.model.ResolvedPlayback
import com.idlix.data.model.Season
import com.idlix.data.model.SeriesDetail
import com.idlix.data.model.SubdlSubtitle
import com.idlix.data.model.SubtitleTrack
import com.idlix.data.repository.IdlixRepository
import com.idlix.data.repository.SubdlRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NextEpisodeInfo(
    val episode: EpisodeItem,
    val seasonNumber: Int,
    val seriesTitle: String,
    val seriesSlug: String,
    val posterUrl: String?,
    val backdropUrl: String?
)

data class DetailUiState(
    val isLoadingDetail: Boolean = true,
    val detailError: String? = null,
    val movie: MovieDetail? = null,
    val series: SeriesDetail? = null,
    val selectedSeason: Season? = null,
    val episodes: List<EpisodeItem> = emptyList(),
    val selectedEpisode: EpisodeItem? = null,

    // Playback state
    val isStreamLoading: Boolean = false,
    val countdownSeconds: Int = 0,
    val streamError: String? = null,
    val resolvedStream: ResolvedPlayback? = null,
    val initialPositionMs: Long = 0L,

    // Subtitles
    val selectedSubtitle: SubtitleTrack? = null,
    val isSubtitleEnabled: Boolean = true,
    val isSubtitlePickerVisible: Boolean = false,
    val subdlSubtitles: List<SubdlSubtitle> = emptyList(),
    val isSearchingSubdl: Boolean = false,

    // Next Episode Auto-Prompt
    val nextEpisode: NextEpisodeInfo? = null,
    val isNextEpisodeDismissed: Boolean = false
)

class DetailViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository = IdlixRepository()
    private val historyStore = WatchHistoryStore.getInstance(application.applicationContext)

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var currentContentId: String = ""
    private var currentContentType: String = ""
    private var currentContentTitle: String = ""
    private var currentPosterUrl: String? = null
    private var currentBackdropUrl: String? = null
    private var currentSlug: String? = null
    private var currentSeasonNumber: Int? = null
    private var currentEpisodeNumber: Int? = null

    fun loadDetail(contentType: String, slug: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingDetail = true,
                detailError = null
            )

            try {
                if (contentType == "tv_series" || contentType == "series") {
                    val series = repository.getSeriesDetail(slug)
                    if (series != null) {
                        val allSeasons = series.seasons
                        val defaultSeason = series.defaultSeason
                        val firstSeason = allSeasons.firstOrNull() ?: Season(
                            seasonNumber = defaultSeason?.seasonNumber ?: 1,
                            name = defaultSeason?.name ?: "Season 1"
                        )
                        val initialEpisodes = defaultSeason?.episodes ?: emptyList()
                        val firstEp = initialEpisodes.firstOrNull()

                        _uiState.value = _uiState.value.copy(
                            isLoadingDetail = false,
                            series = series,
                            movie = null,
                            selectedSeason = firstSeason,
                            episodes = initialEpisodes,
                            selectedEpisode = firstEp
                        )

                        // If we have an initial episode, start playback
                        if (firstEp != null) {
                            playEpisode(series, firstSeason.seasonNumber, firstEp)
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoadingDetail = false,
                            detailError = "Failed to load TV series details"
                        )
                    }
                } else {
                    val movie = repository.getMovieDetail(slug)
                    if (movie != null) {
                        _uiState.value = _uiState.value.copy(
                            isLoadingDetail = false,
                            movie = movie,
                            series = null,
                            episodes = emptyList()
                        )
                        playMovie(movie)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoadingDetail = false,
                            detailError = "Failed to load movie details"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("DetailViewModel", "Error loading detail: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoadingDetail = false,
                    detailError = e.localizedMessage ?: "Network error loading details"
                )
            }
        }
    }

    fun selectSeason(season: Season) {
        val series = _uiState.value.series ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(selectedSeason = season)
            try {
                val epRes = repository.getEpisodeDetail(series.slug, season.seasonNumber, 1)
                val newEpisodes = epRes?.season?.episodes ?: emptyList()
                if (newEpisodes.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(episodes = newEpisodes)
                }
            } catch (e: Exception) {
                Log.w("DetailViewModel", "Failed to fetch episodes for season ${season.seasonNumber}: ${e.message}")
            }
        }
    }

    fun selectEpisode(episode: EpisodeItem) {
        val series = _uiState.value.series ?: return
        val seasonNum = _uiState.value.selectedSeason?.seasonNumber ?: 1
        _uiState.value = _uiState.value.copy(selectedEpisode = episode)
        playEpisode(series, seasonNum, episode)
    }

    private fun playMovie(movie: MovieDetail) {
        startStreamPlayback(
            contentType = "movie",
            contentId = movie.id,
            title = movie.title,
            posterUrl = movie.fullPosterUrl,
            backdropUrl = movie.fullBackdropUrl,
            slug = movie.slug,
            seasonNumber = null,
            episodeNumber = null
        )
    }

    private fun playEpisode(series: SeriesDetail, seasonNumber: Int, episode: EpisodeItem) {
        val episodeTitle = "${series.title} - S${seasonNumber}E${episode.episodeNumber}: ${episode.name}"
        startStreamPlayback(
            contentType = "episode",
            contentId = episode.id,
            title = episodeTitle,
            posterUrl = episode.fullStillUrl.ifBlank { series.fullPosterUrl },
            backdropUrl = series.fullBackdropUrl,
            slug = series.slug,
            seasonNumber = seasonNumber,
            episodeNumber = episode.episodeNumber
        )
    }

    fun retryPlayback() {
        if (currentContentId.isNotBlank()) {
            startStreamPlayback(
                contentType = currentContentType,
                contentId = currentContentId,
                title = currentContentTitle,
                posterUrl = currentPosterUrl,
                backdropUrl = currentBackdropUrl,
                slug = currentSlug,
                seasonNumber = currentSeasonNumber,
                episodeNumber = currentEpisodeNumber
            )
        }
    }

    private fun startStreamPlayback(
        contentType: String,
        contentId: String,
        title: String,
        posterUrl: String? = null,
        backdropUrl: String? = null,
        slug: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ) {
        currentContentId = contentId
        currentContentType = contentType
        currentContentTitle = title
        currentPosterUrl = posterUrl
        currentBackdropUrl = backdropUrl
        currentSlug = slug
        currentSeasonNumber = seasonNumber
        currentEpisodeNumber = episodeNumber

        val savedPos = historyStore.getSavedPosition(contentId)

        historyStore.recordStartPlayback(
            contentId = contentId,
            contentType = contentType,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            slug = slug,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber
        )

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isStreamLoading = true,
                countdownSeconds = 0,
                streamError = null,
                resolvedStream = null,
                initialPositionMs = savedPos,
                nextEpisode = null,
                isNextEpisodeDismissed = false
            )

            // Asynchronously resolve next episode for Series
            if (contentType == "episode" && !slug.isNullOrBlank()) {
                resolveNextEpisode(
                    seriesSlug = slug,
                    currentEpisodeId = contentId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    fallbackTitle = title,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl
                )
            }

            val result = repository.resolveStream(
                contentType = contentType,
                contentId = contentId,
                contentTitle = title,
                onCountdown = { sec ->
                    _uiState.value = _uiState.value.copy(countdownSeconds = sec)
                }
            )

            result.onSuccess { resolved ->
                val defaultSub = resolved.subtitles.firstOrNull { it.lang == "id" }
                    ?: resolved.subtitles.firstOrNull()

                _uiState.value = _uiState.value.copy(
                    isStreamLoading = false,
                    resolvedStream = resolved,
                    selectedSubtitle = defaultSub,
                    isSubtitleEnabled = defaultSub != null
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isStreamLoading = false,
                    streamError = err.localizedMessage ?: "Failed to play stream"
                )
            }
        }
    }

    private fun resolveNextEpisode(
        seriesSlug: String,
        currentEpisodeId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        fallbackTitle: String,
        posterUrl: String?,
        backdropUrl: String?
    ) {
        viewModelScope.launch {
            try {
                val series = repository.getSeriesDetail(seriesSlug) ?: return@launch
                val defaultSeason = series.defaultSeason
                val seriesTitle = series.title.ifBlank { fallbackTitle.substringBefore(" -") }
                val allSeasons = series.seasons.ifEmpty {
                    if (defaultSeason != null) {
                        listOf(Season(seasonNumber = defaultSeason.seasonNumber, name = defaultSeason.name))
                    } else emptyList()
                }

                var currentSeasonNum = seasonNumber
                val currentEpNum = episodeNumber
                var seasonEpisodes: List<EpisodeItem> = emptyList()

                if (currentSeasonNum != null) {
                    if (defaultSeason?.seasonNumber == currentSeasonNum && !defaultSeason.episodes.isNullOrEmpty()) {
                        seasonEpisodes = defaultSeason.episodes
                    } else {
                        val epDetail = repository.getEpisodeDetail(seriesSlug, currentSeasonNum, currentEpNum ?: 1)
                        seasonEpisodes = epDetail?.season?.episodes ?: emptyList()
                    }
                } else {
                    if (defaultSeason?.episodes?.any { it.id == currentEpisodeId } == true) {
                        currentSeasonNum = defaultSeason.seasonNumber
                        seasonEpisodes = defaultSeason.episodes
                    } else {
                        for (s in allSeasons) {
                            val epDetail = repository.getEpisodeDetail(seriesSlug, s.seasonNumber, 1)
                            val eps = epDetail?.season?.episodes ?: emptyList()
                            if (eps.any { it.id == currentEpisodeId }) {
                                currentSeasonNum = s.seasonNumber
                                seasonEpisodes = eps
                                break
                            }
                        }
                    }
                }

                if (seasonEpisodes.isEmpty()) return@launch

                val currentIndex = seasonEpisodes.indexOfFirst {
                    it.id == currentEpisodeId || (currentEpNum != null && it.episodeNumber == currentEpNum)
                }

                if (currentIndex != -1 && currentIndex + 1 < seasonEpisodes.size) {
                    val nextEp = seasonEpisodes[currentIndex + 1]
                    _uiState.value = _uiState.value.copy(
                        nextEpisode = NextEpisodeInfo(
                            episode = nextEp,
                            seasonNumber = currentSeasonNum ?: 1,
                            seriesTitle = seriesTitle,
                            seriesSlug = seriesSlug,
                            posterUrl = nextEp.fullStillUrl.ifBlank { posterUrl },
                            backdropUrl = backdropUrl ?: series.fullBackdropUrl
                        )
                    )
                } else if (currentSeasonNum != null) {
                    val currentSeasonIndex = allSeasons.indexOfFirst { it.seasonNumber == currentSeasonNum }
                    val nextSeason = if (currentSeasonIndex != -1 && currentSeasonIndex + 1 < allSeasons.size) {
                        allSeasons[currentSeasonIndex + 1]
                    } else {
                        allSeasons.firstOrNull { it.seasonNumber == currentSeasonNum + 1 }
                    }

                    if (nextSeason != null) {
                        val nextSeasonDetail = repository.getEpisodeDetail(seriesSlug, nextSeason.seasonNumber, 1)
                        val nextEp = nextSeasonDetail?.episode
                            ?: nextSeasonDetail?.season?.episodes?.firstOrNull()
                        if (nextEp != null) {
                            _uiState.value = _uiState.value.copy(
                                nextEpisode = NextEpisodeInfo(
                                    episode = nextEp,
                                    seasonNumber = nextSeason.seasonNumber,
                                    seriesTitle = seriesTitle,
                                    seriesSlug = seriesSlug,
                                    posterUrl = nextEp.fullStillUrl.ifBlank { posterUrl },
                                    backdropUrl = backdropUrl ?: series.fullBackdropUrl
                                )
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(nextEpisode = null)
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(nextEpisode = null)
                    }
                }
            } catch (e: Exception) {
                Log.w("DetailViewModel", "Failed to resolve next episode: ${e.message}")
            }
        }
    }

    fun dismissNextEpisode() {
        _uiState.value = _uiState.value.copy(isNextEpisodeDismissed = true)
    }

    fun playNextEpisode() {
        val next = _uiState.value.nextEpisode ?: return
        val series = _uiState.value.series ?: return
        _uiState.value = _uiState.value.copy(selectedEpisode = next.episode)
        playEpisode(series, next.seasonNumber, next.episode)
    }

    fun openSubtitlePicker() {
        _uiState.value = _uiState.value.copy(isSubtitlePickerVisible = true)
        if (_uiState.value.subdlSubtitles.isEmpty()) {
            searchSubdlSubtitles()
        }
    }

    fun closeSubtitlePicker() {
        _uiState.value = _uiState.value.copy(isSubtitlePickerVisible = false)
    }

    fun searchSubdlSubtitles() {
        val title = currentContentTitle
        if (title.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearchingSubdl = true)
            val results = SubdlRepository.searchAllSubtitles(
                title = title,
                type = if (currentContentType == "episode") "tv" else "movie",
                season = currentSeasonNumber,
                episode = currentEpisodeNumber
            )
            _uiState.value = _uiState.value.copy(
                isSearchingSubdl = false,
                subdlSubtitles = results
            )
        }
    }

    fun selectSubtitleTrack(track: SubtitleTrack?) {
        _uiState.value = _uiState.value.copy(
            selectedSubtitle = track,
            isSubtitleEnabled = track != null,
            isSubtitlePickerVisible = false
        )
    }

    fun selectSubdlSubtitle(
        sub: SubdlSubtitle,
        onSubtitleReady: (SubtitleTrack) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearchingSubdl = true)
            val downloaded = SubdlRepository.downloadSubtitle(
                sub = sub,
                context = getApplication<Application>().applicationContext
            )
            _uiState.value = _uiState.value.copy(isSearchingSubdl = false)

            if (downloaded != null) {
                val currentStream = _uiState.value.resolvedStream
                if (currentStream != null) {
                    val updatedSubs = currentStream.subtitles.toMutableList()
                    updatedSubs.removeAll { it.path == downloaded.path }
                    updatedSubs.add(0, downloaded)
                    _uiState.value = _uiState.value.copy(
                        resolvedStream = currentStream.copy(subtitles = updatedSubs),
                        selectedSubtitle = downloaded,
                        isSubtitleEnabled = true,
                        isSubtitlePickerVisible = false
                    )
                }
                onSubtitleReady(downloaded)
            }
        }
    }

    fun toggleSubtitle() {
        val current = _uiState.value.isSubtitleEnabled
        _uiState.value = _uiState.value.copy(isSubtitleEnabled = !current)
    }

    fun saveProgress(currentPosMs: Long, durationMs: Long) {
        if (currentContentId.isNotBlank() && durationMs > 0) {
            historyStore.updateProgress(currentContentId, currentPosMs, durationMs)
        }
    }
}
