package com.arcaea.songpack.manager

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 版本化备份/还原系统
 *
 * 设计目标: 每一步改动都能还原, 至少能还原到改动之前。
 *
 * 机制:
 *  - 每次写 songlist / packlist 之前, 自动把当前内容存为带时间戳的快照
 *    <game>/backup/{毫秒时间戳}_songlist.json  /  {毫秒时间戳}_packlist.json
 *  - manifest.json 索引所有快照(时间/类型/标签/包含内容)
 *  - 保留最近 MAX_SNAPSHOTS 个快照, 超出自动删除最旧
 *  - 恢复任意快照时, 先自动保存当前状态为快照(可撤销恢复)
 *  - 备份目录放在游戏根目录 backup/, 不能放 songs/ 下(游戏会把 songs/ 子目录当歌曲文件夹)
 *
 * 性能: 优先 FileStore(File 直连), 失败回退 SAF DocumentFile。
 */
object BackupManager {

    const val BACKUP_DIR = "backup"
    private const val MANIFEST_FILE = "manifest.json"
    private const val MAX_SNAPSHOTS = 50

    enum class SnapshotType { AUTO, MANUAL, BEFORE_RESTORE }

    data class Snapshot(
        val timestamp: Long,
        val label: String,
        val type: SnapshotType,
        val hasSonglist: Boolean,
        val hasPacklist: Boolean
    ) {
        val typeLabel: String get() = when (type) {
            SnapshotType.AUTO -> "自动"
            SnapshotType.MANUAL -> "手动"
            SnapshotType.BEFORE_RESTORE -> "恢复前"
        }
    }

    // ---------- 目录 ----------

    /** File 直连备份目录 */
    fun backupFileDir(): File? = FileStore.backupDir()

    /** SAF 备份目录(File 不可用时) */
    fun backupDocDir(context: Context): DocumentFile? {
        val rootUri = GameRepository.getGameDir(context) ?: return null
        val root = GameRepository.root(context, rootUri) ?: return null
        return findOrCreateDocDir(root, BACKUP_DIR)
    }

    // ---------- 快照 ----------

    fun snapshot(context: Context, type: SnapshotType, label: String): String? {
        val songlistText = GameRepository.readCurrentSonglistText(context)
        val packlistText = GameRepository.readCurrentPacklistText(context)
        if (songlistText == null && packlistText == null) {
            return "songs 目录下没有 songlist/packlist 可备份"
        }
        val ts = System.currentTimeMillis()
        try {
            val fileDir = backupFileDir()
            if (fileDir != null) {
                if (!fileDir.isDirectory) fileDir.mkdirs()
                if (songlistText != null) File(fileDir, "${ts}_songlist.json").writeText(songlistText, Charsets.UTF_8)
                if (packlistText != null) File(fileDir, "${ts}_packlist.json").writeText(packlistText, Charsets.UTF_8)
            } else {
                val docDir = backupDocDir(context) ?: return "未找到游戏目录(请先选择)"
                if (songlistText != null) writeDoc(context, docDir, "${ts}_songlist.json", songlistText)
                if (packlistText != null) writeDoc(context, docDir, "${ts}_packlist.json", packlistText)
            }
            addToManifest(context, Snapshot(ts, label, type, songlistText != null, packlistText != null))
            trimOld(context)
            return null
        } catch (e: Exception) {
            return "备份失败: ${e.message}"
        }
    }

