package com.ithingtalk.zhome.ui.screens.content

import android.util.Log
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.RemoteMediaType
import com.ithingtalk.zhome.data.classifyRemoteMediaType
import com.ithingtalk.zhome.data.local.LocalLibraryKind
import com.ithingtalk.zhome.data.pathsOfType
import com.ithingtalk.zhome.data.toRecentFileType
import com.ithingtalk.zhome.data.local.documentMimeTypesForUpload
import com.ithingtalk.zhome.data.local.inferLocalLibraryKindFromNasPath
import com.ithingtalk.zhome.data.local.db.FileEntity
import com.ithingtalk.zhome.data.remote.nas.NasHttpDownload
import com.ithingtalk.zhome.data.remote.nas.NasHttpUpload
import com.ithingtalk.zhome.data.remote.nas.NasUrl
import com.ithingtalk.zhome.data.remote.p2p.RemoteLinkCoordinator
import com.ithingtalk.zhome.data.remote.nas.NasCommands
import com.ithingtalk.zhome.data.remote.nas.NasUserDbSync
import com.ithingtalk.zhome.data.remote.nas.NasUserDbSync.SyncFromDeviceResult
import com.ithingtalk.zhome.jni.NativeBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat

class FileBrowserViewModel : ViewModel() {
    private val fileRepo = ZhomeApp.instance.fileRepo
    private val prefs = ZhomeApp.instance.prefs
    private val deviceRepo = ZhomeApp.instance.deviceRepo
    private val transferRepo = ZhomeApp.instance.transferRepo
    private val appCtx = ZhomeApp.instance.applicationContext

