@file:OptIn(ExperimentalMaterial3Api::class)

package com.ithingtalk.zhome.ui.screens.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ithingtalk.zhome.R

@Composable
fun AboutScreen(
    mac: String,
    onBack: () -> Unit,
    vm: AboutViewModel = viewModel(),
) {
    LaunchedEffect(mac) { vm.load(mac) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.about_section_device),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            when {
                vm.busy && vm.deviceRows.isEmpty() && vm.error == null ->
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                vm.error != null ->
                    Text(
                        vm.error!!,
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
            }
            vm.deviceRows.forEachIndexed { i, (label, value) ->
                if (i > 0) HorizontalDivider()
                AboutInfoRow(label = label, value = value)
            }
            if (vm.diskRows.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.about_section_disk),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                vm.diskRows.forEachIndexed { i, (label, value) ->
                    if (i > 0) HorizontalDivider()
                    AboutInfoRow(label = label, value = value)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.about_section_oss),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.about_oss_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            value.ifBlank { "-" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
