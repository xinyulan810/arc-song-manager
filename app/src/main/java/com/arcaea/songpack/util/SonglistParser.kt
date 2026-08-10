package com.arcaea.songpack.util

import com.arcaea.songpack.model.SongEntry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * songlist 解析与整合工具
 *
 * 游戏 songlist 结构(本游戏 Arc6 版本): 顶层为对象 {"songs": [ ... ]}
 * 自制谱片段通常为裸对象 { ... }(可能带尾逗号) 或数组 [{ ... }]
 */
object SonglistParser {

    /** 宽容解析 songlist 文本,返回原始 JSONObject 列表 */
    fun parseFragmentObjects(text: String): List<JSONObject> {
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return emptyList()

        val result = mutableListOf<JSONObject>()

        // 1. 尝试整体解析为 JSON 数组
        try {
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { result.add(it) }
            }
            if (result.isNotEmpty()) return result
        } catch (_: Exception) {}

        // 2. 尝试整体解析为 JSON 对象(可能是 {"songs":[...]} 或单个歌曲对象)
        try {
            val obj = JSONObject(trimmed)
            obj.optJSONArray("songs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { result.add(it) }
                }
                if (result.isNotEmpty()) return result
            }
            if (obj.has("id")) return listOf(obj)
        } catch (_: Exception) {}

        // 3. 流式逐个读取顶层对象(处理 "...},{..." 带尾逗号的片段)
        try {
            val tok = JSONTokener(trimmed)
            while (true) {
                val value = tok.nextValue() ?: break
                when (value) {
                    is JSONObject -> result.add(value)
                    is JSONArray -> {
                        for (i in 0 until value.length()) {
                            value.optJSONObject(i)?.let { result.add(it) }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return result
    }

    /** 解析为 SongEntry 模型列表, 同时补全 4 个难度槽位 */
    fun parseFragment(text: String): List<SongEntry> =
        parseFragmentObjects(text).map {
            padDifficulties(it)
            SongEntry.fromJson(it)
        }

    /** songlist 校验结果 */
    data class SonglistCheck(
        val valid: Boolean,
        val issues: List<String>
    )

    /**
     * 校验解析出的歌曲条目是否健康。
     * 返回 invalid 时, 应提示用户 songlist 异常, 修改源文件后再继续。
     */
    fun validateEntries(entries: List<SongEntry>): SonglistCheck {
        if (entries.isEmpty()) {
            return SonglistCheck(false, listOf("未能从 songlist 中解析出任何歌曲条目, 文件可能不是有效的 songlist"))
        }
        val issues = mutableListOf<String>()
        val emptyId = entries.filter { it.id.isBlank() }
        if (emptyId.isNotEmpty()) {
            issues.add("存在缺少 id 的无效条目(${emptyId.size} 个), 请修改源 songlist 删除或补全 id")
        }
        entries.forEachIndexed { i, e ->
            if (e.difficulties.isEmpty()) {
                issues.add("第 ${i + 1} 首(${e.id.ifBlank { "无id" }})没有难度信息")
            }
        }
        return SonglistCheck(issues.isEmpty(), issues)
    }

    /**
     * 将自制谱条目整合进游戏 songlist。
     * @param existingText 游戏现有 songlist 文本(可为空)
     * @param newObjects   自制谱条目对象列表
     * @return Pair(整合后的完整 songlist 文本, 发生替换的歌曲 id 列表)
     */
    fun merge(existingText: String?, newObjects: List<JSONObject>): Pair<String, List<String>> {
        val root: JSONObject
        if (existingText.isNullOrBlank()) {
            root = JSONObject()
            root.put("songs", JSONArray())
        } else {
            val trimmed = existingText.trim()
            root = try {
                JSONObject(trimmed)
            } catch (_: Exception) {
                // 若是顶层数组, 包装成 {"songs": [...]}
                val arr = JSONArray(trimmed)
                JSONObject().put("songs", arr)
            }
        }

        if (!root.has("songs")) root.put("songs", JSONArray())
        val songs = root.getJSONArray("songs")

        // 已有 id 集合
        val existingIds = mutableMapOf<String, Int>() // id -> 数组下标
        for (i in 0 until songs.length()) {
            songs.optJSONObject(i)?.optString("id")?.let { existingIds[it] = i }
        }

        val replaced = mutableListOf<String>()
        for (obj in newObjects) {
            val id = obj.optString("id")
            if (id.isBlank()) continue
            // 规范化: 补全必需字段
            normalizeEntry(obj)
            val existingIndex = existingIds[id]
            if (existingIndex != null) {
                // 替换旧条目
                songs.put(existingIndex, obj)
                replaced.add(id)
            } else {
                songs.put(obj)
                existingIds[id] = songs.length() - 1
            }
        }

        val out = root.toString(4)
        return out to replaced
    }

    /** 确保条目含关键字段, 缺失给默认值 */
    private fun normalizeEntry(obj: JSONObject) {
        if (!obj.has("title_localized") || obj.isNull("title_localized")) {
            obj.put("title_localized", JSONObject().put("en", ""))
        }
        if (!obj.has("artist") || obj.isNull("artist")) obj.put("artist", "")
        if (!obj.has("bpm") || obj.isNull("bpm")) obj.put("bpm", "")
        if (!obj.has("set") || obj.isNull("set")) obj.put("set", "base")
        if (!obj.has("side") || obj.isNull("side")) obj.put("side", 0)
        if (!obj.has("difficulties") || obj.isNull("difficulties")) {
            obj.put("difficulties", JSONArray())
        }
        // 补全 4 个难度槽位(0=PST, 1=PRS, 2=FTR, 3=BYD), 缺失的填空难度, 避免游戏显示 bug
        padDifficulties(obj)
    }

    /**
     * 确保 difficulties 数组包含完整的 4 个难度槽位(0..3)。
     * 缺失的槽位补入空难度 {ratingClass, rating:0, ratingPlus:false, ...}。
     */
    private fun padDifficulties(obj: JSONObject) {
        val diffs = obj.optJSONArray("difficulties") ?: JSONArray().also { obj.put("difficulties", it) }
        val present = mutableSetOf<Int>()
        for (i in 0 until diffs.length()) {
            val rc = diffs.optJSONObject(i)?.optInt("ratingClass", -1) ?: -1
            if (rc in 0..3) present.add(rc)
        }
        for (rc in 0..3) {
            if (rc !in present) {
                diffs.put(JSONObject().apply {
                    put("ratingClass", rc)
                    put("rating", 0)
                    put("ratingPlus", false)
                    put("chartDesigner", "")
                    put("jacketDesigner", "")
                })
            }
        }
        // 按 ratingClass 排序(0,1,2,3)
        val sorted = (0 until diffs.length())
            .map { diffs.getJSONObject(it) }
            .sortedBy { it.optInt("ratingClass", -1) }
        val newArr = JSONArray()
        sorted.forEach { newArr.put(it) }
        obj.put("difficulties", newArr)
    }
}
