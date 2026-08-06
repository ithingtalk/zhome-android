package com.ithingtalk.zhome.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ithingtalk.zhome.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmAccountScreen(onConfirmed: () -> Unit, onBack: () -> Unit, vm: AuthViewModel = viewModel()) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auth_confirm_account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.auth_confirm_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.auth_email)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text(stringResource(R.string.auth_confirmation_code)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { vm.confirmSignUp(email, code, onConfirmed) },
                enabled = email.isNotBlank() && code.isNotBlank() && !vm.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.auth_confirm))
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { vm.resendCode(email) }, enabled = email.isNotBlank()) {
                Text(stringResource(R.string.auth_resend_code))
            }

            vm.error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            vm.message?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
