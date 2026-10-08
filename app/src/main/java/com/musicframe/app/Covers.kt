package com.musicframe.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache

object Covers {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun load(ctx: Context, s: Song, maxSide: Int): Bitmap? {
        val key = s.uri + "|" + s.cover + "|" + maxSide
        cache.get(key)?.let { return it }
        val bmp = try {
            if (s.cover.isNotBlank()) loadCustom(ctx, s.cover, maxSide) else loadEmbedded(ctx, s.uri, maxSide)
        } catch (e: Exception) {
            null
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    private fun loadCustom(ctx: Context, uri: String, maxSide: Int): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val m = maxOf(info.size.width, info.size.height)
            if (m > maxSide) dec.setTargetSampleSize(m / maxSide)
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
        } finally {
            r.release()
        }
    }
}
