package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalMessageEntity

internal class CompanionConversionOutputException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** 汇总可让模型补正的输出错误；任何一条不合格都不能推进本批进度。 */
internal object CompanionConversionValidation {
    fun check(drafts: List<CompanionDraft>, target: List<LocalMessageEntity>, aliases: Map<String, String>, existingKeys: Set<String>) {
        val sources = target.associateBy { aliases.getValue(it.id) }
        val available = existingKeys.toMutableSet()
        val errors = mutableListOf<String>()
        drafts.forEachIndexed { index, draft ->
            fun check(ok: Boolean, detail: String) { if (!ok) errors += "第${index + 1}条：$detail" }
            check(draft.operation in setOf("add", "update", "complete", "cancel", "retract"), "operation 必填，按规则选择 add/update/complete/cancel/retract。")
            check(draft.kind in setOf("core", "fact", "task", "birthday", "episode"), "kind 不符合格式。")
            check(draft.category in FusedCompanionPolicy.categories, "category 不符合分类。")
            check(draft.key.isNotBlank() && draft.key.length <= 120 && !draft.key.matches(Regex("legacy\\d+")), "key 必须是新的简短语义键，不能借用 legacy 编号。")
            check(draft.content.isNotBlank() && draft.content.length <= 1500, "content 需要 1～1500 字符的有依据正文。")
            val source = sources[draft.sourceId]
            check(source != null, "source_id 必须照抄 target 中的短编号。")
            check(source != null && draft.quote.isNotBlank() && source.content.contains(draft.quote), "quote 必须是对应 source_id 原话中的连续原文。")
            check(draft.eventEvidence.isBlank() || draft.quote.contains(draft.eventEvidence), "event_evidence 必须是本条 quote 中的时间原文，不能借用别条的日期；没有依据请留空。")
            check(draft.category != "user_profile" || source?.role == "user", "用户资料必须引用用户原话。")
            val key = draft.targetKey.ifBlank { draft.key }
            check(draft.targetKey.isBlank() || key in available, "target_key 必须指向 existing 或本批先前新增的 key，旧参考的纠正请用 add。")
            check(draft.operation !in setOf("update", "retract") || key in available, "更新/否定的事实不存在；纠正 legacy_reference 请用 add 保存正确说法。")
            available += key
        }
        if (errors.isNotEmpty()) throw CompanionConversionOutputException(errors.take(8).joinToString("\n"))
    }
}
