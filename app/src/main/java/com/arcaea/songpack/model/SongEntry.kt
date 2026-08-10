package com.arcaea.songpack.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一首歌的 songlist 条目数据模型
 * 对应 Arcaea songlist 中的一个歌曲对象
 */
data class SongDifficulty(
    val ratingClass: Int,
    val rating: Int,
    val ratingPlus: Boolean,
    val chartDesigner: String?,
    val jacketDesigner: String?
) {
    companion object {
        fun fromJson(obj: JSONObject): SongDifficulty {
            return SongDifficulty(
                ratingClass = obj.optInt("ratingClass", 0),
                rating = obj.optInt("rating", 0),
                ratingPlus = obj.optBoolean("ratingPlus", false),
                chartDesigner = obj.optString("chartDesigner", "").ifEmpty { null },
                jacketDesigner = obj.optString("jacketDesigner", "").ifEmpty { null }
            )
        }
    }
}

data class SongEntry(
    val id: String,
    val titleLocalized: Map<String, String>,
    val artist: String?,
    val bpm: String?,
    val bpmBase: Int?,
    val side: Int?,
    val bg: String?,
    val version: String?,
    val sourceLocalized: Map<String, String>,
    val purchase: String?,
    val audioPreview: Long?,
    val audioPreviewEnd: Long?,
    val date: Long?,
    val difficulties: List<SongDifficulty>,
    val raw: JSONObject
) {

    /** 所属曲包 id。实时从 raw 读取(编辑 set 字段后立即可见, 不缓存快照) */
    val set: String?
        get() = raw.optString("set", "").ifEmpty { null }

    /** 主标题(英文优先,否则第一个语言) */
    val displayTitle: String
        get() {
            titleLocalized["en"]?.takeIf { it.isNotBlank() }?.let { return it }
            return titleLocalized.values.firstOrNull { it.isNotBlank() } ?: ""
        }

    /** 难度数 */
    val difficultyCount: Int get() = difficulties.size

    /** 最高定数 */
    val maxRating: Int get() = difficulties.maxOfOrNull { it.rating } ?: 0

    companion object {
        fun fromJson(obj: JSONObject): SongEntry {
            val titles = mutableMapOf<String, String>()
            obj.optJSONObject("title_localized")?.let { t ->
                t.keys().forEach { k -> titles[k] = t.optString(k) }
            }
            val sources = mutableMapOf<String, String>()
            obj.optJSONObject("source_localized")?.let { s ->
                s.keys().forEach { k -> sources[k] = s.optString(k) }
            }
            val diffs = mutableListOf<SongDifficulty>()
            obj.optJSONArray("difficulties")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { diffs.add(SongDifficulty.fromJson(it)) }
                }
            }
            return SongEntry(
                id = obj.optString("id", ""),
                titleLocalized = titles,
                artist = obj.optString("artist", "").ifEmpty { null },
                bpm = obj.optString("bpm", "").ifEmpty { null },
                bpmBase = if (obj.has("bpm_base")) obj.optInt("bpm_base") else null,
                side = if (obj.has("side")) obj.optInt("side") else null,
                bg = obj.optString("bg", "").ifEmpty { null },
                version = obj.optString("version", "").ifEmpty { null },
                sourceLocalized = sources,
                purchase = obj.optString("purchase", "").ifEmpty { null },
                audioPreview = if (obj.has("audioPreview")) obj.optLong("audioPreview") else null,
                audioPreviewEnd = if (obj.has("audioPreviewEnd")) obj.optLong("audioPreviewEnd") else null,
                date = if (obj.has("date")) obj.optLong("date") else null,
                difficulties = diffs,
                raw = obj
            )
        }
    }

    /** 生成用于 UI 展示的多行文本 */
    fun toDisplayString(): String {
        val sb = StringBuilder()
        sb.append("ID: ").append(id).append('\n')
        if (displayTitle.isNotBlank()) sb.append("标题: ").append(displayTitle).append('\n')
        if (!artist.isNullOrBlank()) sb.append("艺术家: ").append(artist).append('\n')
        if (!bpm.isNullOrBlank()) sb.append("BPM: ").append(bpm).append('\n')
        if (!set.isNullOrBlank()) sb.append("包: ").append(set).append('\n')
        if (bg != null) sb.append("背景: ").append(bg).append('\n')
        if (difficulties.isNotEmpty()) {
            sb.append("难度: ").append(difficulties.size).append(" 个")
            val ratingText = difficulties.joinToString(" / ") {
                "${difficultyLabel(it.ratingClass)}${it.rating}${if (it.ratingPlus) "+" else ""}"
            }
            sb.append(" (").append(ratingText).append(')').append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun difficultyLabel(ratingClass: Int): String = when (ratingClass) {
        0 -> "PST "
        1 -> "PRS "
        2 -> "FTR "
        3 -> "BYD "
        else -> ""
    }
}
