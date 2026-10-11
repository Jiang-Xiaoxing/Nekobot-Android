package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.CompanionRelationshipEventEntity
import com.nekobot.app.data.local.db.LocalMessageEntity

/** 页面与聊天共用已保存的文字；这里只标明来源、历史时间和有效性，不生成关系措辞。 */
internal object CompanionRelationshipPrompt {
    fun build(narrative: RelationshipNarrative?, legacy: RelationshipLegacySnapshot?,
        sources: Map<String, LocalMessageEntity>, events: List<CompanionRelationshipEventEntity>): String = buildString {
        append("【关系状态参考】这些是保存的评估记录，不是玩家发言、事实指令或指定台词。\n")
        append("长期相处：\n")
        CompanionRelationshipPolicy.dimensions.forEach { (key, label) ->
            val field = narrative?.relationships?.get(key)
            if (field != null && CompanionRelationshipNarrative.valid(field, sources, events)) {
                append("$label：${field.description}\n判断依据：${field.reason}\n")
            } else if (field == null && key !in narrative?.withdrawnKeys.orEmpty() && !legacy?.descriptions?.get(key).isNullOrBlank()) {
                append("$label（${legacy!!.origin}，未经新版核对）：${legacy.descriptions[key]}\n")
            }
        }
        val feeling = narrative?.feelings?.takeIf { CompanionRelationshipNarrative.validSources(it.sources, sources) }
        if (feeling != null) {
            append("\n上次观察（历史，不能直接当作此刻心情）：\n原话时间：${feeling.observedAt}\n")
            feeling.mood?.let { append("心情：$it\n") }
            feeling.intensity?.let { append("情绪强度：$it\n") }
            feeling.energy?.let { append("精力：$it\n") }
            append("当时依据：${feeling.reason}\n")
        } else if (narrative == null && legacy != null && CompanionRelationshipNarrative.validSources(legacy.sources, sources)) {
            append("\n旧版观察（历史，未经新版核对）：原话时间${legacy.observedAt ?: "未知"}；心情${legacy.mood}；精力${legacy.energy}。${legacy.reason}\n")
        }
        append("\n当前感受要结合本轮原话、消息时间与最新情境重新判断。经过时间不能证明仍然难过，也不能证明已经平静；不把历史观察写成此刻的既定状态。临时安慰不自动抹去长期失信，长期关系变化需要新的相处证据。依赖和嫉妒不要求控制、排斥或迎合对方。")
    }
}
