package no.nrk.player.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import no.nrk.player.core.player.DigestEngine
import no.nrk.player.core.player.NrkPlayerController
import no.nrk.player.data.repository.NrkRepository
import no.nrk.player.domain.model.*
import timber.log.Timber

/**
 * ViewModel for the player screen.
 *
 * Manages:
 *  - Manifest loading and player initialisation
 *  - Normal vs digest playback mode
 *  - Credits-skip UX (show prompt N seconds before credits start)
 *  - Live subtitle display with optional ML Kit translation
 *  - Watch progress persistence via [NrkRepository]
 *  - UI state as a single [PlayerUiState] snapshot
 */
@UnstableApi
class PlayerViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: NrkRepository,
    private val playerController: NrkPlayerController,
    private val digestEngine: DigestEngine
) : ViewModel() {

    private val programmeId: String = checkNotNull(savedStateHandle["programmeId"])

    // ------------------------------------------------------------------
    // UI state
    // ------------------------------------------------------------------

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Expose the ExoPlayer instance directly — Compose reads it via AndroidView
    val player get() = playerController.player

    // ------------------------------------------------------------------
    // Init
    // ------------------------------------------------------------------

    init {
        loadManifest()
        observePlayerState()
        observeSubtitles()
        observeCredits()
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private fun loadManifest() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            repository.getPlaybackManifest(programmeId)
                .onSuccess { manifest ->
                    playerController.load(manifest)
                    _uiState.update { it.copy(
                        isLoading = false,
                        manifest  = manifest
                    )}
                    precomputeDigest(manifest)
                }
                .onFailure { error ->
                    Timber.e(error, "Manifest load failed")
                    _uiState.update { it.copy(
                        isLoading = false,
                        error     = "Could not load video: ${error.message}"
                    )}
                }
        }
    }

    private fun precomputeDigest(manifest: PlaybackManifest) {
        viewModelScope.launch {
            runCatching {
                digestEngine.computeDigest(
                    programmeId = programmeId,
                    streamUrl   = manifest.streamUrl,
                    durationMs  = manifest.durationSeconds * 1000L
                )
            }.onSuccess { digest ->
                _uiState.update { it.copy(digest = digest) }
                Timber.d("Digest ready: ${digest.segments.size} segments")
            }.onFailure { e ->
                Timber.w(e, "Digest computation failed — digest mode unavailable")
            }
        }
    }

    // ------------------------------------------------------------------
    // Player state observation
    // ------------------------------------------------------------------

    private fun observePlayerState() {
        playerController.playerState
            .onEach { state ->
                _uiState.update { it.copy(playerState = state) }
            }
            .launchIn(viewModelScope)

        playerController.isDigestMode
            .onEach { isDigest ->
                _uiState.update { it.copy(isDigestMode = isDigest) }
            }
            .launchIn(viewModelScope)
    }

    // ------------------------------------------------------------------
    // Subtitle observation
    // ------------------------------------------------------------------

    private fun observeSubtitles() {
        playerController.subtitleLines
            .onEach { line ->
                _uiState.update { it.copy(currentSubtitle = line.text) }
            }
            .launchIn(viewModelScope)

        playerController.translatedSubtitles
            .onEach { line ->
                _uiState.update { it.copy(translatedSubtitle = line.text) }
            }
            .launchIn(viewModelScope)
    }

    // ------------------------------------------------------------------
    // Credits detection
    // ------------------------------------------------------------------

    private fun observeCredits() {
        playerController.creditsStartMs
            .filterNotNull()
            .onEach { creditsMs ->
                // Poll position — show prompt when we're within 5 seconds of credits
                viewModelScope.launch {
                    while (true) {
                        delay(1_000)
                        val pos = player.currentPosition
                        val showPrompt = pos >= creditsMs - 5_000 && pos < creditsMs + 30_000
                        _uiState.update { it.copy(showSkipCredits = showPrompt) }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    // ------------------------------------------------------------------
    // User actions
    // ------------------------------------------------------------------

    fun togglePlayPause() {
        if (player.isPlaying) playerController.pause() else playerController.play()
    }

    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)

    fun skipForward() = playerController.skipForward()
    fun skipBack()    = playerController.skipBack()
    fun skipCredits() = playerController.skipCredits()

    fun startDigestMode() {
        val digest = _uiState.value.digest ?: return
        playerController.startDigestMode(digest)
    }

    fun exitDigestMode() = playerController.exitDigestMode()

    fun enableTranslation(targetLanguage: String) {
        playerController.enableTranslation(targetLanguage)
        _uiState.update { it.copy(translationEnabled = true, translationLanguage = targetLanguage) }
    }

    fun disableTranslation() {
        playerController.disableTranslation()
        _uiState.update { it.copy(translationEnabled = false, translatedSubtitle = null) }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    // ------------------------------------------------------------------
    // Progress saving
    // ------------------------------------------------------------------

    fun saveProgress() {
        viewModelScope.launch {
            repository.saveWatchProgress(
                WatchProgress(
                    programmeId = programmeId,
                    positionMs  = player.currentPosition,
                    durationMs  = player.duration.coerceAtLeast(0)
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    override fun onCleared() {
        saveProgress()
        playerController.release()
    }
}

// ---------------------------------------------------------------------------
// UI state snapshot
// ---------------------------------------------------------------------------

data class PlayerUiState(
    val isLoading: Boolean                          = true,
    val manifest: PlaybackManifest?                 = null,
    val playerState: NrkPlayerController.PlayerState = NrkPlayerController.PlayerState.Idle,
    val currentSubtitle: String?                    = null,
    val translatedSubtitle: String?                 = null,
    val translationEnabled: Boolean                 = false,
    val translationLanguage: String?                = null,
    val showSkipCredits: Boolean                    = false,
    val digest: ProgrammeDigest?                    = null,
    val isDigestMode: Boolean                       = false,
    val error: String?                              = null
)
