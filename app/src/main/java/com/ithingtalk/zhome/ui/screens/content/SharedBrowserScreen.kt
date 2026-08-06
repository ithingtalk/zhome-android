package com.ithingtalk.zhome.ui.screens.content

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.RemoteMediaType
import com.ithingtalk.zhome.data.classifyRemoteMediaType
import com.ithingtalk.zhome.data.local.db.FileEntity
import com.ithingtalk.zhome.data.remote.nas.NasCommands
import com.ithingtalk.zhome.data.remote.nas.NasUserDbSync
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DecimalFormat

class SharedBrowserViewModel : ViewModel() {
    private val fileRepo = ZhomeApp.instance.fileRepo
    private val prefs = ZhomeApp.instance.prefs
    private val deviceRepo = ZhomeApp.instance.deviceRepo
    private val transferRepo = ZhomeApp.instance.transferRepo
    private val appCtx = ZhomeApp.instance.applicationContext

    var files by mutableStateOf<List<FileEntity>>(emptyList()); private set
    var isLoading by mutableStateOf(false); private set
    var operationBusy by mutableStateOf(false); private set
    var operationResult by mutableStateOf<String?>(null); private set

    private var job: kotlinx.coroutines.Job? = null

    private data class SharedPathParts(
        val shareUser: String,
        val myFilesPath: String, // starts with "/MyFiles"
    )

    private fun parseSharedPath(remotePath: String): SharedPathParts? {
        // Expected: "/Ftp/<user>/MyFiles/..."
        val parts = remotePath.split('/', ignoreCase = false, limit = 0)
        // parts like ["", "Ftp", "<user>", "MyFiles", ...]
        if (parts.size < 4) return null
        val ftpIdx = parts.indexOfFirst { it == "Ftp" }
        if (ftpIdx < 0 || ftpIdx + 2 >= parts.size) return null
        val user = parts[ftpIdx + 1]
        if (user.isBlank()) return null
        val myIdx = parts.indexOfFirst { it == "MyFiles" }
        if (myIdx < 0 || myIdx >= parts.size) return null
        val tail = parts.drop(myIdx).joinToString("/") // "MyFiles/Doc/a.txt"
        return SharedPathParts(shareUser = user, myFilesPath = "/$tail")
    }

    fun load(dir: String, mine: Boolean) {
        job?.cancel()
        viewModelScope.launch {
            isLoading = true
            val user = prefs.getUser()
            if (user.isBlank()) {
                files = emptyList()
                isLoading = false
                return@launch
            }
            val owner = "__shared__${user}"
            job = launch {
                fileRepo.observeAll(owner).collectLatest { all ->
                    val requestDir = (if (dir.isBlank()) "/MyFiles" else dir).trimEnd('/')
                    val prefix = "$requestDir/"

                    // 1) Partition by share-user (Qt: bMyfile = remotePath.contains(LocalSettings::getUser())) but we do stricter parse.
                    val parsed = all.mapNotNull { e -> parseSharedPath(e.remotePath)?.let { it to e } }
                    val byTab = if (mine) parsed.filter { it.first.shareUser == user } else parsed.filter { it.first.shareUser != user }

                    // 2) List first-level children under requestDir, preserving full remotePath (/Ftp/<user>/MyFiles/...).
                    val seenKeys = mutableSetOf<String>() // key = "<shareUser>|<childKey>"
                    val out = ArrayList<FileEntity>()
                    for ((sp, e) in byTab) {
                        val n = sp.myFilesPath
                        if (!(n == requestDir || n.startsWith(prefix))) continue
                        if (requestDir == "/MyFiles" && n.contains("/MyFiles/.")) continue // Qt skips hidden under root

                        val rest = when {
                            n == requestDir -> ""
                            n.startsWith(prefix) -> n.removePrefix(prefix)
                            else -> ""
                        }
                        if (rest.isEmpty()) continue

                        val childName = rest.substringBefore("/")
                        val childMy = "$requestDir/$childName"
                        val key = "${sp.shareUser}|$childMy"
                        if (!seenKeys.add(key)) continue

                        // Prefer exact dir/file row if present for this user+child, else synthesize a dir.
                        val exact = byTab.firstOrNull { (sp2, e2) -> sp2.shareUser == sp.shareUser && sp2.myFilesPath.trimEnd('/') == childMy.trimEnd('/') }?.second
                        out.add(
                            exact ?: FileEntity(
                                remotePath = "/Ftp/${sp.shareUser}$childMy",
                                size = 0,
                                date = e.date,
                                isDir = true,
                                owner = owner,
                            )
                        )
                    }
                    files = out.sortedWith(compareByDescending<FileEntity> { it.isDir }.thenBy { it.remotePath })
                    isLoading = false
                }
            }
        }
    }

