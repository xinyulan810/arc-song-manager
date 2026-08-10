package com.arcaea.songpack.model

import java.io.File

/**
 * 解压后识别出的一个文件
 */
data class ClassifiedFile(
    val file: File,
    val relativePath: String,
    val size: Long,
    var type: FileType,
    /** 图片分辨率, 如 "1920x1440"; 非图片或读取失败为 null */
    val resolution: String? = null
)
