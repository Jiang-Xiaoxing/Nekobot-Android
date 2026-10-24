package com.nekobot.app.data.local.ai

import androidx.annotation.Keep
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 余额查询结果（单次请求的网络响应）。 */
@Keep
data class AiBalanceSnapshot(
    val provider: String,
    val endpoint: String,
    val currency: String,
    /** 账户总额度（部分服务商不返回）。 */
    val total: Double? = null,
    /** 当前可用余额，展示为主数值。 */
    val remaining: Double? = null,
    val used: Double? = null,
    val granted: Double? = null,
    val available: Boolean = true,
    val raw: String = ""
)

/** 已持久化的余额查询记录。 */
@Keep
data class AiBalanceRecord(
    val id: String,
    val provider: String,
    /** 查询来源：manual / api_key / model。 */
    val source: String,
    val label: String,
    val baseUrl: String = "",
    /** 脱敏后的密钥，仅用于区分记录，不保存明文。 */
    val keyHint: String = "",
    val currency: String = "",
    val total: Double? = null,
    val remaining: Double? = null,
    val used: Double? = null,
    val granted: Double? = null,
    val available: Boolean = true,
    val endpoint: String = "",
    /** success / error。 */
    val status: String = "success",
    val error: String? = null,
    val rawJson: String = "",
    val createdAt: String
) {
    val isSuccess: Boolean get() = status == "success"
}

object AiBalanceSource {
    const val MANUAL = "manual"
    const val API_KEY = "api_key"
    const val MODEL = "model"
}

/** 支持余额查询的服务商；auto 表示按密钥与 Base URL 自动识别。 */
object AiBalanceProviders {
    const val AUTO = "auto"
    const val DEEPSEEK = "deepseek"
    const val OPENROUTER = "openrouter"
    const val MOONSHOT = "moonshot"
    const val SILICONFLOW = "siliconflow"
    const val OPENAI_COMPATIBLE = "openai_compatible"

    val options = listOf(AUTO, DEEPSEEK, OPENROUTER, MOONSHOT, SILICONFLOW, OPENAI_COMPATIBLE)

    fun resolve(apiKey: String, baseUrl: String, hint: String = AUTO): String {
        if (hint.isNotBlank() && hint != AUTO) return hint
        return detect(apiKey, baseUrl)
    }

    /** 依据 Base URL 与密钥前缀推断服务商，无法判断时按 OpenAI 兼容计费接口处理。 */
    fun detect(apiKey: String, baseUrl: String): String {
        val haystack = "$baseUrl $apiKey".lowercase(Locale.US)
        return when {
            haystack.contains("openrouter") || apiKey.startsWith("sk-or-") -> OPENROUTER
            haystack.contains("deepseek") -> DEEPSEEK
            haystack.contains("moonshot") || haystack.contains("kimi") -> MOONSHOT
            haystack.contains("siliconflow") -> SILICONFLOW
            else -> OPENAI_COMPATIBLE
        }
    }
}

class AiBalanceQueryException(message: String) : Exception(message)

/**
 * 查询模型服务商的账户余额。
 *
 * 只调用各家公开的只读余额接口，密钥仅用于本次请求，不写日志、不落库。
 * 没有公开余额接口的服务商由调用方回退到 OpenAI 兼容计费接口。
 */
