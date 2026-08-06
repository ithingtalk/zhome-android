package com.ithingtalk.zhome.util

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder

object QrBitmap {
    fun encode(content: String, sizePx: Int = 512): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints,
        )
        return BarcodeEncoder().createBitmap(matrix)
    }
}
