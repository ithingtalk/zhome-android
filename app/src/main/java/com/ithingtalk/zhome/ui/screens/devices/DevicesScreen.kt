package com.ithingtalk.zhome.ui.screens.devices

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.data.local.db.DeviceEntity
import com.ithingtalk.zhome.ui.screens.auth.AuthViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onDeviceClick: (String) -> Unit,
    onConfigureDevice: (DeviceEntity) -> Unit,
    onAddDevice: () -> Unit,
    /** After setting current device — open disk / name / admin password screen. */
    onOpenDeviceManagement: (String) -> Unit,
    /** After setting current device — open user allow/reject/delete screen. */
    onOpenUserManagement: (String) -> Unit,
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
    vm: DevicesViewModel = viewModel(),
    authVm: AuthViewModel = viewModel()
) {
    var showMenu by remember { mutableStateOf(false) }
    /** MAC for bottom sheet: 设备管理 vs 用户管理 */
    var manageChoiceMac by remember { mutableStateOf<String?>(null) }
    var offlineDevice by remember { mutableStateOf<DeviceEntity?>(null) }
    var showEmptyConfirm by remember { mutableStateOf(false) }
    var deleteConfirmDevice by remember { mutableStateOf<DeviceEntity?>(null) }
    var showSignOutConfirm by remember { mutableStateOf(false) }

    // Full refresh when entering or returning to the device list (aligned with iOS DeviceListView.onAppear).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshDevices()
    }

    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            title = { Text(stringResource(R.string.devices_empty_all_title)) },
            text = { Text(stringResource(R.string.devices_empty_all_body)) },
            confirmButton = {
                TextButton(onClick = { showEmptyConfirm = false; vm.deleteAllDevices() }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text(stringResource(R.string.devices_sign_out_dialog_title)) },
            text = { Text(stringResource(R.string.devices_sign_out_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showSignOutConfirm = false; authVm.signOut(onSignOut) }) {
                    Text(stringResource(R.string.devices_sign_out_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    deleteConfirmDevice?.let { dev ->
        val removeName = dev.name.ifBlank { dev.mac }
        AlertDialog(
            onDismissRequest = { deleteConfirmDevice = null },
            title = { Text(stringResource(R.string.devices_remove_title)) },
            text = { Text(stringResource(R.string.devices_remove_body, removeName)) },
            confirmButton = {
                TextButton(onClick = { deleteConfirmDevice = null; vm.deleteDevice(dev.mac) }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmDevice = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    offlineDevice?.let {
        AlertDialog(
            onDismissRequest = { offlineDevice = null },
            title = { Text(stringResource(R.string.devices_offline_title)) },
            text = { Text(stringResource(R.string.devices_offline_body)) },
            confirmButton = {
                TextButton(onClick = { offlineDevice = null; onSettings() }) {
                    Text(stringResource(R.string.devices_go_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { offlineDevice = null }) { Text(stringResource(R.string.devices_got_it)) }
            }
        )
    }

    manageChoiceMac?.let { sheetMac ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { manageChoiceMac = null },
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.devices_sheet_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.devices_disk_not_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        manageChoiceMac = null
                        onOpenDeviceManagement(sheetMac)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.content_menu_device_mgmt)) }
                Button(
                    onClick = {
                        manageChoiceMac = null
                        onOpenUserManagement(sheetMac)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.content_menu_user_mgmt)) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_screen_title)) },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.devices_cd_menu))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.devices_menu_add_new)) },
                            onClick = { showMenu = false; onAddDevice() },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.devices_menu_empty_list)) },
                            onClick = { showMenu = false; showEmptyConfirm = true },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_refresh)) },
                            onClick = { showMenu = false; vm.refreshDevicesManual() },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_settings)) },
                            onClick = { showMenu = false; onSettings() },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.devices_menu_sign_out)) },
                            onClick = { showMenu = false; showSignOutConfirm = true },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) },
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddDevice) {
                Icon(Icons.Default.Add, stringResource(R.string.devices_cd_add_device))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.devices.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.DevicesOther, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.devices_empty_state), color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onAddDevice) { Text(stringResource(R.string.devices_add_cta)) }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.devices, key = { it.mac }) { device ->
                        val needsConfigure = Constants.deviceNeedsConfigure(device.cfg)
                        val runtimeIp = vm.runtimeIpFor(device.mac)
                        val localStatus = vm.localStatus(device)
                        val remoteStatus = vm.remoteStatus(device)

                        RevealSwipeItem(
                            onManage = { manageChoiceMac = device.mac },
                            onDelete = { deleteConfirmDevice = device },
                        ) {
                            Surface {
                                ListItem(
                                    headlineContent = { Text(device.name.ifBlank { device.mac }) },
                                    supportingContent = {
                                        if (needsConfigure) {
                                            Text(stringResource(R.string.devices_not_configured), color = Color(0xFFEF6C00))
                                        } else {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                DeviceStatusLine(
                                                    label = stringResource(R.string.devices_status_local),
                                                    status = localStatus,
                                                    runtimeIp = runtimeIp,
                                                    isLocalLine = true,
                                                )
                                                DeviceStatusLine(
                                                    label = stringResource(R.string.devices_status_remote),
                                                    status = remoteStatus,
                                                    runtimeIp = "",
                                                    isLocalLine = false,
                                                )
                                            }
                                        }
                                    },
                                    leadingContent = { Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary) },
                                    trailingContent = {
                                        if (vm.isUserApprovalPending(device.mac)) {
                                            Text(
                                                stringResource(R.string.devices_pending_approval),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = Color(0xFFEF6C00),
                                            )
                                        }
                                    },
                                    modifier = Modifier.clickable {
                                        vm.selectDevice(device.mac)
                                        when {
                                            needsConfigure -> onConfigureDevice(device)
                                            vm.canConnect(device) -> onDeviceClick(device.mac)
                                            vm.isStatusChecking(device) -> { /* wait for status */ }
                                            else -> offlineDevice = device
                                        }
                                    }
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceStatusLine(
    label: String,
    status: DeviceChannelStatus,
    runtimeIp: String,
    isLocalLine: Boolean,
) {
    val statusText = when (status) {
        DeviceChannelStatus.Checking -> stringResource(R.string.devices_status_checking)
        DeviceChannelStatus.Online -> {
            if (isLocalLine && runtimeIp.isNotBlank()) {
                stringResource(R.string.devices_status_local_online_ip, runtimeIp)
            } else {
                stringResource(R.string.devices_status_online)
            }
        }
        DeviceChannelStatus.Offline -> stringResource(R.string.devices_status_offline)
    }
    val color = when (status) {
        DeviceChannelStatus.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
        DeviceChannelStatus.Online ->
            if (isLocalLine) Color(0xFF1976D2) else Color(0xFFEF6C00)
        DeviceChannelStatus.Offline -> MaterialTheme.colorScheme.outline
    }
    Text(
        text = label + statusText,
        style = MaterialTheme.typography.bodySmall,
        color = color,
    )
}

@Composable
private fun RevealSwipeItem(
    onManage: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val buttonWidthPx = with(density) { 80.dp.toPx() }
    val totalReveal = buttonWidthPx * 2
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {
        Row(
            Modifier
                .matchParentSize(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(80.dp)
                    .background(Color(0xFF2196F3))
                    .clickable {
                        scope.launch { offsetX.animateTo(0f) }
                        onManage()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Build, null, tint = Color.White)
                    Text(stringResource(R.string.devices_swipe_manage), color = Color.White, fontSize = 12.sp)
                }
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(80.dp)
                    .background(Color(0xFFFF6347))
                    .clickable {
                        scope.launch { offsetX.animateTo(0f) }
                        onDelete()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Delete, null, tint = Color.White)
                    Text(stringResource(R.string.devices_swipe_delete), color = Color.White, fontSize = 12.sp)
                }
            }
        }

        Surface(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            val newVal = (offsetX.value + delta).coerceIn(-totalReveal, 0f)
                            offsetX.snapTo(newVal)
                        }
                    },
                    onDragStopped = {
                        scope.launch {
                            val target = if (offsetX.value < -totalReveal / 2) -totalReveal else 0f
                            offsetX.animateTo(target, spring(stiffness = Spring.StiffnessMedium))
                        }
                    }
                )
        ) {
            content()
        }
    }
}
