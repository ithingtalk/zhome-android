package com.ithingtalk.zhome.data.local

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Android local storage layout, aligned with qtApp's `LocalSettings` conventions
 * and the unified multi-user / multi-device spec.
 *
 * Layout under the app's internal files dir (`<filesDir>`):
 *
 *     <filesDir>/
 *       bootstrap.properties      -- tiny "last_user" pointer
 *       users/
 *         <userKey>/
 *           zhome.db              -- main Room DB (device list + file index +
 *                                    transfers + recent files)
 *           devices/
 *             <macKey>/
 *               file.db           -- NAS user-file mirror
 *               shared.db         -- NAS share-file mirror
 *               transfer.db       -- reserved for per-device transfer queue
 *
 * Downloads and cache are NOT user / device scoped per the unified spec. They
 * live under the public Downloads folder (`~/Downloads/zhome/[download,cache]`)
 * when possible, falling back to app-specific external storage.
 */
object AppPaths {

    const val ANONYMOUS_USER_KEY: String = "_anonymous"
    private const val BOOTSTRAP_FILE_NAME = "bootstrap.properties"
    private const val BOOTSTRAP_LAST_USER = "last_user"

    /** `<filesDir>/users`. */
    fun usersRoot(ctx: Context): File = File(ctx.filesDir, "users").apply { mkdirs() }

    /** `<filesDir>/users/<userKey>`. */
    fun userRoot(ctx: Context, username: String): File =
        File(usersRoot(ctx), userKey(username)).apply { mkdirs() }

    /** `<filesDir>/users/<userKey>/zhome.db`. */
    fun userMainDb(ctx: Context, username: String): File =
        File(userRoot(ctx, username), "zhome.db")

    /** `<filesDir>/users/<userKey>/device.db` (qtApp parity; reserved). */
    fun userDeviceDb(ctx: Context, username: String): File =
        File(userRoot(ctx, username), "device.db")

    /** `<filesDir>/users/<userKey>/devices/<macKey>/`. */
    fun deviceRoot(ctx: Context, username: String, deviceMac: String): File =
        File(File(userRoot(ctx, username), "devices"), macKey(deviceMac)).apply { mkdirs() }

    fun deviceFileDb(ctx: Context, username: String, deviceMac: String): File =
        File(deviceRoot(ctx, username, deviceMac), "file.db")

    fun deviceSharedDb(ctx: Context, username: String, deviceMac: String): File =
        File(deviceRoot(ctx, username, deviceMac), "shared.db")

    fun deviceTransferDb(ctx: Context, username: String, deviceMac: String): File =
        File(deviceRoot(ctx, username, deviceMac), "transfer.db")

    /**
     * Global (non user / device scoped) zhome staging root.
     *
     * Prefers the public Downloads folder so the unified `~/Downloads/zhome`
     * layout matches desktopApp; when that is unavailable (e.g. scoped storage
     * restrictions) falls back to app-specific external / internal storage.
     */
    fun globalZhomeDir(ctx: Context): File {
        runCatching {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (pub != null && (pub.exists() || pub.mkdirs())) {
                val z = File(pub, "zhome")
                if (z.exists() || z.mkdirs()) return z
            }
        }
        runCatching {
            val ext = ctx.getExternalFilesDir(null)
            if (ext != null) return File(ext, "zhome").apply { mkdirs() }
        }
        return File(ctx.filesDir, "zhome").apply { mkdirs() }
    }

    /** `<Downloads>/zhome/download` (falls back to app-scoped storage). */
    fun globalDownloadDir(ctx: Context): File =
        File(globalZhomeDir(ctx), "download").apply { mkdirs() }

    /** `<Downloads>/zhome/cache` (falls back to app-scoped storage). */
    fun globalCacheDir(ctx: Context): File =
        File(globalZhomeDir(ctx), "cache").apply { mkdirs() }

    fun readBootstrapLastUser(ctx: Context): String {
        val f = File(ctx.filesDir, BOOTSTRAP_FILE_NAME)
        if (!f.isFile) return ""
        return try {
            val props = java.util.Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty(BOOTSTRAP_LAST_USER, "")
        } catch (_: Exception) {
            ""
        }
    }

    fun writeBootstrapLastUser(ctx: Context, username: String) {
        val f = File(ctx.filesDir, BOOTSTRAP_FILE_NAME)
        try {
            val props = java.util.Properties()
            if (f.isFile) {
                f.inputStream().use { props.load(it) }
            }
            props.setProperty(BOOTSTRAP_LAST_USER, username)
            f.outputStream().use { props.store(it, "zhome bootstrap") }
        } catch (_: Exception) {
        }
    }

    fun userKey(username: String): String =
        username.trim().lowercase().ifBlank { ANONYMOUS_USER_KEY }
            .map { ch ->
                when {
                    ch.isLetterOrDigit() -> ch
                    ch == '.' || ch == '_' || ch == '-' -> ch
                    else -> '_'
                }
            }.joinToString("")
            .take(80)

    fun macKey(mac: String): String =
        mac.trim().lowercase().ifBlank { "_" }
            .map { ch ->
                when {
                    ch in '0'..'9' || ch in 'a'..'f' -> ch
                    else -> '_'
                }
            }.joinToString("")
            .take(64)
}
