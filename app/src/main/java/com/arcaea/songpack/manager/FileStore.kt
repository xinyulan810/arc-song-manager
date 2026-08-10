package com.arcaea.songpack.manager

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

/**
 * 游戏目录的 java.io.File 直连访问(性能优化核心)。
 *
 * 背景: Android SAF 下每个 DocumentFile.findFile/listFiles 都是一次跨进程 IPC,
 * 几百次操作累计可达数秒。若游戏目录是普通本地路径(非 Android/data 受限),
 * SAF 授权后可直接用 java.io.File 访问, 速度提升几十倍。
 *
 * 仅支持 ExternalStorageProvider(内置存储 primary: 与可插拔存储卷)。
 * 解析失败返回 null, 调用方自动回退 SAF DocumentFile。
 */
object FileStore {

    private var cachedRoot: File? = null
    private var cachedRootInvalid = false

    val fileRoot: File?
        get() = cachedRoot

    /** 解析并缓存游戏目录的本地路径; 失败返回 null */
    fun init(context: Context, treeUri: Uri): File? {
        if (cachedRoot != null) return cachedRoot
        if (cachedRootInvalid) return null
        if (treeUri.authority != "com.android.externalstorage.documents") {
            cachedRootInvalid = true
            return null
        }
        val root = try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val idx = treeDocId.indexOf(':')
            if (idx < 0) return null
            val volume = treeDocId.substring(0, idx)
            val relPath = treeDocId.substring(idx + 1)
            var resolved: File? = null
            if (volume.equals("primary", ignoreCase = true)) {
                resolved = File(Environment.getExternalStorageDirectory().absolutePath, relPath)
            } else {
                // 可插拔 SD 卡等, 卷名为存储卷 ID
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                sm?.storageVolumes?.forEach { v ->
                    val dir = v.directory ?: return@forEach
                    if (dir.absolutePath.contains(volume, ignoreCase = true)) {
                        resolved = File(dir, relPath)
                    }
                }
            }
            resolved?.takeIf { it.isDirectory }
        } catch (_: Exception) {
            null
        }
        if (root != null && root.isDirectory && canReadFiles(root)) {
            cachedRoot = root
        } else {
            cachedRootInvalid = true
        }
        return cachedRoot
    }

    /**
     * 自检 File 直连是否真正可用。
     * SAF 授权只保证 URI 权限, 某些设备/版本上 java.io.File 仍无法访问(受限存储)。
     * 读不到实际文件内容就禁用 File 直连, 回退 SAF DocumentFile。
     */
    private fun canReadFiles(root: File): Boolean {
        return try {
            // 探测文件: 优先 songs/songlist, 否则根下任一文件
            val probe = (File(File(root, "songs"), "songlist")).takeIf { it.isFile }
                ?: root.listFiles()?.firstOrNull { it.isFile }
            if (probe != null) {
                probe.inputStream().use { it.read() >= 0 }
            } else {
                // 没有文件可探测: 目录能列出即认为可用
                root.listFiles() != null
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 失效缓存(目录变更/恢复时调用) */
    fun invalidate() {
        cachedRoot = null
        cachedRootInvalid = false
    }

    fun songsDir(): File? = cachedRoot?.let { File(it, "songs") }
    fun packDir(): File? = cachedRoot?.let { File(it, "songs/pack") }
    fun songDir(songId: String): File? = cachedRoot?.let { File(it, "songs/$songId") }
    fun songlistFile(): File? = cachedRoot?.let { File(it, "songs/songlist") }
    fun packlistFile(): File? = cachedRoot?.let { File(it, "songs/packlist") }
    fun backupDir(): File? = cachedRoot?.let { File(it, "backup") }
}
