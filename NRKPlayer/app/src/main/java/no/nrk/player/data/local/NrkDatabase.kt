package no.nrk.player.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------
// Room database
// ---------------------------------------------------------------------------

@Database(
    entities = [WatchProgressEntity::class, DigestCacheEntity::class],
    version  = 1,
    exportSchema = true
)
abstract class NrkDatabase : RoomDatabase() {
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun digestCacheDao(): DigestCacheDao
}

// ---------------------------------------------------------------------------
// Watch progress
// ---------------------------------------------------------------------------

@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey val programmeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatched: Long
)

@Dao
interface WatchProgressDao {

    @Query("SELECT * FROM watch_progress WHERE programmeId = :id")
    fun observeProgress(id: String): Flow<WatchProgressEntity?>

    @Query("SELECT * FROM watch_progress ORDER BY lastWatched DESC")
    suspend fun getAllProgress(): List<WatchProgressEntity>

    @Upsert
    suspend fun upsert(entity: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE programmeId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM watch_progress")
    suspend fun deleteAll()
}

// ---------------------------------------------------------------------------
// Digest cache — avoids re-computing expensive scene analysis
// ---------------------------------------------------------------------------

@Entity(tableName = "digest_cache")
data class DigestCacheEntity(
    @PrimaryKey val programmeId: String,
    val segmentsJson: String,   // serialised List<DigestSegment>
    val totalDurationMs: Long,
    val computedAt: Long
)

@Dao
interface DigestCacheDao {

    @Query("SELECT * FROM digest_cache WHERE programmeId = :id")
    suspend fun getDigest(id: String): DigestCacheEntity?

    @Upsert
    suspend fun upsert(entity: DigestCacheEntity)

    @Query("DELETE FROM digest_cache WHERE computedAt < :cutoff")
    suspend fun evictOlderThan(cutoff: Long)
}
