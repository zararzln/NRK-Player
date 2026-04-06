package no.nrk.player.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// ---------------------------------------------------------------------------
// Clean domain models — decoupled from API DTOs
// ---------------------------------------------------------------------------

data class Programme(
    val id: String,
    val title: String,
    val description: String?,
    val imageUrl: String,
    val duration: Duration,
    val category: String?,
    val productionYear: Int?,
    val contributors: List<Contributor>,
    val isAvailable: Boolean,
    val isGeoBlocked: Boolean
)

data class Contributor(
    val role: String,
    val name: String
)

data class ContentSection(
    val id: String,
    val title: String,
    val items: List<ContentItem>
)

data class ContentItem(
    val id: String,
    val title: String,
    val tagline: String?,
    val imageUrl: String,
    val durationSeconds: Long,
    val type: ContentType
)

enum class ContentType { PROGRAMME, SERIES, EPISODE, UNKNOWN }

data class PlaybackManifest(
    val streamUrl: String,
    val format: StreamFormat,
    val subtitles: List<SubtitleTrack>,
    val durationSeconds: Long,
    val creditsStartSeconds: Long?
)

data class SubtitleTrack(
    val language: String,
    val label: String,
    val url: String,
    val type: SubtitleType,
    val isDefault: Boolean
)

enum class SubtitleType { NORMAL, HARD_OF_HEARING, FOREIGN }

enum class StreamFormat { HLS, DASH, UNKNOWN }

data class SearchResult(
    val id: String,
    val title: String,
    val description: String?,
    val imageUrl: String,
    val type: ContentType
)

// ---------------------------------------------------------------------------
// Digest model — represents a computed 60-second highlights reel
// ---------------------------------------------------------------------------

data class DigestSegment(
    val startMs: Long,
    val endMs: Long,
    val sceneScore: Float,      // 0–1, higher = more visually distinct scene change
    val subtitleSnippet: String // associated subtitle text for this segment
)

data class ProgrammeDigest(
    val programmeId: String,
    val segments: List<DigestSegment>,
    val totalDurationMs: Long,
    val computedAt: Long = System.currentTimeMillis()
)

// ---------------------------------------------------------------------------
// Watch history
// ---------------------------------------------------------------------------

data class WatchProgress(
    val programmeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatched: Long = System.currentTimeMillis()
) {
    val progressFraction: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val isFinished: Boolean get() = progressFraction > 0.92f
}
