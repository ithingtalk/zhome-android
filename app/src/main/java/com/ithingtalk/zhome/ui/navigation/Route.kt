package com.ithingtalk.zhome.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation routes for the app. */
sealed interface Route {
    @Serializable data object Welcome : Route
    @Serializable data object AwsLogin : Route
    @Serializable data object SignIn : Route
    @Serializable data object SignUp : Route
    @Serializable data object ForgotPassword : Route
    @Serializable data object ConfirmAccount : Route
    @Serializable data object Devices : Route
    /** Device connect flow: shows connection stages, then enters ContentMain on success. */
    @Serializable data class Connecting(val mac: String) : Route
    @Serializable data object DeviceSearch : Route
    /** 出示设备二维码（`zh2:` + JSON，与 Qt / Android 一致）。 */
    @Serializable data class DeviceQr(val mac: String) : Route
    @Serializable data class ContentMain(val mac: String) : Route
    @Serializable data class FileBrowser(val dir: String = "", val isTrash: Boolean = false) : Route
    /** Shared browser: [mine]=true shows my shares; false shows others' shares. */
    @Serializable data class SharedBrowser(val dir: String = "/MyFiles", val mine: Boolean = false) : Route
    @Serializable data class DeviceManagement(
        val adminPass: String = "",
        /** When true, management page auto-logins using [adminPass]. */
        val autoLogin: Boolean = false,
        /** When true, back button returns to device list (post-config flow). */
        val returnToDevicesOnBack: Boolean = false,
    ) : Route
    /** End-user account admin (allow/reject/delete). Use `DeviceUsers()` from main menu; optional [adminPass] if already known. */
    @Serializable data class DeviceUsers(val adminPass: String = "") : Route
    @Serializable data class DeviceConfigure(
        val mac: String,
        val sn: String = "",
        val name: String = "",
        val cfg: String = "0",
        val ip: String = "",
    ) : Route

    /** 关于：设备状态摘要 + 开源声明 */
    @Serializable data class About(val mac: String) : Route
    @Serializable
    data class PlayVideo(
        val urls: List<String>,
        val startIndex: Int = 0,
    ) : Route
    @Serializable
    data class PlayAudio(
        val urls: List<String>,
        val startIndex: Int = 0,
    ) : Route
    @Serializable
    data class ImagePreview(
        val urls: List<String>,
        val startIndex: Int = 0,
    ) : Route
    @Serializable data class DocumentViewer(val url: String) : Route
    @Serializable data object Transfers : Route
    @Serializable data object Settings : Route
}