    fun listSnapshots(context: Context): List<Snapshot> {
        val manifestText = readManifest(context) ?: return emptyList()
        return try {
            val root = JSONObject(manifestText.trim())
            val arr = root.optJSONArray("snapshots") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = runCatching { SnapshotType.valueOf(o.optString("type", "AUTO")) }
                    .getOrDefault(SnapshotType.AUTO)
                Snapshot(
                    timestamp = o.optLong("timestamp", 0L),
                    label = o.optString("label", ""),
                    type = type,
                    hasSonglist = o.optBoolean("hasSonglist", false),
                    hasPacklist = o.optBoolean("hasPacklist", false)
                )
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun restore(context: Context, snap: Snapshot): String? {
        // 先备份当前状态, 以便撤销
        snapshot(context, SnapshotType.BEFORE_RESTORE, "恢复「${formatTime(snap.timestamp)}」前自动备份")
        try {
            if (snap.hasSonglist) {
                val text = readSnapshotFile(context, "${snap.timestamp}_songlist.json")
                    ?: return "快照 songlist 文件缺失"
                writeSonglist(context, text)
            }
            if (snap.hasPacklist) {
                val text = readSnapshotFile(context, "${snap.timestamp}_packlist.json")
                    ?: return "快照 packlist 文件缺失"
                writePacklist(context, text)
            }
            GameCache.invalidate()
            return null
        } catch (e: Exception) {
            return "恢复失败: ${e.message}"
        }
    }

    fun delete(context: Context, snap: Snapshot) {
        listOf("${snap.timestamp}_songlist.json", "${snap.timestamp}_packlist.json").forEach { name ->
            val fileDir = backupFileDir()
            if (fileDir != null) {
                File(fileDir, name).takeIf { it.isFile }?.delete()
            } else {
                backupDocDir(context)?.findFile(name)?.let { if (it.isFile) it.delete() }
            }
        }
        removeFromManifest(context, snap.timestamp)
    }

    fun hasSnapshots(context: Context): Boolean {
        val text = readManifest(context) ?: return false
        return text.contains("snapshots")
    }

    // ---------- manifest ----------

    private fun addToManifest(context: Context, snap: Snapshot) {
        val existing = readManifest(context)
        val root = try {
            JSONObject(existing ?: "")
        } catch (_: Exception) {
            JSONObject()
        }
        val arr = root.optJSONArray("snapshots") ?: JSONArray().also { root.put("snapshots", it) }
        arr.put(JSONObject().apply {
            put("timestamp", snap.timestamp)
            put("label", snap.label)
            put("type", snap.type.name)
            put("hasSonglist", snap.hasSonglist)
            put("hasPacklist", snap.hasPacklist)
        })
        writeManifest(context, root.toString(4))
    }

    private fun removeFromManifest(context: Context, timestamp: Long) {
        val text = readManifest(context) ?: return
        try {
            val root = JSONObject(text.trim())
            val arr = root.optJSONArray("snapshots") ?: return
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null || o.optLong("timestamp") != timestamp) newArr.put(o ?: JSONObject.NULL)
            }
            root.put("snapshots", newArr)
            writeManifest(context, root.toString(4))
        } catch (_: Exception) {}
    }

    private fun trimOld(context: Context) {
        val all = listSnapshots(context)
        if (all.size <= MAX_SNAPSHOTS) return
        all.drop(MAX_SNAPSHOTS).forEach { delete(context, it) }
    }

    // ---------- 文件读写(File 优先) ----------

    private fun readManifest(context: Context): String? = readSnapshotFile(context, MANIFEST_FILE)

    private fun writeManifest(context: Context, text: String) {
        writeSnapshotFile(context, MANIFEST_FILE, text)
    }

    private fun readSnapshotFile(context: Context, name: String): String? {
        FileStore.backupDir()?.let { dir ->
            val f = File(dir, name)
            if (f.isFile) return f.readText()
        }
        val docDir = backupDocDir(context) ?: return null
        val doc = docDir.findFile(name) ?: return null
        return GameRepository.readDocText(context, doc)
    }

    private fun writeSnapshotFile(context: Context, name: String, text: String) {
        val fileDir = FileStore.backupDir()
        if (fileDir != null) {
            if (!fileDir.isDirectory) fileDir.mkdirs()
            File(fileDir, name).writeText(text, Charsets.UTF_8)
            return
        }
        val docDir = backupDocDir(context) ?: throw IOException("无法访问备份目录")
        writeDoc(context, docDir, name, text)
    }

    private fun writeSonglist(context: Context, text: String) {
        FileStore.songlistFile()?.let {
            it.writeText(text, Charsets.UTF_8)
            return
        }
        val dir = GameRepository.gameSongsDir(context) ?: throw IOException("未找到 songs 目录")
        GameRepository.writeDocText(context, dir, "songlist", text)
    }

    private fun writePacklist(context: Context, text: String) {
        FileStore.packlistFile()?.let {
            it.writeText(text, Charsets.UTF_8)
            return
        }
        val dir = GameRepository.gameSongsDir(context) ?: throw IOException("未找到 songs 目录")
        GameRepository.writeDocText(context, dir, "packlist", text)
    }

    private fun writeDoc(context: Context, parent: DocumentFile, name: String, text: String) {
        parent.findFile(name)?.delete()
        val doc = parent.createFile("application/octet-stream", name)
            ?: throw IOException("无法创建文件: $name")
        if (doc.name != name && !doc.renameTo(name)) throw IOException("无法创建文件 $name")
        context.contentResolver.openOutputStream(doc.uri)?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("无法写入文件: $name")
    }

    private fun findOrCreateDocDir(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.let { if (it.isDirectory) return it }
        return parent.createDirectory(name)
    }

    // ---------- 辅助 ----------

    fun formatTime(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
}
