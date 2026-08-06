package com.ithingtalk.zhome.data.remote.nas

import com.ithingtalk.zhome.Constants
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure command builder mirroring Qt's NasApi.
 * Each method returns a JSON string ready to publish via IoT or HTTP.
 * Stateless — caller supplies user/pass from the coroutine that already has prefs access.
 */
object NasCommands {

    /* ---- Admin commands ---- */
    fun adminCmd(adminPass: String, vararg pairs: Pair<String, String>): String {
        val obj = JSONObject()
        obj.put(Constants.CMD_ROLE, Constants.CMD_ADMIN)
        obj.put(Constants.CMD_ADMIN_PWD, adminPass)
        pairs.forEach { obj.put(it.first, it.second) }
        return obj.toString()
    }

    fun configureNewDevice(adminPass: String, deviceName: String, email: String, userPass: String, userId: String): String =
        adminCmd(adminPass,
            Constants.CMD_CONFIG_DEVICE to Constants.VAL_CMD_NOW,
            Constants.CMD_DEVICE_NAME to deviceName,
            Constants.CMD_USER_ID to userId,
            Constants.CMD_USER_PASSWD to userPass,
            Constants.CMD_USER_EMAIL to email)

    /** Qt: `ret.contains("config_device") && (ret.contains("success") || ret.contains("user_exists"))` */
    fun configureNewDeviceSuccess(resp: String): Boolean {
        val r = resp.lowercase()
        return r.contains(Constants.CMD_CONFIG_DEVICE.lowercase()) &&
            (r.contains("success") || r.contains("user_exists"))
    }

    fun adminLogin(adminPass: String) = adminCmd(adminPass, Constants.CMD_ADMIN_LOGIN to Constants.VAL_CMD_NOW)
    fun getUserList(adminPass: String) = adminCmd(adminPass, Constants.CMD_GET_USER_LIST to Constants.VAL_CMD_NOW)
    fun deleteUser(adminPass: String, user: String) = adminCmd(adminPass, Constants.CMD_DELETE_USER to user)
    fun allowUser(adminPass: String, user: String) = adminCmd(adminPass, Constants.CMD_ALLOW_USER to user)
    fun rejectUser(adminPass: String, user: String) = adminCmd(adminPass, Constants.CMD_REJECT_USER to user)
    fun getHddStatus(adminPass: String) = adminCmd(adminPass, Constants.CMD_GET_HDD_STATUS to Constants.VAL_CMD_NOW)

    /** Qt [NasApi::getAdminDeviceStatus] — same JSON key as nasCode [himsgcenter.c]. */
    fun getAdminDeviceStatus(adminPass: String) =
        adminCmd(adminPass, Constants.CMD_KEY_GET_ADMIN_DEVICE_STATUS to Constants.VAL_CMD_NOW)
    fun initHdd(adminPass: String) = adminCmd(adminPass, Constants.CMD_INIT_DISK to Constants.VAL_CMD_NOW)
    fun repairHdd(adminPass: String) = adminCmd(adminPass, Constants.CMD_REPAIR_DISK to Constants.VAL_CMD_NOW)
    fun replaceHardDisk(adminPass: String, step: String) =
        adminCmd(
            adminPass,
            Constants.CMD_REPLACE_HARD_DISK to Constants.VAL_CMD_NOW,
            Constants.CMD_STEP to step,
        )
    fun replaceHardDiskPrepare(adminPass: String) =
        replaceHardDisk(adminPass, Constants.VAL_STEP_PREPARE)
    fun replaceHardDiskStart(adminPass: String) =
        replaceHardDisk(adminPass, Constants.VAL_STEP_START)
    fun replaceHardDiskStatus(adminPass: String) =
        replaceHardDisk(adminPass, Constants.VAL_STEP_STATUS)

