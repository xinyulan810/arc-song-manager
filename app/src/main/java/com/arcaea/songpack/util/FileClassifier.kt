package com.arcaea.songpack.util

import android.graphics.BitmapFactory
import com.arcaea.songpack.model.ClassifiedFile
import com.arcaea.songpack.model.FileType
import java.io.File

/**
 * 解压目录扫描 + 自动识别文件用途
 *
 * 识别策略:
 *  - songlist    文件名包含 "songlist" 或 "slst"(任意后缀)
 *  - AFF         扩展名 .aff
 *  - AUDIO       扩展名 .ogg / .mp3 / .m4a / .wav / .flac
 *  - 图片:
 *      * 先解析 songlist 的 bg 字段 -> bg 引用的图片视为背景图(通常只有一个)
 *      * *_256 / *_thumb / *_s 视为封面缩略图
 *      * 已知 bg 时: 其余图片都视为封面(封面可能有多个, 如 base.jpg + 3.jpg)
 *      * 无 bg 信息时: 用命名启发式(base/jacket/cover 为封面, 其余为背景)
 */
object FileClassifier {

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp")
    private val AUDIO_EXTS = setOf("ogg", "mp3", "m4a", "wav", "flac", "aac", "opus")
    private val IMAGE_TYPES = setOf(FileType.JACKET, FileType.JACKET_THUMB, FileType.BACKGROUND)

    fun classify(directory: File): List<ClassifiedFile> {
        val files = directory.walkTopDown()
            .filter { it.isFile }
            .toList()

        // 先解析 songlist 拿到 bg 字段集合
        val bgNames = extractBgNames(files)

        val result = files.map { file ->
            val type = guessType(file, bgNames)
            ClassifiedFile(
                file = file,
                relativePath = file.relativeTo(directory).path.replace('\\', '/'),
                size = file.length(),
                type = type,
                resolution = if (type in IMAGE_TYPES) detectResolution(file) else null
            )
        }.toMutableList()

        // 兜底: 没有任何封面时, 把第一张非缩略图图片设为封面
        if (result.none { it.type == FileType.JACKET }) {
            result.firstOrNull { it.type == FileType.BACKGROUND }
                ?.let { it.type = FileType.JACKET }
        }

        // 按类型排序: songlist → 音乐 → 谱面 → 封面 → 封面_256 → bg背景 → 未识别 → 忽略
        val priority = mapOf(
            FileType.SONGLIST to 0,
            FileType.AUDIO to 1,
            FileType.AFF to 2,
            FileType.JACKET to 3,
            FileType.JACKET_THUMB to 4,
            FileType.BACKGROUND to 5,
            FileType.UNKNOWN to 6,
            FileType.IGNORE to 7
        )
        result.sortBy { priority[it.type] ?: 99 }

        return result
    }

    /** 从 songlist 中提取所有 bg 字段名(背景图只有一个, 封面可多个) */
    private fun extractBgNames(files: List<File>): Set<String> {
        val songlistFile = files.firstOrNull { f ->
            val n = f.name.lowercase()
            n.contains("songlist") || n.contains("slst")
        } ?: return emptySet()
        return try {
            SonglistParser.parseFragmentObjects(songlistFile.readText())
                .mapNotNull { it.optString("bg").takeIf { b -> b.isNotBlank() } }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** 读取图片宽x高(仅读头部, 不加载整图); 失败返回 null */
    private fun detectResolution(file: File): String? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                "${opts.outWidth}x${opts.outHeight}"
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun guessType(file: File, bgNames: Set<String>): FileType {
        val name = file.name.lowercase()
        val base = name.substringBeforeLast('.').lowercase()
        val ext = file.extension.lowercase()

        // songlist
        if (name.contains("songlist") || name.contains("slst")) return FileType.SONGLIST

        // 谱面
        if (ext == "aff") return FileType.AFF

        // 音频
        if (ext in AUDIO_EXTS) return FileType.AUDIO

        // 图片
        if (ext in IMAGE_EXTS) {
            // 背景图: songlist bg 字段引用的(通常只有一个)
            if (bgNames.isNotEmpty() && base in bgNames) return FileType.BACKGROUND

            // 封面缩略图
            if (base.endsWith("_256") || base.contains("_256") ||
                base.endsWith("_thumb") || base.contains("_thumb") ||
                base.endsWith("_s")
            ) {
                return FileType.JACKET_THUMB
            }

            // 已知 bg 信息时: 除背景外的图片都视为封面(封面可能多个)
            if (bgNames.isNotEmpty()) return FileType.JACKET

            // 无 bg 信息时: 命名启发式
            if (base == "base" || base == "jacket" || base == "cover") return FileType.JACKET

            return FileType.BACKGROUND
        }

        return FileType.UNKNOWN
    }
}
