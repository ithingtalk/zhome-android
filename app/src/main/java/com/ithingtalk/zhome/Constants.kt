package com.ithingtalk.zhome

import com.ithingtalk.zhome.nas.NasProtocolKeys

/** App-wide constants, mirroring cfg/config.json from the Qt project. */
object Constants {

    // Local network discovery — must match zhome-qml cpp/searchLocalIdevice.cpp
    /** UDP port NAS listens on for discovery broadcast */
    const val LOCAL_UDP_BROADCAST_PORT = 8877
    /** TCP port this app listens on for NAS reply (Qt uses fixed 10001, not dynamic) */
    const val LOCAL_TCP_DISCOVERY_PORT = 10001
    /** Legacy name in himsgcenter.h (LISTEN_PORT); not used for LAN search in Qt client */
    const val LOCAL_CMD_PORT = 12345
    const val BROADCAST_SEARCH = "T-NAS?"

    /** libp2p_export.h [IOT_APP_CLIENT_ID] — required on every NAS JSON command */
    val IOT_APP_CLIENT_ID: String get() = NasProtocolKeys.IOT_APP_CLIENT_ID

    /** libp2p_export.h P2P_CMD_LOGIN — legacy command prefix kept for compatibility parsing. */
    const val P2P_CMD_LOGIN = "hip2p_cmd_login"

    /** Align with libip2p ICE_TYPE_T (see libp2p_export.h / bundled lib) — prefs, libp2p_init at startup, libp2p_update_ice_gather_mode from settings only. */
    object IceGatherMode {
        const val BOTH = 0
        const val P2P_ONLY = 1
        const val RELAY_ONLY = 2
        fun clamp(v: Int): Int = when (v) {
            P2P_ONLY -> P2P_ONLY
            RELAY_ONLY -> RELAY_ONLY
            else -> BOTH
        }
    }

