package com.dirtunnel.core.network

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码生成（REQ 4.3.1 公共隧道：生成外网访问地址 + 二维码；
 * TECH 模块拆分 9 · core:network：二维码生成）。
 */
object QrCodeEncoder {

    /**
     * @param text 二维码内容（访问地址）
     * @param sizePx 输出边长（px）
     * @return 黑白高对比二维码，扫码成功率最高；失败返回 null
     */
    fun encode(text: String, sizePx: Int = 640, margin: Int = 2): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to margin,
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    pixels[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            null
        }
    }
}
