package com.ithingtalk.zhome.data.remote.nas

import com.ithingtalk.zhome.Constants
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * NAS 文件 URL 规则，对应 zhome-qml `NasApi` 的 [getRemoteFilePath] / [p2pRemotePathFromHttpsUrl] /
 * [getPlayerUrl] / [fileUser] / [filePasswd]。
 *
 * 典型数据流：
 * ```
 * DB remotePath  ──getPlayerHttpUrl──▶  播放 URL
 *   (逻辑路径)         ├─ LAN:  https://nas-ip/~user/Video/x.mp4（播放经 `NasLanLoopbackHttpServer` → `http://127.0.0.1:6000`）
 *                      └─ P2P:  http://127.0.0.1:5000/Ftp/user%40qq.com/MyFiles/Video/x.mp4
 *                 ──playbackUrlWithAuth──▶  嵌入 user:pass 的最终 MRL
 *                      http://user%40qq.com:pass@127.0.0.1:5000/Ftp/user%40qq.com/MyFiles/Video/x.mp4
 * ```
 */
object NasUrl {

    // P2P_HTTP_* come from native at runtime — cannot use const val.
    private val p2pBase: String
        get() = "http://${Constants.P2P_HTTP_IP}:${Constants.P2P_HTTP_PORT}/"

    fun dbFileDownloadAddress(deviceIp: String): String =
        "https://$deviceIp/file/download.cgi?"

    /**
     * P2P remote path for the user's file database.
     * NAS stores it at `/mnt/sda1/DB/{user}/file.db`.
     * P2P file API now accepts DB-root form directly.
     * Matches Qt [NasApi::p2pRemotePathFromHttpsUrl] for TAG_DB_FILE.
     */
    fun p2pUserDbPath(accountUser: String): String =
        "DB/$accountUser/file.db"

    /**
     * P2P remote path for the shared file database.
     * NAS stores it at `/mnt/sda1/DB/SHARED/shared.db`.
     * Matches Qt [NasApi::p2pRemotePathFromHttpsUrl] for TAG_SHARED_DB_FILE.
     */
    fun p2pSharedDbPath(): String =
        "DB/SHARED/shared.db"

    // ── LAN URL ─────────────────────────────────────────────────────────

    /**
     * 逻辑路径 → 直连 LAN HTTPS URL（同 Qt `NasApi::getRemoteFilePath` 语义，scheme 为 https）。
     *
     * - 私有: `https://ip/~user/Video/x.mp4`
     * - 共享: `https://ip/~share@nas/user/MyFiles/Video/x.mp4`
     */
    fun getRemoteFilePath(
        deviceIp: String,
        accountUser: String,
        strPath: String,
        isShared: Boolean,
    ): String {
        val base = "https://$deviceIp"
        return if (isShared) {
            val rest = if (strPath.startsWith("/Ftp")) strPath.substring("/Ftp".length) else strPath
            "$base${Constants.TAG_SHARED_FILE_URL}$rest"
        } else {
            val tail = strPath.let {
                val i = it.indexOf(Constants.TAG_MYFILES)
                if (i < 0) it else it.substring(i + Constants.TAG_MYFILES.length)
            }
            "$base/~$accountUser$tail"
        }
    }

    // ── P2P 路径转换 ────────────────────────────────────────────────────

    /**
     * LAN HTTP URL → libp2p P2P 路径。私有和共享统一为 `Ftp/user/MyFiles/…` 格式，
     * 与 NAS 侧 `file_parse_request_cmd` 的 `strHomeDir/Ftp/user/MyFiles/…` 解析一致。
     *
     * libp2p HTTP 代理的 `is_safe_relative_path` 拒绝含 `..` 的路径，因此不能用
     * Qt 的 `../user/…` 共享路径形式。
     *
     * | 类型 | 输入 | 输出 |
     * |------|------|------|
     * | 私有 | `https://ip/~user/Video/…` | `Ftp/user/MyFiles/Video/…` |
     * | 共享 | `https://ip/~share@nas/user/MyFiles/…` | `Ftp/user/MyFiles/…` |
     */
    private fun p2pRelativePath(httpUrl: String, accountUser: String): String {
        if (!httpUrl.startsWith("http", ignoreCase = true)) return httpUrl

        if (httpUrl.contains(Constants.TAG_SHARED_FILE_URL)) {
            val i = httpUrl.indexOf(Constants.TAG_SHARED_FILE_URL)
            val rest = httpUrl.substring(i + Constants.TAG_SHARED_FILE_URL.length).trimStart('/')
            return "Ftp/$rest"
        }

        val userTag = "/~$accountUser"
        val i = httpUrl.indexOf(userTag)
        if (i >= 0) {
            val rest = httpUrl.substring(i + userTag.length)
            return "Ftp/$accountUser${Constants.TAG_MYFILES}$rest"
        }
        return ""
    }

    /**
     * Logical remotePath (from `file.db`) → libp2p remote relative path for native file transfer.
     *
     * Output example: `Ftp/user@host/MyFiles/Video/a.mkv` or `Ftp/user/MyFiles/...` for shared.
     * This mirrors Qt `NasApi::p2pRemotePathFromHttpsUrl` behavior for file transfers.
     */
    fun p2pRemoteFilePath(
        deviceIp: String,
        accountUser: String,
        logicalRemotePath: String,
        isShared: Boolean,
    ): String {
        val direct = normalizeLibp2pTransferPath(logicalRemotePath, accountUser)
        if (direct.isNotEmpty()) return direct

        val httpUrl = getRemoteFilePath(deviceIp, accountUser, logicalRemotePath, isShared)
        return p2pRelativePath(httpUrl, accountUser)
    }

    private fun normalizeLibp2pTransferPath(path: String, accountUser: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return ""

        return when {
            trimmed.startsWith("DB/") || trimmed.startsWith("Ftp/") -> trimmed
            trimmed.startsWith("/DB/") || trimmed.startsWith("/Ftp/") -> trimmed.substring(1)
            trimmed.startsWith("/MyFiles/") -> "Ftp/$accountUser$trimmed"
            trimmed.startsWith("MyFiles/") -> "Ftp/$accountUser/$trimmed"
            trimmed.contains("/MyFiles/") -> {
                val idx = trimmed.indexOf("/MyFiles/")
                "Ftp/$accountUser/${trimmed.substring(idx + 1)}"
            }
            trimmed.contains("MyFiles/") -> {
                val idx = trimmed.indexOf("MyFiles/")
                "Ftp/$accountUser/${trimmed.substring(idx)}"
            }
            else -> ""
        }
    }

    // ── 播放 URL 组装 ───────────────────────────────────────────────────

    /**
     * 逻辑路径（或已有 HTTP URL）→ 最终播放 URL（不含认证信息）。
     *
     * - LAN: 直连 `https://nas-ip/~user/…`
     * - P2P: `http://127.0.0.1:5000/Ftp/user@host/MyFiles/…`（中文文件名不编码，且用户名中的 '@' 保持原样不被编码）
     */
    fun getPlayerHttpUrl(
        strPathOrUrl: String,
        deviceIp: String,
        accountUser: String,
        useLocalLink: Boolean,
        isShared: Boolean,
    ): String {
        if (useLocalLink) {
            val rawHttpUrl = if (strPathOrUrl.startsWith("http", ignoreCase = true)) {
                strPathOrUrl
            } else {
                getRemoteFilePath(deviceIp, accountUser, strPathOrUrl, isShared)
            }
            return normalizePlaybackUrl(rawHttpUrl)
        }

        // Remote/P2P: logical paths do not need a LAN IP (aligned with iOS buildRemotePlaybackURL).
        val p2pPath = if (strPathOrUrl.startsWith("http", ignoreCase = true)) {
            // Derive from raw URL before normalization so '@' in accountUser is not encoded yet.
            p2pRelativePath(strPathOrUrl, accountUser)
        } else {
            p2pRemoteFilePath(deviceIp, accountUser, strPathOrUrl, isShared)
        }
        if (p2pPath.isEmpty()) return ""
        val normalizedPath = normalizeP2pPlaybackPath(p2pPath)
        return p2pBase + if (normalizedPath.startsWith("/")) normalizedPath.substring(1) else normalizedPath
    }

    /**
     * Percent-encode P2P path segments but keep '@' literal (NAS libp2p expects literal '@' in path).
     */
    private fun normalizeP2pPlaybackPath(p2pPath: String): String {
        return p2pPath.split("/")
            .joinToString("/") { segment ->
                encodePathSegment(segment).replace("%40", "@")
            }
    }

    private fun normalizePlaybackUrl(url: String): String {
        val trimmed = url.trim()
        val parsed = trimmed.toHttpUrlOrNull() ?: return trimmed

        val encodedSegments = parsed.pathSegments.map { encodePathSegment(it) }
        val builder = parsed.newBuilder()
        builder.encodedPath("/")
        encodedSegments.forEach { builder.addEncodedPathSegment(it) }
        return builder.build().toString()
    }

    private fun encodePathSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")

    // ── HTTP 认证 ───────────────────────────────────────────────────────

    /**
     * 文件访问的 HTTP Basic 凭据（同 Qt `NasApi::fileUser` / `filePasswd`）。
     *
     * P2P 代理 (`127.0.0.1:5000`) 不检查认证，统一用账号凭据；
     * LAN 直连时共享文件仍需 `share@nas` + sharePwd。
     */
    fun fileHttpCredentials(
        remoteHttpUrl: String,
        isShared: Boolean,
        dbDownloadBase: String,
        accountUser: String,
        accountPass: String,
        sharePwd: String,
    ): Pair<String, String> {
        if (remoteHttpUrl.contains("127.0.0.1") && remoteHttpUrl.contains(":${NasLanLoopbackHttpServer.LISTEN_PORT}")) {
            return "" to ""
        }
        if (remoteHttpUrl.startsWith(p2pBase)) return accountUser to accountPass
        if (remoteHttpUrl.startsWith(dbDownloadBase) || !isShared) return accountUser to accountPass
        return Constants.SHARE_HTTP_USER to sharePwd
    }

    /**
     * 把 HTTP Basic 认证嵌入 URL（同 Qt `QUrl::setUserName` / `setPassword`）。
     *
     * P2P 代理 URL 走手工拼接（保持路径里中文原样不编码）；LAN URL 走 OkHttp Builder。
     *
     * 输出示例：
     * `http://user%40example.com:password@127.0.0.1:5000/Ftp/user%40example.com/MyFiles/Video/sample.mp4`
     */
    fun playbackUrlWithBasicAuth(baseUrl: String, user: String?, password: String?): String {
        val u = user?.trim().orEmpty()
        val p = password?.trim().orEmpty()
        if (u.isEmpty() && p.isEmpty()) return baseUrl.trim()
        val base = baseUrl.trim()

        if (base.startsWith(p2pBase)) {
            val path = base.substring(p2pBase.length)
            return "http://${u.replace("@", "%40")}:${p.replace("@", "%40")}" +
                    "@${Constants.P2P_HTTP_IP}:${Constants.P2P_HTTP_PORT}/$path"
        }

        val parsed = base.toHttpUrlOrNull() ?: return base
        return parsed.newBuilder().username(u).password(p).build().toString()
    }
}

