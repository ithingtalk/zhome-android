package com.ithingtalk.zhome.ui.screens.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.local.db.TransferEntity
import com.ithingtalk.zhome.data.repository.TransferRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable

class TransferViewModel : ViewModel() {
    private val repo = ZhomeApp.instance.transferRepo
    var uploads by mutableStateOf<List<TransferEntity>>(emptyList()); private set
    var downloads by mutableStateOf<List<TransferEntity>>(emptyList()); private set

    init {
        viewModelScope.launch { repo.observeUploads().collectLatest { uploads = it } }
        viewModelScope.launch { repo.observeDownloads().collectLatest { downloads = it } }
    }

    fun refresh() {
        // Repository already exposes Flow; this forces a one-shot reload for "manual refresh".
        viewModelScope.launch {
            uploads = repo.getUploads()
            downloads = repo.getDownloads()
        }
    }

    fun deleteTransfer(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch { repo.deleteAll() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(onBack: () -> Unit, vm: TransferViewModel = viewModel()) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var initialTabApplied by remember { mutableStateOf(false) }
    var didAutoRefresh by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!didAutoRefresh) {
            didAutoRefresh = true
            vm.refresh()
        }
    }

    // 打开页面时：仅上传非空 → 上传页；上传空且下载非空 → 下载页；都空或都非空 → 默认（上传页）
    LaunchedEffect(vm.uploads, vm.downloads) {
        if (initialTabApplied) return@LaunchedEffect
        val u = vm.uploads.isNotEmpty()
        val d = vm.downloads.isNotEmpty()
        if (!u && !d) return@LaunchedEffect
        initialTabApplied = true
        tabIndex = when {
            u && !d -> 0
            !u && d -> 1
            else -> 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.transfer_cd_refresh))
                    }
                    IconButton(onClick = { vm.clearAll() }) {
                        Icon(Icons.Default.DeleteSweep, stringResource(R.string.transfer_cd_clear_all))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 },
                    text = { Text(stringResource(R.string.transfer_tab_uploads, vm.uploads.size)) })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 },
                    text = { Text(stringResource(R.string.transfer_tab_downloads, vm.downloads.size)) })
            }

            val items = if (tabIndex == 0) vm.uploads else vm.downloads

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.transfer_empty), color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn {
                    items(items, key = { it.id }) { t ->
                        val statusText = when (t.status) {
                            TransferRepository.STATUS_QUEUED -> stringResource(R.string.transfer_status_queued)
                            TransferRepository.STATUS_RUNNING -> stringResource(R.string.transfer_status_running)
                            TransferRepository.STATUS_SUCCESS -> stringResource(R.string.transfer_status_success)
                            TransferRepository.STATUS_ERROR -> stringResource(R.string.transfer_status_error)
                            TransferRepository.STATUS_STOPPED -> stringResource(R.string.transfer_status_stopped)
                            else -> stringResource(R.string.common_unknown)
                        }
                        val progressPct = transferProgressPercentInt(t)
                        ListItem(
                            headlineContent = { Text(t.remotePath.substringAfterLast("/")) },
                            supportingContent = {
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(statusText)
                                        if (t.status == TransferRepository.STATUS_QUEUED ||
                                            t.status == TransferRepository.STATUS_RUNNING
                                        ) {
                                            Text(
                                                "${progressPct}%",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    if (t.status == TransferRepository.STATUS_RUNNING) {
                                        LinearProgressIndicator(
                                            progress = { (progressPct / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                        )
                                    }
                                    if (t.status == TransferRepository.STATUS_ERROR && t.error.isNotBlank()) {
                                        Text(t.error, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            leadingContent = {
                                Icon(if (tabIndex == 0) Icons.Default.Upload else Icons.Default.Download, null,
                                    tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                IconButton(onClick = { vm.deleteTransfer(t.id) }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.transfer_cd_remove))
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** 0–100 for UI; prefers byte ratio when total is known, else [TransferEntity.progressPercent]. */
private fun transferProgressPercentInt(t: TransferEntity): Int =
    when {
        t.totalBytes > 0L ->
            ((t.transferredBytes.coerceAtLeast(0) * 100L) / t.totalBytes).toInt().coerceIn(0, 100)
        else ->
            t.progressPercent.toInt().coerceIn(0, 100)
    }

