package com.ithingtalk.zhome.data.repository

import com.ithingtalk.zhome.data.local.db.TransferDao
import com.ithingtalk.zhome.data.local.db.TransferEntity
import com.ithingtalk.zhome.data.local.prefs.LocalPrefs

class TransferRepository(
    private val dao: TransferDao,
    private val prefs: LocalPrefs
) {
    companion object {
        const val TYPE_UPLOAD = 0
        const val TYPE_DOWNLOAD = 1
        const val STATUS_QUEUED = 0
        const val STATUS_RUNNING = 1
        const val STATUS_SUCCESS = 2
        const val STATUS_ERROR = 3
        const val STATUS_STOPPED = 4
    }

    suspend fun getUploads(): List<TransferEntity> = dao.getByType(TYPE_UPLOAD)
    suspend fun getDownloads(): List<TransferEntity> = dao.getByType(TYPE_DOWNLOAD)

    fun observeUploads() = dao.observeByType(TYPE_UPLOAD)
    fun observeDownloads() = dao.observeByType(TYPE_DOWNLOAD)
    fun observeQueued() = dao.observeByStatus(STATUS_QUEUED)

    suspend fun getById(id: Long): TransferEntity? = dao.getById(id)

    suspend fun addUpload(deviceMac: String, remotePath: String, localPath: String): Long =
        dao.insert(
            TransferEntity(
                deviceMac = deviceMac,
                remotePath = remotePath,
                localPath = localPath,
                type = TYPE_UPLOAD,
                status = STATUS_QUEUED,
                updatedAt = System.currentTimeMillis(),
            )
        )

    suspend fun addDownload(deviceMac: String, remotePath: String, localPath: String = ""): Long =
        dao.insert(
            TransferEntity(
                deviceMac = deviceMac,
                remotePath = remotePath,
                localPath = localPath,
                type = TYPE_DOWNLOAD,
                status = STATUS_QUEUED,
                updatedAt = System.currentTimeMillis(),
            )
        )

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteAllUploads() = dao.deleteByType(TYPE_UPLOAD)
    suspend fun deleteAllDownloads() = dao.deleteByType(TYPE_DOWNLOAD)
    suspend fun deleteAll() = dao.deleteAll()

    suspend fun totalCount(): Int = dao.countAll()

    suspend fun updateStatus(id: Long, status: Int) = dao.updateStatus(id, status)
    suspend fun updateProgress(
        id: Long,
        status: Int,
        progress: Float,
        transferred: Long,
        total: Long,
        error: String = "",
    ) = dao.updateProgress(id, status, progress, transferred, total, error, System.currentTimeMillis())

    suspend fun success(id: Long) = dao.delete(id)
    suspend fun fail(id: Long, error: String) = updateProgress(id, STATUS_ERROR, 0f, 0L, 0L, error)
    suspend fun stop(id: Long) = updateStatus(id, STATUS_STOPPED)
}

