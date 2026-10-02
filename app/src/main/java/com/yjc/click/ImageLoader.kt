package com.yjc.click

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * 背景图解码。
 *
 * 两个要点：
 * 1. 按需要的尺寸**采样解码**：整屏背景和缩略图都不该把原图整张读进内存（一张 5000 万像素的
 *    照片按原尺寸解出来就是几百兆，滑动透明度时会直接 OOM）；
 * 2. API 28+ 走 [ImageDecoder]，它会顺带处理 EXIF 旋转（手机竖拍的照片用 BitmapFactory 解出来是躺着的）。
 *
 * 结果按（路径, 目标尺寸）缓存最近几张，拖动滑块反复重组时不会重复解码。
 */
object ImageLoader {

    private data class Key(val path: String, val w: Int, val h: Int)

    private val cache = object : LinkedHashMap<Key, ImageBitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ImageBitmap>) = size > 4
    }

    /** 取一张不超过 reqW×reqH 的图（保持比例），解码失败返回 null */
    fun load(path: String, reqW: Int, reqH: Int): ImageBitmap? {
        if (path.isEmpty()) return null
        val file = File(path)
        if (!file.exists()) return null
        val key = Key(path, reqW.coerceAtLeast(1), reqH.coerceAtLeast(1))
        cache[key]?.let { return it }
        val bitmap = decode(file, key.w, key.h) ?: return null
        return bitmap.asImageBitmap().also { cache[key] = it }
    }

    /** 只解一个很小的采样，用来判断"这是不是一张能打开的图片" */
    fun isDecodableImage(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                    decoder.setTargetSampleSize(32)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.width > 0
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                bounds.outWidth > 0 && bounds.outHeight > 0
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun decode(file: File, reqW: Int, reqH: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val size = info.size
                var sample = 1
                while (size.width / (sample * 2) >= reqW && size.height / (sample * 2) >= reqH) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }
    } catch (e: Exception) {
        null
    }
}