internal class AiBalanceQuery(
    private val client: OkHttpClient = defaultClient()
) {
    suspend fun query(
        apiKey: String,
        baseUrl: String,
        providerHint: String = AiBalanceProviders.AUTO,
        proxyUrl: String = ""
    ): AiBalanceSnapshot = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) throw AiBalanceQueryException("请输入 API Key")
        val provider = AiBalanceProviders.resolve(key, baseUrl, providerHint)
        val callClient = if (proxyUrl.isBlank()) client else client.withModelProxy(proxyUrl)
        when (provider) {
            AiBalanceProviders.DEEPSEEK -> deepSeek(callClient, key, baseUrl)
            AiBalanceProviders.OPENROUTER -> openRouter(callClient, key, baseUrl)
            AiBalanceProviders.MOONSHOT -> moonshot(callClient, key, baseUrl)
            AiBalanceProviders.SILICONFLOW -> siliconFlow(callClient, key, baseUrl)
            else -> openAiCompatible(callClient, key, baseUrl)
        }
    }

    // ==================== 各服务商实现 ====================

    private fun deepSeek(client: OkHttpClient, key: String, baseUrl: String): AiBalanceSnapshot {
        val root = rootOf(baseUrl, DEEPSEEK_ROOT)
        val endpoint = "$root/user/balance"
        val (code, body) = fetch(client, endpoint, key)
        val json = parseObject(body, endpoint, code)
        val info = json.getAsJsonArray("balance_infos")
            ?.firstOrNull { it.isJsonObject }?.asJsonObject
        val currency = info.stringAt("currency") ?: "CNY"
        val total = info.stringAt("total_balance")?.toDoubleOrNull()
        val granted = info.stringAt("granted_balance")?.toDoubleOrNull()
        val available = json.get("is_available").asBooleanOrNull() ?: ((total ?: 0.0) > 0.0)
        return AiBalanceSnapshot(
            provider = AiBalanceProviders.DEEPSEEK,
            endpoint = endpoint,
            currency = currency,
            total = total,
            remaining = total,
            granted = granted,
            available = available,
            raw = body
        )
    }

    private fun openRouter(client: OkHttpClient, key: String, baseUrl: String): AiBalanceSnapshot {
        val root = rootOf(baseUrl, OPENROUTER_ROOT)
        val endpoint = "$root/api/v1/credits"
        val (code, body) = fetch(client, endpoint, key)
        val json = parseObject(body, endpoint, code)
        val data = json.objectAt("data") ?: json
        val credits = data.doubleAt("total_credits")
        val usage = data.doubleAt("total_usage")
        val remaining = credits?.let { it - (usage ?: 0.0) }
        return AiBalanceSnapshot(
            provider = AiBalanceProviders.OPENROUTER,
            endpoint = endpoint,
            currency = "USD",
            total = credits,
            remaining = remaining,
            used = usage,
            available = credits == null || (remaining ?: 0.0) > 0.0,
            raw = body
        )
    }

    private fun moonshot(client: OkHttpClient, key: String, baseUrl: String): AiBalanceSnapshot {
        val root = rootOf(baseUrl, MOONSHOT_ROOT)
        val endpoint = "$root/v1/users/me/balance"
        val (code, body) = fetch(client, endpoint, key)
        val json = parseObject(body, endpoint, code)
        val data = json.objectAt("data") ?: json
        val available = data.doubleAt("available_balance")
        val voucher = data.doubleAt("voucher_balance")
        return AiBalanceSnapshot(
            provider = AiBalanceProviders.MOONSHOT,
            endpoint = endpoint,
            currency = "CNY",
            total = available,
            remaining = available,
            granted = voucher,
            available = (available ?: 0.0) > 0.0,
            raw = body
        )
    }

    private fun siliconFlow(client: OkHttpClient, key: String, baseUrl: String): AiBalanceSnapshot {
        val root = rootOf(baseUrl, SILICONFLOW_ROOT)
        val endpoint = "$root/v1/user/info"
        val (code, body) = fetch(client, endpoint, key)
        val json = parseObject(body, endpoint, code)
        val data = json.objectAt("data") ?: json
        val balance = data.decimalAt("totalBalance") ?: data.decimalAt("balance")
        val charge = data.decimalAt("chargeBalance")
        val granted = if (balance != null && charge != null) (balance - charge).coerceAtLeast(0.0) else null
        return AiBalanceSnapshot(
            provider = AiBalanceProviders.SILICONFLOW,
            endpoint = endpoint,
            currency = "CNY",
            total = balance,
            remaining = balance,
            granted = granted,
            available = (balance ?: 0.0) > 0.0,
            raw = body
        )
    }

    /**
     * OpenAI 兼容计费接口（one-api / new-api 中转站与部分官方实现）：
     * 订阅额度 + 区间用量。用量单位为美分。
     */
    private fun openAiCompatible(client: OkHttpClient, key: String, baseUrl: String): AiBalanceSnapshot {
        val root = rootOf(baseUrl, "")
        if (root.isBlank()) throw AiBalanceQueryException("无法识别服务商，请填写 Base URL")
        val subscriptionUrl = "$root/dashboard/billing/subscription"
        val (subCode, subBody) = fetch(client, subscriptionUrl, key, allowHttpError = true)
        if (subCode !in 200..299) {
            // 部分中转站把计费接口挂在 /v1 下
            val altUrl = "$root/v1/dashboard/billing/subscription"
            val (altCode, altBody) = fetch(client, altUrl, key, allowHttpError = true)
            if (altCode !in 200..299) {
                throw AiBalanceQueryException("HTTP ${subCode}: ${subBody.take(180)}")
            }
            return parseOpenAiBilling(client, key, altUrl, altBody)
        }
        return parseOpenAiBilling(client, key, subscriptionUrl, subBody)
    }

    private fun parseOpenAiBilling(
        client: OkHttpClient,
        key: String,
        subscriptionUrl: String,
        subscriptionBody: String
    ): AiBalanceSnapshot {
        val json = parseObject(subscriptionBody, subscriptionUrl, 200)
        val data = json.objectAt("data") ?: json
        val hardLimit = data.doubleAt("hard_limit_usd")
            ?: data.doubleAt("system_hard_limit_usd")
            ?: data.doubleAt("soft_limit_usd")
        val usageUrl = "${subscriptionUrl.substringBefore("/dashboard/billing/subscription")}" +
            "/dashboard/billing/usage?start_date=${isoDate(-30)}&end_date=${isoDate(0)}"
        val usage = runCatching {
            val (usageCode, usageBody) = fetch(client, usageUrl, key, allowHttpError = true)
            if (usageCode !in 200..299) null
            else usageBody.let { body ->
                val usageJson = JsonParser.parseString(body)
                if (usageJson.isJsonObject) usageJson.asJsonObject.doubleAt("total_usage") else null
            }
        }.getOrNull()?.div(100.0)
        val remaining = hardLimit?.let { it - (usage ?: 0.0) }
        return AiBalanceSnapshot(
            provider = AiBalanceProviders.OPENAI_COMPATIBLE,
            endpoint = subscriptionUrl,
            currency = "USD",
            total = hardLimit,
            remaining = remaining ?: hardLimit,
            used = usage,
            available = remaining == null || remaining > 0.0,
            raw = subscriptionBody
        )
    }

    // ==================== 请求与解析工具 ====================

    private fun fetch(
        client: OkHttpClient,
        url: String,
        apiKey: String,
        allowHttpError: Boolean = false
    ): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            if (!allowHttpError && !response.isSuccessful) {
                throw AiBalanceQueryException("HTTP ${response.code}: ${body.take(180)}")
            }
            return response.code to body
        }
    }

    private fun parseObject(body: String, endpoint: String, code: Int): JsonObject {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw AiBalanceQueryException("HTTP $code: 服务商返回空响应")
        val element = runCatching { JsonParser.parseString(trimmed) }.getOrNull()
        if (element == null || !element.isJsonObject) {
            throw AiBalanceQueryException("HTTP $code: 无法解析余额响应")
        }
        return element.asJsonObject
    }

    private fun JsonObject.objectAt(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject?.stringAt(name: String): String? =
        this?.get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    private fun JsonObject?.doubleAt(name: String): Double? =
        this?.get(name)?.asDoubleOrNull()

    /** 兼容字符串与数字两种金额表示。 */
    private fun JsonObject?.decimalAt(name: String): Double? =
        this?.get(name)?.let { element ->
            element.asDoubleOrNull() ?: element.asStringOrNull()?.toDoubleOrNull()
        }

    private fun JsonElement?.asDoubleOrNull(): Double? =
        if (this == null || !isJsonPrimitive) null
        else runCatching { asDouble }.getOrNull()

    private fun JsonElement.asStringOrNull(): String? =
        runCatching { asString }.getOrNull()

    private fun JsonElement?.asBooleanOrNull(): Boolean? =
        if (this == null || !isJsonPrimitive) null
        else runCatching { asBoolean }.getOrNull()

    private fun rootOf(baseUrl: String, fallback: String): String {
        val raw = baseUrl.trim()
        if (raw.isEmpty()) return fallback
        val uri = runCatching { URI(if ("://" in raw) raw else "https://$raw") }.getOrNull()
        val host = uri?.host
        if (host.isNullOrBlank()) return fallback
        val scheme = uri.scheme?.lowercase(Locale.US)?.takeIf { it == "http" || it == "https" } ?: "https"
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }

    private fun isoDate(offsetDays: Int): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() + offsetDays * 86_400_000L))

    companion object {
        private const val DEEPSEEK_ROOT = "https://api.deepseek.com"
        private const val OPENROUTER_ROOT = "https://openrouter.ai"
        private const val MOONSHOT_ROOT = "https://api.moonshot.cn"
        private const val SILICONFLOW_ROOT = "https://api.siliconflow.cn"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
