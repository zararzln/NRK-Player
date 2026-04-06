package no.nrk.player.core.player

import android.content.Context
import androidx.media3.common.*
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import no.nrk.player.domain.model.*
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Single-player controller for the NRK TV app.
 *
 * Responsibilities:
 *  - Configures ExoPlayer with an OkHttp data source (shared client → connection pooling)
 *  - Manages HLS and DASH playback with automatic quality adaptation
 *  - Drives subtitle display and on-device ML Kit translation (NO → target language)
 *  - Detects the credits start position and emits a "skip credits" prompt
 *  - Switches between normal and digest (highlight reel) playback modes
 *  - Persists watch progress to Room via [progressCallback] every [PROGRESS_SAVE_INTERVAL_MS]
 */
@UnstableApi
class NrkPlayerController(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val progressCallback: suspend (positionMs: Long, durationMs: Long) -> Unit
) {

    companion object {
        private const val PROGRESS_SAVE_INTERVAL_MS = 10_000L
        private const val CREDITS_PROMPT_LEAD_MS    = 3_000L  // show prompt N ms before credits
        private const val SUBTITLE_BUFFER_SIZE       = 8
    }

    // ------------------------------------------------------------------
    // ExoPlayer
    // ------------------------------------------------------------------

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(OkHttpDataSource.Factory(okHttpClient))
        )
        .build()
        .apply {
            playWhenReady = true
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }

    // ------------------------------------------------------------------
    // State flows
    // ------------------------------------------------------------------

    private val _playerState   = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _subtitleLines = MutableSharedFlow<SubtitleLine>(
        extraBufferCapacity = SUBTITLE_BUFFER_SIZE,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val subtitleLines: SharedFlow<SubtitleLine> = _subtitleLines.asSharedFlow()

    private val _translatedSubtitles = MutableSharedFlow<SubtitleLine>(
        extraBufferCapacity = SUBTITLE_BUFFER_SIZE,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val translatedSubtitles: SharedFlow<SubtitleLine> = _translatedSubtitles.asSharedFlow()

    private val _creditsDetected = MutableStateFlow<Long?>(null)
    val creditsStartMs: StateFlow<Long?> = _creditsDetected.asStateFlow()

    private val _digestMode = MutableStateFlow(false)
    val isDigestMode: StateFlow<Boolean> = _digestMode.asStateFlow()

    // ------------------------------------------------------------------
    // Private state
    // ------------------------------------------------------------------

    private var manifest: PlaybackManifest? = null
    private var digest: ProgrammeDigest?    = null
    private var digestSegmentIndex          = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var translationJob: Job?    = null
    private var progressJob: Job?       = null
    private var creditsJob: Job?        = null
    private var digestJob: Job?         = null

    private var mlKitTranslator: com.google.mlkit.nl.translate.Translator? = null

    // ------------------------------------------------------------------
    // Player listener
    // ------------------------------------------------------------------

    init {
        player.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(state: Int) {
                _playerState.value = when (state) {
                    Player.STATE_IDLE     -> PlayerState.Idle
                    Player.STATE_BUFFERING -> PlayerState.Buffering
                    Player.STATE_READY    -> PlayerState.Ready
                    Player.STATE_ENDED    -> PlayerState.Ended
                    else                  -> PlayerState.Idle
                }
                if (state == Player.STATE_READY) startPeriodicJobs()
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.e(error, "ExoPlayer error: ${error.errorCodeName}")
                _playerState.value = PlayerState.Error(error.errorCodeName)
            }

            override fun onCues(cueGroup: CueGroup) {
                val text = cueGroup.cues.joinToString("\n") { it.text?.toString() ?: "" }
                if (text.isNotBlank()) {
                    val line = SubtitleLine(text = text, positionMs = player.currentPosition)
                    _subtitleLines.tryEmit(line)
                    if (mlKitTranslator != null) translateSubtitle(line)
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // Playback control
    // ------------------------------------------------------------------

    fun load(newManifest: PlaybackManifest) {
        manifest = newManifest
        _creditsDetected.value = newManifest.creditsStartSeconds?.let { it * 1000L }

        val mediaItem = MediaItem.Builder()
            .setUri(newManifest.streamUrl)
            .setMimeType(
                if (newManifest.format == StreamFormat.HLS) MimeTypes.APPLICATION_M3U8
                else MimeTypes.APPLICATION_MPD
            )
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
    }

    fun play()  { player.play() }
    fun pause() { player.pause() }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceIn(0, player.duration))
    }

    fun skipCredits() {
        _creditsDetected.value?.let { seekTo(it) }
    }

    fun skipForward(ms: Long = 10_000) = seekTo(player.currentPosition + ms)
    fun skipBack(ms: Long = 10_000)    = seekTo(player.currentPosition - ms)

    // ------------------------------------------------------------------
    // Digest mode
    // ------------------------------------------------------------------

    fun startDigestMode(newDigest: ProgrammeDigest) {
        digest              = newDigest
        digestSegmentIndex  = 0
        _digestMode.value   = true
        playNextDigestSegment()
    }

    fun exitDigestMode() {
        _digestMode.value = false
        digestJob?.cancel()
        digest = null
    }

    private fun playNextDigestSegment() {
        val segments = digest?.segments ?: return
        if (digestSegmentIndex >= segments.size) {
            _digestMode.value = false
            return
        }
        val segment = segments[digestSegmentIndex++]
        seekTo(segment.startMs)
        play()

        digestJob?.cancel()
        digestJob = scope.launch {
            delay(segment.endMs - segment.startMs)
            playNextDigestSegment()
        }
    }

    // ------------------------------------------------------------------
    // ML Kit subtitle translation
    // ------------------------------------------------------------------

    fun enableTranslation(targetLanguage: String) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.NORWEGIAN)
            .setTargetLanguage(targetLanguage)
            .build()

        mlKitTranslator?.close()
        mlKitTranslator = Translation.getClient(options).also { translator ->
            // Download the language model if not already cached (background, ~15 MB)
            translator.downloadModelIfNeeded()
                .addOnFailureListener { e -> Timber.e(e, "Translation model download failed") }
        }
    }

    fun disableTranslation() {
        mlKitTranslator?.close()
        mlKitTranslator = null
    }

    private fun translateSubtitle(line: SubtitleLine) {
        translationJob?.cancel()
        translationJob = scope.launch {
            mlKitTranslator?.translate(line.text)
                ?.addOnSuccessListener { translated ->
                    _translatedSubtitles.tryEmit(line.copy(text = translated))
                }
                ?.addOnFailureListener { e ->
                    Timber.w(e, "Subtitle translation failed, showing original")
                    _translatedSubtitles.tryEmit(line)
                }
        }
    }

    // ------------------------------------------------------------------
    // Background jobs
    // ------------------------------------------------------------------

    private fun startPeriodicJobs() {
        startProgressSaving()
        startCreditsMonitor()
    }

    private fun startProgressSaving() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                if (player.isPlaying) {
                    progressCallback(player.currentPosition, player.duration)
                }
            }
        }
    }

    private fun startCreditsMonitor() {
        val creditsMs = _creditsDetected.value ?: return
        creditsJob?.cancel()
        creditsJob = scope.launch {
            while (isActive) {
                delay(500)
                val remaining = creditsMs - player.currentPosition
                if (remaining in 0..CREDITS_PROMPT_LEAD_MS) {
                    // The UI observes creditsStartMs and shows the "Skip" button
                    break
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    fun release() {
        scope.cancel()
        mlKitTranslator?.close()
        player.release()
    }

    // ------------------------------------------------------------------
    // Supporting types
    // ------------------------------------------------------------------

    sealed class PlayerState {
        object Idle      : PlayerState()
        object Buffering : PlayerState()
        object Ready     : PlayerState()
        object Ended     : PlayerState()
        data class Error(val code: String) : PlayerState()
    }

    data class SubtitleLine(val text: String, val positionMs: Long)
}
