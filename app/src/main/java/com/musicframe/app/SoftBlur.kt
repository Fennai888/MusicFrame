
package com.musicframe.app

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * เบลอภาพแบบเบามาก: ย่อภาพเหลือกว้าง targetW พิกเซล แล้วเบลอบน CPU ครั้งเดียว
 * ใช้แทน Modifier.blur(...) ที่ต้องรันบน GPU ทุกเฟรม
 * (ภาพที่ได้เล็กมาก วาดขยายเต็มจอด้วย bilinear ก็นุ่มเหมือนภาพเบลอ)
 * ห้ามเรียก recycle กับ bitmap ต้นฉบับ เพราะอาจเป็นตัวเดียวกับที่แคชไว้
 */
fun softBlurBitmap(src: Bitmap, targetW: Int = 96, radius: Int = 3): Bitmap {
    val w = targetW
    val h = (src.height * w.toFloat() / src.width).roundToInt().coerceAtLeast(8)
    val base = if (src.width > w * 4) Bitmap.createScaledBitmap(src, w * 4, h * 4, true) else src
    val small = Bitmap.createScaledBitmap(base, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    boxBlur(px, w, h, radius)
    boxBlur(px, w, h, radius)
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    val tmp = IntArray(px.size)
    blurPass(px, tmp, w, h, r, true)
    blurPass(tmp, px, w, h, r, false)
}

private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val len = if (horizontal) w else h
    val lines = if (horizontal) h else w
    val div = 2 * r + 1
    for (l in 0 until lines) {
        var a = 0
        var rr = 0
        var g = 0
        var b = 0
        for (i in -r..r) {
            val k = i.coerceIn(0, len - 1)
            val c = src[if (horizontal) l * w + k else k * w + l]
            a += c ushr 24
            rr += (c shr 16) and 255
            g += (c shr 8) and 255
            b += c and 255
        }
        for (i in 0 until len) {
            dst[if (horizontal) l * w + i else i * w + l] =
                ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
            val ai = (i + r + 1).coerceAtMost(len - 1)
            val si = (i - r).coerceAtLeast(0)
            val add = src[if (horizontal) l * w + ai else ai * w + l]
            val sub = src[if (horizontal) l * w + si else si * w + l]
            a += (add ushr 24) - (sub ushr 24)
            rr += ((add shr 16) and 255) - ((sub shr 16) and 255)
            g += ((add shr 8) and 255) - ((sub shr 8) and 255)
            b += (add and 255) - (sub and 255)
        }
    }
}
