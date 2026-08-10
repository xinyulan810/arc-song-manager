package com.arcaea.songpack.manager.model

import com.arcaea.songpack.model.SongEntry

/**
 * 歌曲列表项 —— SongEntry + 文件系统信息(用于排序/显示)
 *
 * @param sortTime 歌曲文件夹修改时间(毫秒)。用户用它表示"最近导入/修改的排前面"。
 * @param hasFolder 歌曲文件夹在 songs/ 下是否存在
 */
data class SongItem(
    val entry: SongEntry,
    val sortTime: Long = 0L,
    val hasFolder: Boolean = true
) {
    val id: String get() = entry.id
    val title: String get() = entry.displayTitle
    val set: String get() = entry.set ?: ""
}
