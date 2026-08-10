package com.arcaea.songpack.manager

import android.net.Uri
import com.arcaea.songpack.manager.model.Pack
import com.arcaea.songpack.model.SongEntry

/**
 * 管理器数据缓存(内存)。
 *
 * songlist/packlist/文件夹修改时间/封面uri 都是低频变更数据,
 * 缓存后 onResume 刷新不再重复走 SAF/File IO。
 * 任何写操作(saveSonglist/savePacklist/恢复)后调用 invalidate() 失效。
 */
object GameCache {

    var songs: List<SongEntry>? = null
        private set
    var packs: List<Pack>? = null
        private set
    var folderTime: Map<String, Long>? = null
        private set

    /** songId -> 封面 Uri(File uri 或 content uri) */
    private var jacketUris: MutableMap<String, Uri>? = null
    /** packId -> 包图 Uri */
    private var packImageUris: MutableMap<String, Uri>? = null

    fun setSongs(v: List<SongEntry>?) { songs = v }
    fun setPacks(v: List<Pack>?) { packs = v }
    fun setFolderTime(v: Map<String, Long>?) { folderTime = v }

    /** 追加单个歌曲封面 uri 到缓存 */
    fun putJacketUri(songId: String, uri: Uri) {
        val m = jacketUris ?: HashMap<String, Uri>().also { jacketUris = it }
        m[songId] = uri
    }

    /** 追加单个曲包图 uri 到缓存 */
    fun putPackImageUri(packId: String, uri: Uri) {
        val m = packImageUris ?: HashMap<String, Uri>().also { packImageUris = it }
        m[packId] = uri
    }

    fun jacketUri(songId: String): Uri? = jacketUris?.get(songId)
    fun packImageUri(packId: String): Uri? = packImageUris?.get(packId)

    /** 批量写入封面 uri 缓存 */
    fun setAllJacketUris(m: Map<String, Uri>) {
        val cur = jacketUris ?: HashMap<String, Uri>().also { jacketUris = it }
        cur.putAll(m)
    }

    /** 批量写入曲包图 uri 缓存 */
    fun setAllPackImageUris(m: Map<String, Uri>) {
        val cur = packImageUris ?: HashMap<String, Uri>().also { packImageUris = it }
        cur.putAll(m)
    }

    /** 全部失效(删除/重命名/导入封面等结构性变化) */
    fun invalidate() {
        songs = null
        packs = null
        folderTime = null
        jacketUris = null
        packImageUris = null
    }

    /**
     * 仅失效歌曲/曲包数据(如移动歌曲改 set 字段), 保留封面uri缓存。
     * 封面文件路径不随歌曲移动而变, 保留缓存可避免切包时逐个 SAF 查封面导致的卡顿。
     */
    fun invalidateData() {
        songs = null
        packs = null
        folderTime = null
    }
}
