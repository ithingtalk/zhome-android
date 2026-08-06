package com.ithingtalk.zhome.ui.screens.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.remote.nas.ConnectStage
import com.ithingtalk.zhome.data.remote.nas.NasUserDbSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun ConnectingDeviceScreen(
    mac: String,
    onConnected: (String) -> Unit,
    onBackToDevices: () -> Unit,
    onOpenDeviceManagement: (String) -> Unit,
) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf(ConnectStage.ConnectingRemote) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(mac) {
        error = null
        done = false
        stage = ConnectStage.ConnectingRemote
        // Give UI a chance to render before heavy work.
        delay(80)

        // Mark current device immediately so downstream screens are consistent.
        runCatching { ZhomeApp.instance.deviceRepo.setCurrent(mac) }

        val result = try {
            NasUserDbSync.syncFromDevice(mac) { st -> stage = st }
        } catch (e: CancellationException) {
            NasUserDbSync.SyncFromDeviceResult.Error(
                context.getString(R.string.sync_connection_interrupted),
            )
        } catch (e: Exception) {
            NasUserDbSync.SyncFromDeviceResult.Error(e.message ?: context.getString(R.string.video_load_failed))
        }

        when (result) {
            NasUserDbSync.SyncFromDeviceResult.Success -> {
                stage = ConnectStage.Finishing
                done = true
                onConnected(mac)
            }
            is NasUserDbSync.SyncFromDeviceResult.Error -> {
                error = result.message
            }
            NasUserDbSync.SyncFromDeviceResult.NeedAdminApproval -> {
                error = context.getString(R.string.devices_pending_approval)
            }
            NasUserDbSync.SyncFromDeviceResult.WrongPassword -> {
                error = context.getString(R.string.sync_wrong_password)
            }
            NasUserDbSync.SyncFromDeviceResult.DiskUninitialized -> {
                error = context.getString(R.string.devices_disk_not_ready)
            }
        }
    }

    val title = when (stage) {
        ConnectStage.ConnectingRemote -> stringResource(R.string.connect_stage_connecting_remote)
        ConnectStage.UserLogin -> stringResource(R.string.connect_stage_user_login)
        ConnectStage.GetStatus -> stringResource(R.string.connect_stage_get_status)
        ConnectStage.DownloadFileDb -> stringResource(R.string.connect_stage_download_file_db)
        ConnectStage.ImportFileDb -> stringResource(R.string.connect_stage_import_file_db)
        ConnectStage.DownloadSharedDb -> stringResource(R.string.connect_stage_download_shared_db)
        ConnectStage.ImportSharedDb -> stringResource(R.string.connect_stage_import_shared_db)
        ConnectStage.Finishing -> stringResource(R.string.connect_stage_finishing)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!done && error == null) {
            CircularProgressIndicator()
        } else {
            Box(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBackToDevices) {
                    Text(stringResource(R.string.connect_action_back_to_devices))
                }
                if (it == context.getString(R.string.devices_disk_not_ready)) {
                    Button(onClick = { onOpenDeviceManagement(mac) }) {
                        Text(stringResource(R.string.connect_action_open_device_mgmt))
                    }
                }
            }
        }
    }
}

