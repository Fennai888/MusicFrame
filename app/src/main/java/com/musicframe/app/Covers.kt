package com.musicframe.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache

data class CoverSpec(val image: String, val video: String, val sound: Boolean)

fun parseCover(c: String): CoverSpec {
    if (!c.startsWith("mfv:")) return CoverSpec(c, "", false)
    val parts = c.removePrefix("mfv:").split("\n", limit = 3)
    return CoverSpec(
        image = parts.getOrElse(2) { "" },
        video = parts.getOrElse(1) { "" },
        sound = parts.getOrElse(0) { "0" } == "1"
    )
}

fun buildCover(spec: CoverSpec): String =
    if (spec.video.isBlank()) {
        spec.image
    } else {
        "mfv:" + (if (spec.sound) "1" else "0") + "\n" + spec.video + "\n" + spec.image
    }

object Covers {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun load(ctx: Context, s: Song, maxSide: Int): Bitmap? {
        val key = s.uri + "|" + s.cover + "|" + maxSide
        cache.get(key)?.let { return it }
        val spec = parseCover(s.cover)
        val bmp = try {
            var b: Bitmap? = null
            if (spec.video.isNotBlank()) b = loadVideoFrame(ctx, spec.video, maxSide)
            if (b == null && spec.image.isNotBlank()) b = loadCustom(ctx, spec.image, maxSide)
            if (b == null) b = loadEmbedded(ctx, s.uri, maxSide)
            b
        } catch (e: Exception) {
            null
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    private fun loadCustom(ctx: Context, uri: String, maxSide: Int): Bitmap? {
        return try {
            val src = ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))
            ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val m = maxOf(info.size.width, info.size.height)
                if (m > maxSide) dec.setTargetSampleSize(m / maxSide)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadVideoFrame(ctx: Context, uri: String, maxSide: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, Uri.parse(uri))
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val ow = if (rot == 90 || rot == 270) h else w
            val oh = if (rot == 90 || rot == 270) w else h
            var bmp: Bitmap? = null
            if (Build.VERSION.SDK_INT >= 27 && ow > 0 && oh > 0) {
                val m = maxOf(ow, oh)
                val sc = if (m > maxSide) maxSide.toFloat() / m else 1f
                val dw = (ow * sc).toInt().coerceAtLeast(1)
                val dh = (oh * sc).toInt().coerceAtLeast(1)
                bmp = r.getScaledFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dw, dh)
                if (bmp == null) {
                    bmp = r.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dw, dh)
                }
            }
            if (bmp == null) {
                bmp = r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            return bmp
        } catch (e: Exception) {
            return null
        } finally {
            r.release()
        }
    }

    private fun loadEmbedded(ctx: Context, uri: String, maxSide: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, Uri.parse(uri))
            val data = r.embeddedPicture ?: return null
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bo)
            var ss = 1
            while (maxOf(bo.outWidth, bo.outHeight) / ss > maxSide) ss *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = ss }
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, o) ?: return null
            return prepareCover(bmp)
        } catch (e: Exception) {
            return null
        } finally {
            r.release()
        }
    }
}