    data class ReplaceHardDiskState(
        val ok: Boolean,
        val step: String = "",
        val status: String = "",
        val progress: String = "",
        val errorCode: String = "",
        val errorMessage: String = "",
        val usbSizeBytes: String = "",
        val hddUsedBytes: String = "",
    )

    fun parseReplaceHardDisk(response: String): ReplaceHardDiskState {
        return try {
            val obj = JSONObject(response)
            val cmd = obj.optString(Constants.CMD_REPLACE_HARD_DISK, "")
            val ok = cmd.equals(Constants.RES_OK, ignoreCase = true) || cmd.isBlank()
            ReplaceHardDiskState(
                ok = ok,
                step = obj.optString(Constants.CMD_STEP, ""),
                status = obj.optString(Constants.FIELD_REPLACE_STATUS, ""),
                progress = obj.optString(Constants.FIELD_REPLACE_PROGRESS, ""),
                errorCode = obj.optString(Constants.FIELD_ERROR_CODE, ""),
                errorMessage = obj.optString(Constants.FIELD_ERROR_MESSAGE, ""),
                usbSizeBytes = obj.optString(Constants.FIELD_USB_SIZE, ""),
                hddUsedBytes = obj.optString(Constants.FIELD_HDD_USED_SIZE, ""),
            )
        } catch (e: Exception) {
            ReplaceHardDiskState(ok = false, errorMessage = e.message.orEmpty())
        }
    }

    fun changeDeviceName(adminPass: String, name: String) = adminCmd(adminPass, Constants.CMD_SAVE_DEVICE_NAME to name)
    fun changeAdminPass(adminPass: String, newPass: String) = adminCmd(adminPass, Constants.CMD_CHANGE_ADMIN_PWD to Constants.VAL_CMD_NOW, Constants.CMD_NEW_PWD to newPass)

    /* ---- User commands ---- */
    fun userCmd(user: String, pass: String, vararg pairs: Pair<String, String>, files: List<String>? = null): String {
        val obj = JSONObject()
        obj.put(Constants.CMD_ROLE, Constants.VAL_ROLE_USER)
        obj.put(Constants.CMD_USER_ID, user)
        obj.put(Constants.CMD_USER_PASSWD, pass)
        obj.put(Constants.CMD_USER_EMAIL, user)
        pairs.forEach { obj.put(it.first, it.second) }
        if (files != null) {
            val arr = JSONArray()
            files.forEach { arr.put(it) }
            obj.put(Constants.CMD_FILE_LIST, arr)
        }
        return obj.toString()
    }

    fun userLogin(user: String, pass: String) = userCmd(user, pass, Constants.CMD_USER_LOGIN to Constants.VAL_CMD_NOW)
    fun userGetStatus(user: String, pass: String) = userCmd(user, pass, Constants.CMD_GET_STATUS to Constants.VAL_CMD_NOW)

    /** Rescan NAS user directory (default `MyFiles`) and merge missing rows into `file.db`. */
    fun repairUserDatabase(user: String, pass: String, subDir: String = "MyFiles") =
        userCmd(user, pass, Constants.CMD_REPAIR_USER_DATABASE to subDir)

    fun repairUserDatabaseSuccess(resp: String): Boolean {
        val r = resp.lowercase()
        return r.contains(Constants.CMD_REPAIR_USER_DATABASE.lowercase()) && r.contains(Constants.RES_OK.lowercase())
    }

    /**
     * Index one uploaded file into NAS `file.db`.
     * [relPath] must contain `MyFiles/.../filename` (e.g. `MyFiles/Video/a.mp4`).
     */
    fun addOneFile(user: String, pass: String, relPath: String) =
        userCmd(user, pass, Constants.CMD_ADD_ONE_FILE to relPath)

    fun addOneFileSuccess(resp: String): Boolean =
        commandStatus(resp, Constants.CMD_ADD_ONE_FILE).equals(Constants.RES_OK, ignoreCase = true)

