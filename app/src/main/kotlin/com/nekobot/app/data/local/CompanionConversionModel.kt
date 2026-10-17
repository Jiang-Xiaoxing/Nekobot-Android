package com.nekobot.app.data.local

/** UI 只接收展示字段，不把密钥或完整模型配置留在页面状态。 */
internal data class CompanionConversionModel(val id: String, val name: String, val provider: String, val model: String,
    val timeoutSeconds: Int = 300)

internal open class CompanionConversionCallException(val technicalDetail: String,
    message: String = "模型调用未完成，已完成部分保留。请查看错误报告后继续。") : IllegalStateException(message) {
    var diagnosticReport: String? = null
        internal set
}

internal class CompanionConversionBlockedException(reason: String) : CompanionConversionCallException(
    "服务商返回 finish_reason=$reason",
    "所选服务商拒绝处理当前这段资料，转换已停止。原资料和已完成进度都已保留，没有导入不完整的副本。这不是格式错误，请先查看详情确认服务商的处理限制。")

internal fun requireCompanionConversionContent(result: com.nekobot.app.data.local.ai.LocalAiResult): String {
    if (result.finishReason.lowercase(java.util.Locale.ROOT) in setOf("content_filter", "refusal")) {
        throw CompanionConversionBlockedException(result.finishReason)
    }
    return result.content.takeIf { it.isNotBlank() }
        ?: error("模型没有返回转换结果。已完成部分保留，请重试或更换模型。")
}