    // NAS command / response keys — nas_export.h via [NasProtocolKeys] (C macro names)
    /** JSON key `role` (see [NasProtocolKeys.CMD_KEY_USER_ROLE]). */
    val CMD_ROLE get() = NasProtocolKeys.CMD_KEY_USER_ROLE
    /** Value for admin login: `"admin"`. */
    val CMD_ADMIN get() = NasProtocolKeys.CMD_KEY_ADMIN_USER
    /** Value for user login: `"user"` — not the key name `role`. */
    val VAL_ROLE_USER get() = NasProtocolKeys.CMD_VAL_ROLE_USER
    val CMD_ADMIN_PWD get() = NasProtocolKeys.CMD_KEY_ADMIN_PWD
    val CMD_USER_ID get() = NasProtocolKeys.CMD_KEY_USER_ID
    val CMD_USER_PASSWD get() = NasProtocolKeys.CMD_KEY_USER_PASSWD
    val CMD_USER_EMAIL get() = NasProtocolKeys.CMD_KEY_USER_EMAIL
    val CMD_CONFIG_DEVICE get() = NasProtocolKeys.CMD_KEY_CONFIG_DEVICE
    val CMD_ADMIN_LOGIN get() = NasProtocolKeys.CMD_KEY_ADMIN_LOGIN
    val CMD_USER_LOGIN get() = NasProtocolKeys.CMD_KEY_USER_LOGIN
    val CMD_GET_STATUS get() = NasProtocolKeys.CMD_KEY_GET_STATUS
    val CMD_GET_USER_LIST get() = NasProtocolKeys.CMD_KEY_GET_USER_LIST
    val CMD_DELETE_USER get() = NasProtocolKeys.CMD_KEY_DELETE_USER
    val CMD_ALLOW_USER get() = NasProtocolKeys.CMD_KEY_ALLOW_USER
    val CMD_REJECT_USER get() = NasProtocolKeys.CMD_KEY_REJECT_USER
    val CMD_GET_HDD_STATUS get() = NasProtocolKeys.CMD_KEY_GET_HDD_STATUS
    val CMD_KEY_GET_ADMIN_DEVICE_STATUS get() = NasProtocolKeys.CMD_KEY_GET_ADMIN_DEVICE_STATUS
    val CMD_KEY_GET_HDD_FORMAT_PROGRESS get() = NasProtocolKeys.CMD_KEY_GET_HDD_FORMAT_PROGRESS
    val CMD_INIT_DISK get() = NasProtocolKeys.CMD_KEY_INIT_DISK
    val CMD_REPAIR_DISK get() = NasProtocolKeys.CMD_KEY_REPAIR_DISK
    val CMD_REPLACE_HARD_DISK get() = NasProtocolKeys.CMD_KEY_REPLACE_HARD_DISK
    val CMD_STEP get() = NasProtocolKeys.CMD_KEY_STEP
    val VAL_STEP_PREPARE get() = NasProtocolKeys.CMD_VAL_STEP_PREPARE
    val VAL_STEP_START get() = NasProtocolKeys.CMD_VAL_STEP_START
    val VAL_STEP_STATUS get() = NasProtocolKeys.CMD_VAL_STEP_STATUS
    val VAL_REPLACE_STATUS_INIT get() = NasProtocolKeys.CMD_VAL_STATUS_INIT
    val VAL_REPLACE_STATUS_COPY get() = NasProtocolKeys.CMD_VAL_STATUS_COPY
    val VAL_REPLACE_STATUS_FINISH get() = NasProtocolKeys.CMD_VAL_STATUS_FINISH
    val VAL_REPLACE_STATUS_ERROR get() = NasProtocolKeys.CMD_VAL_STATUS_ERROR
    val VAL_REPLACE_STATUS_IDLE get() = NasProtocolKeys.CMD_VAL_STATUS_IDLE
    val FIELD_REPLACE_STATUS get() = NasProtocolKeys.CMD_KEY_STATUS
    val FIELD_REPLACE_PROGRESS get() = NasProtocolKeys.CMD_KEY_PROGRESS
    val FIELD_ERROR_CODE get() = NasProtocolKeys.CMD_KEY_ERROR_CODE
    val FIELD_ERROR_MESSAGE get() = NasProtocolKeys.CMD_KEY_ERR_MESSAGE
    val FIELD_USB_SIZE get() = NasProtocolKeys.CMD_KEY_USB_SIZE
    val FIELD_HDD_USED_SIZE get() = NasProtocolKeys.CMD_KEY_HDD_USED_SIZE
    val ERR_NO_USB get() = NasProtocolKeys.CMD_VAL_ERR_NO_USB
    val ERR_DISK_TOO_SMALL get() = NasProtocolKeys.CMD_VAL_ERR_DISK_TOO_SMALL
    val ERR_FORMAT_FAILED get() = NasProtocolKeys.CMD_VAL_ERR_FORMAT_FAILED
    val ERR_COPY_FAILED get() = NasProtocolKeys.CMD_VAL_ERR_COPY_FAILED
    val CMD_DEVICE_NAME get() = NasProtocolKeys.CMD_KEY_DEVICE_NAME
    val CMD_SAVE_DEVICE_NAME get() = NasProtocolKeys.CMD_SAVE_DEVICE_NAME
    val CMD_CHANGE_ADMIN_PWD get() = NasProtocolKeys.CMD_KEY_CHANGE_ADMIN_PWD
    val CMD_NEW_PWD get() = NasProtocolKeys.CMD_KEY_NEW_PWD
    val CMD_USER_AUTHORITY get() = NasProtocolKeys.CMD_KEY_USER_AUTHORITY
    val CMD_SHARE_PWD_FOR_APP get() = NasProtocolKeys.CMD_KEY_SHARE_PWD_FOR_APP

