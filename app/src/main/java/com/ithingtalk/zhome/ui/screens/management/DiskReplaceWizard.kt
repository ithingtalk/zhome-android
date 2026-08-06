package com.ithingtalk.zhome.ui.screens.management

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.remote.nas.NasCommands
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Eight-step disk-replace wizard embedded in Management
 * (prepare → USB → copy → physical swap).
 */
@Composable
fun DiskReplaceWizardPanel(
    adminPass: String,
    onCancel: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val deviceRepo = remember { ZhomeApp.instance.deviceRepo }

    var step by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var errorCode by remember { mutableStateOf("") }
    var progressStatus by remember { mutableStateOf("") }
    var progressRaw by remember { mutableStateOf("") }

    val prepareFailed = stringResource(R.string.mgmt_replace_prepare_failed)
    val startFailed = stringResource(R.string.mgmt_replace_start_failed)
    val errNoUsb = stringResource(R.string.mgmt_replace_err_no_usb)
    val errTooSmall = stringResource(R.string.mgmt_replace_err_disk_too_small)
    val errFormat = stringResource(R.string.mgmt_replace_err_format_failed)
    val errCopy = stringResource(R.string.mgmt_replace_err_copy_failed)
    val errGeneric = stringResource(R.string.mgmt_replace_err_generic)
    val sizeDetailTemplate = stringResource(R.string.mgmt_replace_size_detail)
    val noDevice = stringResource(R.string.mgmt_replace_no_device)

    suspend fun postReplace(stepValue: String): NasCommands.ReplaceHardDiskState {
        val device = deviceRepo.getCurrent()
            ?: return NasCommands.ReplaceHardDiskState(ok = false, errorMessage = noDevice)
        val resp = deviceRepo.postDeviceCommand(
            device,
            NasCommands.replaceHardDisk(adminPass, stepValue),
            httpUser = "admin",
            httpPass = adminPass,
        )
        return NasCommands.parseReplaceHardDisk(resp)
    }

    fun formatBytes(raw: String): String {
        val n = raw.toLongOrNull() ?: return raw
        if (n <= 0L) return raw
        val gb = n / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb)
        else String.format(Locale.US, "%.0f MB", n / (1024.0 * 1024.0))
    }

    fun errorUiText(state: NasCommands.ReplaceHardDiskState): String {
        val base = when (state.errorCode) {
            Constants.ERR_NO_USB -> errNoUsb
            Constants.ERR_DISK_TOO_SMALL -> errTooSmall
            Constants.ERR_FORMAT_FAILED -> state.errorMessage.ifBlank { errFormat }
            Constants.ERR_COPY_FAILED, "rsync_failed" -> state.errorMessage.ifBlank { errCopy }
            else -> state.errorMessage.ifBlank { errGeneric }
        }
        val usb = state.usbSizeBytes.takeIf { it.isNotBlank() }?.let { formatBytes(it) }
        val used = state.hddUsedBytes.takeIf { it.isNotBlank() }?.let { formatBytes(it) }
        return if (state.errorCode == Constants.ERR_DISK_TOO_SMALL && usb != null && used != null) {
            "$base\n${sizeDetailTemplate.format(usb, used)}"
        } else base
    }

    fun progressFraction(raw: String): Float? {
        val m = Regex("""^(\d{1,3})\s*%$""").find(raw.trim()) ?: return null
        val p = m.groupValues[1].toIntOrNull() ?: return null
        return (p.coerceIn(0, 100)) / 100f
    }

    fun isFormatError(code: String): Boolean = code == Constants.ERR_FORMAT_FAILED
    fun isCopyError(code: String): Boolean =
        code == Constants.ERR_COPY_FAILED || code == "rsync_failed"

    val step3FatalError = step == 3 && errorText != null &&
        (isFormatError(errorCode) || isCopyError(errorCode))

    val stepImageRes = when (step) {
        1 -> R.drawable.disk_replace_step_1
        2 -> R.drawable.disk_replace_step_2
        3 -> R.drawable.disk_replace_step_3
        4 -> R.drawable.disk_replace_step_4
        5 -> R.drawable.disk_replace_step_5
        6 -> R.drawable.disk_replace_step_6
        7 -> R.drawable.disk_replace_step_7
        else -> R.drawable.disk_replace_step_8
    }
    val stepTitleRes = when (step) {
        1 -> R.string.mgmt_replace_wizard_step1_title
        2 -> R.string.mgmt_replace_wizard_step2_title
        3 -> R.string.mgmt_replace_wizard_step3_title
        4 -> R.string.mgmt_replace_wizard_step4_title
        5 -> R.string.mgmt_replace_wizard_step5_title
        6 -> R.string.mgmt_replace_wizard_step6_title
        7 -> R.string.mgmt_replace_wizard_step7_title
        else -> R.string.mgmt_replace_wizard_step8_title
    }
    val stepBodyRes = when (step) {
        1 -> R.string.mgmt_replace_wizard_step1_body
        2 -> R.string.mgmt_replace_wizard_step2_body
        3 -> R.string.mgmt_replace_wizard_step3_body
        4 -> R.string.mgmt_replace_wizard_step4_body
        5 -> R.string.mgmt_replace_wizard_step5_body
        6 -> R.string.mgmt_replace_wizard_step6_body
        7 -> R.string.mgmt_replace_wizard_step7_body
        else -> R.string.mgmt_replace_wizard_step8_body
    }

    LaunchedEffect(Unit) {
        busy = true
        errorText = null
        errorCode = ""
        try {
            val state = postReplace(Constants.VAL_STEP_PREPARE)
            if (state.ok) step = 2
            else errorText = state.errorMessage.ifBlank { prepareFailed }
        } catch (e: Exception) {
            errorText = e.message ?: prepareFailed
        } finally {
            busy = false
        }
    }

    LaunchedEffect(step) {
        if (step != 3) return@LaunchedEffect
        while (true) {
            try {
                val state = postReplace(Constants.VAL_STEP_STATUS)
                progressStatus = state.status
                progressRaw = state.progress
                when {
                    state.status.equals(Constants.VAL_REPLACE_STATUS_FINISH, true) -> {
                        errorText = null
                        errorCode = ""
                        step = 4
                        break
                    }
                    state.status.equals(Constants.VAL_REPLACE_STATUS_ERROR, true) -> {
                        errorCode = state.errorCode
                        errorText = errorUiText(state)
                        if (isFormatError(state.errorCode) || isCopyError(state.errorCode)) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                errorText = e.message
            }
            delay(3_000)
            if (step != 3) break
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.mgmt_replace_disk_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.mgmt_replace_wizard_step_label, step, 8),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Image(
            painter = painterResource(stepImageRes),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            contentScale = ContentScale.Fit,
        )
        Text(stringResource(stepTitleRes), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(stepBodyRes), style = MaterialTheme.typography.bodyMedium)

        if (step == 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.mgmt_replace_preparing))
            }
        }

        if (step == 3 && !step3FatalError) {
            val phase = when (progressStatus.lowercase()) {
                Constants.VAL_REPLACE_STATUS_INIT -> stringResource(R.string.mgmt_replace_phase_init)
                Constants.VAL_REPLACE_STATUS_COPY -> stringResource(R.string.mgmt_replace_phase_copy)
                else -> progressStatus.ifBlank { stringResource(R.string.mgmt_replace_phase_starting) }
            }
            Text(phase, color = MaterialTheme.colorScheme.primary)
            if (progressRaw.isNotBlank()) {
                Text(progressRaw, style = MaterialTheme.typography.titleLarge)
            }
            val frac = progressFraction(progressRaw)
            if (frac != null) {
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                step3FatalError && isFormatError(errorCode) -> {
                    Button(
                        onClick = {
                            step = 2
                            errorText = null
                            errorCode = ""
                            progressStatus = ""
                            progressRaw = ""
                        },
                    ) { Text(stringResource(R.string.mgmt_replace_back)) }
                }
                step3FatalError && isCopyError(errorCode) -> {
                    Button(onClick = onCancel) {
                        Text(stringResource(R.string.mgmt_replace_stop))
                    }
                }
                else -> {
                    if (step != 8) {
                        TextButton(
                            onClick = onCancel,
                            enabled = step != 3 || errorText != null,
                        ) { Text(stringResource(R.string.common_cancel)) }
                        Spacer(Modifier.width(8.dp))
                    }

                    when (step) {
                        2 -> Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    errorText = null
                                    errorCode = ""
                                    try {
                                        val state = postReplace(Constants.VAL_STEP_START)
                                        if (state.status.equals(Constants.VAL_REPLACE_STATUS_ERROR, true) ||
                                            state.errorCode.isNotBlank()
                                        ) {
                                            errorCode = state.errorCode
                                            errorText = errorUiText(state)
                                        } else {
                                            progressStatus = state.status
                                            progressRaw = state.progress
                                            step = 3
                                        }
                                    } catch (e: Exception) {
                                        errorText = e.message ?: startFailed
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.mgmt_replace_next)) }

                        3 -> if (errorText != null) {
                            OutlinedButton(onClick = {
                                step = 2
                                errorText = null
                                errorCode = ""
                                progressStatus = ""
                                progressRaw = ""
                            }) { Text(stringResource(R.string.mgmt_replace_back)) }
                        }

                        in 4..7 -> {
                            OutlinedButton(onClick = { step -= 1; errorText = null }) {
                                Text(stringResource(R.string.mgmt_replace_prev))
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { step += 1; errorText = null }) {
                                Text(stringResource(R.string.mgmt_replace_next))
                            }
                        }

                        8 -> {
                            OutlinedButton(onClick = { step = 7; errorText = null }) {
                                Text(stringResource(R.string.mgmt_replace_prev))
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = onFinished) {
                                Text(stringResource(R.string.mgmt_replace_done))
                            }
                        }
                    }
                }
            }
        }
    }
}
