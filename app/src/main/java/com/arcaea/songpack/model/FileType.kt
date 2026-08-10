package com.arcaea.songpack.model

import com.arcaea.songpack.R

/**
 * 压缩包内文件的用途类型
 */
enum class FileType(val displayNameRes: Int) {
    AFF(R.string.file_type_aff),              // 谱面文件
    JACKET(R.string.file_type_jacket),        // 歌曲封面
    JACKET_THUMB(R.string.file_type_jacket_thumb), // 封面缩略图
    BACKGROUND(R.string.file_type_bg),        // 游玩背景图
    AUDIO(R.string.file_type_audio),          // 歌曲音乐
    SONGLIST(R.string.file_type_songlist),    // songlist 片段
    UNKNOWN(R.string.file_type_unknown),      // 未识别
    IGNORE(R.string.file_type_ignore)         // 忽略(不导入)
}