/** 与 NAS `video_cvt`：`dirname/.<basename>/.hd.mp4`、`.sd.mp4` */
enum class VideoTranscodeQuality {
    ORIGINAL,
    HD,
    SD,
}

fun transcodedNasVideoRemotePath(remotePath: String, quality: VideoTranscodeQuality): String {
    if (quality == VideoTranscodeQuality.ORIGINAL) return remotePath
    val needle = "${Constants.TAG_VIDEO}/"
    if (!remotePath.contains(needle)) return remotePath
    val slash = remotePath.lastIndexOf('/')
    if (slash < 0) return remotePath
    val dir = remotePath.substring(0, slash)
    val file = remotePath.substring(slash + 1)
    if (file.startsWith('.')) return remotePath
    val dot = file.lastIndexOf('.')
    val base = if (dot > 0) file.substring(0, dot) else file
    if (base.isEmpty()) return remotePath
    val sub = "$dir/.$base"
    return when (quality) {
        VideoTranscodeQuality.HD -> "$sub/.hd.mp4"
        VideoTranscodeQuality.SD -> "$sub/.sd.mp4"
        else -> remotePath
    }
}

fun transcodedNasImageRemotePath(remotePath: String): String {
    val needle = "${Constants.TAG_IMAGE}/"
    if (!remotePath.contains(needle)) return remotePath
    val slash = remotePath.lastIndexOf('/')
    if (slash < 0) return remotePath
    val dir = remotePath.substring(0, slash)
    val file = remotePath.substring(slash + 1)
    if (file.isEmpty() || file.startsWith('.')) return remotePath
    val dot = file.lastIndexOf('.')
    val base = if (dot > 0) file.substring(0, dot) else file
    if (base.isEmpty()) return remotePath
    val ext = if (dot > 0) file.substring(dot) else ""
    return "$dir/.$base/.sd$ext"
}
