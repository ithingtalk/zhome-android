@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ithingtalk.zhome.data.local.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ithingtalk.zhome.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "zhome_prefs")

class LocalPrefs(private val ctx: Context) {

    /* ---------- Keys ---------- */
    private val KEY_USER = stringPreferencesKey("user_name")
    private val KEY_PASS = stringPreferencesKey("user_passwd")
    private val KEY_FORCE_P2P = booleanPreferencesKey("force_p2p")
    private val KEY_ICE_GATHER_MODE = intPreferencesKey("ice_gather_mode")
    private val KEY_FONT_SIZE = intPreferencesKey("font_size_idx")
    private val KEY_ID_TOKEN = stringPreferencesKey("id_token")
    private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
    private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    private val KEY_TOKEN_EXPIRY = longPreferencesKey("token_expiry")
    private val KEY_IDENTITY_ID = stringPreferencesKey("identity_id")
    private val KEY_CRED_AK = stringPreferencesKey("cred_ak")
    private val KEY_CRED_SK = stringPreferencesKey("cred_sk")
    private val KEY_CRED_ST = stringPreferencesKey("cred_st")
    private val KEY_CURR_DEVICE_MAC = stringPreferencesKey("curr_device_mac")
    private val KEY_DISPLAY_TYPE = intPreferencesKey("file_display_type")
    private val KEY_SHARE_PWD = stringPreferencesKey("share_pwd_for_app")