    var files by mutableStateOf<List<FileEntity>>(emptyList()); private set
    var isLoading by mutableStateOf(false); private set
    var displayType by mutableStateOf(0); private set
    var currentPath by mutableStateOf(""); private set
    var operationResult by mutableStateOf<String?>(null); private set
    var operationBusy by mutableStateOf(false); private set
    /** True when uploads or downloads list has any row (for toolbar shortcut). */
    var hasTransfersInList by mutableStateOf(false); private set
    /** True when recycle bin has at least one row (for toolbar shortcut). */
    var trashHasItems by mutableStateOf(false); private set
    private var fileListJob: kotlinx.coroutines.Job? = null
    private var trashCountJob: kotlinx.coroutines.Job? = null
    private var playbackPrewarmJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            combine(transferRepo.observeUploads(), transferRepo.observeDownloads()) { u, d ->
                u.isNotEmpty() || d.isNotEmpty()
            }.collect { hasTransfersInList = it }
        }
    }

    fun recordFileAccess(remotePath: String) {
        viewModelScope.launch {
            val mac = deviceRepo.getCurrent()?.mac ?: return@launch
            val displayName = remotePath.substringAfterLast("/")
            val fileType = classifyRemoteMediaType(remotePath).toRecentFileType()
            fileRepo.recordAccess(remotePath, displayName, fileType, mac)
        }
    }

    fun loadFiles(dir: String) {
        currentPath = dir
        viewModelScope.launch {
            fileListJob?.cancel()
            trashCountJob?.cancel()

            isLoading = true
            displayType = fileRepo.getDisplayType()

            val baseOwner = prefs.getUser()
            val owner = when (dir) {
                "__shared__" -> "__shared__${baseOwner}"
                "__trash__" -> "__trash__${baseOwner}"
                else -> baseOwner
            }

            fileListJob = launch {
                fileRepo.observeFilesInDir(owner, dir).collectLatest { list ->
                    files = list
                    isLoading = false
                }
            }

            if (baseOwner.isNotBlank()) {
                val trashOwner = "__trash__${baseOwner}"
                trashCountJob = launch {
                    fileRepo.observeCount(trashOwner).collectLatest { c ->
                        trashHasItems = c > 0
                    }
                }
            } else {
                trashHasItems = false
            }

            prewarmRemotePlayback()
        }
    }

    private fun prewarmRemotePlayback() {
        if (playbackPrewarmJob?.isActive == true) return
        playbackPrewarmJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val device = deviceRepo.getCurrent() ?: return@launch
                if (deviceRepo.useLocalLink(device)) return@launch
                if (!deviceRepo.isRemoteConnected(device)) return@launch
                val err = RemoteLinkCoordinator.ensureP2pPlaybackReady(device)
                if (err != null) {
                    Log.w("FileBrowser", "prewarmRemotePlayback skipped for ${device.mac}: $err")
                }
            } catch (e: Exception) {
                Log.w("FileBrowser", "prewarmRemotePlayback failed", e)
            }
        }
    }

    /** 从设备重新下载 file.db / shared.db 并刷新当前列表（与主界面刷新一致）。 */
    fun syncFromDevice() {
        viewModelScope.launch {
            val mac = deviceRepo.getCurrent()?.mac ?: run {
                operationResult = appCtx.getString(R.string.file_no_device)
                return@launch
            }
            operationBusy = true
            operationResult = null
            try {
                when (val r = NasUserDbSync.syncFromDevice(mac)) {
                    SyncFromDeviceResult.Success -> {
                        prefs.removePendingUserApprovalMac(mac)
                        loadFiles(currentPath)
                    }
                    SyncFromDeviceResult.NeedAdminApproval -> {
                        prefs.addPendingUserApprovalMac(mac)
                        operationResult = appCtx.getString(R.string.file_sync_need_admin)
                        return@launch
                    }
                    SyncFromDeviceResult.WrongPassword -> {
                        operationResult = appCtx.getString(R.string.sync_wrong_password)
                        return@launch
                    }
                    SyncFromDeviceResult.DiskUninitialized -> {
                        operationResult = appCtx.getString(R.string.sync_disk_uninit_dialog_body)
                        return@launch
                    }
                    is SyncFromDeviceResult.Error -> {
                        operationResult = r.message
                        return@launch
                    }
                }
            } catch (e: Exception) {
                operationResult = e.message ?: appCtx.getString(R.string.sync_failed_generic)
                Log.e("FileBrowser", "syncFromDevice failed", e)
            } finally {
                operationBusy = false
            }
        }
    }

    /**
     * NAS `repair_user_database`: rescan current folder on disk into file.db, then sync `file.db`.
     * [nasSubDir] is relative under the user account, e.g. `MyFiles/Video`.
     */
    fun repairFileDatabaseForSubDir(nasSubDir: String) {
        viewModelScope.launch {
            val mac = deviceRepo.getCurrent()?.mac ?: run {
                operationResult = appCtx.getString(R.string.file_no_device)
                return@launch
            }
            val d = deviceRepo.getByMac(mac) ?: run {
                operationResult = appCtx.getString(R.string.file_device_not_found)
                return@launch
            }
            val user = prefs.getUser()
            val pass = prefs.getPass()
            if (user.isBlank() || pass.isBlank()) {
                operationResult = appCtx.getString(R.string.file_not_signed_in)
                return@launch
            }
            operationBusy = true
            operationResult = null
            try {
                val cmd = NasCommands.repairUserDatabase(user, pass, nasSubDir)
                val resp = deviceRepo.postDeviceCommand(
                    d,
                    cmd,
                    remoteTimeoutMs = Constants.REPAIR_USER_DATABASE_REMOTE_TIMEOUT_MS,
                    lanReadTimeoutSec = Constants.REPAIR_USER_DATABASE_LAN_TIMEOUT_SEC,
                )
                if (!NasCommands.repairUserDatabaseSuccess(resp)) {
                    operationResult = appCtx.getString(R.string.file_repair_failed)
                    return@launch
                }
                when (val sr = NasUserDbSync.syncFromDevice(mac)) {
                    SyncFromDeviceResult.Success -> {
                        prefs.removePendingUserApprovalMac(mac)
                    }
                    SyncFromDeviceResult.NeedAdminApproval -> {
                        prefs.addPendingUserApprovalMac(mac)
                        operationResult = appCtx.getString(R.string.file_repair_ok_need_admin)
                        return@launch
                    }
                    SyncFromDeviceResult.WrongPassword -> {
                        operationResult = appCtx.getString(R.string.sync_wrong_password)
                        return@launch
                    }
                    SyncFromDeviceResult.DiskUninitialized -> {
                        operationResult = appCtx.getString(R.string.sync_disk_uninit_dialog_body)
                        return@launch
                    }
                    is SyncFromDeviceResult.Error -> {
                        operationResult = appCtx.getString(R.string.file_repair_sync_failed, sr.message)
                        return@launch
                    }
                }
                loadFiles(currentPath)
                operationResult = appCtx.getString(R.string.file_repair_dir_ok, nasSubDir)
            } catch (e: Exception) {
                Log.e("FileBrowser", "repairFileDatabaseForSubDir", e)
                operationResult = e.message ?: appCtx.getString(R.string.file_repair_failed)
            } finally {
                operationBusy = false
            }
        }
    }

    suspend fun listAllDirectoriesForMove(): List<String> {
        val baseOwner = prefs.getUser()
        if (baseOwner.isBlank()) return emptyList()
        val all = fileRepo.getFiles(baseOwner)
        val dirs = mutableSetOf<String>()
        dirs.add("/MyFiles")
        for (e in all) {
            val p = e.remotePath
            val i = p.indexOf("/MyFiles")
            if (i < 0) continue
            val n = p.substring(i).trimEnd('/')
            val parts = n.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            var cur = ""
            for (k in parts) {
                cur += "/$k"
                dirs.add(cur)
            }
        }
        return dirs.toList().sorted()
    }

    fun toggleDisplayType() {
        displayType = if (displayType == 0) 1 else 0
        viewModelScope.launch { fileRepo.setDisplayType(displayType) }
    }

    private fun runFileCommand(
        cmdBuilder: (String, String) -> String,
        isSuccess: (String) -> Boolean,
        applyLocalDb: suspend (owner: String, device: com.ithingtalk.zhome.data.local.db.DeviceEntity) -> Unit = { _, _ -> },
    ) {
        viewModelScope.launch {
            operationBusy = true; operationResult = null
            try {
                val user = prefs.getUser()
                val pass = prefs.getPass()
                val device = deviceRepo.getCurrent() ?: run {
                    operationResult = appCtx.getString(R.string.file_no_device)
                    return@launch
                }
                val cmd = cmdBuilder(user, pass)
                val resp = deviceRepo.postDeviceCommand(device, cmd)
                if (isSuccess(resp)) {
                    operationResult = appCtx.getString(R.string.common_ok)
                    applyLocalDb(user, device)
                    loadFiles(currentPath)
                } else {
                    val detail = resp.ifBlank { appCtx.getString(R.string.common_unknown) }
                    operationResult = appCtx.getString(R.string.file_operation_failed_detail, detail)
                }
            } catch (e: Exception) {
                operationResult = e.message ?: appCtx.getString(R.string.shared_err_generic)
                Log.e("FileBrowser", "File operation failed", e)
            } finally { operationBusy = false }
        }
    }


    fun deleteFiles(paths: List<String>) = runFileCommand(
        cmdBuilder = { u, p -> NasCommands.removeFiles(u, p, paths) },
        isSuccess = NasCommands::removeFilesSuccess,
        applyLocalDb = { owner, _ ->
            val trashOwner = "__trash__${owner}"
            val toMove = fileRepo.collectPathsUnderRoots(owner, paths)
            if (toMove.isNotEmpty()) {
                fileRepo.addFiles(toMove.map { it.copy(owner = trashOwner) })
            }
            for (p in paths) {
                fileRepo.deleteSubtree(owner, p)
            }
        }
    )

    fun permanentDelete(paths: List<String>) = runFileCommand(
        cmdBuilder = { u, p -> NasCommands.deleteFiles(u, p, paths) },
        isSuccess = NasCommands::deleteFilesSuccess,
        applyLocalDb = { owner, _ ->
            val trashOwner = "__trash__${owner}"
            for (p in paths) {
                fileRepo.deleteSubtree(trashOwner, p)
            }
        }
    )

    /** Recycle bin: restore every top-level item in the current list (Qt “Restore all”). */
    fun recoverAllInTrash() {
        val paths = files.map { it.remotePath }
        if (paths.isNotEmpty()) recoverFiles(paths)
    }

    /** Recycle bin: permanently delete every top-level item in the current list (Qt “Empty”). */
    fun emptyTrashPermanently() {
        val paths = files.map { it.remotePath }
        if (paths.isNotEmpty()) permanentDelete(paths)
    }

    fun recoverFiles(paths: List<String>) = runFileCommand(
        cmdBuilder = { u, p -> NasCommands.recoverFiles(u, p, paths) },
        isSuccess = NasCommands::recoverFilesSuccess,
        applyLocalDb = { owner, _ ->
            val trashOwner = "__trash__${owner}"
            val toRecover = fileRepo.collectPathsUnderRoots(trashOwner, paths)
            if (toRecover.isNotEmpty()) {
                fileRepo.addFiles(toRecover.map { it.copy(owner = owner) })
            }
            for (p in paths) {
                fileRepo.deleteSubtree(trashOwner, p)
            }
        }
    )

    fun renameFile(from: String, to: String) = runFileCommand(
        cmdBuilder = { u, p ->
            NasCommands.renameFile(u, p, NasCommands.normalizeMyFilesPath(from), NasCommands.normalizeMyFilesPath(to))
        },
        isSuccess = NasCommands::renameFileSuccess,
        applyLocalDb = { owner, _ ->
            val existing = fileRepo.getFileByPath(owner, from) ?: return@runFileCommand
            fileRepo.deleteFile(owner, from)
            fileRepo.addFile(existing.copy(remotePath = to))
        }
    )

    fun createFolder(name: String) = runFileCommand(
        cmdBuilder = { u, p -> NasCommands.createNewFolder(u, p, NasCommands.normalizeMyFilesPath(currentPath), name) },
        isSuccess = NasCommands::createNewFolderSuccess,
        applyLocalDb = { owner, _ ->
            val destDir = (if (currentPath.isBlank()) "/MyFiles" else currentPath).trimEnd('/')
            val newPath = "$destDir/$name"
            fileRepo.addFile(FileEntity(remotePath = newPath, isDir = true, owner = owner, size = 0, date = System.currentTimeMillis()))
        }
    )

    fun moveFiles(paths: List<String>, dest: String) = runFileCommand(
        cmdBuilder = { u, p ->
            NasCommands.moveFiles(
                u,
                p,
                paths.map { NasCommands.normalizeMyFilesPath(it) },
                NasCommands.normalizeMyFilesPath(dest),
            )
        },
        isSuccess = NasCommands::moveFilesSuccess,
        applyLocalDb = { owner, _ ->
            for (p in paths) {
                val existing = fileRepo.getFileByPath(owner, p) ?: continue
                val fileName = p.substringAfterLast("/")
                val destDir = dest.trimEnd('/')
                val newPath = "$destDir/$fileName"
                fileRepo.deleteFile(owner, p)
                fileRepo.addFile(existing.copy(remotePath = newPath))
            }
        }
    )

    fun shareFiles(paths: List<String>) = runFileCommand(
        cmdBuilder = { u, p -> NasCommands.shareFiles(u, p, paths) },
        isSuccess = NasCommands::shareFilesSuccess,
        applyLocalDb = { owner, _ ->
            val sharedOwner = "__shared__${owner}"
            val shared = paths.mapNotNull { p -> fileRepo.getFileByPath(owner, p)?.copy(owner = sharedOwner) }
            if (shared.isNotEmpty()) fileRepo.addFiles(shared)
        }
    )
    fun clearResult() { operationResult = null }
    fun showResult(msg: String) { operationResult = msg }

    fun showAudioPickerUnsupported() {
        operationResult = appCtx.getString(R.string.file_audio_picker_unsupported)
    }

    fun downloadFiles(remotePaths: List<String>) {
        viewModelScope.launch {
            operationResult = null
            try {
                val device = deviceRepo.getCurrent() ?: run {
                    operationResult = appCtx.getString(R.string.file_no_device)
                    return@launch
                }
                val mac = device.mac
                // Unified "~/Downloads/zhome/download" (not user / device
                // scoped per the cross-app path spec).
                val appDlDir = com.ithingtalk.zhome.data.local.AppPaths
                    .globalDownloadDir(appCtx)
                for (rp in remotePaths) {
                    if (rp.isBlank()) continue
                    val fileName = rp.substringAfterLast("/").ifBlank { "file" }
                    val tempDest = File(appDlDir, fileName)
                    transferRepo.addDownload(deviceMac = mac, remotePath = rp, localPath = tempDest.absolutePath)
                }
                operationResult = appCtx.getString(R.string.file_added_transfer)
            } catch (e: Exception) {
                operationResult = e.message ?: appCtx.getString(R.string.file_download_error)
                Log.e("FileBrowser", "downloadFiles enqueue failed", e)
            }
        }
    }

    fun uploadFromUri(ctx: Context, localUri: Uri) {
        uploadFromUris(ctx, listOf(localUri))
    }

    /** Enqueue one or more uploads (multi-select). */
    fun uploadFromUris(ctx: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            operationResult = null
            try {
                val device = deviceRepo.getCurrent() ?: run {
                    operationResult = appCtx.getString(R.string.file_no_device)
                    return@launch
                }
                val user = prefs.getUser()
                val pass = prefs.getPass()
                if (user.isBlank() || pass.isBlank()) {
                    operationResult = appCtx.getString(R.string.file_not_signed_in)
                    return@launch
                }
                val mac = device.mac
                val usedNames = mutableSetOf<String>()
                var ok = 0
                var fail = 0
                for (uri in uris) {
                    runCatching {
                        enqueueUploadFromUri(ctx, uri, mac, usedNames)
                        ok++
                    }.onFailure { e ->
                        fail++
                        Log.e("FileBrowser", "enqueue upload failed", e)
                    }
                }
                operationResult = when {
                    fail == 0 -> appCtx.getString(R.string.file_added_transfer_count, ok)
                    ok == 0 -> appCtx.getString(R.string.file_upload_failed)
                    else -> appCtx.getString(R.string.file_upload_partial, ok, fail)
                }
            } catch (e: Exception) {
                operationResult = e.message ?: appCtx.getString(R.string.file_upload_error)
                Log.e("FileBrowser", "uploadFromUris failed", e)
            }
        }
    }

    private suspend fun enqueueUploadFromUri(
        ctx: Context,
        localUri: Uri,
        mac: String,
        usedRemoteNames: MutableSet<String>,
    ) {
        val rawName = queryDisplayName(ctx, localUri) ?: "upload.bin"
        val fileName = disambiguateRemoteFileName(rawName, usedRemoteNames)
        val tmp = File(appCtx.cacheDir, "upload_tmp").apply { mkdirs() }.resolve("${System.nanoTime()}-$fileName")
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(localUri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException(appCtx.getString(R.string.file_cannot_open_file))
        }
        val destDir = (if (currentPath.isBlank()) "/MyFiles" else currentPath).trimEnd('/')
        val destLogical = "$destDir/$fileName"
        transferRepo.addUpload(deviceMac = mac, remotePath = destLogical, localPath = tmp.absolutePath)
    }

    private fun disambiguateRemoteFileName(name: String, used: MutableSet<String>): String {
        if (name !in used) {
            used.add(name)
            return name
        }
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = if (ext.isNotEmpty()) "${stem}_$n$ext" else "${name}_$n"
            if (candidate !in used) {
                used.add(candidate)
                return candidate
            }
            n++
        }
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        val c: Cursor? = ctx.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)
        c?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return null
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    dir: String,
    isTrash: Boolean,
    onOpenDir: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onPlayVideo: (paths: List<String>, startIndex: Int) -> Unit,
    onPlayAudio: (paths: List<String>, startIndex: Int) -> Unit,
    onPreviewImage: (List<String>, Int) -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenTrash: () -> Unit = {},
    onGoHome: () -> Unit = {},
    onBack: () -> Unit,
    vm: FileBrowserViewModel = viewModel()
) {
    // Directory changes only re-subscribe to Room Flow — no NAS login/sync here.
    LaunchedEffect(dir) { vm.loadFiles(dir) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun extractUrisFromResult(data: android.content.Intent?): List<Uri> {
        if (data == null) return emptyList()
        val out = mutableListOf<Uri>()
        data.data?.let { out.add(it) }
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { out.add(it) }
            }
        }
        return out.distinct()
    }

    // Prefer gallery app for video/image (Qt-like "album" experience).
    val pickGalleryVideo = rememberLauncherForActivityResult(
        contract = StartActivityForResult(),
        onResult = { res ->
            val uris = extractUrisFromResult(res.data)
            if (uris.isNotEmpty()) vm.uploadFromUris(ctx, uris)
        },
    )
    val pickGalleryImage = rememberLauncherForActivityResult(
        contract = StartActivityForResult(),
        onResult = { res ->
            val uris = extractUrisFromResult(res.data)
            if (uris.isNotEmpty()) vm.uploadFromUris(ctx, uris)
        },
    )
    val pickAudioLibrary = rememberLauncherForActivityResult(
        contract = StartActivityForResult(),
        onResult = { res ->
            val uris = extractUrisFromResult(res.data)
            if (uris.isNotEmpty()) vm.uploadFromUris(ctx, uris)
        },
    )
    val openDocuments = rememberLauncherForActivityResult(
        contract = StartActivityForResult(),
        onResult = { res ->
            val uris = extractUrisFromResult(res.data)
            if (uris.isNotEmpty()) vm.uploadFromUris(ctx, uris)
        },
    )
    val openMultipleDocuments = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris -> if (uris.isNotEmpty()) vm.uploadFromUris(ctx, uris) },
    )

    fun launchUploadPicker(kind: LocalLibraryKind) {
        when (kind) {
            LocalLibraryKind.GALLERY_VIDEO ->
                pickGalleryVideo.launch(
                    Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).apply {
                        type = "video/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                )
            LocalLibraryKind.GALLERY_IMAGE ->
                pickGalleryImage.launch(
                    Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                )
            LocalLibraryKind.AUDIO_LIBRARY ->
                runCatching {
                    // Some ROMs don't provide an Activity for ACTION_PICK audio/*.
                    // Use SAF documents picker as the most compatible option.
                    openMultipleDocuments.launch(arrayOf("audio/*"))
                }.onFailure {
                    // Last-resort fallback: try gallery-like pick if available.
                    val intent = Intent(Intent.ACTION_PICK, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI).apply {
                        type = "audio/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    val pm = ctx.packageManager
                    if (intent.resolveActivity(pm) != null) {
                        pickAudioLibrary.launch(intent)
                    } else {
                        vm.showAudioPickerUnsupported()
                    }
                }
            LocalLibraryKind.DOCUMENTS ->
                openDocuments.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        putExtra(Intent.EXTRA_MIME_TYPES, documentMimeTypesForUpload())
                    }
                )
            LocalLibraryKind.ANY ->
                openMultipleDocuments.launch(arrayOf("*/*"))
        }
    }

    var moreMenuExpanded by remember { mutableStateOf(false) }
    val audioPlaylist = remember(vm.files) { vm.files.pathsOfType(RemoteMediaType.AUDIO) }

    var selectionMode by rememberSaveable(dir, isTrash) { mutableStateOf(false) }
    var selectedPaths by rememberSaveable(dir, isTrash) { mutableStateOf(emptySet<String>()) }

    fun clearSelection() {
        selectionMode = false
        selectedPaths = emptySet()
    }
    fun toggleSelected(path: String) {
        selectedPaths = if (selectedPaths.contains(path)) selectedPaths - path else selectedPaths + path
        if (selectedPaths.isEmpty()) selectionMode = false
    }

    val selectedList = remember(selectedPaths) { selectedPaths.toList() }
    val canRename = !isTrash && dir != "__shared__" && selectedPaths.size == 1 && !vm.operationBusy
    val canShareOrMove = !isTrash && dir != "__shared__" && selectedPaths.isNotEmpty() && !vm.operationBusy
    val canDelete = selectedPaths.isNotEmpty() && !vm.operationBusy
    val canDownload = selectedPaths.isNotEmpty() && !vm.operationBusy

    BackHandler(enabled = selectionMode) {
        clearSelection()
    }

    // File operation dialogs
    var contextFile by remember { mutableStateOf<FileEntity?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf<FileEntity?>(null) }
    var showMove by remember { mutableStateOf(false) }
    var moveDestDir by remember { mutableStateOf("/MyFiles") }
    var moveDirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var moveLoading by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameTo by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var showRestoreAllDialog by remember { mutableStateOf(false) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }

    val title = when {
        dir.isBlank() -> stringResource(R.string.file_browse_all_files)
        isTrash -> stringResource(R.string.file_title_trash)
        dir == "__shared__" -> stringResource(R.string.file_title_shared)
        else -> dir.substringAfterLast("/", dir)
    }

    // Snackbar for operation results
    vm.operationResult?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2000)
            vm.clearResult()
        }
    }

    // New folder dialog
    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text(stringResource(R.string.file_new_folder_title)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.file_folder_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showNewFolder = false; vm.createFolder(newFolderName); newFolderName = "" },
                    enabled = newFolderName.isNotBlank(),
                ) { Text(stringResource(R.string.file_action_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false; newFolderName = "" }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Rename dialog
    showRename?.let { file ->
        AlertDialog(
            onDismissRequest = { showRename = null },
            title = { Text(stringResource(R.string.file_rename)) },
            text = {
                OutlinedTextField(
                    value = renameTo,
                    onValueChange = { renameTo = it },
                    label = { Text(stringResource(R.string.file_new_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parent = file.remotePath.substringBeforeLast("/", "")
                        val newPath = if (parent.isNotBlank()) "$parent/$renameTo" else renameTo
                        vm.renameFile(file.remotePath, newPath)
                        showRename = null; renameTo = ""
                    },
                    enabled = renameTo.isNotBlank(),
                ) { Text(stringResource(R.string.file_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = null; renameTo = "" }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Move dialog (simple destination input; aligns with Qt move-to-folder)
    if (showMove) {
        LaunchedEffect(Unit) {
            moveLoading = true
            moveDirs = vm.listAllDirectoriesForMove()
            moveLoading = false
        }
        val oldPath = remember(selectedList) {
            if (selectedList.size == 1) selectedList.firstOrNull()?.substringBeforeLast("/", "")?.ifBlank { "/MyFiles" }
            else "/MyFiles"
        }
        val moveDialogScroll = rememberScrollState()
        AlertDialog(
            onDismissRequest = { showMove = false },
            title = { Text(stringResource(R.string.file_move_title)) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(moveDialogScroll),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.file_location), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.file_original_location),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(oldPath ?: "-", style = MaterialTheme.typography.bodyMedium)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.file_new_location),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(moveDestDir, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Text(stringResource(R.string.file_pick_new_location), style = MaterialTheme.typography.bodyMedium)
                    if (moveLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        DirectoryTreePicker(
                            dirs = moveDirs,
                            selected = moveDestDir,
                            onSelect = { moveDestDir = it },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMove = false
                        vm.moveFiles(selectedList, moveDestDir)
                        clearSelection()
                    },
                    enabled = selectedList.isNotEmpty() && !vm.operationBusy,
                ) { Text(stringResource(R.string.file_confirm_move)) }
            },
            dismissButton = {
                TextButton(onClick = { showMove = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showDeleteConfirm) {
        val firstName = deleteTargets.firstOrNull()?.substringAfterLast("/") ?: ""
        val deleteTitle = if (isTrash) {
            stringResource(R.string.file_delete_forever_title)
        } else {
            stringResource(R.string.file_delete_title)
        }
        val deleteMsg = if (deleteTargets.size == 1) {
            stringResource(R.string.file_delete_confirm_one, firstName)
        } else {
            stringResource(R.string.file_delete_confirm_many, deleteTargets.size)
        }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(deleteTitle) },
            text = { Text(deleteMsg) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        if (isTrash) vm.permanentDelete(deleteTargets) else vm.deleteFiles(deleteTargets)
                        clearSelection()
                    },
                    enabled = deleteTargets.isNotEmpty() && !vm.operationBusy,
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showRestoreAllDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreAllDialog = false },
            title = { Text(stringResource(R.string.file_restore_all_title)) },
            text = { Text(stringResource(R.string.file_restore_all_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreAllDialog = false
                        vm.recoverAllInTrash()
                    },
                    enabled = !vm.operationBusy,
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreAllDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showEmptyTrashDialog) {
        val n = vm.files.size
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            title = { Text(stringResource(R.string.file_empty_trash_title)) },
            text = { Text(stringResource(R.string.file_empty_trash_body, n)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmptyTrashDialog = false
                        vm.emptyTrashPermanently()
                    },
                    enabled = n > 0 && !vm.operationBusy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.file_empty_trash_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // Context menu (long-press)
    contextFile?.let { file ->
        AlertDialog(
            onDismissRequest = { contextFile = null },
            title = { Text(file.remotePath.substringAfterLast("/")) },
            text = {
                Column {
                    if (isTrash) {
                        TextButton(onClick = { contextFile = null; vm.recoverFiles(listOf(file.remotePath)) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.RestoreFromTrash, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.file_restore))
                        }
                        TextButton(onClick = { contextFile = null; deleteTargets = listOf(file.remotePath); showDeleteConfirm = true }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.DeleteForever, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.file_delete_forever))
                        }
                    } else {
                        if (dir != "__shared__") {
                            TextButton(onClick = { showRename = file; renameTo = file.remotePath.substringAfterLast("/"); contextFile = null }, Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Edit, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.file_rename))
                            }
                        }
                        if (!file.isDir) {
                            TextButton(onClick = { contextFile = null; vm.shareFiles(listOf(file.remotePath)) }, Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Share, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.file_share))
                            }
                        }
                        TextButton(onClick = { contextFile = null; deleteTargets = listOf(file.remotePath); showDeleteConfirm = true }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.file_delete_action))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { contextFile = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.shared_selected_count, selectedPaths.size)) },
                    navigationIcon = {
                        IconButton(onClick = { clearSelection() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                        }
                    }
                    ,
                    actions = {
                        TextButton(
                            onClick = { selectedPaths = vm.files.map { it.remotePath }.toSet() },
                            enabled = vm.files.isNotEmpty(),
                        ) { Text(stringResource(R.string.file_select_all)) }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                        }
                    },
                    actions = {
                        Row(
                            modifier = Modifier.heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (vm.hasTransfersInList) {
                                IconButton(onClick = onOpenTransfers) {
                                    Icon(Icons.Default.SwapVert, stringResource(R.string.file_cd_transfers))
                                }
                            }
                            if (!isTrash && vm.trashHasItems) {
                                IconButton(onClick = onOpenTrash) {
                                    Icon(Icons.Default.DeleteOutline, stringResource(R.string.file_cd_trash))
                                }
                            }
                            IconButton(onClick = onGoHome) {
                                Icon(Icons.Default.Home, stringResource(R.string.file_cd_home))
                            }
                            IconButton(onClick = { vm.syncFromDevice() }, enabled = !vm.operationBusy) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.file_cd_refresh))
                            }
                            val isAudioCategory = !isTrash && vm.currentPath == Constants.TAG_AUDIO
                            if (isAudioCategory && audioPlaylist.isNotEmpty()) {
                                TextButton(
                                    onClick = { onPlayAudio(audioPlaylist, 0) },
                                    enabled = !vm.operationBusy,
                                ) { Text(stringResource(R.string.file_play_all)) }
                            }
                            Box {
                            IconButton(onClick = { moreMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.file_cd_more))
                            }
                            DropdownMenu(
                                expanded = moreMenuExpanded,
                                onDismissRequest = { moreMenuExpanded = false }
                            ) {
                                if (!isTrash) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.file_new_folder_title)) },
                                        onClick = { moreMenuExpanded = false; showNewFolder = true },
                                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.file_upload_files)) },
                                        onClick = {
                                            moreMenuExpanded = false
                                            launchUploadPicker(inferLocalLibraryKindFromNasPath(vm.currentPath))
                                        },
                                        leadingIcon = { Icon(Icons.Default.Upload, null) }
                                    )
                                    if (dir != "__shared__") {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.file_rebuild_db)) },
                                            onClick = {
                                                moreMenuExpanded = false
                                                val sub = when {
                                                    vm.currentPath.isBlank() -> "MyFiles"
                                                    else -> NasCommands.normalizeMyFilesPath(vm.currentPath).trimEnd('/').ifBlank { "MyFiles" }
                                                }
                                                vm.repairFileDatabaseForSubDir(sub)
                                            },
                                            leadingIcon = { Icon(Icons.Default.Storage, null) }
                                        )
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_refresh)) },
                                    onClick = {
                                        moreMenuExpanded = false
                                        vm.syncFromDevice()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null) }
                                )
                            }
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            when {
                isTrash && !selectionMode -> {
                    BottomAppBar {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val trashHasItems = vm.files.isNotEmpty()
                            BottomToolButton(
                                icon = Icons.Default.RestoreFromTrash,
                                label = stringResource(R.string.file_restore_all),
                                enabled = trashHasItems && !vm.operationBusy,
                                onClick = { showRestoreAllDialog = true },
                            )
                            BottomToolButton(
                                icon = Icons.Default.DeleteForever,
                                label = stringResource(R.string.file_empty_trash),
                                enabled = trashHasItems && !vm.operationBusy,
                                onClick = { showEmptyTrashDialog = true },
                            )
                        }
                    }
                }
                isTrash && selectionMode -> {
                    BottomAppBar {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BottomToolButton(
                                icon = Icons.Default.RestoreFromTrash,
                                label = stringResource(R.string.file_restore),
                                enabled = canDelete,
                                onClick = {
                                    vm.recoverFiles(selectedList)
                                    clearSelection()
                                },
                            )
                            BottomToolButton(
                                icon = Icons.Default.DeleteForever,
                                label = stringResource(R.string.file_delete_forever),
                                enabled = canDelete,
                                onClick = {
                                    deleteTargets = selectedList
                                    showDeleteConfirm = true
                                },
                            )
                        }
                    }
                }
                selectionMode -> {
                    BottomAppBar {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BottomToolButton(
                                icon = Icons.Default.DeleteOutline,
                                label = stringResource(R.string.file_delete_action),
                                enabled = canDelete,
                                onClick = {
                                    deleteTargets = selectedList
                                    showDeleteConfirm = true
                                },
                            )
                            BottomToolButton(
                                icon = Icons.Default.Share,
                                label = stringResource(R.string.file_share),
                                enabled = canShareOrMove,
                                onClick = { vm.shareFiles(selectedList); clearSelection() },
                            )
                            if (selectedPaths.size == 1) {
                                BottomToolButton(
                                    icon = Icons.Default.Edit,
                                    label = stringResource(R.string.file_rename),
                                    enabled = canRename,
                                    onClick = {
                                        val only = selectedList.firstOrNull() ?: return@BottomToolButton
                                        val f = vm.files.firstOrNull { it.remotePath == only } ?: return@BottomToolButton
                                        showRename = f
                                        renameTo = f.remotePath.substringAfterLast("/")
                                    },
                                )
                            }
                            BottomToolButton(
                                icon = Icons.AutoMirrored.Filled.DriveFileMove,
                                label = stringResource(R.string.file_move),
                                enabled = canShareOrMove,
                                onClick = { showMove = true },
                            )
                            BottomToolButton(
                                icon = Icons.Default.Download,
                                label = stringResource(R.string.file_download),
                                enabled = canDownload,
                                onClick = {
                                    vm.downloadFiles(selectedList.filter { p ->
                                        vm.files.firstOrNull { it.remotePath == p }?.isDir != true
                                    })
                                    clearSelection()
                                },
                            )
                        }
                    }
                }
                else -> {
                    BottomAppBar {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CategoryNavButton(
                                icon = Icons.Default.Image,
                                label = stringResource(R.string.file_type_image),
                                onClick = { onOpenCategory("/MyFiles/Image") },
                            )
                            CategoryNavButton(
                                icon = Icons.Default.VideoLibrary,
                                label = stringResource(R.string.file_type_video),
                                onClick = { onOpenCategory("/MyFiles/Video") },
                            )
                            CategoryNavButton(
                                icon = Icons.Default.Audiotrack,
                                label = stringResource(R.string.file_type_audio),
                                onClick = { onOpenCategory("/MyFiles/Audio") },
                            )
                            CategoryNavButton(
                                icon = Icons.Default.Description,
                                label = stringResource(R.string.file_type_doc),
                                onClick = { onOpenCategory("/MyFiles/Doc") },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (vm.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (vm.files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.file_no_files), color = MaterialTheme.colorScheme.outline)
                }
            } else {
                // Simple pull-to-refresh: pull down to reload from local DB.
                var pullDown by remember { mutableFloatStateOf(0f) }
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(dir, vm.isLoading, vm.operationBusy) {
                            pullDown = 0f
                            detectDragGestures(
                                onDragCancel = { pullDown = 0f },
                                onDragEnd = {
                                    if (pullDown > 120f && !vm.isLoading && !vm.operationBusy) vm.syncFromDevice()
                                    pullDown = 0f
                                },
                            ) { _, dragAmount ->
                                if (dragAmount.y > 0f) pullDown += dragAmount.y
                            }
                        }
                ) {
                    items(vm.files, key = { it.remotePath }) { file ->
                        FileListItem(
                            file = file,
                            selected = selectedPaths.contains(file.remotePath),
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelected(file.remotePath)
                                    return@FileListItem
                                }
                                if (file.isDir && !isTrash) {
                                    onOpenDir(file.remotePath)
                                } else if (!file.isDir) {
                                    when (classifyRemoteMediaType(file.remotePath)) {
                                        RemoteMediaType.VIDEO -> {
                                            vm.recordFileAccess(file.remotePath)
                                            onPlayVideo(listOf(file.remotePath), 0)
                                        }
                                        RemoteMediaType.AUDIO -> {
                                            vm.recordFileAccess(file.remotePath)
                                            onPlayAudio(listOf(file.remotePath), 0)
                                        }
                                        RemoteMediaType.IMAGE -> {
                                            vm.recordFileAccess(file.remotePath)
                                            val playlist = vm.files.pathsOfType(RemoteMediaType.IMAGE)
                                            val idx = playlist.indexOf(file.remotePath).takeIf { it >= 0 } ?: 0
                                            onPreviewImage(playlist, idx)
                                        }
                                        RemoteMediaType.DOCUMENT -> {
                                            vm.recordFileAccess(file.remotePath)
                                            onOpenDocument(file.remotePath)
                                        }
                                        RemoteMediaType.UNKNOWN -> { contextFile = file }
                                    }
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) selectionMode = true
                                toggleSelected(file.remotePath)
                            },
                            onSwipeSelect = {
                                if (!selectionMode) selectionMode = true
                                if (!selectedPaths.contains(file.remotePath)) {
                                    toggleSelected(file.remotePath)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }

            // Operation feedback
            if (vm.operationBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            vm.operationResult?.let { msg ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) { Text(msg) }
            }
        }
    }
}

