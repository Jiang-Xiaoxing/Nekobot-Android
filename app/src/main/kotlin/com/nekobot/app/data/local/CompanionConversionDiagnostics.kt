package com.nekobot.app.data.local

import android.os.Build
import android.content.Context
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.LocalAiModelEntity
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 只记录白名单元数据。异常正文可能含密钥、请求或模型原话，绝不写入可分享报告。 */
internal class CompanionConversionDiagnostics(private val jobId: String, private val model: LocalAiModelEntity, context: Context?) {
    private val appVersion = runCatching {
        val owner = requireNotNull(context)
        val info = owner.packageManager.getPackageInfo(owner.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName} ($code)"
    }.getOrDefault("未取得版本")
    private val started = System.nanoTime()
    private var requestStarted = 0L
    private var requestDurationMs = 0L
    private var waiting = false
    private var calls = 0
    private var inputTokens = 0
    private var phase = "准备转换"
    private var processed = 0L
    private var total = 0L
    private var finishReason = "未返回"
    val timeoutSeconds = model.failoverTimeout.takeIf { it > 0 }?.toLong()
        ?: defaultFailoverTimeout("companion_conversion").inWholeSeconds

    fun progress(value: CompanionTransferProgress) {
        phase = value.phase
        processed = value.processed
        total = value.total
    }

    fun request(system: String, material: String) {
        calls++
        requestStarted = System.nanoTime()
        waiting = true
        inputTokens = estimateLocalTextTokens(system) + estimateLocalTextTokens(material)
        finishReason = "未返回"
    }

    fun response(reason: String) {
        requestDurationMs = elapsed(requestStarted)
        waiting = false
        // 不接受任意服务端字段进入报告。
        finishReason = reason.takeIf { it in setOf("stop", "length", "content_filter", "refusal", "tool_calls", "end_turn", "max_tokens") }
            ?: "其他或未提供"
    }

    fun describe(error: Exception): CompanionConversionFailure {
        val failed = (error as? FailoverAllFailedException)?.failures?.lastOrNull()
        val code = failed?.statusCode ?: (error as? FailoverHttpException)?.statusCode
        // 错误正文仅用于分类，不进入报告或界面。
        val reason = (failed?.message ?: error.message).orEmpty().lowercase(java.util.Locale.ROOT)
        return when {
            error is CompanionConversionBlockedException -> CompanionConversionFailure("PROVIDER_REFUSAL",
                "所选服务商拒绝处理这段资料，转换已停止。原资料和已完成进度保留，可复制错误报告查看原因。", code)
            code == -2 -> CompanionConversionFailure("REQUEST_TIMEOUT",
                "这一批在 $timeoutSeconds 秒内没有处理完成，转换已暂停。已完成部分保留，稍后点击转换可继续。", code)
            reason.contains("timeout") || reason.contains("timed out") || error is java.net.SocketTimeoutException ->
                CompanionConversionFailure("NETWORK_TIMEOUT", "连接或读取模型响应超时，转换已暂停。请检查网络后继续，已完成部分保留。", code)
            code == 401 -> CompanionConversionFailure("AUTHENTICATION", "服务商未接受当前密钥或登录凭据，请检查所选模型的配置后继续。", code)
            code == 403 -> CompanionConversionFailure("ACCESS_DENIED", "服务商拒绝访问，请核对账号权限、模型权限或服务商限制后继续。", code)
            code == 402 -> CompanionConversionFailure("PAYMENT_REQUIRED", "服务商要求检查账单或余额，请到服务商后台确认后继续。", code)
            code == 429 -> CompanionConversionFailure("RATE_OR_QUOTA_LIMIT", "服务商限制了请求频率或额度，请稍后重试或检查服务商额度。已完成部分保留。", code)
            code == 413 -> CompanionConversionFailure("REQUEST_TOO_LARGE", "这一批请求超过接口接受的大小，转换已停止。请复制错误报告用于排查。", code)
            code == 400 || code == 422 -> CompanionConversionFailure("REQUEST_REJECTED", "服务商未接受请求参数或格式，请复制错误报告核对模型接口配置。", code)
            code == 404 -> CompanionConversionFailure("ENDPOINT_OR_MODEL_NOT_FOUND", "接口地址或模型未找到，请核对所选模型的配置后继续。", code)
            code != null && code >= 500 -> CompanionConversionFailure("PROVIDER_ERROR", "服务商暂时无法完成请求，请稍后继续。已完成部分保留。", code)
            error is FailoverAllFailedException && error.attempts.isEmpty() -> CompanionConversionFailure("MODEL_UNAVAILABLE",
                "所选模型暂时处于失败冷却期，或已达到本机设置的用量上限，请稍后继续或检查模型配置。", code)
            reason.contains("可用上下文太小") || reason.contains("context") && (reason.contains("length") || reason.contains("limit")) ->
                CompanionConversionFailure("CONTEXT_LIMIT", "这一批内容超过模型的上下文限制，请复制错误报告核对模型容量。", code)
            error is FailoverAllFailedException || error is java.io.IOException -> CompanionConversionFailure("CONNECTION_OR_RESPONSE",
                "未能完成与所选模型的通信，请检查网络及接口状态后继续。可复制错误报告进一步排查。", code)
            reason.contains("存储空间不足") -> CompanionConversionFailure("LOCAL_STORAGE", "本机存储空间不足，请腾出空间后继续。原资料保留。", code)
            reason.contains("原会话或记忆已变化") -> CompanionConversionFailure("SOURCE_CHANGED", "原会话或记忆已经变化，请返回并重新预览后转换。", code)
            calls > 0 && !waiting && phase == "正在整理记忆" -> CompanionConversionFailure("OUTPUT_VALIDATION",
                "模型结果未能通过转换核验，本批尚未完成。已完成部分保留，请复制错误报告用于排查。", code)
            else -> CompanionConversionFailure("CONVERSION_FAILED", "转换未完成，原资料保留。请复制错误报告用于排查，未完成结果不会自动导入。", code)
        }
    }