    fun cancelShare(paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            operationBusy = true
            operationResult = null
            try {
                val device = deviceRepo.getCurrent() ?: run {
                    operationResult = appCtx.getString(R.string.file_no_device)
                    return@launch
                }
                val user = prefs.getUser()
                val pass = prefs.getPass()
                val cmd = NasCommands.deleteShared(user, pass, paths)
                val resp = deviceRepo.postDeviceCommand(device, cmd)
                if (!NasCommands.deleteSharedSuccess(resp)) {
                    operationResult = appCtx.getString(R.string.shared_unshare_failed)
                    return@launch
                }
                // Immediate UI update: remove subtree from shared owner namespace.
                val sharedOwner = "__shared__${user}"
                for (p in paths) fileRepo.deleteSubtree(sharedOwner, p)
                operationResult = appCtx.getString(R.string.shared_snackbar_ok)
            } catch (e: Exception) {
                operationResult = e.message ?: appCtx.getString(R.string.shared_err_generic)
                Log.e("SharedBrowser", "cancelShare failed", e)
            } finally {
                operationBusy = false
            }
        }
    }

    fun download(paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            operationBusy = true
            operationResult = null
            try {
                val device = deviceRepo.getCurrent() ?: run {
                    operationResult = appCtx.getString(R.string.file_no_device)
                    return@launch
                }
                val files = paths.filter { p -> files.firstOrNull { it.remotePath == p }?.isDir != true }
                if (files.isEmpty()) {
                    operationResult = appCtx.getString(R.string.shared_no_files_selected)
                    return@launch
                }
                for (p in files) transferRepo.addDownload(device.mac, p)
                operationResult = appCtx.getString(R.string.shared_added_download_queue)
            } catch (e: Exception) {
                operationResult = e.message ?: appCtx.getString(R.string.shared_err_generic)
                Log.e("SharedBrowser", "download failed", e)
            } finally {
                operationBusy = false
            }
        }
    }

    fun refreshSharedDb() {
        viewModelScope.launch {
            operationBusy = true
            operationResult = null
            try {
                val device = deviceRepo.getCurrent() ?: run {
                    operationResult = appCtx.getString(R.string.file_no_device)
                    return@launch
                }
                val ok = NasUserDbSync.syncSharedDbOnly(device.mac)
                operationResult = if (ok) {
                    appCtx.getString(R.string.shared_refresh_done)
                } else {
                    appCtx.getString(R.string.shared_refresh_failed)
                }
            } catch (e: Exception) {
                operationResult = e.message ?: appCtx.getString(R.string.shared_err_generic)
                Log.e("SharedBrowser", "refreshSharedDb failed", e)
            } finally {
                operationBusy = false
            }
        }
    }

    fun clearResult() { operationResult = null }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SharedBrowserScreen(
    dir: String,
    mine: Boolean,
    onNavigate: (dir: String, mine: Boolean) -> Unit,
    onPlayVideo: (paths: List<String>, startIndex: Int) -> Unit,
    onPlayAudio: (paths: List<String>, startIndex: Int) -> Unit,
    onPreviewImage: (List<String>, Int) -> Unit,
    onOpenDocument: (String) -> Unit,
    onOpenTransfers: () -> Unit,
    onGoHome: () -> Unit = {},
    onBack: () -> Unit,
    vm: SharedBrowserViewModel = viewModel(),
) {
    var tabMine by rememberSaveable { mutableStateOf(mine) }
    LaunchedEffect(dir, tabMine) { vm.load(dir, tabMine) }

    var selectionMode by rememberSaveable(dir, mine) { mutableStateOf(false) }
    var selectedPaths by rememberSaveable(dir, mine) { mutableStateOf(emptySet<String>()) }
    fun clearSelection() { selectionMode = false; selectedPaths = emptySet() }
    fun toggleSelected(path: String) {
        selectedPaths = if (selectedPaths.contains(path)) selectedPaths - path else selectedPaths + path
        if (selectedPaths.isEmpty()) selectionMode = false
    }

    var cancelTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCancelConfirm by remember { mutableStateOf(false) }

    if (showCancelConfirm) {
        val n = cancelTargets.size
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text(stringResource(R.string.shared_cancel_share_title)) },
            text = { Text(stringResource(R.string.shared_cancel_share_body, n)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelConfirm = false
                        vm.cancelShare(cancelTargets)
                        clearSelection()
                    },
                    enabled = cancelTargets.isNotEmpty() && !vm.operationBusy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    vm.operationResult?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2000)
            vm.clearResult()
        }
    }

    val title = if (dir == "/MyFiles" || dir.isBlank()) {
        stringResource(R.string.shared_root_title)
    } else {
        dir.substringAfterLast("/")
    }
    val isRoot = dir == "/MyFiles" || dir.isBlank()

    var didInitialRefresh by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isRoot) {
        if (isRoot && !didInitialRefresh) {
            didInitialRefresh = true
            vm.refreshSharedDb()
        }
    }

    Scaffold(
        topBar = {
            Column {
                if (selectionMode) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.shared_selected_count, selectedPaths.size)) },
                        navigationIcon = {
                            IconButton(onClick = { clearSelection() }) {
                                Icon(Icons.Default.Close, stringResource(R.string.shared_cd_close_select))
                            }
                        },
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
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    stringResource(R.string.common_back),
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = onGoHome) {
                                Icon(Icons.Default.Home, stringResource(R.string.file_cd_home))
                            }
                            IconButton(onClick = { vm.refreshSharedDb() }) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.file_cd_refresh))
                            }
                            IconButton(onClick = onOpenTransfers) {
                                Icon(Icons.Default.SwapVert, stringResource(R.string.file_cd_transfers))
                            }
                        }
                    )
                }
                if (isRoot) {
                    TabRow(selectedTabIndex = if (tabMine) 1 else 0) {
                        Tab(
                            selected = !tabMine,
                            onClick = { clearSelection(); tabMine = false },
                            text = { Text(stringResource(R.string.shared_tab_browse)) }
                        )
                        Tab(
                            selected = tabMine,
                            onClick = { clearSelection(); tabMine = true },
                            text = { Text(stringResource(R.string.shared_tab_mine)) }
                        )
                    }
                }
            }
        },
        bottomBar = {
            when {
                selectionMode -> {
                    BottomAppBar {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (tabMine) {
                                TextButton(
                                    onClick = {
                                        cancelTargets = selectedPaths.toList()
                                        showCancelConfirm = true
                                    },
                                    enabled = selectedPaths.isNotEmpty() && !vm.operationBusy,
                                ) {
                                    Icon(Icons.Default.LinkOff, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.shared_action_unshare))
                                }
                            } else {
                                TextButton(
                                    onClick = { vm.download(selectedPaths.toList()); clearSelection() },
                                    enabled = selectedPaths.isNotEmpty() && !vm.operationBusy,
                                ) {
                                    Icon(Icons.Default.Download, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.shared_action_download))
                                }
                            }
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
                                onClick = { onNavigate("/MyFiles/Image", tabMine) },
                            )
                            CategoryNavButton(
                                icon = Icons.Default.VideoLibrary,
                                label = stringResource(R.string.file_type_video),
                                onClick = { onNavigate("/MyFiles/Video", tabMine) },
                            )
                            CategoryNavButton(
                                icon = Icons.Default.Audiotrack,
                                label = stringResource(R.string.file_type_audio),
                                onClick = { onNavigate("/MyFiles/Audio", tabMine) },
                            )
                            CategoryNavButton(
                                icon = Icons.Default.Description,
                                label = stringResource(R.string.file_type_doc),
                                onClick = { onNavigate("/MyFiles/Doc", tabMine) },
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                vm.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                vm.files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.shared_no_files), color = MaterialTheme.colorScheme.outline)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.files, key = { it.remotePath }) { file ->
                        fun parseParts(p: String): Triple<String, String, String> {
                            // returns (shareUser, myFilesPath, displayName)
                            val parts = p.split('/')
                            val ftpIdx = parts.indexOfFirst { it == "Ftp" }
                            val u = if (ftpIdx >= 0 && ftpIdx + 1 < parts.size) parts[ftpIdx + 1] else ""
                            val myIdx = parts.indexOfFirst { it == "MyFiles" }
                            val my = if (myIdx >= 0) "/" + parts.drop(myIdx).joinToString("/") else p
                            val name = my.substringAfterLast("/").ifBlank { p.substringAfterLast("/") }
                            return Triple(u, my, name)
                        }
                        val (shareUser, myFilesPath, displayName) = parseParts(file.remotePath)

                        val swipeModifier = Modifier.pointerInput(selectionMode, file.remotePath) {
                            detectHorizontalDragGestures { _, dragAmount ->
                                // Left swipe to enter/select.
                                if (dragAmount < -30f) {
                                    if (!selectionMode) selectionMode = true
                                    selectedPaths = selectedPaths + file.remotePath
                                }
                            }
                        }
                        ListItem(
                            headlineContent = { Text(displayName) },
                            supportingContent = {
                                val sizeStr = if (!file.isDir) formatFileSize(file.size) else ""
                                val line = listOf(sizeStr.takeIf { it.isNotBlank() }, shareUser.takeIf { it.isNotBlank() }).filterNotNull().joinToString("  ")
                                if (line.isNotBlank()) Text(line)
                            },
                            leadingContent = {
                                val icon = if (file.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile
                                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                if (selectionMode) Checkbox(checked = selectedPaths.contains(file.remotePath), onCheckedChange = { toggleSelected(file.remotePath) })
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        toggleSelected(file.remotePath); return@combinedClickable
                                    }
                                    if (file.isDir) onNavigate(myFilesPath, tabMine)
                                    else {
                                        when (classifyRemoteMediaType(file.remotePath)) {
                                            RemoteMediaType.VIDEO -> onPlayVideo(listOf(file.remotePath), 0)
                                            RemoteMediaType.AUDIO -> onPlayAudio(listOf(file.remotePath), 0)
                                            RemoteMediaType.IMAGE -> onPreviewImage(listOf(file.remotePath), 0)
                                            RemoteMediaType.DOCUMENT -> onOpenDocument(file.remotePath)
                                            RemoteMediaType.UNKNOWN -> {
                                                val parent = file.remotePath.substringBeforeLast("/", "")
                                                if (parent.isNotEmpty()) onNavigate(parent, tabMine)
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) selectionMode = true
                                    toggleSelected(file.remotePath)
                                }
                            ).then(swipeModifier)
                        )
                        HorizontalDivider()
                    }
                }
            }

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
    icon: ImageVector,
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

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