    const val NAS_USER_DB_FILE = "file.db"
    val CMD_LOGIN_FORGET_PWD get() = NasProtocolKeys.CMD_KEY_LOGIN_FORGET_PWD
    val CMD_LOGIN_RESET_PWD get() = NasProtocolKeys.CMD_KEY_LOGIN_RESET_PWD
    val CMD_RANDOM_CODE get() = NasProtocolKeys.CMD_KEY_RANDOM_CODE
    val CMD_SHARE_FILES get() = NasProtocolKeys.CMD_KEY_ADD_SHARE
    val CMD_DELETE_SHARED get() = NasProtocolKeys.CMD_KEY_DELETE_SHARE
    val CMD_REMOVE_FILES get() = NasProtocolKeys.CMD_KEY_REMOVE_FILES
    val CMD_DELETE_FILES get() = NasProtocolKeys.CMD_KEY_DOUBLE_DELETE_FILES
    val CMD_RECOVER_FILES get() = NasProtocolKeys.CMD_KEY_RECOVER_FILES
    val CMD_RENAME_FILE get() = NasProtocolKeys.CMD_KEY_FILE_RENAME
    val CMD_CHECK_FILE_EXISTS get() = NasProtocolKeys.CMD_KEY_CHECK_FILE_EXISTS
    val CMD_FILE_EXISTS get() = NasProtocolKeys.CMD_KEY_FILE_EXISTS
    val CMD_MOVE_FILES get() = NasProtocolKeys.CMD_KEY_MOVE_FILES
    val CMD_CREATE_DIR get() = NasProtocolKeys.CMD_KEY_MAKE_DIR
    val CMD_FILE_LIST get() = NasProtocolKeys.CMD_KEY_FILE_LIST
    val CMD_CURRENT_DIRECTORY get() = NasProtocolKeys.CMD_CURRENT_DIRECTORY
    val CMD_SUBDIR_NAME get() = NasProtocolKeys.CMD_KET_SUBDIR
    val CMD_REPAIR_USER_DATABASE get() = NasProtocolKeys.CMD_REPAIR_USER_DATABASE
    val CMD_ADD_ONE_FILE get() = NasProtocolKeys.CMD_ADD_ONE_FILE
    val CMD_SET_USER_NICKNAME get() = NasProtocolKeys.CMD_KEY_SET_USER_NICKNAME
    val CMD_CHANGE_USER_PASSWD get() = NasProtocolKeys.CMD_KEY_CHANGE_PASSWD
    val RES_OK get() = NasProtocolKeys.RES_OK
    val RES_FAIL get() = NasProtocolKeys.RES_FAIL
    val RES_USER_EXISTS get() = NasProtocolKeys.RES_USER_EXISTS
    val FIELD_HARD_DISK_SPACE get() = NasProtocolKeys.CMD_DEVICE_SPACE
    val FIELD_HARD_DISK_REMAIN get() = NasProtocolKeys.CMD_REAMIN_SPACE
    val FIELD_USER_STORAGE get() = NasProtocolKeys.CMD_USER_STOAGE
    val FIELD_USER_NICKNAME get() = NasProtocolKeys.CMD_USER_NICKNAME
    val FIELD_IP_ADDR get() = NasProtocolKeys.CMD_KEY_IPADDR
    val FIELD_FW_VERSION get() = NasProtocolKeys.CMD_KEY_VERSION
    val FIELD_HDD_STATUS get() = NasProtocolKeys.CMD_KEY_HDD_STATUS
    val FIELD_ADD_SHARE_STATUS get() = NasProtocolKeys.MSG_KEY_SHARE_RESULT
    val FIELD_CANCEL_SHARED_STATUS get() = NasProtocolKeys.MSG_KEY_CANCEL_SHARE_RESULT
    val FIELD_USER_LIST get() = NasProtocolKeys.CMD_PAR_USER_LIST
    val FIELD_DB_FILE_TIME get() = NasProtocolKeys.CMD_KEY_DB_FILE_TIME
    val FIELD_GET_HDD_STATUS get() = NasProtocolKeys.CMD_KEY_GET_HDD_STATUS
    val FIELD_FORMAT_PERCENT get() = NasProtocolKeys.CMD_KEY_FORMAT_PERCENT