    fun report(error: Exception, failure: CompanionConversionFailure): String = buildString {
        appendLine("Nekobot 转换错误报告 v1")
        appendLine("时间：${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        appendLine("应用：$appVersion")
        appendLine("系统：Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("规则：${CompanionConversionRules.version}")
        appendLine("记录编号：${safe(jobId)}")
        appendLine("错误类型：${failure.code}")
        appendLine("提示：${failure.message}")
        appendLine("阶段：${safe(phase)}")
        appendLine("该阶段进度：$processed / $total（0/0 表示该阶段未提供计数）")
        appendLine("模型名称：${safe(model.name)}")
        appendLine("模型 ID：${safe(model.model)}")
        appendLine("接口协议：${safe(model.protocol)}")
        appendLine("接口主机：${safe(runCatching { URI(model.baseUrl).host }.getOrNull().orEmpty())}")
        appendLine("单次请求时限：$timeoutSeconds 秒（${if (model.failoverTimeout > 0) "模型自定义" else "转换默认"}）")
        appendLine("本次继续处理调用次数：$calls（含格式补正，不等于已完成批次）")
        appendLine("最近请求估算输入：$inputTokens token；输出上限：${model.maxTokens ?: 4096}")
        appendLine("最近请求耗时：${if (waiting) elapsed(requestStarted) else requestDurationMs} 毫秒")
        appendLine("本次操作耗时：${elapsed(started)} 毫秒")
        appendLine("HTTP 状态：${failure.statusCode?.takeIf { it > 0 } ?: "未取得"}")
        appendLine("内部状态：${failure.statusCode ?: "无"}；模型结束原因：$finishReason")
        appendLine("异常类型：${error.javaClass.simpleName}")
        appendLine("调用位置（不含异常正文）：")
        error.stackTrace.take(12).forEach {
            appendLine("  ${it.className}.${it.methodName} (${it.fileName}:${it.lineNumber})")
        }
        appendLine("隐私处理：未记录密钥、请求头、完整网址、聊天原话、提示词、模型回复或原始异常正文。")
        append("此报告只反映本次失败，不能据此判定全部转换结果已通过。")
    }

    private fun elapsed(since: Long) = (System.nanoTime() - since).coerceAtLeast(0) / 1_000_000

    private fun safe(value: String): String {
        var text = value
        if (model.apiKey.isNotBlank()) text = text.replace(model.apiKey, "[已隐藏]")
        text = text.replace(Regex("(?i)https?://\\S+"), "[地址已隐藏]")
            .replace(Regex("(?i)(?:bearer\\s+|sk-)[a-z0-9._~+/=-]+"), "[凭据已隐藏]")
        return text.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").take(160).ifBlank { "未提供" }
    }
}

internal data class CompanionConversionFailure(val code: String, val message: String, val statusCode: Int?)