    /** ConnectDevicePage.qml: `user_authority` in idevice_key_get_status response. */
    fun parseUserAuthorityStatus(statusJson: String): String =
        try {
            JSONObject(statusJson).optString(Constants.CMD_USER_AUTHORITY, "")
        } catch (_: Exception) {
            ""
        }

    /** ConnectDevicePage.qml: `share_pwd_for_app` before [DbFiles::updateDbFile]. */
    fun parseSharePwdForApp(statusJson: String): String =
        try {
            JSONObject(statusJson).optString(Constants.CMD_SHARE_PWD_FOR_APP, "")
        } catch (_: Exception) {
            ""
        }

    /**
     * File database timestamp reported by `idevice_key_get_status`.
     *
     * Qt uses this value to decide whether to update `file.db`.
     * NAS firmware has used different field names across versions, so we try a few.
     *
     * Returns epoch millis when parseable, otherwise null.
     */
    fun parseUserDbTimestampMs(statusJson: String): Long? {
        val raw: Long = try {
            val o = JSONObject(statusJson)
            val keys = listOf(
                Constants.FIELD_DB_FILE_TIME,
                "db_timestamp",
                "db_time",
                "file_db_timestamp",
                "file_db_time",
                "db_file_time",
                "dbfiles_time",
                "dbfiles_timestamp",
                "filedb_time",
                "filedb_timestamp",
            )
            var v = 0L
            for (k in keys) {
                v = o.optLong(k, 0L)
                if (v > 0L) break
                // Some firmware returns as string.
                val s = o.optString(k, "")
                if (s.isNotBlank()) {
                    v = s.toLongOrNull() ?: 0L
                    if (v > 0L) break
                }
            }
            v.takeIf { it > 0L } ?: return null
        } catch (_: Exception) {
            return null
        }
        // Normalize seconds vs millis.
        return if (raw < 1_000_000_000_000L) raw * 1000L else raw
    }
    fun removeFiles(user: String, pass: String, files: List<String>) = userCmd(user, pass, Constants.CMD_REMOVE_FILES to Constants.VAL_CMD_NOW, files = files)
    fun deleteFiles(user: String, pass: String, files: List<String>) = userCmd(user, pass, Constants.CMD_DELETE_FILES to Constants.VAL_CMD_NOW, files = files)
    fun recoverFiles(user: String, pass: String, files: List<String>) = userCmd(user, pass, Constants.CMD_RECOVER_FILES to Constants.VAL_CMD_NOW, files = files)
    fun createNewFolder(user: String, pass: String, subDir: String, name: String) = userCmd(
        user,
        pass,
        Constants.CMD_CREATE_DIR to Constants.VAL_CMD_NOW,
        Constants.CMD_CURRENT_DIRECTORY to normalizeMyFilesPath(subDir),
        Constants.CMD_SUBDIR_NAME to name,
    )
    fun renameFile(user: String, pass: String, from: String, to: String) = userCmd(
        user,
        pass,
        Constants.CMD_RENAME_FILE to Constants.VAL_CMD_NOW,
        Constants.JSON_KEY_FROM to from,
        Constants.JSON_KEY_TO to to,
    )
    fun checkFileExists(user: String, pass: String, filePath: String) =
        userCmd(user, pass, Constants.CMD_CHECK_FILE_EXISTS to filePath)
    fun moveFiles(user: String, pass: String, files: List<String>, destDir: String) = userCmd(
        user,
        pass,
        Constants.CMD_MOVE_FILES to Constants.VAL_CMD_NOW,
        Constants.CMD_DEST_SUB_DIR to destDir,
        files = files,
    )
    fun shareFiles(user: String, pass: String, files: List<String>) =
        userCmd(
            user,
            pass,
            Constants.CMD_SHARE_FILES to Constants.VAL_CMD_NOW,
            // NAS expects "<user>/MyFiles/..." for share/cancel-share.
            files = files.map { normalizeUserPrefixedMyFilesPath(user, it) },
        )