    /* ---------- Helpers ---------- */
    private suspend fun <T> get(key: Preferences.Key<T>, default: T): T =
        ctx.dataStore.data.first()[key] ?: default

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        ctx.dataStore.edit { it[key] = value }
    }

    fun <T> observe(key: Preferences.Key<T>, default: T): Flow<T> =
        ctx.dataStore.data.map { it[key] ?: default }

    /* ---------- User credentials ---------- */
    suspend fun getUser(): String = get(KEY_USER, "")
    suspend fun setUser(v: String) = set(KEY_USER, v)
    suspend fun getPass(): String = get(KEY_PASS, "")
    suspend fun setPass(v: String) = set(KEY_PASS, v)
    fun observeUser(): Flow<String> = observe(KEY_USER, "")

    /* ---------- Settings ---------- */
    suspend fun getForceP2p(): Boolean = get(KEY_FORCE_P2P, false)
    suspend fun setForceP2p(v: Boolean) = set(KEY_FORCE_P2P, v)
    suspend fun getFontSizeIdx(): Int = get(KEY_FONT_SIZE, 0)
    suspend fun setFontSizeIdx(v: Int) = set(KEY_FONT_SIZE, v)
    fun observeFontSizeIdx(): Flow<Int> = observe(KEY_FONT_SIZE, 0)

    fun observeForceP2p(): Flow<Boolean> = observe(KEY_FORCE_P2P, false)

    suspend fun getIceGatherMode(): Int = Constants.IceGatherMode.clamp(get(KEY_ICE_GATHER_MODE, Constants.IceGatherMode.BOTH))
    suspend fun setIceGatherMode(v: Int) = set(KEY_ICE_GATHER_MODE, Constants.IceGatherMode.clamp(v))
    fun observeIceGatherMode(): Flow<Int> = ctx.dataStore.data.map { prefs ->
        Constants.IceGatherMode.clamp(prefs[KEY_ICE_GATHER_MODE] ?: Constants.IceGatherMode.BOTH)
    }
    suspend fun getDisplayType(): Int = get(KEY_DISPLAY_TYPE, 0)
    suspend fun setDisplayType(v: Int) = set(KEY_DISPLAY_TYPE, v)

    /* ---------- AWS tokens ---------- */
    suspend fun getIdToken(): String = get(KEY_ID_TOKEN, "")
    suspend fun setIdToken(v: String) = set(KEY_ID_TOKEN, v)
    suspend fun getAccessToken(): String = get(KEY_ACCESS_TOKEN, "")
    suspend fun setAccessToken(v: String) = set(KEY_ACCESS_TOKEN, v)
    suspend fun getRefreshToken(): String = get(KEY_REFRESH_TOKEN, "")
    suspend fun setRefreshToken(v: String) = set(KEY_REFRESH_TOKEN, v)
    suspend fun getTokenExpiry(): Long = get(KEY_TOKEN_EXPIRY, 0L)
    suspend fun setTokenExpiry(v: Long) = set(KEY_TOKEN_EXPIRY, v)
    suspend fun getIdentityId(): String = get(KEY_IDENTITY_ID, "")
    suspend fun setIdentityId(v: String) = set(KEY_IDENTITY_ID, v)
    suspend fun getCredAk(): String = get(KEY_CRED_AK, "")
    suspend fun setCredAk(v: String) = set(KEY_CRED_AK, v)
    suspend fun getCredSk(): String = get(KEY_CRED_SK, "")
    suspend fun setCredSk(v: String) = set(KEY_CRED_SK, v)
    suspend fun getCredSt(): String = get(KEY_CRED_ST, "")
    suspend fun setCredSt(v: String) = set(KEY_CRED_ST, v)

    /* ---------- Current device ---------- */
    suspend fun getCurrDeviceMac(): String = get(KEY_CURR_DEVICE_MAC, "")
    suspend fun setCurrDeviceMac(v: String) = set(KEY_CURR_DEVICE_MAC, v)
    fun observeCurrDeviceMac(): Flow<String> = observe(KEY_CURR_DEVICE_MAC, "")

    /** NAS share access password from idevice_key_get_status (Qt [NasApi::sharePwd]). */
    suspend fun getSharePwd(): String = get(KEY_SHARE_PWD, "")
    suspend fun setSharePwd(v: String) = set(KEY_SHARE_PWD, v)

    private fun userNicknameKey(user: String): Preferences.Key<String> =
        stringPreferencesKey("user_nickname_${user.ifBlank { "_" }}")

    private fun userStorageKey(user: String): Preferences.Key<String> =
        stringPreferencesKey("user_storage_${user.ifBlank { "_" }}")

    suspend fun getUserNickname(): String = get(userNicknameKey(getUser()), "")
    suspend fun setUserNickname(v: String) = set(userNicknameKey(getUser()), v)
    fun observeUserNickname(): Flow<String> =
        observeUser().flatMapLatest { user ->
            ctx.dataStore.data.map { it[userNicknameKey(user)] ?: "" }
        }

    suspend fun getUserStorage(): String = get(userStorageKey(getUser()), "")
    suspend fun setUserStorage(v: String) = set(userStorageKey(getUser()), v)
    fun observeUserStorage(): Flow<String> =
        observeUser().flatMapLatest { user ->
            ctx.dataStore.data.map { it[userStorageKey(user)] ?: "" }
        }

    suspend fun clearUserProfile() {
        val user = getUser()
        ctx.dataStore.edit {
            it.remove(userNicknameKey(user))
            it.remove(userStorageKey(user))
        }
    }

    /**
     * Current account’s device MACs that hit [NasUserDbSync.SyncFromDeviceResult.NeedAdminApproval]
     * (shown as「未审批」on the device list until sync succeeds).
     */
    private fun pendingUserApprovalKeyForUser(user: String): Preferences.Key<String> =
        stringPreferencesKey("pending_user_approval_${user.ifBlank { "_" }}")

    suspend fun getPendingUserApprovalMacs(): Set<String> {
        val key = pendingUserApprovalKeyForUser(getUser())
        val raw = get(key, "")
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private suspend fun setPendingUserApprovalMacs(macSet: Set<String>) {
        val key = pendingUserApprovalKeyForUser(getUser())
        set(key, macSet.joinToString(","))
    }

    suspend fun addPendingUserApprovalMac(mac: String) {
        if (mac.isBlank()) return
        val cur = getPendingUserApprovalMacs().toMutableSet()
        if (cur.add(mac)) setPendingUserApprovalMacs(cur)
    }

    suspend fun removePendingUserApprovalMac(mac: String) {
        val cur = getPendingUserApprovalMacs().toMutableSet()
        if (cur.remove(mac)) setPendingUserApprovalMacs(cur)
    }

    suspend fun clearPendingUserApprovalMacsForCurrentUser() {
        val key = pendingUserApprovalKeyForUser(getUser())
        set(key, "")
    }

    fun observePendingUserApprovalMacs(): Flow<Set<String>> =
        observeUser().flatMapLatest { user ->
            val key = pendingUserApprovalKeyForUser(user)
            ctx.dataStore.data.map { prefs ->
                (prefs[key] ?: "")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
        }

    /* ---------- Clear all ---------- */
    suspend fun clearTokens() {
        ctx.dataStore.edit {
            it.remove(KEY_ID_TOKEN); it.remove(KEY_ACCESS_TOKEN)
            it.remove(KEY_REFRESH_TOKEN); it.remove(KEY_TOKEN_EXPIRY)
            it.remove(KEY_IDENTITY_ID)
            it.remove(KEY_CRED_AK); it.remove(KEY_CRED_SK); it.remove(KEY_CRED_ST)
        }
    }

    suspend fun clearAll() {
        ctx.dataStore.edit { it.clear() }
    }
}

