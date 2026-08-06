package com.ithingtalk.zhome.data.remote.aws

import aws.sdk.kotlin.services.cognitoidentity.CognitoIdentityClient
import aws.sdk.kotlin.services.cognitoidentity.model.GetCredentialsForIdentityRequest
import aws.sdk.kotlin.services.cognitoidentity.model.GetIdRequest
import aws.sdk.kotlin.services.cognitoidentityprovider.CognitoIdentityProviderClient
import aws.sdk.kotlin.services.cognitoidentityprovider.model.*
import com.ithingtalk.zhome.AwsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AuthTokens(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int = 3600
)

data class AwsCredentials(
    val accessKeyId: String,
    val secretKey: String,
    val sessionToken: String,
    val identityId: String
)

/**
 * Thin wrapper around AWS Cognito SDK for Kotlin.
 * All methods are suspend and throw on failure – callers handle errors.
 */
class AwsAuthService {

    private suspend fun <T> withClient(block: suspend CognitoIdentityProviderClient.() -> T): T = withContext(Dispatchers.IO) {
        CognitoIdentityProviderClient { region = AwsConfig.region }.use { it.block() }
    }

    private suspend fun <T> withIdentityClient(block: suspend CognitoIdentityClient.() -> T): T = withContext(Dispatchers.IO) {
        CognitoIdentityClient { region = AwsConfig.region }.use { it.block() }
    }

    /* ---- Sign Up ---- */
    suspend fun signUp(email: String, pass: String): String = withClient {
        val signUpResponse = this.signUp(SignUpRequest {
            this.clientId = AwsConfig.userPoolClientId
            this.username = email
            this.password = pass
            this.userAttributes = listOf(AttributeType { 
                this.name = "email"
                this.value = email 
            })
        })
        signUpResponse.userSub ?: ""
    }

    /* ---- Confirm Account ---- */
    suspend fun confirmSignUp(email: String, code: String) = withClient {
        this.confirmSignUp(ConfirmSignUpRequest {
            this.clientId = AwsConfig.userPoolClientId
            this.username = email
            this.confirmationCode = code
        })
    }

    /* ---- Resend Confirmation Code ---- */
    suspend fun resendCode(email: String) = withClient {
        this.resendConfirmationCode(ResendConfirmationCodeRequest {
            this.clientId = AwsConfig.userPoolClientId
            this.username = email
        })
    }

    /* ---- Sign In (USER_PASSWORD_AUTH for simplicity) ---- */
    suspend fun signIn(email: String, pass: String): AuthTokens = withClient {
        val resp = this.initiateAuth(InitiateAuthRequest {
            this.clientId = AwsConfig.userPoolClientId
            this.authFlow = AuthFlowType.UserPasswordAuth
            this.authParameters = mapOf("USERNAME" to email, "PASSWORD" to pass)
        })
        val result = resp.authenticationResult ?: error("No auth result")
        AuthTokens(
            idToken = result.idToken ?: "",
            accessToken = result.accessToken ?: "",
            refreshToken = result.refreshToken ?: "",
            expiresIn = result.expiresIn
        )
    }

    /* ---- Refresh tokens ---- */
    suspend fun refreshTokens(refreshToken: String): AuthTokens = withClient {
        val resp = this.initiateAuth(InitiateAuthRequest {
            this.clientId = AwsConfig.userPoolClientId
            this.authFlow = AuthFlowType.RefreshTokenAuth
            this.authParameters = mapOf("REFRESH_TOKEN" to refreshToken)
        })
        val result = resp.authenticationResult ?: error("No auth result")
        AuthTokens(
            idToken = result.idToken ?: "",
            accessToken = result.accessToken ?: "",
            refreshToken = refreshToken, // refresh token stays the same
            expiresIn = result.expiresIn
        )
    }

    /* ---- Forgot Password ---- */
    suspend fun forgotPassword(email: String) = withClient {
        this.forgotPassword(ForgotPasswordRequest {
            this.clientId = AwsConfig.userPoolClientId
            this.username = email
        })
    }

    /* ---- Reset Password ---- */
    suspend fun resetPassword(email: String, code: String, newPass: String) = withClient {
        this.confirmForgotPassword(ConfirmForgotPasswordRequest {
            this.clientId = AwsConfig.userPoolClientId
            this.username = email
            this.confirmationCode = code
            this.password = newPass
        })
    }

    /* ---- Change Password ---- */
    suspend fun changePassword(token: String, oldPass: String, newPass: String) = withClient {
        this.changePassword(ChangePasswordRequest {
            this.accessToken = token
            this.previousPassword = oldPass
            this.proposedPassword = newPass
        })
    }

    /* ---- Sign Out ---- */
    suspend fun signOut(token: String) = withClient {
        this.globalSignOut(GlobalSignOutRequest { this.accessToken = token })
    }

    /* ---- Get AWS Credentials via Cognito Identity ---- */
    suspend fun getAwsCredentials(idToken: String): AwsCredentials = withIdentityClient {
        val provider = "cognito-idp.${AwsConfig.region}.amazonaws.com/${AwsConfig.userPoolId}"
        val idResp = this.getId(GetIdRequest {
            this.identityPoolId = AwsConfig.identityPoolId
            this.logins = mapOf(provider to idToken)
        })
        val identityId = idResp.identityId ?: error("No identity id")
        val credResp = this.getCredentialsForIdentity(GetCredentialsForIdentityRequest {
            this.identityId = identityId
            this.logins = mapOf(provider to idToken)
        })
        val cred = credResp.credentials ?: error("No credentials")
        AwsCredentials(
            accessKeyId = cred.accessKeyId ?: "",
            secretKey = cred.secretKey ?: "",
            sessionToken = cred.sessionToken ?: "",
            identityId = identityId
        )
    }
}
