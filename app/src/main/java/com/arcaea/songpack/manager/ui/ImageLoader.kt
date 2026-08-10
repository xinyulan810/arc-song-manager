package com.arcaea.songpack.manager.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Semaphore

/**
 * 轻量图片加载器: 异步 + 采样解码 + 内存缓存 + 并发限制。
 * 支持 file:// 与 content:// uri(File 直连场景用 file uri, 加载极快)。
 */
object ImageLoader {

    private const val MAX_SAMPLE = 8
    private const val CONCURRENCY = 4
    private const val CACHE_MAX_KB = 40 * 1024 // 40MB 内存缓存

    /** key=uri string, value=采样后的 bitmap */
    private val cache = object : LruCache<String, Bitmap>(CACHE_MAX_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val semaphore = Semaphore(CONCURRENCY)

    fun load(scope: LifecycleCoroutineScope, context: Context, uri: Uri?, imageView: ImageView) {
        if (uri == null) {
            imageView.setImageDrawable(null)
            return
        }
        // 内存缓存命中直接设置
        cache.get(uri.toString())?.let { bmp ->
            imageView.setImageBitmap(bmp)
            return
        }
        imageView.tag = uri.toString()
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                semaphore.acquire()
                try {
                    decodeSampled(context, uri)
                } catch (_: Exception) {
                    null
                } finally {
                    semaphore.release()
                }
            }
            if (bmp != null && imageView.tag == uri.toString()) {
                cache.put(uri.toString(), bmp)
                imageView.setImageBitmap(bmp)
            }
        }
    }

    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        // file:// uri 直接用 File 解码, 不走 ContentResolver(快)
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return null)
            if (!file.isFile) return null
            return decodeSampledFile(file)
        }
        return decodeSampledContent(context, uri)
    }

    private fun decodeSampledFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = calcSample(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun decodeSampledContent(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = calcSample(bounds.outWidth, bounds.outHeight) }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun calcSample(w: Int, h: Int): Int {
        var sample = 1
        val target = 512
        while (w / sample > target && h / sample > target && sample < MAX_SAMPLE) {
            sample *= 2
        }
        return sample
    }

    /** 清理缓存(退出/目录变更时可调用) */
    fun clearCache() {
        cache.evictAll()
    }
}
