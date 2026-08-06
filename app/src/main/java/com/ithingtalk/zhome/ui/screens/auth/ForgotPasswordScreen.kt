package com.ithingtalk.zhome.ui.screens.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ithingtalk.zhome.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(onReset: () -> Unit, onBack: () -> Unit, vm: AuthViewModel = viewModel()) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var codeSent by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auth_forgot_title)) },
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
            Text(
                if (codeSent) stringResource(R.string.auth_forgot_hint_reset)
                else stringResource(R.string.auth_forgot_hint_request),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(stringResource(R.string.auth_email)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !codeSent,
            )

            if (codeSent) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.auth_confirmation_code)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text(stringResource(R.string.auth_new_password)) },
                    singleLine = true,
                    visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                            Icon(
                                if (newPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (newPasswordVisible) {
                                    stringResource(R.string.common_hide_password)
                                } else {
                                    stringResource(R.string.common_show_password)
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))

            if (!codeSent) {
                Button(
                    onClick = { vm.forgotPassword(email) { codeSent = true } },
                    enabled = email.isNotBlank() && !vm.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (vm.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.auth_send_reset_code))
                }
            } else {
                Button(
                    onClick = { vm.resetPassword(email, code, newPassword, onReset) },
                    enabled = code.isNotBlank() && newPassword.isNotBlank() && !vm.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (vm.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.auth_reset_password))
                }
            }

            vm.error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            vm.message?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
