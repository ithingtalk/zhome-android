package com.ithingtalk.zhome.data.repository

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.data.normalizeToMyFilesPath
import com.ithingtalk.zhome.data.local.db.FileDao
import com.ithingtalk.zhome.data.local.db.FileEntity
import com.ithingtalk.zhome.data.local.db.RecentFileDao
import com.ithingtalk.zhome.data.local.db.RecentFileEntity
import com.ithingtalk.zhome.data.local.prefs.LocalPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class CategoryCounts(
    val allFiles: Int,
    val images: Int,
    val videos: Int,
    val audio: Int,
    val documents: Int,
    val transfers: Int,
    val recycleBin: Int = 0,
    val shared: Int = 0,
)

class FileRepository(
    private val dao: FileDao,
    private val recentDao: RecentFileDao,
    private val prefs: LocalPrefs
) {
    private val tag = "FileRepository"

    fun observeAll(owner: String): Flow<List<FileEntity>> = dao.observeAll(owner)
    fun observeCount(owner: String): Flow<Int> = dao.observeCount(owner)

    suspend fun categoryCounts(owner: String): CategoryCounts = withContext(Dispatchers.Default) {
        val all = dao.getAll(owner)
        fun norm(p: String) = normalizeToMyFilesPath(p)
        fun underMyFiles(n: String): Boolean =
            n == Constants.TAG_MYFILES || n.startsWith(Constants.TAG_MYFILES + "/")
        fun fileInCategory(n: String, categoryRoot: String): Boolean =
            n.startsWith(categoryRoot + "/") || n == categoryRoot
        val files = all.filter { !it.isDir }
        CategoryCounts(
            allFiles = files.count { underMyFiles(norm(it.remotePath)) },
            images = files.count { fileInCategory(norm(it.remotePath), Constants.TAG_IMAGE) },
            videos = files.count { fileInCategory(norm(it.remotePath), Constants.TAG_VIDEO) },
            audio = files.count { fileInCategory(norm(it.remotePath), Constants.TAG_AUDIO) },
            documents = files.count { fileInCategory(norm(it.remotePath), Constants.TAG_DOC) },
            transfers = 0,
        )
    }

    suspend fun getFiles(owner: String): List<FileEntity> = dao.getAll(owner)

    suspend fun getFilesInDir(owner: String, dir: String): List<FileEntity> {
        val all = dao.getAll(owner)
        return filesInDirFromAll(all, owner, dir)
    }

    fun observeFilesInDir(owner: String, dir: String): Flow<List<FileEntity>> =
        dao.observeAll(owner).map { all -> filesInDirFromAll(all, owner, dir) }

    fun filesInDirFromAll(all: List<FileEntity>, owner: String, dir: String): List<FileEntity> {
        if (all.isEmpty()) return emptyList()

        when (dir) {
            "__trash__" -> return filterTrashTopLevelOnly(all).sortedBy { it.remotePath }
            "__shared__" -> return all
        }

        val requestDirRaw = (if (dir.isEmpty()) Constants.TAG_MYFILES else dir).trimEnd('/')
        val requestDir = normalizeToMyFilesPath(requestDirRaw)
        val prefix = "$requestDir/"

        val pairs = all.map { e -> e to normalizeToMyFilesPath(e.remotePath) }

        if (requestDir == Constants.TAG_MYFILES) {
            val seen = mutableSetOf<String>()
            val out = ArrayList<FileEntity>()
            val rootPrefix = "${Constants.TAG_MYFILES}/"
            for ((e, n) in pairs) {
                if (!n.startsWith(rootPrefix)) continue
                val rest = n.removePrefix(rootPrefix)
                if (rest.isEmpty() || rest.contains("/")) continue
                if (seen.add(n)) out.add(e)
            }
            return out.sortedBy { it.remotePath }
        }

        val seenKeys = mutableSetOf<String>()
        val out = ArrayList<FileEntity>()
        for ((e, n) in pairs) {
            if (!n.startsWith(prefix)) continue
            val rest = n.removePrefix(prefix)
            if (rest.isEmpty()) continue

            if (!rest.contains("/")) {
                if (seenKeys.add(n)) out.add(e)
                continue
            }

            val childKey = "$requestDir/${rest.substringBefore("/")}"
            if (!seenKeys.add(childKey)) continue

            val exact = all.find { normalizeToMyFilesPath(it.remotePath) == childKey }
            out.add(
                exact ?: FileEntity(
                    remotePath = childKey,
                    size = 0,
                    date = e.date,
                    isDir = true,
                    owner = owner
                )
            )
        }
        return out.sortedBy { it.remotePath }
    }

    /**
     * Recycle bin: only rows that are not under another deleted path (Qt-style top level).
     */
    private fun filterTrashTopLevelOnly(entries: List<FileEntity>): List<FileEntity> {
        if (entries.isEmpty()) return emptyList()
        val paths = entries.map { it.remotePath.trimEnd('/') }.toSet()
        return entries.filter { e ->
            val p = e.remotePath.trimEnd('/')
            !paths.any { other -> other != p && p.startsWith(other + "/") }
        }
    }

    suspend fun searchFiles(owner: String, query: String): List<FileEntity> {
        if (query.isBlank()) return emptyList()
        return dao.searchByName(owner, query)
    }

    suspend fun addFile(file: FileEntity) = dao.upsert(file)
    suspend fun addFiles(files: List<FileEntity>) = dao.upsertAll(files)
    suspend fun deleteFile(owner: String, path: String) = dao.delete(owner, path)
    suspend fun deleteFiles(owner: String, paths: List<String>) = dao.deleteMany(owner, paths)

    /** Removes [root] and all descendants from the given owner namespace. */
    suspend fun deleteSubtree(owner: String, root: String) {
        val r = root.trimEnd('/')
        dao.deleteSubtree(owner, r, "$r/%")
    }

    /** All rows under any of [roots] (inclusive), for subtree move / recover. */
    suspend fun collectPathsUnderRoots(owner: String, roots: List<String>): List<FileEntity> {
        if (roots.isEmpty()) return emptyList()
        val normalized = roots.map { it.trimEnd('/') }
        val all = dao.getAll(owner)
        return all.filter { e ->
            val p = e.remotePath.trimEnd('/')
            normalized.any { root -> p == root || p.startsWith(root + "/") }
        }
    }
    suspend fun getFileByPath(owner: String, path: String): FileEntity? = dao.getByPath(owner, path)
    suspend fun deleteAllForOwner(owner: String) = dao.deleteAllForOwner(owner)
    suspend fun count(owner: String) = dao.count(owner)

    suspend fun getDisplayType(): Int = prefs.getDisplayType()
    suspend fun setDisplayType(type: Int) = prefs.setDisplayType(type)

    /* ---- Recent files ---- */

    fun observeRecentFiles(mac: String, limit: Int = 20): Flow<List<RecentFileEntity>> =
        recentDao.observeRecent(mac, limit)

    suspend fun recordAccess(remotePath: String, displayName: String, fileType: String, deviceMac: String) {
        recentDao.deleteByPath(remotePath, deviceMac)
        recentDao.upsert(
            RecentFileEntity(
                remotePath = remotePath,
                displayName = displayName,
                fileType = fileType,
                deviceMac = deviceMac,
                accessedAt = System.currentTimeMillis()
            )
        )
        recentDao.trimOld()
    }

    /* ---- NAS file.db import ---- */

    suspend fun importFromNasSqliteFile(absolutePath: String, owner: String) = withContext(Dispatchers.IO) {
        val db = SQLiteDatabase.openDatabase(absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val list = mutableListOf<FileEntity>()
            db.rawQuery(
                "SELECT remotePath,size,createdTime,directory FROM files_table WHERE status = ?",
                arrayOf("normal")
            ).use { cursor ->
                val idxPath = cursor.getColumnIndexOrThrow("remotePath")
                val idxSize = cursor.getColumnIndexOrThrow("size")
                val idxTime = cursor.getColumnIndexOrThrow("createdTime")
                val idxDir = cursor.getColumnIndexOrThrow("directory")
                while (cursor.moveToNext()) {
                    val isDir = readBoolColumn(cursor, idxDir)
                    list.add(
                        FileEntity(
                            remotePath = cursor.getString(idxPath),
                            size = cursor.getLong(idxSize),
                            date = cursor.getLong(idxTime),
                            isDir = isDir,
                            owner = owner
                        )
                    )
                }
            }
            dao.deleteAllForOwner(owner)
            if (list.isNotEmpty()) dao.upsertAll(list)
            Log.d(tag, "Imported ${list.size} file rows for $owner")
        } catch (e: Exception) {
            Log.e(tag, "importFromNasSqliteFile failed", e)
            dao.deleteAllForOwner(owner)
            throw e
        } finally {
            db.close()
        }
    }

    /**
     * Shared DB import (shared.db) — firmware variants differ slightly.
     *
     * Qt reads `files_table` too, but some NAS builds omit `status` or use different column names.
     */
    suspend fun importFromNasSharedSqliteFile(absolutePath: String, owner: String) = withContext(Dispatchers.IO) {
        val db = SQLiteDatabase.openDatabase(absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val cols = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info(files_table)", null).use { c ->
                val idxName = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    val n = if (idxName >= 0) c.getString(idxName) else c.getString(1)
                    if (!n.isNullOrBlank()) cols.add(n)
                }
            }

            fun pick(vararg candidates: String): String? = candidates.firstOrNull { cols.contains(it) }

            val colPath = pick("remotePath", "remote_path", "filepath", "filePath", "path") ?: "remotePath"
            val colSize = pick("size", "fileSize", "filesize") // optional
            val colTime = pick("createdTime", "created_time", "filedate", "date", "time") // optional
            val colDir = pick("directory", "isDir", "isdir", "dir") // optional
            val hasStatus = cols.contains("status")

            val list = mutableListOf<FileEntity>()
            val sql = buildString {
                append("SELECT $colPath")
                if (colSize != null) append(",$colSize") else append(",0 AS size")
                if (colTime != null) append(",$colTime") else append(",0 AS createdTime")
                if (colDir != null) append(",$colDir") else append(",0 AS directory")
                append(" FROM files_table")
                if (hasStatus) append(" WHERE status = ?")
            }
            val args = if (hasStatus) arrayOf("normal") else null

            db.rawQuery(sql, args).use { cursor ->
                val idxPath = cursor.getColumnIndexOrThrow(colPath)
                val idxSize = cursor.getColumnIndex("size").takeIf { it >= 0 } ?: cursor.getColumnIndex(colSize ?: "size")
                val idxTime = cursor.getColumnIndex("createdTime").takeIf { it >= 0 } ?: cursor.getColumnIndex(colTime ?: "createdTime")
                val idxDir = cursor.getColumnIndex("directory").takeIf { it >= 0 } ?: cursor.getColumnIndex(colDir ?: "directory")
                while (cursor.moveToNext()) {
                    val path = cursor.getString(idxPath) ?: continue
                    val size = if (idxSize >= 0) cursor.getLong(idxSize) else 0L
                    val time = if (idxTime >= 0) cursor.getLong(idxTime) else 0L
                    val isDir = if (idxDir >= 0) readBoolColumn(cursor, idxDir) else false
                    list.add(
                        FileEntity(
                            remotePath = path,
                            size = size,
                            date = time,
                            isDir = isDir,
                            owner = owner,
                        )
                    )
                }
            }

            dao.deleteAllForOwner(owner)
            if (list.isNotEmpty()) dao.upsertAll(list)
            Log.d(tag, "Imported ${list.size} shared rows for $owner (cols=${cols.sorted()})")
        } catch (e: Exception) {
            Log.e(tag, "importFromNasSharedSqliteFile failed", e)
            dao.deleteAllForOwner(owner)
            throw e
        } finally {
            db.close()
        }
    }

    suspend fun importFromNasSqliteFileByStatus(absolutePath: String, owner: String, status: String) = withContext(Dispatchers.IO) {
        val db = SQLiteDatabase.openDatabase(absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val list = mutableListOf<FileEntity>()
            db.rawQuery(
                "SELECT remotePath,size,createdTime,directory FROM files_table WHERE status = ?",
                arrayOf(status)
            ).use { cursor ->
                val idxPath = cursor.getColumnIndexOrThrow("remotePath")
                val idxSize = cursor.getColumnIndexOrThrow("size")
                val idxTime = cursor.getColumnIndexOrThrow("createdTime")
                val idxDir = cursor.getColumnIndexOrThrow("directory")
                while (cursor.moveToNext()) {
                    val isDir = readBoolColumn(cursor, idxDir)
                    list.add(
                        FileEntity(
                            remotePath = cursor.getString(idxPath),
                            size = cursor.getLong(idxSize),
                            date = cursor.getLong(idxTime),
                            isDir = isDir,
                            owner = owner
                        )
                    )
                }
            }
            dao.deleteAllForOwner(owner)
            if (list.isNotEmpty()) dao.upsertAll(list)
            Log.d(tag, "Imported ${list.size} file rows for $owner (status=$status)")
        } catch (e: Exception) {
            Log.e(tag, "importFromNasSqliteFileByStatus failed", e)
            dao.deleteAllForOwner(owner)
            throw e
        } finally {
            db.close()
        }
    }

    private fun readBoolColumn(cursor: Cursor, idx: Int): Boolean {
        return when (cursor.getType(idx)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(idx) != 0L
            Cursor.FIELD_TYPE_STRING -> {
                val s = cursor.getString(idx) ?: return false
                s == "1" || s.equals("true", ignoreCase = true)
            }
            Cursor.FIELD_TYPE_NULL -> false
            else -> false
        }
    }
}
