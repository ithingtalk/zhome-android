package com.ithingtalk.zhome.data.local.db

import android.content.Context
import java.io.File
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/* ==================== Entities ==================== */

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val mac: String,
    val sn: String = "",
    val name: String = "",
    val cfg: String = "",
    val online: String = "",
    val pending: String = "" // "add" / "del" / ""
)

@Entity(
    tableName = "files",
    primaryKeys = ["owner", "remotePath"],
)
data class FileEntity(
    val remotePath: String,
    val size: Long = 0,
    val date: Long = 0,
    val isDir: Boolean = false,
    val owner: String = "" // user email – separate private vs shared
)

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceMac: String = "",
    val remotePath: String,
    val localPath: String = "",
    val type: Int = 0,   // 0=upload, 1=download
    val status: Int = 0,  // 0=queued, 1=running, 2=success, 3=error, 4=stopped
    val progressPercent: Float = 0f,
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String = "",
    val updatedAt: Long = 0L,
)

@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val remotePath: String,
    val displayName: String,
    val fileType: String, // "image", "video", "audio", "document"
    val deviceMac: String,
    val accessedAt: Long
)

/* ==================== DAOs ==================== */

@Dao
interface DeviceDao {
    /** Visible rows only (hides soft-deleted records with pending='del'). */
    @Query("SELECT * FROM devices WHERE pending != 'del' ORDER BY name")
    suspend fun getAll(): List<DeviceEntity>

    /** Visible rows only (hides soft-deleted records). */
    @Query("SELECT * FROM devices WHERE pending != 'del' ORDER BY name")
    fun observeAll(): Flow<List<DeviceEntity>>

    /** Raw row set including soft-deleted rows; used by cloud sync / backlog push. */
    @Query("SELECT * FROM devices ORDER BY name")
    suspend fun getAllRaw(): List<DeviceEntity>

    /** Returns every row whose pending column matches the given value. */
    @Query("SELECT * FROM devices WHERE pending = :pending ORDER BY name")
    suspend fun getByPending(pending: String): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE mac = :mac LIMIT 1")
    suspend fun getByMac(mac: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE mac = :mac LIMIT 1")
    fun observeByMac(mac: String): Flow<DeviceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    @Query("DELETE FROM devices WHERE mac = :mac")
    suspend fun delete(mac: String)

    @Query("DELETE FROM devices")
    suspend fun deleteAll()

    @Query("UPDATE devices SET name = :name WHERE mac = :mac")
    suspend fun updateName(mac: String, name: String)

    @Query("UPDATE devices SET pending = :pending WHERE mac = :mac")
    suspend fun updatePending(mac: String, pending: String)
}

@Dao
interface FileDao {
    @Query("SELECT * FROM files WHERE owner = :owner ORDER BY isDir DESC, remotePath")
    suspend fun getAll(owner: String): List<FileEntity>

    @Query("SELECT * FROM files WHERE owner = :owner ORDER BY isDir DESC, remotePath")
    fun observeAll(owner: String): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE owner = :owner AND remotePath LIKE '%' || :query || '%' AND isDir = 0 ORDER BY remotePath LIMIT 50")
    suspend fun searchByName(owner: String, query: String): List<FileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: FileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<FileEntity>)

    @Query("DELETE FROM files WHERE owner = :owner AND remotePath = :path")
    suspend fun delete(owner: String, path: String)

    @Query("DELETE FROM files WHERE owner = :owner AND remotePath IN (:paths)")
    suspend fun deleteMany(owner: String, paths: List<String>)

    /** Deletes [root] and every row whose path is under [root] (prefix + "/"). */
    @Query("DELETE FROM files WHERE owner = :owner AND (remotePath = :root OR remotePath LIKE :childPattern)")
    suspend fun deleteSubtree(owner: String, root: String, childPattern: String)

    @Query("SELECT * FROM files WHERE owner = :owner AND remotePath = :path LIMIT 1")
    suspend fun getByPath(owner: String, path: String): FileEntity?

