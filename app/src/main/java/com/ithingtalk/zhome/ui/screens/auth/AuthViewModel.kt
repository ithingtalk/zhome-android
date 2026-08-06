package com.ithingtalk.zhome.ui.screens.auth

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {
    private val repo = ZhomeApp.instance.authRepo
    private val deviceRepo = ZhomeApp.instance.deviceRepo
    private val appCtx = ZhomeApp.instance.applicationContext

    var isLoading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set

    // ---- Unified login flow (matching iOS AwsLoginView) ----

    /**
     * Smart sign-in: detects Cognito error types and routes accordingly.
     * - UserNotConfirmed → auto-resend confirmation code → [onUserNotConfirmed]
     * - UserNotFound → auto-register → [onAutoRegistered]
     * - InvalidPassword (NotAuthorized) → [onInvalidPassword]
     * - Success → cloud DB pull → [onSuccess]
     */
    fun signInSmart(
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onUserNotConfirmed: () -> Unit,
        onAutoRegistered: () -> Unit,
        onInvalidPassword: () -> Unit,
    ) {
        viewModelScope.launch {
            isLoading = true; error = null
            try {
                repo.signIn(email, password)
                try { repo.getAwsCredentials() } catch (_: Exception) {}
                try { deviceRepo.syncFromCloud() } catch (_: Exception) {}
                onSuccess()
            } catch (e: Exception) {
                when {
                    isCognitoException(e, "UserNotConfirmedException") -> {
                        try { repo.resendCode(email) } catch (_: Exception) {}
                        message = appCtx.getString(R.string.auth_msg_confirmation_sent)
                        onUserNotConfirmed()
                    }
                    isCognitoException(e, "UserNotFoundException") -> {
                        // Unified login: auto-register when user does not exist
                        try {
                            repo.signUp(email, password)
                            message = appCtx.getString(R.string.auth_msg_confirmation_sent)
                            onAutoRegistered()
                        } catch (e2: Exception) {
                            error = e2.message ?: appCtx.getString(R.string.auth_err_sign_up_failed)
                        }
                    }
                    isCognitoException(e, "NotAuthorizedException") -> {
                        onInvalidPassword()
                    }
                    else -> {
                        Log.e("AuthViewModel", "Sign in failed", e)
                        error = e.message ?: e.toString()
                    }
                }
            }
            isLoading = false
        }
    }

    /** Confirm account then auto-sign-in (matching iOS handleConfirmResult). */
    fun confirmAndSignIn(
        email: String,
        code: String,
        password: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            isLoading = true; error = null
            try {
                repo.confirmSignUp(email, code)
                // Auto-sign-in after confirmation
                repo.signIn(email, password)
                try { repo.getAwsCredentials() } catch (_: Exception) {}
                try { deviceRepo.syncFromCloud() } catch (_: Exception) {}
                onSuccess()
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Confirm + sign in failed", e)
                error = e.message ?: appCtx.getString(R.string.auth_err_confirm_failed)
            }
            isLoading = false
        }
    }

    /** Sign up only (explicit, no auto-sign-in). */
    fun signUpOnly(email: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true; error = null
            try {
                repo.signUp(email, password)
                message = appCtx.getString(R.string.auth_msg_sign_up_success)
                onSuccess()
            } catch (e: Exception) {
                error = e.message ?: appCtx.getString(R.string.auth_err_sign_up_failed)
            }
            isLoading = false
        }
    }

    /** Reset password, then signal caller to switch to sign-in mode. */
    fun resetPasswordAndSwitch(
        email: String,
        code: String,
        newPassword: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            isLoading = true; error = null
            try {
                repo.resetPassword(email, code, newPassword)
                message = appCtx.getString(R.string.auth_can_signin_new_password)
                onSuccess()
            } catch (e: Exception) {
                error = e.message ?: appCtx.getString(R.string.auth_err_reset_failed)
            }
            isLoading = false
        }
    }

    /** Send / resend confirmation or forgot-password code. */
    fun sendCode(email: String, isForgotPassword: Boolean) {
        viewModelScope.launch {
            try {
                if (isForgotPassword) repo.forgotPassword(email)
                else repo.resendCode(email)
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    // ---- Legacy methods (still used by old screens if any) ----

    fun signIn(email: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true; error = null
            try {
                Log.d("AuthViewModel", "Attempting sign in for: $email")
                repo.signIn(email, password)
                try { 
                    repo.getAwsCredentials() 
                } catch (e: Exception) {
                    Log.e("AuthViewModel", "Failed to get AWS credentials", e)
                }
                onSuccess()
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Sign in failed", e)
                error = e.message ?: e.toString()
            }
            isLoading = false
        }
    }

    fun autoSignIn(onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true; error = null
            try {
                val tokens = repo.autoSignIn()
                if (tokens != null) {
                    try { repo.getAwsCredentials() } catch (_: Exception) {}
                    // Pull cloud device list + push any pending Add/Del up so
                    // the device screen shows an up-to-date list on launch,
                    // mirroring signInSmart / confirmAndSignIn.
                    try { deviceRepo.syncFromCloud() } catch (_: Exception) {}
                    onSuccess()
                }
            } catch (e: Exception) {
                error = e.message
            }
            isLoading = false
        }
    }

    fun signUp(email: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true; error = null
            try {
                repo.signUp(email, password)
                message = appCtx.getString(R.string.auth_msg_sign_up_success)
                onSuccess()
            } catch (e: Exception) {
                error = e.message ?: appCtx.getString(R.string.auth_err_sign_up_failed)
            }
            isLoading = false
        }
    }

    fun confirmSignUp(email: String, code: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true; error = null
            try {
                repo.confirmSignUp(email, code)
                message = appCtx.getString(R.string.auth_msg_account_confirmed)
                onSuccess()
            } catch (e: Exception) {
                error = e.message ?: appCtx.getString(R.string.auth_err_confirm_failed)
            }
            isLoading = false
        }
    }

    fun resendCode(email: String) {
        viewModelScope.launch {
            try {
                repo.resendCode(email)
                message = appCtx.getString(R.string.auth_msg_confirmation_sent)
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun forgotPassword(email: String, onCodeSent: () -> Unit = {}) {
        viewModelScope.launch {
            isLoading = true; error = null
            try {
                repo.forgotPassword(email)
                message = appCtx.getString(R.string.auth_msg_reset_code_sent)
                onCodeSent()
            } catch (e: Exception) {
                error = e.message ?: appCtx.getString(R.string.auth_err_generic)
            }
            isLoading = false
        }
    }

    fun resetPassword(email: String, code: String, newPassword: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true; error = null
            try {
                repo.resetPassword(email, code, newPassword)
                message = appCtx.getString(R.string.auth_msg_password_reset_ok)
                onSuccess()
            } catch (e: Exception) {
                error = e.message ?: appCtx.getString(R.string.auth_err_reset_failed)
            }
            isLoading = false
        }
    }

    fun signOut(onSuccess: () -> Unit) {
        viewModelScope.launch {
            try { repo.signOut() } catch (_: Exception) {}
            onSuccess()
        }
    }

    fun clearError() { error = null }
    fun clearMessage() { message = null }

    // ---- Cognito exception detection ----

    private fun isCognitoException(e: Exception, name: String): Boolean {
        // Check by class simple name (works for both provider and identity SDK exceptions)
        if (e::class.simpleName == name) return true
        // Fallback: check exception message for wrapped exceptions
        val msg = e.message ?: return false
        return msg.contains(name, ignoreCase = true)
    }
}