@Composable
private fun CategoryNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(file: FileEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    FileListItem(
        file = file,
        selected = false,
        selectionMode = false,
        onClick = onClick,
        onLongClick = onLongClick,
        onSwipeSelect = {},
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    file: FileEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeSelect: () -> Unit,
) {
    val mediaType = remember(file.remotePath) { classifyRemoteMediaType(file.remotePath) }
    val icon = if (file.isDir) Icons.Default.Folder else {
        when (mediaType) {
            RemoteMediaType.IMAGE -> Icons.Default.Image
            RemoteMediaType.VIDEO -> Icons.Default.VideoFile
            RemoteMediaType.AUDIO -> Icons.Default.AudioFile
            RemoteMediaType.DOCUMENT -> Icons.Default.Description
            RemoteMediaType.UNKNOWN -> Icons.AutoMirrored.Filled.InsertDriveFile
        }
    }
    val cachedThumb: File? = remember(file.remotePath) {
        if (file.isDir) return@remember null
        if (mediaType != RemoteMediaType.IMAGE) return@remember null
        val dir = File(ZhomeApp.instance.cacheDir, "image_preview")
        val baseName = "img_${file.remotePath.hashCode().toUInt().toString(16)}"
        File(dir, "$baseName.bin").takeIf { it.isFile && it.length() > 0L }
    }

    ListItem(
        headlineContent = { Text(file.remotePath.substringAfterLast("/")) },
        supportingContent = {
            if (!file.isDir) Text(formatFileSize(file.size))
        },
        leadingContent = {
            if (cachedThumb != null) {
                AsyncImage(
                    model = cachedThumb.toURI().toString(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
        },
        modifier = Modifier
            .pointerInput(file.remotePath, selectionMode) {
                detectHorizontalDragGestures(
                    onDragEnd = {},
                    onHorizontalDrag = { _, dragAmount ->
                        // Left-swipe to enter multi-select and select this file (Qt-like shortcut).
                        if (dragAmount < -12f && !selectionMode) {
                            onSwipeSelect()
                        }
                    }
                )
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    )
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

@Composable
private fun BottomToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private data class DirNode(
    val name: String,
    val path: String,
    val children: MutableList<DirNode> = mutableListOf(),
)

private fun buildDirTree(dirs: List<String>): DirNode {
    val root = DirNode("MyFiles", "/MyFiles")
    val byPath = mutableMapOf(root.path to root)
    for (d in dirs.sorted()) {
        if (!d.startsWith("/MyFiles")) continue
        val parts = d.removePrefix("/").split('/').filter { it.isNotBlank() }
        var cur = ""
        var parent: DirNode? = null
        for ((idx, p) in parts.withIndex()) {
            cur += "/$p"
            val node = byPath.getOrPut(cur) {
                DirNode(name = p, path = cur).also { n ->
                    val par = parent ?: root
                    if (par.children.none { it.path == n.path }) par.children.add(n)
                }
            }
            parent = node
            if (idx == 0 && node.path != root.path) {
                // Ensure /MyFiles exists as root.
                if (root.children.none { it.path == node.path }) root.children.add(node)
            }
        }
    }
    return root
}

@Composable
private fun DirectoryTreePicker(
    dirs: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val tree = remember(dirs) { buildDirTree(dirs) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp, max = 280.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DirNodeRow(tree, selected, expanded, onSelect, indent = 0)
    }
}

@Composable
private fun DirNodeRow(
    node: DirNode,
    selected: String,
    expanded: MutableMap<String, Boolean>,
    onSelect: (String) -> Unit,
    indent: Int,
) {
    val isExpanded = expanded[node.path] ?: (indent == 0)
    val isSelected = node.path == selected
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 6.dp)
            .padding(start = (indent * 14).dp)
            .clickable { onSelect(node.path) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.children.isNotEmpty()) {
            IconButton(
                onClick = { expanded[node.path] = !isExpanded },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = null
                )
            }
        } else {
            Spacer(Modifier.width(28.dp))
        }
        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            node.name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (isExpanded) {
        node.children.sortedBy { it.name }.forEach { child ->
            DirNodeRow(child, selected, expanded, onSelect, indent = indent + 1)
        }
    }
}