    @Query("DELETE FROM files WHERE owner = :owner")
    suspend fun deleteAllForOwner(owner: String)

    @Query("SELECT COUNT(*) FROM files WHERE owner = :owner")
    suspend fun count(owner: String): Int

    @Query("SELECT COUNT(*) FROM files WHERE owner = :owner")
    fun observeCount(owner: String): Flow<Int>
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers WHERE type = :type ORDER BY id")
    suspend fun getByType(type: Int): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE type = :type ORDER BY id DESC")
    fun observeByType(type: Int): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE status = :status ORDER BY id ASC")
    fun observeByStatus(status: Int): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TransferEntity?

    @Insert
    suspend fun insert(t: TransferEntity): Long

    @Update
    suspend fun update(t: TransferEntity)

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transfers WHERE type = :type")
    suspend fun deleteByType(type: Int)

    @Query("DELETE FROM transfers")
    suspend fun deleteAll()

    @Query("UPDATE transfers SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: Int)

    @Query("UPDATE transfers SET status = :status, progressPercent = :progress, transferredBytes = :transferred, totalBytes = :total, error = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgress(
        id: Long,
        status: Int,
        progress: Float,
        transferred: Long,
        total: Long,
        error: String,
        updatedAt: Long,
    )

    @Query("SELECT COUNT(*) FROM transfers")
    suspend fun countAll(): Int
}

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files WHERE deviceMac = :mac ORDER BY accessedAt DESC LIMIT :limit")
    fun observeRecent(mac: String, limit: Int = 20): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentFileEntity)

    @Query("DELETE FROM recent_files WHERE remotePath = :path AND deviceMac = :mac")
    suspend fun deleteByPath(path: String, mac: String)

    @Query("DELETE FROM recent_files WHERE id NOT IN (SELECT id FROM recent_files ORDER BY accessedAt DESC LIMIT :keep)")
    suspend fun trimOld(keep: Int = 100)
}

/* ==================== Database ==================== */

@Database(
    entities = [DeviceEntity::class, FileEntity::class, TransferEntity::class, RecentFileEntity::class],
    version = 5,
    exportSchema = false
)
abstract class ZhomeDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun fileDao(): FileDao
    abstract fun transferDao(): TransferDao
    abstract fun recentFileDao(): RecentFileDao

    companion object {
        /**
         * Opens the main Room database for [username] under
         * `<filesDir>/users/<userKey>/zhome.db` (unified qtApp layout).
         *
         * One-time migrates the legacy default-location `zhome.db` file (which
         * Room stores under `<databases>/`) into the anonymous user bucket if a
         * new-layout file does not yet exist for the target user.
         */
        fun create(
            ctx: Context,
            username: String,
        ): ZhomeDatabase {
            val target = com.ithingtalk.zhome.data.local.AppPaths.userMainDb(ctx, username)
            target.parentFile?.mkdirs()

            if (!target.exists()) {
                val legacy = ctx.getDatabasePath("zhome.db")
                if (legacy != null && legacy.isFile) {
                    runCatching {
                        legacy.copyTo(target, overwrite = false)
                        copyIfExists(File(legacy.absolutePath + "-wal"), File(target.absolutePath + "-wal"))
                        copyIfExists(File(legacy.absolutePath + "-shm"), File(target.absolutePath + "-shm"))
                        legacy.delete()
                        File(legacy.absolutePath + "-wal").takeIf { it.exists() }?.delete()
                        File(legacy.absolutePath + "-shm").takeIf { it.exists() }?.delete()
                    }
                }
            }

            return Room.databaseBuilder(ctx, ZhomeDatabase::class.java, target.absolutePath)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }

        private fun copyIfExists(src: File, dst: File) {
            if (src.isFile && !dst.exists()) {
                runCatching { src.copyTo(dst, overwrite = false) }
            }
        }
    }
}
