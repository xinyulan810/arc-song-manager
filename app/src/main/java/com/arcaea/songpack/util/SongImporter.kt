package com.arcaea.songpack.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.arcaea.songpack.model.ClassifiedFile
import com.arcaea.songpack.model.FileType
import com.arcaea.songpack.model.SongEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 导入工具: 将识别出的文件 + songlist 条目写入游戏目录
 *
 * 游戏目录规则(基于 Arc6 示例):
 *  - 歌曲文件夹 -> songs/<id>/
 *  - songlist    -> songs/songlist   (结构 {"songs":[...]})
 *  - 背景图      -> img/bg/1080/  (优先) 或 img/bg/
 *  - 歌曲目录内: base.ogg 音乐, base.jpg 封面, base_256.jpg 缩略图, *.aff 谱面
 *
 * 导入分两步:
 *  1. prepareImport(): 只生成合并后的 songlist 文本, 不写盘(用于预览)
 *  2. executeImport(): 复制文件 + 备份原 songlist(songlist.backup) + 写入新 songlist
 */
object SongImporter {

    data class ImportResult(
        val success: Boolean,
        val message: String,
        val importedIds: List<String>,
        val replacedIds: List<String>
    )

    /** 预览阶段的准备结果(未写盘) */
    data class PreparedImport(
        val newSonglistText: String,
        val replacedIds: List<String>,
        val songIds: List<String>,
        val hadExistingSonglist: Boolean,
        val existingSonglistText: String?,
        /** bg 字段引用的背景图在游戏目录中不存在(需要用户编辑 bg 或忽略) */
        val missingBg: List<String> = emptyList()
    )

    /**
     * 第一步: 生成合并后的 songlist 文本(不写入磁盘), 用于预览确认
     * @param bgFile 包内识别为背景图的文件; 若非空则用其文件名更新各条目 bg 字段
     */
    fun prepareImport(
        context: Context,
        gameRootUri: Uri,
        entries: List<SongEntry>,
        bgFile: ClassifiedFile?
    ): PreparedImport? {
        val gameRoot = DocumentFile.fromTreeUri(context, gameRootUri) ?: return null
        val songsDir = gameRoot.findFile("songs") ?: return null
        val existingDoc = songsDir.findFile("songlist")
        val existingText = readDocText(context, existingDoc)

        // 定位游戏背景图目录用于检查 bg 引用
        val imgDir = gameRoot.findFile("img")
        val bgDir = imgDir?.findFile("bg")
        val bg1080 = bgDir?.findFile("1080")
        val bgSearchDirs = listOfNotNull(bg1080, bgDir)

        // 若包内有背景图, 用其文件名(去扩展名)更新 bg 字段;
        // 仅当条目原本没有 bg 时才写入, 不覆盖用户手动设置的值
        val bgName = bgFile?.file?.name?.substringBeforeLast('.')
        if (bgName != null) {
            entries.forEach { entry ->
                if (entry.raw.optString("bg").isBlank()) entry.raw.put("bg", bgName)
            }
        }

        // 检查 bg 引用在游戏目录中是否存在(仅在包内无背景图时检查, 否则 bg 已更新为包内文件)
        val missingBg = mutableListOf<String>()
        if (bgFile == null) {
            for (entry in entries) {
                val ref = entry.raw.optString("bg")
                if (ref.isBlank()) continue
                val found = bgSearchDirs.any { d ->
                    d.findFile("$ref.jpg") != null || d.findFile("$ref.png") != null ||
                        d.findFile("$ref.jpeg") != null
                }
                if (!found) missingBg.add("${entry.id} -> $ref")
            }
        }

        val (newText, replaced) = SonglistParser.merge(existingText, entries.map { it.raw })
        return PreparedImport(
            newSonglistText = newText,
            replacedIds = replaced,
            songIds = entries.map { it.id },
            hadExistingSonglist = existingDoc != null,
            existingSonglistText = existingText,
            missingBg = missingBg
        )
    }