    fun deleteShared(user: String, pass: String, files: List<String>) =
        userCmd(
            user,
            pass,
            Constants.CMD_DELETE_SHARED to Constants.VAL_CMD_NOW,
            files = files.map { normalizeUserPrefixedMyFilesPath(user, it) },
        )
    fun userSetNickname(user: String, pass: String, nickname: String) =
        userCmd(user, pass, Constants.CMD_SET_USER_NICKNAME to nickname)

    fun userChangePasswd(user: String, pass: String, newPass: String) =
        userCmd(user, pass, Constants.CMD_CHANGE_USER_PASSWD to Constants.VAL_CMD_NOW, Constants.CMD_NEW_PWD to newPass)
    fun userForgetPasswd(user: String, pass: String) = userCmd(user, pass, Constants.CMD_LOGIN_FORGET_PWD to Constants.VAL_CMD_NOW)
    fun userResetPasswd(user: String, pass: String, code: String, newPass: String) = userCmd(user, pass, Constants.CMD_LOGIN_RESET_PWD to Constants.VAL_CMD_NOW, Constants.CMD_RANDOM_CODE to code, Constants.CMD_NEW_PWD to newPass)

    /* ---- Response parsers (align with Qt [NasApi] + himsgcenter.h RES_OK/RES_FAIL) ---- */

    fun isSuccess(result: String, key: String) =
        commandStatus(result, key).equals(Constants.RES_OK, ignoreCase = true) ||
            commandStatus(result, key).equals("ok", ignoreCase = true)

    /** Value of [key] in NAS JSON (aligns with iOS [NasCommands.isSimpleSuccess]). */
    fun commandStatus(result: String, key: String): String =
        try {
            JSONObject(result).optString(key, "").trim()
        } catch (_: Exception) {
            ""
        }

    fun setUserNicknameSuccess(result: String) = isSuccess(result, Constants.CMD_SET_USER_NICKNAME)

    fun setUserNicknameFail(result: String): Boolean {
        val s = commandStatus(result, Constants.CMD_SET_USER_NICKNAME)
        return s.equals(Constants.RES_FAIL, ignoreCase = true) || s.equals("fail", ignoreCase = true)
    }

    fun isFail(result: String, key: String) =
        result.contains(key) && result.contains(Constants.RES_FAIL)

    fun removeFilesSuccess(result: String) = isSuccess(result, Constants.CMD_REMOVE_FILES)
    fun deleteFilesSuccess(result: String) = isSuccess(result, Constants.CMD_DELETE_FILES)
    fun recoverFilesSuccess(result: String) = isSuccess(result, Constants.CMD_RECOVER_FILES)
    fun createNewFolderSuccess(result: String) = isSuccess(result, Constants.CMD_CREATE_DIR)
    fun moveFilesSuccess(result: String) = isSuccess(result, Constants.CMD_MOVE_FILES)
    fun shareFilesSuccess(result: String): Boolean =
        try { JSONObject(result).optString(Constants.FIELD_ADD_SHARE_STATUS, "") == Constants.RES_OK } catch (_: Exception) { false }

    fun deleteSharedSuccess(result: String): Boolean =
        try { JSONObject(result).optString(Constants.FIELD_CANCEL_SHARED_STATUS, "") == Constants.RES_OK } catch (_: Exception) { false }
    fun renameFileSuccess(result: String) = isSuccess(result, Constants.CMD_RENAME_FILE)

