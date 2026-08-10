package com.arcaea.songpack.manager.model

import org.json.JSONObject

/**
 * 曲包模型 —— 对应 packlist 中的一个 pack 条目
 *
 * 注意: packlist 中同一 id 可能出现在多个 section(如 base 在 free 和 mainstory 都有)。
 * 曲包与歌曲通过 id(set 字段)关联, 歌曲文件夹物理上平铺在 songs/ 下, 不按曲包分目录。
 */
data class Pack(
    val raw: JSONObject,
    val id: String,
    val section: String,
    val isExtendPack: Boolean,
    val customBanner: Boolean,
    val cutoutPackImage: Boolean,
    val plusCharacter: Int,
    val nameLocalized: Map<String, String>,
    val descriptionLocalized: Map<String, String>
) {

    /** 展示名称: en 优先, 否则第一个非空语言, 最后回退到 id */
    val displayName: String
        get() {
            nameLocalized["en"]?.takeIf { it.isNotBlank() }?.let { return it }
            nameLocalized.values.firstOrNull { it.isNotBlank() }?.let { return it }
            return id
        }

    /** 是否有描述 */
    val hasDescription: Boolean get() = descriptionLocalized.values.any { it.isNotBlank() }

    /** 首个非空描述(用于副标题显示) */
    val displayDescription: String
        get() = descriptionLocalized.values.firstOrNull { it.isNotBlank() } ?: ""

    companion object {
        fun fromJson(obj: JSONObject): Pack {
            val names = mutableMapOf<String, String>()
            obj.optJSONObject("name_localized")?.let { n ->
                n.keys().forEach { k -> names[k] = n.optString(k) }
            }
            val descs = mutableMapOf<String, String>()
            obj.optJSONObject("description_localized")?.let { d ->
                d.keys().forEach { k -> descs[k] = d.optString(k) }
            }
            return Pack(
                raw = obj,
                id = obj.optString("id", ""),
                section = obj.optString("section", "free"),
                isExtendPack = obj.optBoolean("is_extend_pack", false),
                customBanner = obj.optBoolean("custom_banner", false),
                cutoutPackImage = obj.optBoolean("cutout_pack_image", true),
                plusCharacter = obj.optInt("plus_character", -1),
                nameLocalized = names,
                descriptionLocalized = descs
            )
        }

        /**
         * 新建默认曲包条目。
         * 默认用 section=mainstory2 + is_extend_pack=true —— 实测这是 Arcaea 免解锁验证的曲包字段
         * (如 vegchi); 若用 free+false 会被游戏要求购买解锁。
         */
        fun createNew(id: String): Pack {
            val obj = JSONObject().apply {
                put("id", id)
                put("plus_character", -1)
                put("section", "mainstory2")
                put("custom_banner", false)
                put("is_extend_pack", true)
                put("cutout_pack_image", true)
                put("name_localized", JSONObject().put("en", id))
                put("description_localized", JSONObject().put("en", "").put("ja", ""))
            }
            return fromJson(obj)
        }
    }
}
