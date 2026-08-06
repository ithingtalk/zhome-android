package com.ithingtalk.zhome.ui.screens.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ithingtalk.zhome.R
import kotlinx.coroutines.delay

/**
 * Unified single-screen login matching the iOS AwsLoginView.
 *
 * Four modes on one page: Sign In, Sign Up, Confirm Account, Forgot Password.
 * Smart error handling: UserNotFound → auto-register, UserNotConfirmed → auto-resend,
 * InvalidPassword → dialog with "Forgot Password?" shortcut.
 * After confirmation → auto-sign-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AwsLoginScreen(
    onLoginSuccess: () -> Unit,
    vm: AuthViewModel = viewModel(),
) {
    var mode by remember { mutableStateOf(LoginMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmCode by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var resendCountdown by remember { mutableIntStateOf(0) }
    var showInvalidPasswordDialog by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    // Auto sign-in on first load (if saved credentials exist)
    LaunchedEffect(Unit) { vm.autoSignIn(onLoginSuccess) }

    // Countdown timer for resend button
    LaunchedEffect(resendCountdown) {
        if (resendCountdown > 0) {
            delay(1000)
            resendCountdown--
        }
    }

    // Main dialog for showing messages
    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearMessage() },
            confirmButton = {
                TextButton(onClick = { vm.clearMessage() }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
            title = { Text(stringResource(R.string.auth_brand)) },
            text = { Text(msg) },
        )
    }

    // Invalid password dialog with "Forgot Password?" shortcut
    if (showInvalidPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showInvalidPasswordDialog = false },
            title = { Text(stringResource(R.string.auth_invalid_password_title)) },
            text = { Text(stringResource(R.string.auth_invalid_password_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showInvalidPasswordDialog = false
                    newPassword = password
                    confirmCode = ""
                    mode = LoginMode.FORGOT_PASSWORD
                }) {
                    Text(stringResource(R.string.auth_forgot_password))
                }
            },
            dismissButton = {
                TextButton(onClick = { showInvalidPasswordDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Text(
                        when (mode) {
                            LoginMode.SIGN_IN -> stringResource(R.string.auth_sign_in)
                            LoginMode.SIGN_UP -> stringResource(R.string.auth_sign_up)
                            LoginMode.CONFIRM_ACCOUNT -> stringResource(R.string.auth_confirm_account_title)
                            LoginMode.FORGOT_PASSWORD -> stringResource(R.string.auth_forgot_password)
                        }
                    )
                })
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Info text per mode
                Text(
                    text = when (mode) {
                        LoginMode.SIGN_IN -> stringResource(R.string.auth_info_signin)
                        LoginMode.SIGN_UP -> stringResource(R.string.auth_info_signup)
                        LoginMode.CONFIRM_ACCOUNT -> stringResource(R.string.auth_info_confirm)
                        LoginMode.FORGOT_PASSWORD -> stringResource(R.string.auth_info_forgot)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )

                Spacer(Modifier.height(8.dp))

                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground_image),
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Fit,
                )

                Spacer(Modifier.height(16.dp))

                // Email field (always visible)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.auth_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                // Confirm code + Send button (visible in confirmAccount and forgotPassword)
                if (mode == LoginMode.CONFIRM_ACCOUNT || mode == LoginMode.FORGOT_PASSWORD) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = confirmCode,
                            onValueChange = { confirmCode = it },
                            label = { Text(stringResource(R.string.auth_confirmation_code)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                vm.sendCode(
                                    email = email,
                                    isForgotPassword = mode == LoginMode.FORGOT_PASSWORD,
                                )
                                confirmCode = ""
                                resendCountdown = 59
                            },
                            enabled = resendCountdown == 0 && email.isNotBlank(),
                        ) {
                            Text(
                                if (resendCountdown > 0) "$resendCountdown"
                                else stringResource(R.string.auth_send)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                }

                // Password field (visible in signIn mode)
                if (mode == LoginMode.SIGN_IN) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.auth_password)) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (passwordVisible) stringResource(R.string.common_hide_password)
                                    else stringResource(R.string.common_show_password),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))
                }

                // New password field (visible in signUp and forgotPassword)
                if (mode == LoginMode.SIGN_UP || mode == LoginMode.FORGOT_PASSWORD) {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text(stringResource(R.string.auth_new_password)) },
                        singleLine = true,
                        visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        trailingIcon = {
                            IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                                Icon(
                                    if (newPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (newPasswordVisible) stringResource(R.string.common_hide_password)
                                    else stringResource(R.string.common_show_password),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))
                }

                // Error text
                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))

                // OK button
                val okEnabled = when (mode) {
                    LoginMode.SIGN_IN -> email.isNotBlank() && password.isNotBlank()
                    LoginMode.SIGN_UP -> email.isNotBlank() && newPassword.isNotBlank()
                    LoginMode.CONFIRM_ACCOUNT -> email.isNotBlank() && confirmCode.isNotBlank()
                    LoginMode.FORGOT_PASSWORD -> email.isNotBlank() && newPassword.isNotBlank() && confirmCode.isNotBlank()
                }

                Button(
                    onClick = {
                        vm.clearError()
                        when (mode) {
                            LoginMode.SIGN_IN -> vm.signInSmart(
                                email = email,
                                password = password,
                                onSuccess = onLoginSuccess,
                                onUserNotConfirmed = {
                                    mode = LoginMode.CONFIRM_ACCOUNT
                                    resendCountdown = 59
                                },
                                onAutoRegistered = {
                                    mode = LoginMode.CONFIRM_ACCOUNT
                                    confirmCode = ""
                                },
                                onInvalidPassword = {
                                    showInvalidPasswordDialog = true
                                },
                            )

                            LoginMode.SIGN_UP -> vm.signUpOnly(
                                email = email,
                                password = newPassword,
                                onSuccess = {
                                    // After signUp success, switch to confirm mode
                                    password = newPassword
                                    mode = LoginMode.CONFIRM_ACCOUNT
                                    confirmCode = ""
                                },
                            )

                            LoginMode.CONFIRM_ACCOUNT -> vm.confirmAndSignIn(
                                email = email,
                                code = confirmCode,
                                password = password.ifBlank { newPassword },
                                onSuccess = onLoginSuccess,
                            )

                            LoginMode.FORGOT_PASSWORD -> vm.resetPasswordAndSwitch(
                                email = email,
                                code = confirmCode,
                                newPassword = newPassword,
                                onSuccess = {
                                    password = newPassword
                                    mode = LoginMode.SIGN_IN
                                },
                            )
                        }
                    },
                    enabled = okEnabled && !vm.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (vm.isLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            when (mode) {
                                LoginMode.SIGN_IN -> stringResource(R.string.auth_sign_in)
                                LoginMode.SIGN_UP -> stringResource(R.string.auth_sign_up)
                                LoginMode.CONFIRM_ACCOUNT -> stringResource(R.string.auth_confirm)
                                LoginMode.FORGOT_PASSWORD -> stringResource(R.string.auth_reset_password)
                            }
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Mode navigation bar (matching iOS bottom bar)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ModeLink(stringResource(R.string.auth_sign_in), mode == LoginMode.SIGN_IN) {
                        vm.clearError(); mode = LoginMode.SIGN_IN
                    }
                    ModeLink(stringResource(R.string.auth_forgot_password), mode == LoginMode.FORGOT_PASSWORD) {
                        vm.clearError()
                        newPassword = password
                        confirmCode = ""
                        mode = LoginMode.FORGOT_PASSWORD
                    }
                    ModeLink(stringResource(R.string.auth_sign_up), mode == LoginMode.SIGN_UP) {
                        vm.clearError(); mode = LoginMode.SIGN_UP
                    }
                    if (mode == LoginMode.CONFIRM_ACCOUNT) {
                        ModeLink(stringResource(R.string.auth_confirm_account_title), true) {}
                    }
                }
            }
        }

        // Loading overlay
        if (vm.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f),
                ) {}
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 8.dp,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ModeLink(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                textDecoration = TextDecoration.Underline,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.primary,
        )
    }
}

private enum class LoginMode {
    SIGN_IN,
    SIGN_UP,
    CONFIRM_ACCOUNT,
    FORGOT_PASSWORD,
}