    /**
     * 第二步: 执行导入(复制文件 + 备份 + 写入)
     */
    fun executeImport(
        context: Context,
        gameRootUri: Uri,
        classifiedFiles: List<ClassifiedFile>,
        entries: List<SongEntry>,
        prepared: PreparedImport
    ): ImportResult {
        val messages = mutableListOf<String>()
        val imported = mutableListOf<String>()
        val replaced = prepared.replacedIds.toMutableList()

        val gameRoot = DocumentFile.fromTreeUri(context, gameRootUri)
            ?: return ImportResult(false, "无法访问所选游戏目录", emptyList(), emptyList())

        val songsDir = findOrCreateDir(gameRoot, "songs")
            ?: return ImportResult(false, "无法访问/创建 songs 目录", emptyList(), emptyList())
        val imgDir = findOrCreateDir(gameRoot, "img")
        val bgDir = imgDir?.let { findOrCreateDir(it, "bg") }
        val bgTarget = bgDir?.let { d -> d.findFile("1080") ?: d }

        // 按类型分组
        val affFiles = classifiedFiles.filter { it.type == FileType.AFF }
        val audioFile = classifiedFiles.firstOrNull { it.type == FileType.AUDIO }
        val jacketFiles = classifiedFiles.filter { it.type == FileType.JACKET }
        val thumbFiles = classifiedFiles.filter { it.type == FileType.JACKET_THUMB }
        val bgFiles = classifiedFiles.filter { it.type == FileType.BACKGROUND }

        try {
            // 复制文件到歌曲目录
            for (entry in entries) {
                if (entry.id.isBlank()) continue
                val songDir = findOrCreateDir(songsDir, entry.id)
                    ?: throw IOException("无法创建歌曲目录: ${entry.id}")

                for (aff in affFiles) {
                    copyToDoc(context, aff.file, songDir, aff.file.name)
                }
                val audioName = entry.raw.optString("audio", "").ifBlank { "base.ogg" }
                audioFile?.let { copyToDoc(context, it.file, songDir, audioName) }

                // 封面: base.* 命名的作为默认封面(base.jpg), 其余封面保持原名(如 3.jpg 自定义封面)
                val jacketName = entry.raw.optString("jacket", "").ifBlank { "base.jpg" }
                val baseJacket = jacketFiles.firstOrNull { it.file.name.lowercase().startsWith("base.") }
                    ?: jacketFiles.firstOrNull()
                baseJacket?.let { copyToDoc(context, it.file, songDir, jacketName) }
                jacketFiles.filter { it !== baseJacket }
                    .forEach { copyToDoc(context, it.file, songDir, it.file.name) }

                // 封面缩略图: base_256 命名作为默认, 其余保持原名
                val baseThumb = thumbFiles.firstOrNull { it.file.name.lowercase().startsWith("base_256") }
                    ?: thumbFiles.firstOrNull()
                baseThumb?.let { copyToDoc(context, it.file, songDir, "base_256.jpg") }
                thumbFiles.filter { it !== baseThumb }
                    .forEach { copyToDoc(context, it.file, songDir, it.file.name) }

                imported.add(entry.id)
                messages.add("歌曲目录创建: ${entry.id}")
            }

            // 背景图 -> img/bg/1080/<原名> (bg 字段已在 prepare 阶段更新)
            if (bgFiles.isNotEmpty() && bgTarget != null) {
                for (bg in bgFiles) {
                    copyToDoc(context, bg.file, bgTarget, bg.file.name)
                    messages.add("背景图已复制: ${bg.file.name}")
                }
            }

            if (imported.isEmpty()) {
                return ImportResult(false, "没有可导入的歌曲(songlist 中缺少有效 id)", emptyList(), emptyList())
            }

            // 备份原 songlist -> songlist.backup
            if (prepared.hadExistingSonglist && prepared.existingSonglistText != null) {
                writeDocText(context, songsDir, "songlist.backup", prepared.existingSonglistText)
                messages.add("已备份原 songlist -> songlist.backup")
            }

            // 写入新 songlist
            writeDocText(context, songsDir, "songlist", prepared.newSonglistText)

            return ImportResult(
                success = true,
                message = buildString {
                    append("导入完成!\n")
                    append("新增 ${imported.size} 首: ").append(imported.joinToString(", ")).append('\n')
                    if (replaced.isNotEmpty()) append("替换 ${replaced.size} 首: ").append(replaced.joinToString(", ")).append('\n')
                    messages.forEach { append(it).append('\n') }
                }.trimEnd(),
                importedIds = imported,
                replacedIds = replaced
            )
        } catch (e: Exception) {
            return ImportResult(false, "导入失败: ${e.message}", imported, replaced)
        }
    }

    // ---------- 备份恢复 ----------

    data class RestoreResult(
        val success: Boolean,
        val message: String,
        /** 恢复后将从 songlist 中移除的歌曲 id(当前有但备份没有) */
        val willRemove: List<String>
    )

    /** 对比备份与当前 songlist, 返回"恢复后将被移除的歌曲 id"(当前有但备份没有) */
    fun diffBackup(context: Context, gameRootUri: Uri): List<String> {
        val (backupText, currentText) = readBackupAndCurrent(context, gameRootUri) ?: return emptyList()
        val backupIds = parseSongIds(backupText ?: "")
        val currentIds = parseSongIds(currentText ?: "")
        return currentIds.filter { it !in backupIds }
    }

