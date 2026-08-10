package com.arcaea.songpack.util

import com.github.junrar.Archive
import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 压缩包解压工具,支持 zip / rar
 */
object ArchiveExtractor {

    /** 解压 archiveFile 到 destDir,返回解压出的文件路径列表(相对 destDir) */
    fun extract(archiveFile: File, destDir: File): List<String> {
        destDir.mkdirs()
        return when (archiveFile.extension.lowercase()) {
            "zip" -> extractZip(archiveFile, destDir)
            "rar" -> extractRar(archiveFile, destDir)
            else -> throw IllegalArgumentException("不支持的文件格式: ${archiveFile.extension}")
        }
    }

    private fun extractZip(archiveFile: File, destDir: File): List<String> {
        val zip = ZipFile(archiveFile)
        zip.extractAll(destDir.absolutePath)
        // zip4j 内部已防护路径穿越;这里收集所有文件头用于返回
        return zip.fileHeaders
            .filter { !it.isDirectory }
            .map { it.fileName }
    }

    private fun extractRar(archiveFile: File, destDir: File): List<String> {
        val extracted = mutableListOf<String>()
        val archive = Archive(archiveFile.inputStream())
        try {
            var header = archive.nextFileHeader()
            while (header != null) {
                if (!header.isDirectory) {
                    val name = header.fileNameString
                    val outFile = safeResolve(destDir, name)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        archive.extractFile(header, fos)
                    }
                    extracted.add(name)
                }
                header = archive.nextFileHeader()
            }
        } finally {
            try { archive.close() } catch (_: Exception) {}
        }
        return extracted
    }

    /** 防止路径穿越:规范化目标路径并确保在 destDir 内 */
    private fun safeResolve(destDir: File, entryName: String): File {
        val cleanName = entryName.replace('\\', '/')
        val normalized = cleanName
            .split('/')
            .filter { it.isNotBlank() && it != "." }
            .joinToString(File.separator)
        val target = File(destDir, normalized).canonicalFile
        val base = destDir.canonicalFile
        if (!target.path.startsWith(base.path + File.separator) && target.path != base.path) {
            throw IOException("压缩包内含有非法路径: $entryName")
        }
        return target
    }
}
