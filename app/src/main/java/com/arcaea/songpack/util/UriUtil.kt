package com.arcaea.songpack.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * SAF (Storage Access Framework) 辅助工具
 */
object UriUtil {

    /** 将 content Uri 指向的文件复制到应用缓存目录,返回本地 File */
    fun copyToCache(context: Context, uri: Uri, name: String): File {
        val dest = File(context.cacheDir, name)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        input.use { ins ->
            dest.outputStream().use { outs -> ins.copyTo(outs) }
        }
        return dest
    }

    /** 人类可读文件大小 */
    fun humanReadableSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    /** 根据文件名推测 MIME 类型 */
    fun guessMimeType(name: String): String {
        return when (name.substringAfterLast('.').lowercase()) {
            "aff" -> "text/plain"
            "ogg" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
    }
}
