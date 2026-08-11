package com.arcaea.songpack.manager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import com.arcaea.songpack.manager.model.Pack
import com.arcaea.songpack.manager.model.SongItem
import com.arcaea.songpack.manager.ui.ImageLoader
import com.arcaea.songpack.model.SongEntry
import com.arcaea.songpack.util.SonglistParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 游戏目录数据仓库(管理器数据层)。
 *
 * 性能策略: 优先 java.io.File 直连(FileStore), 失败回退 SAF DocumentFile。
 *  songlist/packlist/文件夹时间/封面uri 均走 GameCache 内存缓存, 写操作后失效。
 */
object GameRepository {

    private const val KEY_GAME_DIR = "game_dir_uri"

    // ---------- 目录授权 ----------

    /** 持久化目录权限, 并保存 uri */
    fun persistGameDir(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        prefs(context).edit().putString(KEY_GAME_DIR, uri.toString()).apply()
        FileStore.init(context, uri)
        GameCache.invalidate()
    }

    /** 恢复上次保存的游戏目录 uri; 失效则清除并返回 null */
    fun restoreGameDir(context: Context): Uri? {
        val saved = prefs(context).getString(KEY_GAME_DIR, null) ?: return null
        return try {
            val uri = Uri.parse(saved)
            val doc = DocumentFile.fromTreeUri(context, uri)
            if (doc != null && doc.exists()) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                FileStore.init(context, uri)
                uri
            } else {
                prefs(context).edit().remove(KEY_GAME_DIR).apply()
                null
            }
        } catch (_: Exception) {
            prefs(context).edit().remove(KEY_GAME_DIR).apply()
            null
        }
    }

    fun getGameDir(context: Context): Uri? {
        val saved = prefs(context).getString(KEY_GAME_DIR, null) ?: return null
        return try {
            val uri = Uri.parse(saved)
            if (DocumentFile.fromTreeUri(context, uri)?.exists() == true) {
                if (FileStore.fileRoot == null) FileStore.init(context, uri)
                uri
            } else null
        } catch (_: Exception) { null }
    }

    /** 游戏目录是否可用(存在 songs 子目录) */
    fun isGameDirReady(context: Context): Boolean =
        getGameDir(context)?.let { root(context, it)?.findFile("songs") != null } ?: false

    // ---------- 读取 songlist / packlist(缓存) ----------

    /** 读取全部歌曲条目(带缓存) */
    fun loadSongEntries(context: Context): List<SongEntry> {
        GameCache.songs?.let { return it }
        val text = readSonglistText(context) ?: return emptyList()
        val entries = parseSonglistFast(text)
        GameCache.setSongs(entries)
        return entries
    }

    /** 读取歌曲条目原始 JSONObject(不缓存 raw 列表; 供写回) */
    fun loadSongObjects(context: Context): List<JSONObject> {
        val text = readSonglistText(context) ?: return emptyList()
        return SonglistParser.parseFragmentObjects(text)
    }

    /** 读取曲包列表, 按 id 去重(带缓存) */
    fun loadPacks(context: Context): List<Pack> {
        GameCache.packs?.let { return it }
        val text = readPacklistText(context) ?: return emptyList()
        val packs = try {
            val root = JSONObject(text.trim().removePrefix("﻿"))
            val arr = root.optJSONArray("packs") ?: return emptyList()
            val seen = LinkedHashMap<String, Pack>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                if (seen.containsKey(id)) {
                    seen[id] = mergePacks(seen[id]!!, obj)
                } else {
                    seen[id] = Pack.fromJson(obj)
                }
            }
            seen.values.toList()
        } catch (_: Exception) {
            emptyList()
        }
        GameCache.setPacks(packs)
        return packs
    }

    private fun parseSonglistFast(text: String): List<SongEntry> {
        return try {
            val root = JSONObject(text.trim().removePrefix("﻿"))
            val arr = root.optJSONArray("songs")
            if (arr != null) {
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { SongEntry.fromJson(it) } }
            } else {
                SonglistParser.parseFragment(text)
            }
        } catch (_: Exception) {
            SonglistParser.parseFragment(text)
        }
    }

    /** 读取当前磁盘上的 songlist 文本(File 优先, 不读缓存) */
    fun readCurrentSonglistText(context: Context): String? = readSonglistText(context)

    /** 读取当前磁盘上的 packlist 文本(File 优先, 不读缓存) */
    fun readCurrentPacklistText(context: Context): String? = readPacklistText(context)

    private fun readSonglistText(context: Context): String? {
        FileStore.songlistFile()?.let {
            if (it.isFile) {
                try { return it.readText() } catch (_: Exception) {}
            }
        }
        val dir = gameSongsDir(context) ?: return null
        val doc = dir.findFile("songlist") ?: return null
        return readDocText(context, doc)
    }

    private fun readPacklistText(context: Context): String? {
        FileStore.packlistFile()?.let {
            if (it.isFile) {
                try { return it.readText() } catch (_: Exception) {}
            }
        }
        val dir = gameSongsDir(context) ?: return null
        val doc = dir.findFile("packlist") ?: return null
        return readDocText(context, doc)
    }

    private fun mergePacks(first: Pack, second: JSONObject): Pack {
        // 保留所有 section(含重复)! 游戏 packlist 中 base 可能有多个相同 section 条目,
        // 若去重会在 savePacklist 时丢失条目, 导致游戏解析异常。
        val secs = first.raw.optString("_sections", "")
        val allSections = if (secs.isBlank()) first.section else secs
        val secondSec = second.optString("section", "")
        val combined = if (secondSec.isNotBlank()) {
            if (allSections.isBlank()) secondSec else "$allSections,$secondSec"
        } else allSections
        first.raw.put("_sections", combined)
        return first
    }

    // ---------- 歌曲文件夹信息(修改时间) ----------

    /**
     * 批量读取所有歌曲文件夹的修改时间(毫秒)。
     * File 直连一次遍历; SAF 回退用 DocumentsContract 批量 query(替代逐个 findFile)。
     * 顺带填充封面 uri 缓存。
     */
    suspend fun loadSongFolderInfo(context: Context): Map<String, Long> {
        GameCache.folderTime?.let { return it }
        val result = HashMap<String, Long>()
        if (FileStore.fileRoot != null) {
            FileStore.songsDir()?.listFiles { f -> f.isDirectory }?.forEach { d ->
                result[d.name] = d.lastModified()
            }
        } else {
            // SAF 批量 query: 一次拿到所有歌曲文件夹的 lastModified + 封面
            val meta = loadAllSongFolderMeta(context)
            val jackets = HashMap<String, Uri>()
            meta.forEach { (id, m) ->
                result[id] = m.lastModified
                if (m.jacketUri != null) jackets[id] = m.jacketUri!!
            }
            if (jackets.isNotEmpty()) GameCache.setAllJacketUris(jackets)
        }
        GameCache.setFolderTime(result)
        return result
    }

    /** 构造 SongItem 列表 */
    fun buildSongItems(entries: List<SongEntry>, folderInfo: Map<String, Long>): List<SongItem> =
        entries.map { e ->
            val time = folderInfo[e.id]
            SongItem(entry = e, sortTime = time ?: 0L, hasFolder = time != null)
        }

    /**
     * 预热封面缓存: 批量扫描所有歌曲文件夹构建封面 uri。
     * File 直连一次遍历; SAF 回退用 DocumentsContract 批量 query(并发)。
     * 在加载歌曲列表后调用, 避免列表滚动时逐个查找。
     */
    suspend fun warmupCoverCache(context: Context, songs: List<SongEntry>) {
        if (FileStore.fileRoot != null) {
            FileStore.songsDir()?.listFiles { f -> f.isDirectory }?.forEach { d ->
                val uri = findJacketInDir(d)
                if (uri != null) GameCache.putJacketUri(d.name, uri)
            }
            return
        }
        // SAF 回退: 批量 query(一次列出所有歌曲文件夹的文件, 找出封面)
        val meta = loadAllSongFolderMeta(context)
        val jackets = HashMap<String, Uri>()
        meta.forEach { (id, m) -> if (m.jacketUri != null) jackets[id] = m.jacketUri!! }
        if (jackets.isNotEmpty()) GameCache.setAllJacketUris(jackets)
    }

    // ---------- SAF 批量查询(DocumentsContract) ----------

    data class ChildInfo(val docId: String, val name: String, val isDir: Boolean, val lastModified: Long)

    data class SongFolderMeta(val docId: String, val lastModified: Long, val jacketUri: Uri?)

    private val JACKET_NAMES = setOf(
        "base_256.jpg", "base.jpg", "1080_base.jpg",
        "base_256.JPG", "base.JPG", "base.png", "base_256.png"
    )

    /**
     * 批量列出 songs/ 下所有歌曲文件夹的 lastModified + 封面 uri。
     * 一次 query 列 songs/ 子目录, 每歌一次 query 列文件。
     */
    suspend fun loadAllSongFolderMeta(context: Context): Map<String, SongFolderMeta> {
        val t0 = TimingLog.now()
        val treeUri = getGameDir(context) ?: return emptyMap()
        val songsDir = gameSongsDir(context) ?: return emptyMap()
        val songsDocId = DocumentsContract.getDocumentId(songsDir.uri)
        val folders = queryChildren(context, treeUri, songsDocId)
        TimingLog.mark("loadAllSongFolderMeta", t0, "query songs/ 子目录(${folders.size}项)")
        val t1 = TimingLog.now()
        // 逐歌查询封面: 并发 6 路(SAF query 是 binder IPC, 串行慢, 并发可显著提速)
        val foldersList = folders.filter { it.isDir }
        val results = ConcurrentHashMap<String, SongFolderMeta>()
        val sem = Semaphore(6)
        coroutineScope {
            foldersList.map { f ->
                async(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        var jacket: Uri? = null
                        try {
                            for (file in queryChildren(context, treeUri, f.docId)) {
                                if (!file.isDir && JACKET_NAMES.contains(file.name)) {
                                    jacket = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.docId)
                                    break
                                }
                            }
                        } catch (_: Exception) {}
                        results[f.name] = SongFolderMeta(f.docId, f.lastModified, jacket)
                    } finally {
                        sem.release()
                    }
                }
            }.awaitAll()
        }
        TimingLog.mark("loadAllSongFolderMeta", t1, "逐歌查询封面(${foldersList.size}次,并发6)")
        return results
    }

    /** 批量列出 songs/pack/ 下所有曲包图 uri(一次 query) */
    fun loadAllPackImageUris(context: Context): Map<String, Uri> {
        val t0 = TimingLog.now()
        val treeUri = getGameDir(context) ?: return emptyMap()
        val packDir = gamePackDir(context) ?: return emptyMap()
        val packDocId = DocumentsContract.getDocumentId(packDir.uri)
        val files = queryChildren(context, treeUri, packDocId)
        TimingLog.mark("loadAllPackImageUris", t0, "query songs/pack/(${files.size}项)")
        val result = HashMap<String, Uri>()
        for (f in files) {
            if (f.isDir) continue
            val name = f.name
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, f.docId)
            val selectId = if (name.startsWith("select_") && name.endsWith(".png"))
                name.removePrefix("select_").removeSuffix(".png") else null
            if (selectId != null) {
                // select_ 优先: 同名双文件残留时, 以游戏默认名 select_ 为准
                result[selectId] = uri
                continue
            }
            val hiId = if (name.startsWith("1080_select_") && name.endsWith(".png"))
                name.removePrefix("1080_select_").removeSuffix(".png") else null
            if (hiId != null && !result.containsKey(hiId)) {
                result[hiId] = uri
            }
        }
        return result
    }

    /** 用 ContentResolver 一次 query 列出某目录全部子项 */
    private fun queryChildren(context: Context, treeUri: Uri, parentDocId: String): List<ChildInfo> {
        return try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val result = mutableListOf<ChildInfo>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                val iDoc = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val iMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val iMod = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    val docId = if (iDoc >= 0) c.getString(iDoc) ?: "" else ""
                    val name = if (iName >= 0) c.getString(iName) ?: "" else ""
                    val mime = if (iMime >= 0) c.getString(iMime) ?: "" else ""
                    val mod = if (iMod >= 0) c.getLong(iMod) else 0L
                    result.add(ChildInfo(docId, name, mime == DocumentsContract.Document.MIME_TYPE_DIR, mod))
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- 封面 uri(缓存) ----------

    /** 歌曲封面 uri: 优先 base_256, 回退 base, 兼容 1080_base 变体。结果缓存。 */
    fun getSongJacketUri(context: Context, songId: String): Uri? {
        GameCache.jacketUri(songId)?.let { return it }
        // File 直连(仅在自检通过时可写可读)
        if (FileStore.fileRoot != null) {
            FileStore.songDir(songId)?.takeIf { it.isDirectory }?.let { dir ->
                val uri = findJacketInDir(dir)
                if (uri != null) {
                    cacheJacket(songId, uri)
                    return uri
                }
                // File 找不到封面 → 继续走 SAF 回退
            }
        }
        // SAF 回退
        val dir = gameSongsDir(context) ?: return null
        val folder = dir.findFile(songId) ?: return null
        if (!folder.isDirectory) return null
        val candidates = listOf(
            "base_256.jpg", "base.jpg", "1080_base.jpg",
            "base_256.JPG", "base.JPG", "base.png", "base_256.png"
        )
        for (name in candidates) {
            folder.findFile(name)?.let { if (it.isFile) { cacheJacket(songId, it.uri); return it.uri } }
        }
        return null
    }

    private fun findJacketInDir(dir: File): Uri? {
        val candidates = listOf(
            "base_256.jpg", "base.jpg", "1080_base.jpg",
            "base_256.JPG", "base.JPG", "base.png", "base_256.png"
        )
        for (name in candidates) {
            val f = File(dir, name)
            if (f.isFile) return Uri.fromFile(f)
        }
        return null
    }

    private fun cacheJacket(songId: String, uri: Uri) {
        GameCache.putJacketUri(songId, uri)
    }

    /** 曲包图 uri: 优先 select(custom_banner=false 的默认名), 回退 1080_select。结果缓存。 */
    fun getPackImageUri(context: Context, packId: String): Uri? {
        GameCache.packImageUri(packId)?.let { return it }
        // File 直连(仅在自检通过时)
        if (FileStore.fileRoot != null) {
            val packDir = FileStore.packDir()
            if (packDir != null && packDir.isDirectory) {
                val names = listOf("select_$packId.png", "1080_select_$packId.png")
                for (n in names) {
                    val f = File(packDir, n)
                    if (f.isFile) { cachePackImage(packId, Uri.fromFile(f)); return Uri.fromFile(f) }
                }
                // File 找不到包图 → 继续 SAF 回退
            }
        }
        // SAF 回退
        val dir = gamePackDir(context) ?: return null
        val candidates = listOf("select_$packId.png", "1080_select_$packId.png")
        for (name in candidates) {
            val doc = dir.findFile(name) ?: continue
            if (doc.isFile) { cachePackImage(packId, doc.uri); return doc.uri }
        }
        return null
    }

    private fun cachePackImage(packId: String, uri: Uri) {
        GameCache.putPackImageUri(packId, uri)
    }

    /** 曲包是否有图片 */
    fun hasPackImage(context: Context, packId: String): Boolean = getPackImageUri(context, packId) != null

    // ---------- 写入(先快照, 再备份, 后写) ----------

    /**
     * 保存 songlist(写前自动创建版本化快照 + .backup)。
     */
    fun saveSonglist(context: Context, entries: List<SongEntry>): String? {
        BackupManager.snapshot(context, BackupManager.SnapshotType.AUTO, "保存 songlist 前自动备份")
        val root = JSONObject().put("songs", JSONArray())
        entries.forEach { root.getJSONArray("songs").put(it.raw) }
        val text = root.toString(4)
        // File 直连
        FileStore.songlistFile()?.let { file ->
            try {
                if (file.exists()) file.copyTo(File(file.parentFile, "songlist.backup"), overwrite = true)
                file.writeText(text, Charsets.UTF_8)
                GameCache.invalidateData()
                return null
            } catch (e: Exception) {
                return "保存失败: ${e.message}"
            }
        }
        // SAF 回退
        val dir = gameSongsDir(context) ?: return "未找到 songs 目录"
        val existing = dir.findFile("songlist")
        if (existing != null) backupDoc(context, dir, existing, "songlist.backup")
        return try {
            writeDocText(context, dir, "songlist", text)
            GameCache.invalidateData()
            null
        } catch (e: Exception) {
            "保存失败: ${e.message}"
        }
    }

    /**
     * 保存 packlist(写前自动创建版本化快照 + .backup)。
     */
    fun savePacklist(context: Context, packs: List<Pack>): String? {
        BackupManager.snapshot(context, BackupManager.SnapshotType.AUTO, "保存 packlist 前自动备份")
        val root = JSONObject().put("packs", JSONArray())
        val arr = root.getJSONArray("packs")
        packs.forEach { pack ->
            val sections = pack.raw.optString("_sections", "").split(",").filter { it.isNotBlank() }
            if (sections.size > 1) {
                sections.forEach { sec ->
                    val copy = JSONObject(pack.raw.toString())
                    copy.remove("_sections")
                    copy.put("section", sec.trim())
                    arr.put(copy)
                }
            } else {
                val copy = JSONObject(pack.raw.toString())
                copy.remove("_sections")
                arr.put(copy)
            }
        }
        val text = root.toString(4)
        // File 直连
        FileStore.packlistFile()?.let { file ->
            try {
                if (file.exists()) file.copyTo(File(file.parentFile, "packlist.backup"), overwrite = true)
                file.writeText(text, Charsets.UTF_8)
                GameCache.invalidateData()
                return null
            } catch (e: Exception) {
                return "保存失败: ${e.message}"
            }
        }
        // SAF 回退
        val dir = gameSongsDir(context) ?: return "未找到 songs 目录"
        val existing = dir.findFile("packlist")
        if (existing != null) backupDoc(context, dir, existing, "packlist.backup")
        return try {
            writeDocText(context, dir, "packlist", text)
            GameCache.invalidateData()
            null
        } catch (e: Exception) {
            "保存失败: ${e.message}"
        }
    }

    // ---------- 歌曲增删改 ----------

    /** 重命名歌曲 id: 改文件夹名 + songlist 条目 id。返回错误信息, null 表示成功 */
    fun renameSong(context: Context, entries: List<SongEntry>, oldId: String, newId: String): String? {
        if (newId.isBlank()) return "id 不能为空"
        if (newId != oldId && entries.any { it.id == newId }) return "已存在同名歌曲 id: $newId"
        val target = entries.firstOrNull { it.id == oldId } ?: return "未找到歌曲 $oldId"

        // 1. 重命名文件夹
        val dirFile = FileStore.songDir(oldId)
        if (dirFile != null && dirFile.isDirectory) {
            if (!dirFile.renameTo(File(dirFile.parentFile, newId))) return "文件夹重命名失败($oldId -> $newId)"
        } else if (hasSongFolder(context, oldId)) {
            val dir = gameSongsDir(context)!!
            val folder = dir.findFile(oldId)
            if (folder != null && folder.isDirectory && !folder.renameTo(newId)) {
                return "文件夹重命名失败($oldId -> $newId)"
            }
        }
        target.raw.put("id", newId)
        GameCache.invalidate()
        return null
    }

    /** 删除歌曲: 从 entries 移除 + 删除文件夹(可选) */
    fun deleteSong(context: Context, entries: MutableList<SongEntry>, songId: String, deleteFolder: Boolean): String? {
        entries.removeAll { it.id == songId }
        if (deleteFolder) deleteSongFolder(context, songId)?.let { return it }
        GameCache.invalidate()
        return null
    }

    /** 仅删除歌曲文件夹 */
    fun deleteSongFolder(context: Context, songId: String): String? {
        val dirFile = FileStore.songDir(songId)
        if (dirFile != null && dirFile.isDirectory) {
            if (!dirFile.deleteRecursively()) return "文件夹删除失败($songId)"
            GameCache.invalidate()
            return null
        }
        val dir = gameSongsDir(context) ?: return "未找到 songs 目录"
        val folder = dir.findFile(songId)
        if (folder != null && folder.isDirectory && !folder.delete()) return "文件夹删除失败($songId)"
        GameCache.invalidate()
        return null
    }

    /** 移动歌曲到指定曲包(改 set 字段), 不移动文件夹 */
    fun moveSongSet(entry: SongEntry, targetPackId: String) {
        entry.raw.put("set", targetPackId)
    }

    /** 歌曲文件夹是否存在 */
    fun hasSongFolder(context: Context, songId: String): Boolean {
        FileStore.songDir(songId)?.let { return it.isDirectory }
        val dir = gameSongsDir(context) ?: return false
        val folder = dir.findFile(songId) ?: return false
        return folder.isDirectory
    }

    // ---------- 曲包图操作 ----------

    fun importPackImage(context: Context, packId: String, srcUri: Uri): String? {
        val customBanner = loadPacks(context).firstOrNull { it.id == packId }?.customBanner ?: false
        // File 直连
        val packDir = FileStore.packDir()
        if (packDir != null && packDir.isDirectory) {
            return try {
                val target = resolvePackImageName(packId, customBanner) { File(packDir, it).isFile }
                val f = File(packDir, target)
                copyUriToFile(context, srcUri, f)
                if (!isValidPngFile(f)) {
                    f.delete()
                    return "封面图片无效(不是有效的 PNG 文件), 已取消导入"
                }
                deleteOtherPackImage(packDir, packId, target)
                GameCache.invalidate()
                null
            } catch (e: Exception) {
                "图片复制失败: ${e.message}"
            }
        }
        val dir = gamePackDir(context) ?: return "未找到 songs/pack 目录"
        return try {
            val target = resolvePackImageName(packId, customBanner) { name ->
                dir.findFile(name)?.isFile == true
            }
            copyStreamToDoc(context, srcUri, dir, target)
            val doc = dir.findFile(target)
            if (doc != null && !isValidPngDoc(context, doc)) {
                doc.delete()
                return "封面图片无效(不是有效的 PNG 文件), 已取消导入"
            }
            otherPackImageName(packId, target)?.let { name ->
                dir.findFile(name)?.takeIf { it.isFile }?.delete()
            }
            GameCache.invalidate()
            null
        } catch (e: Exception) {
            "图片复制失败: ${e.message}"
        }
    }

    /**
     * 保存编辑器生成的封面: 只写游戏实际使用的那一个文件。
     * 文件名由 packlist 决定(custom_banner=true → 1080_select_<id>.png, 否则 select_<id>.png);
     * 若目录里已有旧封面则沿用旧文件名(兼容 extra 等特例)。写完后删除另一变体, 避免残留旧图。
     * select 变体会按旧文件尺寸缩放, 避免破坏游戏对该包尺寸的预期(如 vegchi 原图 320x640)。
     */
    fun savePackCover(context: Context, packId: String, pngFile: File): String? {
        val customBanner = loadPacks(context).firstOrNull { it.id == packId }?.customBanner ?: false
        // File 直连
        val packDir = FileStore.packDir()
        if (packDir != null && (packDir.isDirectory || packDir.mkdirs())) {
            return try {
                val target = resolvePackImageName(packId, customBanner) { File(packDir, it).isFile }
                writePackImageFile(packDir, target, pngFile)
                deleteOtherPackImage(packDir, packId, target)
                ImageLoader.clearCache()
                GameCache.invalidate()
                null
            } catch (e: Exception) {
                "保存失败: ${e.message}"
            }
        }
        // SAF 回退
        val songsDir = gameSongsDir(context) ?: return "未找到 songs 目录"
        val pack = songsDir.findFile("pack") ?: songsDir.createDirectory("pack")
            ?: return "无法创建 songs/pack 目录"
        return try {
            val target = resolvePackImageName(packId, customBanner) { name ->
                pack.findFile(name)?.isFile == true
            }
            writePackImageDoc(context, pack, target, pngFile)
            otherPackImageName(packId, target)?.let { name ->
                pack.findFile(name)?.takeIf { it.isFile }?.delete()
            }
            ImageLoader.clearCache()
            GameCache.invalidate()
            null
        } catch (e: Exception) {
            "保存失败: ${e.message}"
        }
    }

    /**
     * 决定曲包封面实际写入的文件名: 已有文件优先沿用(游戏实际用的名字),
     * 但 packlist 规则优先——custom_banner=true 用 1080_select_<id>.png,
     * 否则用 select_<id>.png; 同曲包双文件残留时以 packlist 规则为准, 兼容 extra 等特例。
     */
    private fun resolvePackImageName(packId: String, customBanner: Boolean, has: (String) -> Boolean): String {
        val select = "select_$packId.png"
        val hi = "1080_select_$packId.png"
        return if (customBanner) {
            if (has(hi)) hi else select
        } else {
            if (has(select)) select else hi
        }
    }

    /** 与 target 同曲包的另一变体文件名; 若 target 不是这两种则返回 null */
    private fun otherPackImageName(packId: String, target: String): String? {
        val select = "select_$packId.png"
        val hi = "1080_select_$packId.png"
        return when (target) {
            hi -> select
            select -> hi
            else -> null
        }
    }

    private fun deleteOtherPackImage(packDir: File, packId: String, target: String) {
        otherPackImageName(packId, target)?.let {
            File(packDir, it).takeIf { f -> f.isFile }?.delete()
        }
    }

    /** 写入曲包图: 若目标已存在且尺寸与生成的封面不同, 按旧尺寸缩放, 避免破坏游戏预期尺寸 */
    private fun writePackImageFile(packDir: File, name: String, pngFile: File) {
        val target = File(packDir, name)
        if (target.isFile) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            val srcBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(pngFile.absolutePath, srcBounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0 &&
                (bounds.outWidth != srcBounds.outWidth || bounds.outHeight != srcBounds.outHeight)
            ) {
                target.writeBytes(scalePng(pngFile, bounds.outWidth, bounds.outHeight))
                return
            }
        }
        target.writeBytes(pngFile.readBytes())
    }

    /** 写入曲包图(SAF): 若目标已存在且尺寸不同, 按旧尺寸缩放 */
    private fun writePackImageDoc(context: Context, pack: DocumentFile, name: String, pngFile: File) {
        val target = pack.findFile(name)
        if (target != null && target.isFile) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(target.uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val srcBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(pngFile.absolutePath, srcBounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0 &&
                (bounds.outWidth != srcBounds.outWidth || bounds.outHeight != srcBounds.outHeight)
            ) {
                writeBytesToDoc(context, pack, name, scalePng(pngFile, bounds.outWidth, bounds.outHeight))
                return
            }
        }
        copyFileToDoc(context, pngFile, pack, name)
    }

    /** 把 PNG 缩放到指定尺寸, 返回 PNG 字节 */
    private fun scalePng(src: File, w: Int, h: Int): ByteArray {
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return src.readBytes()
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return out.toByteArray()
    }

    /** 校验 File 是否为有效 PNG(检查文件头 89 50 4E 47) */
    private fun isValidPngFile(file: File): Boolean {
        return try {
            file.inputStream().use { ins ->
                val head = ByteArray(8)
                ins.read(head) == 8 &&
                    head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
                    head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()
            }
        } catch (_: Exception) { false }
    }

    /** 校验 SAF DocumentFile 是否为有效 PNG */
    private fun isValidPngDoc(context: Context, doc: DocumentFile): Boolean {
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { ins ->
                val head = ByteArray(8)
                ins.read(head) == 8 &&
                    head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
                    head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()
            } ?: false
        } catch (_: Exception) { false }
    }

    fun deletePackImage(context: Context, packId: String) {
        val packDir = FileStore.packDir()
        if (packDir != null && packDir.isDirectory) {
            listOf("1080_select_$packId.png", "select_$packId.png").forEach { n ->
                File(packDir, n).takeIf { it.isFile }?.delete()
            }
            GameCache.invalidate()
            return
        }
        val dir = gamePackDir(context) ?: return
        listOf("1080_select_$packId.png", "select_$packId.png").forEach { name ->
            dir.findFile(name)?.let { if (it.isFile) it.delete() }
        }
        GameCache.invalidate()
    }

    fun renamePackImage(context: Context, oldId: String, newId: String) {
        val packDir = FileStore.packDir()
        if (packDir != null && packDir.isDirectory) {
            listOf("1080_select_", "select_").forEach { prefix ->
                val old = File(packDir, "$prefix$oldId.png")
                if (old.isFile) old.renameTo(File(packDir, "$prefix$newId.png"))
            }
            GameCache.invalidate()
            return
        }
        val dir = gamePackDir(context) ?: return
        listOf("1080_select_", "select_").forEach { prefix ->
            val old = dir.findFile("$prefix$oldId.png")
            if (old != null && old.isFile) old.renameTo("$prefix$newId.png")
        }
        GameCache.invalidate()
    }

    // ---------- 文件列表 ----------

    data class SongFileInfo(val name: String, val size: Long, val uri: Uri)

    /** 列出歌曲文件夹内文件 */
    fun listSongFiles(context: Context, songId: String): List<SongFileInfo> {
        // File 直连
        val dirFile = FileStore.songDir(songId)
        if (dirFile != null && dirFile.isDirectory) {
            return dirFile.listFiles { f -> f.isFile }?.map {
                SongFileInfo(it.name, it.length(), Uri.fromFile(it))
            } ?: emptyList()
        }
        // SAF 回退
        val dir = gameSongsDir(context) ?: return emptyList()
        val folder = dir.findFile(songId) ?: return emptyList()
        if (!folder.isDirectory) return emptyList()
        return folder.listFiles().filter { it.isFile }.map { SongFileInfo(it.name ?: "", it.length(), it.uri) }
    }

    // ---------- 目录定位 ----------

    fun gameSongsDir(context: Context): DocumentFile? {
        val rootUri = getGameDir(context) ?: return null
        return root(context, rootUri)?.findFile("songs")
    }

    fun gamePackDir(context: Context): DocumentFile? = gameSongsDir(context)?.findFile("pack")

    fun gameBgDir(context: Context): DocumentFile? {
        val img = getGameDir(context)?.let { root(context, it)?.findFile("img") } ?: return null
        val bg = img.findFile("bg") ?: return null
        return bg.findFile("1080") ?: bg
    }

    fun root(context: Context, uri: Uri): DocumentFile? = DocumentFile.fromTreeUri(context, uri)

    private fun prefs(context: Context) = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    // ---------- 文件读写辅助(File 优先) ----------

    fun readDocText(context: Context, doc: DocumentFile): String? {
        return try {
            context.contentResolver.openInputStream(doc.uri)?.use { ins ->
                ins.bufferedReader().readText()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun writeDocText(context: Context, parent: DocumentFile, name: String, text: String) {
        parent.findFile(name)?.delete()
        val doc = parent.createFile("application/octet-stream", name)
            ?: throw IOException("无法创建文件: $name")
        if (doc.name != name) {
            if (!doc.renameTo(name)) throw IOException("无法创建文件 $name (系统将文件名改为 ${doc.name})")
        }
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("无法写入文件: $name")
    }

    private fun backupDoc(context: Context, parent: DocumentFile, doc: DocumentFile, backupName: String) {
        val text = readDocText(context, doc) ?: return
        try {
            parent.findFile(backupName)?.delete()
            writeDocText(context, parent, backupName, text)
        } catch (_: Exception) {}
    }

    private fun copyStreamToDoc(context: Context, srcUri: Uri, parent: DocumentFile, name: String) {
        parent.findFile(name)?.delete()
        val doc = parent.createFile("application/octet-stream", name)
            ?: throw IOException("无法创建文件: $name")
        if (doc.name != name) {
            if (!doc.renameTo(name)) throw IOException("无法创建文件 $name (系统将文件名改为 ${doc.name})")
        }
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            context.contentResolver.openInputStream(srcUri)?.use { ins -> ins.copyTo(out) }
                ?: throw IOException("无法读取源文件")
        } ?: throw IOException("无法写入文件: $name")
    }

    private fun copyFileToDoc(context: Context, src: File, parent: DocumentFile, name: String) {
        parent.findFile(name)?.delete()
        val doc = parent.createFile("application/octet-stream", name)
            ?: throw IOException("无法创建文件: $name")
        if (doc.name != name) {
            if (!doc.renameTo(name)) throw IOException("无法创建文件 $name (系统将文件名改为 ${doc.name})")
        }
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            src.inputStream().use { ins -> ins.copyTo(out) }
        } ?: throw IOException("无法写入文件: $name")
    }

    private fun writeBytesToDoc(context: Context, parent: DocumentFile, name: String, bytes: ByteArray) {
        parent.findFile(name)?.delete()
        val doc = parent.createFile("application/octet-stream", name)
            ?: throw IOException("无法创建文件: $name")
        if (doc.name != name) {
            if (!doc.renameTo(name)) throw IOException("无法创建文件 $name (系统将文件名改为 ${doc.name})")
        }
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            out.write(bytes)
        } ?: throw IOException("无法写入文件: $name")
    }

    private fun copyUriToFile(context: Context, srcUri: Uri, dest: File) {
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(srcUri)?.use { ins ->
            dest.outputStream().use { out -> ins.copyTo(out) }
        } ?: throw IOException("无法读取源文件")
    }

    // ---------- 孤儿包 ----------

    /** 构建完整曲包列表: packlist + 歌曲 set 引用的孤儿包(虚拟) */
    fun buildPackList(context: Context, packs: List<Pack>, songs: List<SongEntry>): List<Pack> {
        val known = packs.map { it.id }.toSet()
        val extraIds = songs.mapNotNull { it.set }
            .filter { it.isNotBlank() && it !in known }
            .distinct()
        val extra = extraIds.map { Pack.createNew(it) }
        return packs + extra
    }
}
