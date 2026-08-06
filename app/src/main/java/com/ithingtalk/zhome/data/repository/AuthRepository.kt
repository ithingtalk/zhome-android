package com.ithingtalk.zhome.data.repository

import android.util.Log
import aws.sdk.kotlin.services.cognitoidentity.model.NotAuthorizedException
import com.ithingtalk.zhome.data.local.prefs.LocalPrefs
import com.ithingtalk.zhome.data.remote.aws.AuthTokens
import com.ithingtalk.zhome.data.remote.aws.AwsAuthService
import com.ithingtalk.zhome.data.remote.aws.AwsCredentials
import com.ithingtalk.zhome.data.remote.aws.AwsIotService
import com.ithingtalk.zhome.data.remote.p2p.RemoteLinkCoordinator
import com.ithingtalk.zhome.ZhomeApp

/**
 * AWS 登录与会话（对齐 Qt：先 User Pool 登录，再 Identity Pool 换临时密钥）。
 *
 * **流程**：Cognito 用户池登录（保存 id / access / refresh token）→ [getAwsCredentials] 用 id token 调
 * Cognito Identity 的 `GetId` + `GetCredentialsForIdentity` 得到 **AK/SK/SessionToken** → 写入 [LocalPrefs]，
 * 并调用 [AwsIotService.updateCredentials] + [AwsIotService.connect]，使 **API Gateway（设备列表 awsdb）与 AWS IoT MQTT
 * 共用同一份临时凭证**（SigV4 execute-api 与 IoT 均依赖该会话）。
 */
