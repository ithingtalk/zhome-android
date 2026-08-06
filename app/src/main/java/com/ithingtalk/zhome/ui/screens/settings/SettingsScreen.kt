package com.ithingtalk.zhome.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import com.ithingtalk.zhome.data.remote.nas.NasCommands
import android.util.Log
import com.ithingtalk.zhome.data.repository.DeviceLinkMode
import com.ithingtalk.zhome.jni.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel : ViewModel() {
    private val tag = "SettingsVM"
    private val prefs = ZhomeApp.instance.prefs
    private val authRepo = ZhomeApp.instance.authRepo
    private val deviceRepo = ZhomeApp.instance.deviceRepo
    private val appCtx = ZhomeApp.instance.applicationContext

    var username by mutableStateOf(""); private set
    var nickname by mutableStateOf("")
    var savedNickname by mutableStateOf(""); private set
    var isSavingNickname by mutableStateOf(false); private set
    var fontSizeIdx by mutableIntStateOf(0); private set
    var forceP2p by mutableStateOf(false); private set
    var iceGatherMode by mutableIntStateOf(com.ithingtalk.zhome.Constants.IceGatherMode.BOTH); private set
    var hasPassword by mutableStateOf(false); private set
    var operationResult by mutableStateOf<String?>(null); private set

    init {
        viewModelScope.launch {
            prefs.observeIceGatherMode().collect { iceGatherMode = it }
        }
    }

    fun load() {
        viewModelScope.launch {
            username = prefs.getUser()
            savedNickname = prefs.getUserNickname()
            nickname = savedNickname
            fontSizeIdx = prefs.getFontSizeIdx()
            forceP2p = prefs.getForceP2p()
            iceGatherMode = prefs.getIceGatherMode()
            hasPassword = prefs.getPass().isNotBlank()
        }
    }

    fun saveNickname(trimmed: String) {
        if (isSavingNickname || trimmed == savedNickname) return
        viewModelScope.launch {
            isSavingNickname = true
            operationResult = null
            try {
                val mac = prefs.getCurrDeviceMac()
                if (mac.isBlank()) {
                    operationResult = appCtx.getString(R.string.settings_nickname_no_device)
                    return@launch
                }
                val device = withContext(Dispatchers.IO) { deviceRepo.getByMac(mac) }
                if (device == null) {
                    operationResult = appCtx.getString(R.string.settings_nickname_no_device)
                    return@launch
                }
                val linkMode = deviceRepo.resolveLinkMode(device, prefs.getForceP2p())
                if (linkMode == DeviceLinkMode.Offline) {
                    operationResult = appCtx.getString(R.string.sync_device_offline)
                    return@launch
                }
                val user = prefs.getUser()
                val pass = prefs.getPass()
                val resp = withContext(Dispatchers.IO) {
                    deviceRepo.postDeviceCommand(device, NasCommands.userSetNickname(user, pass, trimmed))
                }
                when {
                    NasCommands.setUserNicknameSuccess(resp) -> {
                        prefs.setUserNickname(trimmed)
                        savedNickname = trimmed
                        nickname = trimmed
                        operationResult = appCtx.getString(R.string.settings_nickname_saved)
                    }
                    NasCommands.setUserNicknameFail(resp) -> {
                        Log.w(tag, "set_user_nickname=fail link=$linkMode preview=${resp.take(400)}")
                        operationResult = appCtx.getString(R.string.settings_nickname_save_failed)
                    }
                    NasCommands.userLoginResult(resp).isNotBlank() -> {
                        Log.w(
                            tag,
                            "set nickname got login response link=$linkMode login=${NasCommands.userLoginResult(resp)}",
                        )
                        operationResult = when {
                            NasCommands.userLoginNeedAllow(resp) ->
                                appCtx.getString(R.string.devices_pending_approval)
                            NasCommands.userLoginFail(resp) ->
                                appCtx.getString(R.string.sync_wrong_password)
                            else -> appCtx.getString(R.string.settings_nickname_save_failed)
                        }
                    }
                    else -> {
                        Log.w(tag, "set nickname unexpected link=$linkMode preview=${resp.take(400)}")
                        operationResult = appCtx.getString(R.string.settings_nickname_save_failed)
                    }
                }
            } catch (e: Exception) {
                operationResult = e.message ?: appCtx.getString(R.string.settings_nickname_save_failed)
            } finally {
                isSavingNickname = false
            }
        }
    }

    fun fontLabelAt(idx: Int): String {
        val arr = appCtx.resources.getStringArray(R.array.settings_font_size_labels)
        return arr.getOrElse(idx.coerceIn(0, arr.lastIndex)) { arr.firstOrNull().orEmpty() }
    }

    fun updateFontSize(idx: Int) {
        fontSizeIdx = idx
        viewModelScope.launch { prefs.setFontSizeIdx(idx) }
    }

    fun updateForceP2p(v: Boolean) {
        forceP2p = v
        viewModelScope.launch { prefs.setForceP2p(v) }
    }

    fun updateIceGatherMode(v: Int) {
        val c = com.ithingtalk.zhome.Constants.IceGatherMode.clamp(v)
        iceGatherMode = c
        viewModelScope.launch {
            prefs.setIceGatherMode(c)
            withContext(Dispatchers.IO) {
                runCatching<Unit> { NativeBridge.libp2pUpdateIceGatherMode(c) }
            }
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            try { authRepo.signOut() } catch (_: Exception) {}
            hasPassword = false // prefs pass is cleared in signOut()
            onDone()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            operationResult = null
            try {
                val cacheDir = ZhomeApp.instance.cacheDir
                var count = 0
                cacheDir.listFiles()?.forEach { f ->
                    if (f.deleteRecursively()) count++
                }
                operationResult = appCtx.getString(R.string.settings_cache_cleared, count)
            } catch (e: Exception) {
                operationResult = e.message ?: appCtx.getString(R.string.settings_cache_clear_failed)
            }
        }
    }

    fun clearResult() { operationResult = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onSignedOut: () -> Unit, vm: SettingsViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.load() }

    var showClearCacheConfirm by remember { mutableStateOf(false) }
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text(stringResource(R.string.settings_clear_cache_title)) },
            text = { Text(stringResource(R.string.settings_clear_cache_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheConfirm = false
                        vm.clearCache()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.settings_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    val snackHost = remember { SnackbarHostState() }
    vm.operationResult?.let { msg ->
        LaunchedEffect(msg) {
            snackHost.showSnackbar(msg)
            vm.clearResult()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.common_back),
                        )
                    }
                }
            )
        }
    ) { padding ->
        val sectionGap = 24.dp
        var iceMenuExpanded by remember { mutableStateOf(false) }
        val iceOptions = listOf(
            com.ithingtalk.zhome.Constants.IceGatherMode.BOTH to stringResource(R.string.settings_ice_both),
            com.ithingtalk.zhome.Constants.IceGatherMode.P2P_ONLY to stringResource(R.string.settings_ice_force_p2p),
            com.ithingtalk.zhome.Constants.IceGatherMode.RELAY_ONLY to stringResource(R.string.settings_ice_force_relay),
        )
        val iceLabel = iceOptions.firstOrNull { it.first == vm.iceGatherMode }?.second
            ?: stringResource(R.string.settings_ice_both)

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(sectionGap),
        ) {
            SettingsSection(title = stringResource(R.string.settings_nickname)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = vm.nickname,
                        onValueChange = { vm.nickname = it },
                        label = { Text(stringResource(R.string.settings_nickname)) },
                        placeholder = { Text(stringResource(R.string.settings_nickname_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = !vm.isSavingNickname,
                    )
                    if (vm.isSavingNickname) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    } else {
                        TextButton(
                            onClick = { vm.saveNickname(vm.nickname.trim()) },
                            enabled = vm.nickname.trim() != vm.savedNickname,
                        ) {
                            Text(stringResource(R.string.settings_nickname_save))
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_font_size)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        vm.fontLabelAt(vm.fontSizeIdx),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = vm.fontSizeIdx.toFloat(),
                        onValueChange = { vm.updateFontSize(it.toInt()) },
                        valueRange = 0f..9f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_connection)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_force_p2p)) },
                    supportingContent = { Text(stringResource(R.string.settings_force_p2p_desc)) },
                    trailingContent = {
                        Switch(checked = vm.forceP2p, onCheckedChange = { vm.updateForceP2p(it) })
                    },
                )
                HorizontalDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_ice_mode),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_ice_mode_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    ExposedDropdownMenuBox(
                        expanded = iceMenuExpanded,
                        onExpandedChange = { iceMenuExpanded = !iceMenuExpanded },
                    ) {
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            readOnly = true,
                            value = iceLabel,
                            onValueChange = {},
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = iceMenuExpanded)
                            },
                        )
                        ExposedDropdownMenu(
                            expanded = iceMenuExpanded,
                            onDismissRequest = { iceMenuExpanded = false },
                        ) {
                            iceOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        vm.updateIceGatherMode(value)
                                        iceMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_clear_cache)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_clear_cache_desc),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { showClearCacheConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text(stringResource(R.string.settings_clear)) }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_app_version)) {
                Text(
                    stringResource(R.string.common_version_label, stringResource(R.string.build_version)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_current_user)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_username)) },
                    supportingContent = { Text(vm.username) },
                )
                if (vm.hasPassword) {
                    HorizontalDivider()
                    OutlinedButton(
                        onClick = { vm.logout(onSignedOut) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text(stringResource(R.string.settings_logout)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}
