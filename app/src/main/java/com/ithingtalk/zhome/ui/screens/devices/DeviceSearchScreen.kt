package com.ithingtalk.zhome.ui.screens.devices

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.network.DiscoveredDevice
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSearchScreen(
    onDeviceFound: () -> Unit,
    onConfigureNew: (DiscoveredDevice) -> Unit,
    onBack: () -> Unit,
    vm: DevicesViewModel = viewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.searchLocalDevices() }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result?.contents?.trim().orEmpty()
        if (text.isNotEmpty()) {
            vm.addDeviceFromQrScan(text) { err ->
                if (err == null) onDeviceFound()
            }
        }
    }

    vm.scanQrError?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::clearScanQrError,
            title = { Text(stringResource(R.string.search_cannot_add_title)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = vm::clearScanQrError) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title_add_device)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.search_cd_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.searchLocalDevices() },
                        enabled = !vm.isSearching,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.search_cd_refresh_scan),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (vm.isSearching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.search_scanning_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            when {
                vm.discoveredDevices.isEmpty() && !vm.isSearching -> {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.search_no_lan_devices), color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { vm.searchLocalDevices() }) {
                                Text(stringResource(R.string.search_rescan_lan))
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        items(vm.discoveredDevices, key = { it.mac }) { device ->
                            ListItem(
                                headlineContent = { Text(device.name.ifBlank { device.mac }) },
                                supportingContent = {
                                    Text(
                                        stringResource(
                                            R.string.search_device_mac_ip,
                                            device.mac,
                                            device.ip,
                                        ),
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Router,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    val needsConfigure = Constants.deviceNeedsConfigure(device.cfg)
                                    TextButton(
                                        onClick = {
                                            if (needsConfigure) onConfigureNew(device)
                                            else {
                                                vm.addDevice(device)
                                                onDeviceFound()
                                            }
                                        },
                                    ) {
                                        Text(
                                            if (needsConfigure) {
                                                stringResource(R.string.search_action_configure)
                                            } else {
                                                stringResource(R.string.search_action_add)
                                            },
                                        )
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.search_qr_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            scanLauncher.launch(
                                ScanOptions().apply {
                                    setDesiredBarcodeFormats(listOf(ScanOptions.QR_CODE))
                                    setPrompt(context.getString(R.string.search_qr_prompt))
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.search_scan_qr_button))
                    }
                }
            }
        }
    }
}
