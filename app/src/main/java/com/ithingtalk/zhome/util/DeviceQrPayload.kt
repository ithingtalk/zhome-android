package com.ithingtalk.zhome.util

import com.ithingtalk.zhome.data.local.db.DeviceEntity
import org.json.JSONObject

/**
 * 设备分享二维码：**仅**支持 `zh2:` + JSON（v2），与 Qt [DbDevices::buildQrSharePayload] 字段一致。
 */
data class QrDeviceV2Record(
    val mac: String,
    val sn: String,
    val name: String,
    val ip: String,
    val cfg: String,
    val online: String,
    val pending: String,
)

object DeviceQrPayload {

    const val MAGIC_V2 = "zh2:"

    /** V2：自 [DeviceEntity] 编码。 */
    fun encodeV2FromEntity(d: DeviceEntity): String? {
        if (d.mac.isBlank() || d.sn.isBlank()) return null
        val o = JSONObject()
        o.put("v", 2)
        o.put("m", d.mac)
        o.put("s", d.sn)
        o.put("n", d.name)
        o.put("i", "")
        o.put("c", d.cfg)
        o.put("o", d.online)
        o.put("p", pendingExportForV2(d.pending))
        return MAGIC_V2 + o.toString()
    }

    private fun pendingExportForV2(p: String): String {
        return when (p) {
            "add" -> "Add"
            "del" -> "Del"
            else -> "None"
        }
    }

    /** 解析 `zh2:{...}`；非 V2 或缺 mac/sn 时返回 null。 */
    fun parseV2(raw: String): QrDeviceV2Record? {
        val t = raw.trim()
        if (!t.startsWith(MAGIC_V2)) return null
        return try {
            val o = JSONObject(t.substring(MAGIC_V2.length))
            if (o.optInt("v") != 2) return null
            val mac = o.optString("m", "").trim()
            val sn = o.optString("s", "").trim()
            if (mac.isEmpty() || sn.isEmpty()) return null
            QrDeviceV2Record(
                mac = mac,
                sn = sn,
                name = o.optString("n", "").trim(),
                ip = o.optString("i", "").trim(),
                cfg = o.optString("c", "").trim(),
                online = o.optString("o", "").trim(),
                pending = normalizePendingImport(o.optString("p", "")),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizePendingImport(p: String): String {
        return when (p.trim().lowercase()) {
            "", "none" -> ""
            "add" -> "add"
            "del" -> "del"
            else -> ""
        }
    }
}
