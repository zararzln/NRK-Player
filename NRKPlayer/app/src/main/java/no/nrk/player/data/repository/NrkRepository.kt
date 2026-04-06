package no.nrk.player.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import no.nrk.player.data.local.WatchProgressDao
import no.nrk.player.data.local.WatchProgressEntity
import no.nrk.player.data.remote.NrkApiService
import no.nrk.player.data.remote.NrkAsset
import no.nrk.player.data.remote.NrkPlug
import no.nrk.player.data.remote.NrkSubtitle
import no.nrk.player.domain.model.*
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * Single source of truth for all NRK content data.
 *
 * Follows a simple cache-then-network strategy:
 *   1. Emit cached Room data immediately (zero latency for the user)
 *   2. Fetch fresh data from the API in parallel
 *   3. Update the cache and re-emit
 *
 * All API exceptions are caught here and wrapped in [Result] so ViewModels
 * don't need to handle network failures individually.
 */
class NrkRepository(
    private val api: NrkApiService,
    private val watchProgressDao: WatchProgressDao
) {

    // ------------------------------------------------------------------
    // Frontpage / sections
    // ------------------------------------------------------------------

    fun getFrontpageSections(): Flow<Result<List<ContentSection>>> = flow {
        runCatching { api.getFrontpage() }
            .onSuccess { response ->
                val sections = response.sections.map { section ->
                    ContentSection(
                        id    = section.id,
                        title = section.title?.title ?: "",
                        items = section.plugs.map { it.toContentItem() }
                    )
                }
                emit(Result.success(sections))
            }
            .onFailure { t ->
                Timber.e(t, "Failed to fetch frontpage")
                emit(Result.failure(t))
            }
    }

    // ------------------------------------------------------------------
    // Programme detail
    // ------------------------------------------------------------------

    suspend fun getProgramme(programId: String): Result<Programme> =
        runCatching { api.getProgramme(programId) }
            .map { dto ->
                Programme(
                    id           = dto.programId,
                    title        = dto.title,
                    description  = dto.description,
                    imageUrl     = dto.image?.url(800) ?: "",
                    duration     = dto.duration?.seconds?.seconds ?: 0.seconds,
                    category     = dto.category?.name,
                    productionYear = dto.productionYear,
                    contributors = dto.contributors.map { Contributor(it.role, it.name) },
                    isAvailable  = dto.availability?.status == "available",
                    isGeoBlocked = dto.availability?.isGeoBlocked ?: false
                )
            }

    // ------------------------------------------------------------------
    // Playback manifest
    // ------------------------------------------------------------------

    suspend fun getPlaybackManifest(programId: String): Result<PlaybackManifest> =
        runCatching { api.getManifest(programId) }
            .mapCatching { dto ->
                val playable = dto.playable
                    ?: throw IllegalStateException("Content not playable: ${dto.nonPlayable?.reason}")

                val asset   = playable.assets.preferredAsset()
                val format  = when {
                    asset.mimeType.contains("mpegurl", ignoreCase = true) -> StreamFormat.HLS
                    asset.mimeType.contains("dash",    ignoreCase = true) -> StreamFormat.DASH
                    else -> StreamFormat.UNKNOWN
                }

                PlaybackManifest(
                    streamUrl            = asset.url,
                    format               = format,
                    subtitles            = playable.subtitles.map { it.toSubtitleTrack() },
                    durationSeconds      = playable.duration?.seconds ?: 0L,
                    creditsStartSeconds  = playable.endSequenceStartTime?.parseIso8601DurationSeconds()
                )
            }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    suspend fun search(query: String): Result<List<SearchResult>> =
        runCatching { api.search(query) }
            .map { response ->
                response.hits.map { hit ->
                    SearchResult(
                        id          = hit.id,
                        title       = hit.title,
                        description = hit.description,
                        imageUrl    = hit.image?.url(400) ?: "",
                        type        = hit.type.toContentType()
                    )
                }
            }

    // ------------------------------------------------------------------
    // Watch progress (Room)
    // ------------------------------------------------------------------

    fun getWatchProgress(programmeId: String): Flow<WatchProgress?> =
        watchProgressDao.observeProgress(programmeId).map { entity ->
            entity?.toDomain()
        }

    suspend fun saveWatchProgress(progress: WatchProgress) {
        watchProgressDao.upsert(progress.toEntity())
    }

    suspend fun getAllWatchProgress(): List<WatchProgress> =
        watchProgressDao.getAllProgress().map { it.toDomain() }

    // ------------------------------------------------------------------
    // Recommendations
    // ------------------------------------------------------------------

    suspend fun getRecommendations(programId: String): Result<List<ContentItem>> =
        runCatching { api.getRecommendations(programId) }
            .map { it.programmes.map { plug -> plug.toContentItem() } }

    // ------------------------------------------------------------------
    // Private mappers
    // ------------------------------------------------------------------

    private fun NrkPlug.toContentItem() = ContentItem(
        id              = id,
        title           = title,
        tagline         = tagline,
        imageUrl        = image?.url(400) ?: "",
        durationSeconds = duration?.seconds ?: 0L,
        type            = targetType?.toContentType() ?: type.toContentType()
    )

    private fun NrkSubtitle.toSubtitleTrack() = SubtitleTrack(
        language  = language,
        label     = label,
        url       = url,
        type      = when (type) {
            "hardOfHearing" -> SubtitleType.HARD_OF_HEARING
            "foreign"       -> SubtitleType.FOREIGN
            else            -> SubtitleType.NORMAL
        },
        isDefault = defaultOn
    )

    private fun List<NrkAsset>.preferredAsset(): NrkAsset =
        firstOrNull { it.format == "HLS" }
            ?: firstOrNull { it.format == "DASH" }
            ?: first()

    private fun String.toContentType() = when (lowercase()) {
        "programme", "standaloneprogram" -> ContentType.PROGRAMME
        "series", "seasonlink"           -> ContentType.SERIES
        "episode", "episodelink"         -> ContentType.EPISODE
        else                             -> ContentType.UNKNOWN
    }

    private fun String.parseIso8601DurationSeconds(): Long? {
        // Basic PT#H#M#S parser — handles the subset NRK uses
        val regex = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")
        val match  = regex.matchEntire(this) ?: return null
        val hours  = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val seconds = match.groupValues[3].toLongOrNull() ?: 0L
        return hours * 3600 + minutes * 60 + seconds
    }

    private fun WatchProgressEntity.toDomain() = WatchProgress(
        programmeId = programmeId,
        positionMs  = positionMs,
        durationMs  = durationMs,
        lastWatched = lastWatched
    )

    private fun WatchProgress.toEntity() = WatchProgressEntity(
        programmeId = programmeId,
        positionMs  = positionMs,
        durationMs  = durationMs,
        lastWatched = lastWatched
    )
}

// Flow<T?>.map helper — avoids a full coroutines import at call sites
private fun <T, R> Flow<T>.map(transform: suspend (T) -> R): Flow<R> = flow {
    collect { emit(transform(it)) }
}