    fun parseCheckFileExists(result: String): Pair<Boolean, Boolean>? {
        return try {
            val obj = JSONObject(result)
            val cmdResult = obj.optString(Constants.CMD_CHECK_FILE_EXISTS, "")
            if (cmdResult.isBlank()) return null
            val success = cmdResult.equals(Constants.RES_OK, ignoreCase = true)
            val exists = when (val v = obj.opt(Constants.CMD_FILE_EXISTS)) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> {
                    val lowered = v.lowercase()
                    lowered == "true" || lowered == "1" || lowered == "yes"
                }
                else -> false
            }
            success to exists
        } catch (_: Exception) {
            null
        }
    }

    /** NAS expects `MyFiles/...` (no leading slash, no `/Ftp/user/` prefix). */
    fun normalizeMyFilesPath(p: String): String {
        val i = p.indexOf("MyFiles")
        if (i < 0) return p.trimStart('/')
        return p.substring(i).trimStart('/')
    }

    /**
     * For share/cancel-share NAS expects `<user>/MyFiles/...`.
     *
     * Accepts inputs like:
     * - `MyFiles/Doc/a.txt`
     * - `/MyFiles/Doc/a.txt`
     * - `user/MyFiles/Doc/a.txt`
     * - `/user/MyFiles/Doc/a.txt`
     */
    fun normalizeUserPrefixedMyFilesPath(user: String, p: String): String {
        val s = p.trimStart('/')
        val strippedUserPrefix = if (s.startsWith("$user/")) s.removePrefix("$user/") else s
        val myFilesPath = normalizeMyFilesPath(strippedUserPrefix)
        val normalizedMyFilesPath = if (myFilesPath.startsWith("MyFiles/") || myFilesPath == "MyFiles") {
            myFilesPath
        } else {
            "MyFiles/$myFilesPath".trimEnd('/')
        }
        return "$user/$normalizedMyFilesPath"
    }

    /** Same as Qt [NasApi::userLoginResult] — value of [CMD_USER_LOGIN] key. */
    fun userLoginResult(result: String): String =
        try {
            JSONObject(result).optString(Constants.CMD_USER_LOGIN, "")
        } catch (_: Exception) {
            ""
        }

    /** Matches Qt [NasApi::userLoginSuccess] — compares to RES_OK ("success"). */
    fun userLoginSuccess(result: String) = userLoginResult(result) == Constants.RES_OK

    fun userLoginFail(result: String) = userLoginResult(result) == Constants.RES_FAIL

    /** Matches Qt [NasApi::userLoginNeedAllow]. */
    fun userLoginNeedAllow(result: String) = userLoginResult(result) == Constants.VAL_LOGIN_STATUS_NONE
    fun adminLoginSuccess(result: String) = isSuccess(result, Constants.CMD_ADMIN_LOGIN)

    /** Result parser for [userChangePasswd] (key is the command name). */
    fun userChangePassSuccess(result: String) = isSuccess(result, Constants.CMD_CHANGE_USER_PASSWD)

    fun parseUserAuthority(result: String): String {
        return try {
            JSONObject(result).optString(Constants.CMD_USER_AUTHORITY, "")
        } catch (_: Exception) { "" }
    }

    /* ---- Device management response parsers (mirrors Qt NasApi methods) ---- */

    /** Qt NasApi::parseHddStatusFromResult — "hdd_ok" / "hdd_uninit" / "hdd_initing" / "". */
    fun parseHddStatus(result: String): String =
        try { JSONObject(result).optString(Constants.FIELD_GET_HDD_STATUS, "") } catch (_: Exception) { "" }

    /**
     * HDD status included in `idevice_key_get_status` user status response (nasCode: CMD_KEY_HDD_STATUS).
     * Values: "hdd_none" / "hdd_uninit" / "hdd_initing" / "hdd_ready" / "hdd_ok" / "hdd_umount" (firmware-dependent).
     */
    fun parseHddStatusFromUserStatus(statusJson: String): String =
        try { JSONObject(statusJson).optString(Constants.FIELD_HDD_STATUS, "") } catch (_: Exception) { "" }

    /**
     * Qt [NasApi::parseHddFormatingProgressFromResult]: prefers [CMD_KEY_GET_HDD_FORMAT_PROGRESS]
     * (e.g. `513/7453`); some builds use `format_percent` (e.g. `50%` / `done`).
     */
    fun parseFormatProgress(result: String): String {
        return try {
            val o = JSONObject(result)
            val legacy = o.optString(Constants.FIELD_FORMAT_PERCENT, "")
            if (legacy.isNotBlank()) legacy
            else o.optString(Constants.CMD_KEY_GET_HDD_FORMAT_PROGRESS, "")
        } catch (_: Exception) {
            ""
        }
    }

    /** Optional: show `513/7453` as `513/7453 (7%)` for readability. */
    fun formatProgressForDisplay(raw: String): String {
        val s = raw.trim()
        if (s.isBlank() || s.equals(Constants.VAL_FORMAT_DONE, ignoreCase = true)) return s
        val slash = s.indexOf('/')
        if (slash <= 0) return s
        val cur = s.substring(0, slash).toDoubleOrNull() ?: return s
        val total = s.substring(slash + 1).toDoubleOrNull() ?: return s
        if (total <= 0) return s
        val pct = ((cur * 100.0) / total).toInt().coerceIn(0, 100)
        return "$s ($pct%)"
    }

    /**
     * Parses [parseFormatProgress] / `format_percent` / `CMD_KEY_GET_HDD_FORMAT_PROGRESS` values into 0f..1f
     * for determinate progress UI; null if unknown (use indeterminate).
     */
    fun formatProgressFraction(raw: String): Float? {
        val s = raw.trim()
        if (s.isBlank()) return null
        if (s.equals(Constants.VAL_FORMAT_DONE, ignoreCase = true)) return 1f
        val slash = s.indexOf('/')
        if (slash > 0) {
            val cur = s.substring(0, slash).toDoubleOrNull() ?: return null
            val total = s.substring(slash + 1).toDoubleOrNull() ?: return null
            if (total <= 0) return null
            return (cur / total).toFloat().coerceIn(0f, 1f)
        }
        val pctMatch = Regex("""^(\d{1,3})\s*%$""").find(s)
        if (pctMatch != null) {
            val p = pctMatch.groupValues[1].toIntOrNull() ?: return null
            return (p.coerceIn(0, 100)) / 100f
        }
        val plainNum = Regex("""^(\d{1,3})$""").matchEntire(s)
        if (plainNum != null) {
            val p = plainNum.groupValues[1].toIntOrNull() ?: return null
            if (p > 100) return null
            return p / 100f
        }
        return null
    }

    /** e.g. `"37%"` for UI; empty if [formatProgressFraction] cannot parse. */
    fun formatProgressPercentText(raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return ""
        if (s.equals(Constants.VAL_FORMAT_DONE, ignoreCase = true)) return "100%"
        val f = formatProgressFraction(raw) ?: return ""
        return "${(f * 100f).toInt().coerceIn(0, 100)}%"
    }

    /** Qt NasApi::parseUserNumFromResult — -1 means not a user-num response. */
    fun parseUserCount(result: String): Int {
        return try {
            val o = JSONObject(result)
            jsonIntField(o, "user_num")?.let { if (it >= 0) return it }
            jsonIntField(o, "user_total")?.let { if (it >= 0) return it }
            val arr = o.optJSONArray("user_list")
            if (arr != null && arr.length() > 0) arr.length() else -1
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * org.json returns [java.lang.Integer] / [java.lang.Long] for numbers — they are not Kotlin [Int] and
     * do not match `is Number` (kotlin.Number), so we must handle [java.lang.Number] explicitly.
     */
    private fun jsonIntField(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        return try {
            when (val v = o.get(key)) {
                is Int -> v
                is Long -> v.toInt()
                is Double -> v.toInt()
                is String -> v.trim().toIntOrNull()
                else -> (v as? java.lang.Number)?.doubleValue()?.toInt()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Qt UserServicePage: ip_addr field from idevice_key_get_status response. */
    fun parseDeviceIp(result: String): String =
        try { JSONObject(result).optString(Constants.FIELD_IP_ADDR, "") } catch (_: Exception) { "" }

    /** Qt UserServicePage: fw_version field from idevice_key_get_status response. */
    fun parseDeviceVersion(result: String): String =
        try { JSONObject(result).optString(Constants.FIELD_FW_VERSION, "") } catch (_: Exception) { "" }

    fun changeAdminPassSuccess(result: String) = isSuccess(result, Constants.CMD_CHANGE_ADMIN_PWD)
    fun changeAdminPassFail(result: String) = isFail(result, Constants.CMD_CHANGE_ADMIN_PWD)
    fun changeDeviceNameSuccess(result: String) = isSuccess(result, Constants.CMD_SAVE_DEVICE_NAME)

    /** True when NAS JSON includes `user_list` — avoid clearing list from unrelated command replies. */
    fun jsonHasUserList(result: String): Boolean =
        try {
            JSONObject(result).has(Constants.FIELD_USER_LIST)
        } catch (_: Exception) {
            false
        }

    /**
     * Mirrors Qt [Logic.getDeviceUsersListModel] — parses `user_list` from get_user_list and from
     * allow_user / reject_user / delete_user responses (same shape as Qt [DeviceUserPage] onDataReceived).
     * Each entry is `{ "email@host": "pass|none|denied", "user_storage": "1234", "user_nickname": "..." }`.
     */
    fun parseUserList(result: String): List<ParsedUser> {
        val out = mutableListOf<ParsedUser>()
        try {
            val arr = JSONObject(result).optJSONArray(Constants.FIELD_USER_LIST) ?: return out
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val storage = obj.optString(Constants.FIELD_USER_STORAGE, "")
                val nickname = obj.optString(Constants.FIELD_USER_NICKNAME, "")
                val keys = obj.keys()
                var email = ""
                var status = ""
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != Constants.FIELD_USER_STORAGE && k != Constants.FIELD_USER_NICKNAME) {
                        email = k
                        status = obj.optString(k, "")
                        break
                    }
                }
                if (email.isNotBlank()) {
                    out.add(ParsedUser(email, status, storage, nickname))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    data class ParsedUser(val email: String, val status: String, val storage: String, val nickname: String)

    data class UserProfileFromStatus(
        val nickname: String,
        val userStorage: String,
        val hardDiskSpace: String,
        val hardDiskRemain: String,
    )

    /** Mirrors iOS [NasCommands.displayNasStorageRaw] — NAS returns human-readable units; no client conversion. */
    fun displayNasStorageRaw(ctx: android.content.Context, raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return ctx.getString(com.ithingtalk.zhome.R.string.user_space_unknown)
        }
        val lowered = trimmed.lowercase()
        if (lowered.contains("error") || lowered.contains("unknow") || lowered.contains("fail")) {
            return ctx.getString(com.ithingtalk.zhome.R.string.user_space_unknown)
        }
        return trimmed
    }

    fun parseUserProfileFromStatus(statusJson: String, userEmail: String): UserProfileFromStatus {
        var nickname = ""
        var storage = ""
        var total = ""
        var remain = ""
        try {
            val o = org.json.JSONObject(statusJson)
            nickname = o.optString(Constants.FIELD_USER_NICKNAME, "").trim()
            storage = o.optString(Constants.FIELD_USER_STORAGE, "").trim()
            total = o.optString(Constants.FIELD_HARD_DISK_SPACE, "").trim()
            remain = o.optString(Constants.FIELD_HARD_DISK_REMAIN, "").trim()
        } catch (_: Exception) {
        }
        if (nickname.isBlank() || storage.isBlank()) {
            parseUserList(statusJson).find { it.email == userEmail }?.let { u ->
                if (nickname.isBlank()) nickname = u.nickname.trim()
                if (storage.isBlank()) storage = u.storage.trim()
            }
        }
        return UserProfileFromStatus(nickname, storage, total, remain)
    }
}

