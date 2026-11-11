package com.cratez.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object Status {
    const val PENDING = 0
    const val ANALYZING = 1
    const val DONE = 2
    const val ERROR = 3
}

@Entity(tableName = "tracks", indices = [Index(value = ["uri"], unique = true)])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val fileName: String,
    val folder: String = "",
    val mime: String = "",
    val sizeBytes: Long = 0,
    val durationMs: Long = 0,
    val bitrateKbps: Int = 0,
    val sampleRate: Int = 0,
    val lossless: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: String = "",
    val label: String = "",
    val isrc: String = "",
    val genre: String = "",
    val genreConfidence: Double = 0.0,
    val genreReasons: String = "",
    val genreAlternatives: String = "",
    val genreLocked: Boolean = false,
    val bpm: Double = 0.0,
    /** True once the user set the BPM by hand; re-analysis won't overwrite it. */
    @ColumnInfo(defaultValue = "0") val bpmLocked: Boolean = false,
    /** Other plausible tempos from analysis, "175.0:0.82|87.5:0.61". */
    @ColumnInfo(defaultValue = "") val bpmAlts: String = "",
    /** Compact sound fingerprint used to learn genres from the user's own library. */
    @ColumnInfo(defaultValue = "") val featVec: String = "",
    /** Genre backed by a reliable source (store tag, folder, or the user) — used as a reference. */
    @ColumnInfo(defaultValue = "0") val genreTrusted: Boolean = false,
    val musicalKey: Int = -1,
    val keySource: String = "",
    val energy: Int = 0,
    val loudnessDb: Double = 0.0,
    val qualityLabel: String = "",
    val cutoffHz: Int = 0,
    val qualitySuspicious: Boolean = false,
    val artPath: String? = null,
    val waveform: ByteArray? = null,
    val status: Int = Status.PENDING,
    val error: String? = null,
    val tagsWritten: Boolean = false,
    val tagNote: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val analyzedAt: Long = 0,
    val received: Boolean = false,
    val receivedFrom: String? = null,
) {
    val displayTitle get() = title.ifBlank { fileName.substringBeforeLast('.') }
    val displayArtist get() = artist.ifBlank { "Unknown artist" }

    override fun equals(other: Any?) = other is TrackEntity && other.id == id && other.status == status &&
        other.genre == genre && other.bpm == bpm && other.musicalKey == musicalKey && other.artPath == artPath &&
        other.title == title && other.artist == artist && other.energy == energy && other.tagsWritten == tagsWritten &&
        other.analyzedAt == analyzedAt && other.bpmLocked == bpmLocked && other.bpmAlts == bpmAlts
    override fun hashCode() = id.hashCode()
}

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey val code: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis(),
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<TrackEntity>

    @Query("SELECT * FROM tracks")
    suspend fun all(): List<TrackEntity>

    @Query("SELECT uri FROM tracks")
    suspend fun allUris(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(t: TrackEntity): Long

    @Update
    suspend fun update(t: TrackEntity)

    @Query("UPDATE tracks SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: Int)

    @Query("UPDATE tracks SET status = 0 WHERE status = 1")
    suspend fun resetInterrupted()

    @Query("UPDATE tracks SET status = 0 WHERE id IN (:ids)")
    suspend fun requeue(ids: List<Long>)

    @Query("SELECT * FROM tracks WHERE status = 0 ORDER BY addedAt ASC LIMIT 1")
    suspend fun nextPending(): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks WHERE status = 0 OR status = 1")
    fun observeQueued(): Flow<Int>

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()
}

@Dao
interface FriendDao {
    @Query("SELECT * FROM friends ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friends")
    suspend fun all(): List<FriendEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(f: FriendEntity)

    @Query("DELETE FROM friends WHERE code = :code")
    suspend fun delete(code: String)
}

@Database(entities = [TrackEntity::class, FriendEntity::class], version = 3, exportSchema = true)
abstract class CratezDb : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun friends(): FriendDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN bpmLocked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN bpmAlts TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN featVec TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tracks ADD COLUMN genreTrusted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tracks SET genreTrusted = 1 WHERE genreLocked = 1")
            }
        }

        fun create(ctx: Context) = Room.databaseBuilder(ctx, CratezDb::class.java, "cratez.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
