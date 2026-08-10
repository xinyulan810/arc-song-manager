package com.arcaea.songpack.manager

import android.util.Log
import com.arcaea.songpack.BuildConfig

/**
 * 计时日志(性能分析用)。
 *
 * 仅在构建时传入 `-Ptiming=true` 才输出到 logcat(TAG 前缀为 Timing), 正常构建无任何输出。
 * 用法: `gradle assembleDebug -Ptiming=true`
 * 查看: `adb logcat -s Timing`
 */
object TimingLog {

    private val enabled: Boolean = BuildConfig.TIMING_LOGS

    /** 记录一个阶段耗时 */
    fun mark(tag: String, t0: Long, msg: String) {
        if (enabled) Log.i("Timing/$tag", "$msg 耗时=${System.currentTimeMillis() - t0}ms")
    }

    /** 记录一条信息 */
    fun log(tag: String, msg: String) {
        if (enabled) Log.i("Timing/$tag", msg)
    }

    /** 取当前毫秒(计时起点) */
    fun now(): Long = if (enabled) System.currentTimeMillis() else 0L
}