    val VAL_CMD_NOW get() = NasProtocolKeys.CMD_VAL_CMD_NOW
    val VAL_HDD_STATUS_OK get() = NasProtocolKeys.CMD_VAL_HDD_STATUS_OK
    val VAL_HDD_STATUS_READY get() = NasProtocolKeys.CMD_VAL_HDD_STATUS_READY
    val VAL_HDD_STATUS_NONE get() = NasProtocolKeys.CMD_VAL_HDD_STATUS_NONE
    val VAL_HDD_STATUS_UNINIT get() = NasProtocolKeys.CMD_VAL_HDD_STATUS_UNINIT
    val VAL_HDD_STATUS_INITING get() = NasProtocolKeys.CMD_VAL_HDD_STATUS_INITING
    val VAL_HDD_STATUS_UMOUNT get() = NasProtocolKeys.CMD_VAL_HDD_STATUS_UMOUNT
    val VAL_LOGIN_STATUS_NONE get() = NasProtocolKeys.CMD_VAL_LOGIN_STATUS_NONE
    val VAL_USER_AUTHORITY_PASS get() = NasProtocolKeys.CMD_VAL_USER_AUTHORITY_PASS
    val VAL_USER_AUTHORITY_DENIED get() = NasProtocolKeys.CMD_VAL_USER_AUTHORITY_DENIED
    val VAL_FORMAT_DONE get() = NasProtocolKeys.CMD_VAL_FORMAT_DONE
    val JSON_KEY_FROM get() = NasProtocolKeys.NAS_JSON_KEY_FROM
    val JSON_KEY_TO get() = NasProtocolKeys.NAS_JSON_KEY_TO
    val CMD_DEST_SUB_DIR get() = NasProtocolKeys.CMD_KEY_DEST_SUB_DIR

    // File paths on NAS
    const val TAG_MYFILES = "/MyFiles"
    const val TAG_IMAGE = "$TAG_MYFILES/Image"
    const val TAG_VIDEO = "$TAG_MYFILES/Video"
    const val TAG_AUDIO = "$TAG_MYFILES/Audio"
    const val TAG_DOC = "$TAG_MYFILES/Doc"

    // nasApi.h — URL tags for file HTTP and P2P path rewrite
    const val TAG_SHARED_FILE_URL = "/~share@nas"
    const val TAG_DB_FILE = "download.cgi?file.db"
    const val TAG_SHARED_DB_FILE = "../SHARED/shared.db"
    /** libp2p_export.h `P2P_HTTP_IP` / `P2P_HTTP_PORT` — read from native, not duplicated here. */
    val P2P_HTTP_IP: String
        get() = com.ithingtalk.zhome.jni.NativeBridge.libp2pHttpProxyHost()
    val P2P_HTTP_PORT: String
        get() = com.ithingtalk.zhome.jni.NativeBridge.libp2pHttpProxyPort().toString()
    /** Qt [NasApi::shareUser] for shared-folder HTTP basic auth */
    const val SHARE_HTTP_USER = "share@nas"

    /** Wait for IoT MQTT connected before P2P session (aligned with iOS [RemoteLinkTimeouts]). */
    const val REMOTE_IOT_READY_TIMEOUT_MS = 8_000L
    /** Single NAS JSON command response over IoT MQTT. */
    const val REMOTE_COMMAND_TIMEOUT_MS = 3_000L
    /** Initial send + one retry after [reconnectCommandChannel]. */
    const val REMOTE_COMMAND_MAX_ATTEMPTS = 2

    /**
     * `repair_user_database` rescans a directory tree synchronously on NAS before responding.
     * Use only for that command — normal commands keep the short timeouts above.
     */
    const val REPAIR_USER_DATABASE_REMOTE_TIMEOUT_MS = 300_000L
    const val REPAIR_USER_DATABASE_LAN_TIMEOUT_SEC = 300L

    /**
     * NAS LAN discovery `cfg` field: `"0"` = 未配置/需首次配置。
     * 云端合并时可能不写 [cfg]，Room 默认 `""` — 不能与「已配置」混淆，故 **空串也视为未配置**。
     * 非空且非 `"0"`（通常为 `"1"`）视为已配置。
     */
    fun deviceNeedsConfigure(cfg: String): Boolean {
        val c = cfg.trim()
        return c == "0" || c.isEmpty()
    }
}