    /** 从 songlist.backup 恢复 songlist */
    fun restoreFromBackup(context: Context, gameRootUri: Uri): RestoreResult {
        val gameRoot = DocumentFile.fromTreeUri(context, gameRootUri)
            ?: return RestoreResult(false, "无法访问游戏目录", emptyList())
        val songsDir = gameRoot.findFile("songs")
            ?: return RestoreResult(false, "未找到 songs 目录", emptyList())
        val backupDoc = songsDir.findFile("songlist.backup")
            ?: return RestoreResult(false, "未找到 songlist.backup 备份文件", emptyList())
        val currentDoc = songsDir.findFile("songlist")
        val backupText = readDocText(context, backupDoc)
            ?: return RestoreResult(false, "无法读取备份文件", emptyList())

        val willRemove = if (currentDoc != null) {
            val currentText = readDocText(context, currentDoc) ?: ""
            val backupIds = parseSongIds(backupText)
            parseSongIds(currentText).filter { it !in backupIds }
        } else emptyList()

        return try {
            writeDocText(context, songsDir, "songlist", backupText)
            RestoreResult(
                success = true,
                message = buildString {
                    append("已从 songlist.backup 恢复\n")
                    if (willRemove.isNotEmpty()) {
                        append("以下歌曲已从 songlist 中移除: ").append(willRemove.joinToString(", ")).append('\n')
                        append("(歌曲文件夹未删除, 可手动清理)")
                    } else {
                        append("备份与当前歌曲列表一致")
                    }
                }.trimEnd(),
                willRemove = willRemove
            )
        } catch (e: Exception) {
            RestoreResult(false, "恢复失败: ${e.message}", willRemove)
        }
    }

    /** 读取备份与当前 songlist 文本 */
    private fun readBackupAndCurrent(context: Context, gameRootUri: Uri): Pair<String?, String?>? {
        val gameRoot = DocumentFile.fromTreeUri(context, gameRootUri) ?: return null
        val songsDir = gameRoot.findFile("songs") ?: return null
        val backup = readDocText(context, songsDir.findFile("songlist.backup"))
        val current = readDocText(context, songsDir.findFile("songlist"))
        return backup to current
    }

    /** 从 songlist 文本中提取歌曲 id 集合(兼容 {"songs":[...]} 与顶层数组) */
    fun parseSongIds(text: String): Set<String> {
        return try {
            val trimmed = text.trim()
            val arr = try {
                JSONObject(trimmed).optJSONArray("songs")
            } catch (_: Exception) {
                null
            } ?: JSONArray(trimmed)
            val ids = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() } ?: continue
                ids.add(id)
            }
            ids
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** 检查这些歌曲 id 在游戏 songlist 中是否已存在 */
    fun findExistingIds(context: Context, gameRootUri: Uri, ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val gameRoot = DocumentFile.fromTreeUri(context, gameRootUri) ?: return emptyList()
        val songsDir = gameRoot.findFile("songs") ?: return emptyList()
        val songlistDoc = songsDir.findFile("songlist") ?: return emptyList()
        val text = readDocText(context, songlistDoc) ?: return emptyList()
        return try {
            val root = JSONObject(text.trim())
            val arr = root.optJSONArray("songs") ?: return emptyList()
            val existing = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id") ?: continue
                if (id in ids) existing.add(id)
            }
            existing
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- 私有辅助 ----------

    private fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.let { if (it.isDirectory) return it }
        return parent.createDirectory(name)
    }

    private fun copyToDoc(context: Context, src: File, parent: DocumentFile, name: String) {
        parent.findFile(name)?.delete()
        // 用 octet-stream 创建, 避免系统根据 mime 类型给文件名追加错误扩展名(如 3.aff -> 3.aff.txt)
        val doc = parent.createFile("application/octet-stream", name)
            ?: throw IOException("无法创建文件: $name")
        // 若创建后文件名被系统改动, 尝试改回原名, 失败则报错避免生成错误文件
        if (doc.name != name) {
            if (!doc.renameTo(name)) {
                throw IOException("无法创建文件 $name (系统将文件名改为 ${doc.name})")
            }
        }
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            src.inputStream().use { ins -> ins.copyTo(out) }
        } ?: throw IOException("无法写入文件: $name")
    }

    private fun readDocText(context: Context, doc: DocumentFile?): String? {
        if (doc == null) return null
        return context.contentResolver.openInputStream(doc.uri)?.use { ins ->
            ins.bufferedReader().readText()
        }
    }

    private fun writeDocText(context: Context, parent: DocumentFile, name: String, text: String) {
        parent.findFile(name)?.delete()
        val doc = parent.createFile("application/octet-stream", name)
            ?: throw IOException("无法创建文件: $name")
        if (doc.name != name) {
            if (!doc.renameTo(name)) {
                throw IOException("无法创建文件 $name (系统将文件名改为 ${doc.name})")
            }
        }
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("无法写入文件: $name")
    }
}
