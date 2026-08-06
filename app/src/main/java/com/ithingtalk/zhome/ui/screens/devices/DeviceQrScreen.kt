package com.ithingtalk.zhome.ui.screens.devices

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.local.db.DeviceEntity
import com.ithingtalk.zhome.util.DeviceQrPayload
import com.ithingtalk.zhome.util.QrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceQrScreen(
    mac: String,
    onBack: () -> Unit,
) {
    val repo = remember { ZhomeApp.instance.deviceRepo }
    var device by remember { mutableStateOf<DeviceEntity?>(null) }
    LaunchedEffect(mac) {
        device = withContext(Dispatchers.IO) { repo.getByMac(mac) }
    }
    val d = device
    val payload = remember(d) {
        d?.let { DeviceQrPayload.encodeV2FromEntity(it) }
    }
    val bitmap = remember(payload) {
        payload?.let { QrBitmap.encode(it, 480) }
    }

    val titleText = remember(d, mac) {
        when {
            d != null -> d.name.ifBlank { d.mac }
            else -> mac
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleText) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                d == null -> Text(stringResource(R.string.device_qr_not_found), color = MaterialTheme.colorScheme.outline)
                bitmap == null -> Text(
                    stringResource(R.string.device_qr_missing_mac_sn),
                    color = MaterialTheme.colorScheme.outline,
                )
                else -> {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Device QR",
                        modifier = Modifier.size(280.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.device_qr_share_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