class AuthRepository(
    private val auth: AwsAuthService,
    private val prefs: LocalPrefs,
    private val iot: AwsIotService
) {
    companion object {
        private const val TAG = "AuthRepository"
        /** Refresh id token this long before stored expiry so GetId never sees an expired JWT */
        private const val TOKEN_REFRESH_SKEW_MS = 120_000L
    }
    suspend fun signUp(email: String, password: String): String = auth.signUp(email, password)

    suspend fun confirmSignUp(email: String, code: String) = auth.confirmSignUp(email, code)

    suspend fun resendCode(email: String) = auth.resendCode(email)

    suspend fun signIn(email: String, password: String): AuthTokens {
        val tokens = auth.signIn(email, password)
        prefs.setUser(email)
        prefs.setPass(password)
        saveTokens(tokens)
        // Persist the "last user" hint so the next app launch opens this
        // user's per-user zhome.db (matches desktopApp / qtApp behavior).
        runCatching { com.ithingtalk.zhome.ZhomeApp.instance.rememberLastUser(email) }
        return tokens
    }

    suspend fun autoSignIn(): AuthTokens? {
        val user = prefs.getUser()
        val pass = prefs.getPass()
        if (user.isBlank() || pass.isBlank()) return null

        // Try refresh first
        val rt = prefs.getRefreshToken()
        if (rt.isNotBlank()) {
            return try {
                val tokens = auth.refreshTokens(rt)
                saveTokens(tokens)
                tokens
            } catch (_: Exception) {
                auth.signIn(user, pass).also { saveTokens(it) }
            }
        }
        return auth.signIn(user, pass).also { saveTokens(it) }
    }

    /**
     * 在有效 User Pool id token 前提下刷新 Cognito Identity 临时密钥，并驱动 IoT 使用同一密钥连接。
     * 访问 API Gateway 设备接口前应先调用本方法（或由 [DeviceRepository] 在拉云设备前代调）。
     */
    suspend fun getAwsCredentials(): AwsCredentials {
        ensureFreshUserPoolTokens(force = false)
        return try {
            exchangeIdTokenForIotSession()
        } catch (e: NotAuthorizedException) {
            Log.w(TAG, "GetId rejected id token; forcing User Pool refresh", e)
            ensureFreshUserPoolTokens(force = true)
            exchangeIdTokenForIotSession()
        } catch (e: Exception) {
            if (isIdentityLoginTokenError(e)) {
                Log.w(TAG, "Identity pool token error; forcing User Pool refresh", e)
                ensureFreshUserPoolTokens(force = true)
                exchangeIdTokenForIotSession()
            } else {
                throw e
            }
        }
    }

    private fun isIdentityLoginTokenError(e: Exception): Boolean {
        val m = e.message ?: return false
        return m.contains("Token expired", ignoreCase = true) ||
            m.contains("Invalid login token", ignoreCase = true) ||
            m.contains("NotAuthorized", ignoreCase = true)
    }

    /**
     * Cognito Identity [GetId] requires a **valid** User Pool id JWT in `Logins`.
     * Refreshes (or re-signs-in) before that when near expiry or when [force].
     */
    private suspend fun ensureFreshUserPoolTokens(force: Boolean) {
        val now = System.currentTimeMillis()
        val expiry = prefs.getTokenExpiry()
        val idToken = prefs.getIdToken()
        val stale =
            force ||
                idToken.isBlank() ||
                expiry <= 0L ||
                now >= expiry - TOKEN_REFRESH_SKEW_MS
        if (!stale) return

        val user = prefs.getUser()
        val pass = prefs.getPass()
        val rt = prefs.getRefreshToken()

        Log.i(TAG, "Refreshing User Pool session for IoT / Identity (force=$force)")

        when {
            rt.isNotBlank() -> {
                try {
                    saveTokens(auth.refreshTokens(rt))
                } catch (first: Exception) {
                    if (user.isNotBlank() && pass.isNotBlank()) {
                        Log.w(TAG, "Refresh token failed; falling back to password sign-in", first)
                        saveTokens(auth.signIn(user, pass))
                    } else {
                        throw first
                    }
                }
            }
            user.isNotBlank() && pass.isNotBlank() ->
                saveTokens(auth.signIn(user, pass))
            else ->
                error("Cannot refresh AWS session: sign in again (no refresh token or saved password)")
        }
    }

    private suspend fun exchangeIdTokenForIotSession(): AwsCredentials {
        val idToken = prefs.getIdToken()
        if (idToken.isBlank()) {
            error("No id token after refresh; sign in again")
        }
        val cred = auth.getAwsCredentials(idToken)
        prefs.setIdentityId(cred.identityId)
        prefs.setCredAk(cred.accessKeyId)
        prefs.setCredSk(cred.secretKey)
        prefs.setCredSt(cred.sessionToken)
        iot.updateCredentials(cred.accessKeyId, cred.secretKey, cred.sessionToken)
        iot.connect()
        return cred
    }

    suspend fun forgotPassword(email: String) = auth.forgotPassword(email)
    suspend fun resetPassword(email: String, code: String, newPass: String) = auth.resetPassword(email, code, newPass)
    suspend fun changePassword(oldPass: String, newPass: String) = auth.changePassword(prefs.getAccessToken(), oldPass, newPass)

    suspend fun signOut() {
        try { auth.signOut(prefs.getAccessToken()) } catch (_: Exception) {}
        prefs.clearUserProfile()
        prefs.clearTokens()
        prefs.setPass("")
        ZhomeApp.instance.deviceRepo.clearLastKnownLanIps()
        RemoteLinkCoordinator.stopLibp2p()
        iot.disconnect()
    }

    suspend fun isLoggedIn(): Boolean = prefs.getPass().isNotBlank()
    suspend fun currentUser(): String = prefs.getUser()

    private suspend fun saveTokens(t: AuthTokens) {
        prefs.setIdToken(t.idToken)
        prefs.setAccessToken(t.accessToken)
        if (t.refreshToken.isNotBlank()) prefs.setRefreshToken(t.refreshToken)
        prefs.setTokenExpiry(System.currentTimeMillis() + t.expiresIn * 1000L)
    }
}

