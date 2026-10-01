package com.nekobot.app.data.local.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit

data class ModelPricingEntry(
    val id: String,
    val name: String,
    val provider: String,
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    /** 缓存命中（读缓存）价，每百万 token；命中部分按此价计费。 */
    val cacheReadPricePerMillion: Double? = null,
    /** 缓存写入价，每百万 token；仅 Anthropic 等支持显式缓存写入的服务商有值。 */
    val cacheWritePricePerMillion: Double? = null,
    val contextLength: Int? = null,
    val supportsTools: Boolean = false,
    val supportsReasoning: Boolean = false,
    val aliases: List<String> = emptyList(),
    val source: String = ModelPricingCatalog.SOURCE_BUNDLED
)

/**
 * 一次计费实际使用的价格组（每百万 token，美元）。
 *
 * 缓存价与输入价分开保存：命中缓存的输入按 [cacheReadPerMillion] 计费，
 * 未命中部分才按 [inputPerMillion] 计费。
 */
data class ModelPrices(
    val inputPerMillion: Double? = null,
    val outputPerMillion: Double? = null,
    val cacheReadPerMillion: Double? = null,
    val cacheWritePerMillion: Double? = null
)

data class ModelPricingSnapshot(
    val entries: List<ModelPricingEntry>,
    val updatedAt: String,
    val source: String
)

/**
 * 模型价格目录。
 *
 * 手动填写的模型价格始终优先；本目录只负责在价格缺失时按模型标识补全。
 * 在线目录固定从 OpenRouter 的公开 Models API 获取，并保存到应用私有缓存。
 */
object ModelPricingCatalog {
    const val SOURCE_BUNDLED = "bundled"
    const val SOURCE_OPENROUTER = "openrouter"
    const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models"

    private const val PREFS_NAME = "model_pricing_catalog"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
    private const val MAX_REMOTE_ENTRIES = 5_000
    private val gson = Gson()
    private val lock = Any()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val bundledSnapshot = ModelPricingSnapshot(
        entries = bundledEntries(),
        updatedAt = "2026-07-30T00:00:00Z",
        source = SOURCE_BUNDLED
    )

    @Volatile
    private var snapshot: ModelPricingSnapshot = bundledSnapshot

    @Volatile
    private var cacheLoaded = false

    fun current(): ModelPricingSnapshot = snapshot

    fun loadCached(context: Context): ModelPricingSnapshot {
        if (cacheLoaded) return snapshot
        synchronized(lock) {
            if (cacheLoaded) return snapshot
            val cached = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SNAPSHOT, null)
                ?.let(::decodeSnapshot)
            snapshot = cached ?: bundledSnapshot
            cacheLoaded = true
            return snapshot
        }
    }

    suspend fun refresh(context: Context): ModelPricingSnapshot = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(OPENROUTER_MODELS_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "Nekobot-Android/model-pricing-catalog")
            .build()
        val refreshed = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "价格目录更新失败：HTTP ${response.code}" }
            val body = response.body ?: error("价格目录返回为空")
            val declaredLength = body.contentLength()
            check(declaredLength < 0 || declaredLength <= MAX_RESPONSE_BYTES) {
                "价格目录数据过大"
            }
            val json = body.string()
            check(json.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
                "价格目录数据过大"
            }
            parseOpenRouterResponse(json, Instant.now().toString())
        }
        check(refreshed.entries.isNotEmpty()) { "价格目录中没有可用模型" }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, gson.toJson(refreshed))
            .apply()
        synchronized(lock) {
            snapshot = refreshed
            cacheLoaded = true
        }
        refreshed
    }

    fun find(
        modelName: String,
        provider: String? = null,
        catalog: ModelPricingSnapshot = current()
    ): ModelPricingEntry? {
        val queryForms = identifierForms(modelName)
        if (queryForms.isEmpty()) return null
        val providerForms = identifierForms(provider.orEmpty())
        return catalog.entries.asSequence()
            .mapNotNull { entry ->
                val entryForms = buildList {
                    addAll(identifierForms(entry.id))
                    addAll(identifierForms(entry.name))
                    addAll(identifierForms(entry.provider))
                    entry.aliases.forEach { addAll(identifierForms(it)) }
                }.toSet()
                val exact = queryForms.intersect(entryForms).isNotEmpty()
                val dated = !exact && queryForms
                    .map(::stripReleaseDate)
                    .intersect(entryForms.map(::stripReleaseDate).toSet())
                    .any(String::isNotBlank)
                if (!exact && !dated) {
                    null
                } else {
                    val providerBonus = if (
                        providerForms.isNotEmpty() &&
                        providerForms.intersect(identifierForms(entry.provider)).isNotEmpty()
                    ) {
                        5
                    } else {
                        0
                    }
                    entry to ((if (exact) 100 else 80) + providerBonus)
                }
            }
            .sortedWith(
                compareByDescending<Pair<ModelPricingEntry, Int>> { it.second }
                    .thenByDescending { it.first.id.contains('/') }
                    .thenBy { it.first.id.length }
            )
            .firstOrNull()
            ?.first
    }

    fun resolvePrices(
        modelName: String,
        provider: String? = null,
        inputPrice: Double? = null,
        outputPrice: Double? = null,
        catalog: ModelPricingSnapshot = current()
    ): Pair<Double?, Double?> {
        if (inputPrice != null && outputPrice != null) return inputPrice to outputPrice
        val matched = find(modelName, provider, catalog)
        return (inputPrice ?: matched?.inputPricePerMillion) to
            (outputPrice ?: matched?.outputPricePerMillion)
    }

    /**
     * 解析包含缓存价在内的完整价格组。
     *
     * 优先级：用户手填 > 在线/内置目录 > 兜底比例换算。兜底只影响缓存单价，
     * 不改变输入/输出价；刷新价格目录后会被服务商真实缓存价覆盖。
     */
    fun resolveModelPrices(
        modelName: String,
        provider: String? = null,
        inputPrice: Double? = null,
        outputPrice: Double? = null,
        cacheReadPrice: Double? = null,
        cacheWritePrice: Double? = null,
        catalog: ModelPricingSnapshot = current()
    ): ModelPrices {
        val matched = find(modelName, provider, catalog)
        val input = inputPrice ?: matched?.inputPricePerMillion
        val output = outputPrice ?: matched?.outputPricePerMillion
        return ModelPrices(
            inputPerMillion = input,
            outputPerMillion = output,
            cacheReadPerMillion = cacheReadPrice
                ?: matched?.cacheReadPricePerMillion
                ?: defaultCacheReadPrice(modelName, input),
            cacheWritePerMillion = cacheWritePrice
                ?: matched?.cacheWritePricePerMillion
                ?: defaultCacheWritePrice(modelName, input)
        )
    }

    /**
     * 按缓存命中价估算一次调用成本（美元）。
     *
     * 计费口径：
     * - 命中缓存的输入 [cachedInputTokens] 按缓存价（缺失时回退输入价）；
     * - 写入缓存的输入 [cacheWriteTokens] 按缓存写入价（Anthropic，缺失时回退输入价）；
     * - 其余输入按输入价，输出按输出价；
     * - Anthropic 的 input_tokens 已在解析层累加缓存读写量，因此这里三者不会重复计数。
     */
    fun estimateCostUsd(
        inputTokens: Long,
        outputTokens: Long,
        cachedInputTokens: Long = 0,
        cacheWriteTokens: Long = 0,
        prices: ModelPrices
    ): Double {
        val input = inputTokens.coerceAtLeast(0)
        val cached = cachedInputTokens.coerceIn(0, input)
        val written = cacheWriteTokens.coerceIn(0, input - cached)
        val uncached = input - cached - written
        val cacheReadPrice = prices.cacheReadPerMillion ?: prices.inputPerMillion ?: 0.0
        val cacheWritePrice = prices.cacheWritePerMillion ?: prices.inputPerMillion ?: 0.0
        return uncached / 1_000_000.0 * (prices.inputPerMillion ?: 0.0) +
            cached / 1_000_000.0 * cacheReadPrice +
            written / 1_000_000.0 * cacheWritePrice +
            outputTokens.coerceAtLeast(0) / 1_000_000.0 * (prices.outputPerMillion ?: 0.0)
    }

    /**
     * 缓存命中率的统一口径：命中 token / 完整输入 token。
     *
     * 输入为 0 时返回 null（无从计算）。服务商是否上报过缓存字段由调用方判断：
     * 全部记录都没有缓存字段时应显示「—」，而不是这里的 0%。
     */
    fun cacheHitRate(inputTokens: Long, cachedInputTokens: Long): Double? {
        if (inputTokens <= 0) return null
        return (cachedInputTokens.coerceIn(0, inputTokens).toDouble() / inputTokens.toDouble())
            .coerceIn(0.0, 1.0)
    }

    /**
     * 目录缺少缓存价时的兜底换算比例。
     *
     * 主流服务商的缓存命中价约为输入价的固定倍数：Anthropic/DeepSeek 0.1，
     * Gemini 0.25，OpenAI GPT-4o 0.5、GPT-4.1/5 0.25。仅用于目录与手填价都缺失时
     * 的成本估算，比按原价全额计费更接近真实账单。
     */
    private fun defaultCacheReadPrice(modelName: String, input: Double?): Double? {
        if (input == null) return null
        val id = modelName.lowercase()
        val ratio = when {
            id.contains("claude") -> 0.1
            id.contains("deepseek") -> 0.1
            id.contains("gemini") -> 0.25
            id.contains("gpt-4o") -> 0.5
            id.contains("gpt-4.1") || id.contains("gpt-5") -> 0.25
            else -> 0.25
        }
        return input * ratio
    }

    /** 缓存写入价兜底：Anthropic 为输入价 1.25 倍，其余服务商按输入价计。 */
    private fun defaultCacheWritePrice(modelName: String, input: Double?): Double? {
        if (input == null) return null
        return if (modelName.lowercase().contains("claude")) input * 1.25 else input
    }

    internal fun parseOpenRouterResponse(
        json: String,
        updatedAt: String
    ): ModelPricingSnapshot {
        val root = JsonParser.parseString(json).asJsonObject
        val data = root.get("data")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?: error("价格目录缺少 data 字段")
        val entries = data.asSequence()
            .take(MAX_REMOTE_ENTRIES)
            .mapNotNull { element -> runCatching { parseOpenRouterEntry(element) }.getOrNull() }
            .distinctBy(ModelPricingEntry::id)
            .sortedBy(ModelPricingEntry::id)
            .toList()
        return ModelPricingSnapshot(
            entries = entries,
            updatedAt = updatedAt,
            source = SOURCE_OPENROUTER
        )
    }

    private fun parseOpenRouterEntry(element: JsonElement): ModelPricingEntry? {
        val obj = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
        val id = obj.string("id")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val canonical = obj.string("canonical_slug")?.trim().orEmpty()
        val displayName = obj.string("name")?.trim().orEmpty().ifBlank { id.substringAfter('/') }
        val pricing = obj.get("pricing")
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
        val input = pricing?.pricePerMillion("prompt")
        val output = pricing?.pricePerMillion("completion")
        if (input == null && output == null) return null
        // OpenRouter 价格字段：input_cache_read / input_cache_write（每 token 美元）。
        val cacheRead = pricing?.pricePerMillion("input_cache_read")
        val cacheWrite = pricing?.pricePerMillion("input_cache_write")
        val supported = obj.get("supported_parameters")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
            ?.map(String::lowercase)
            ?.toSet()
            .orEmpty()
        val aliases = listOf(
            canonical,
            id.substringAfter('/'),
            canonical.substringAfter('/'),
            displayName.substringAfter(':').trim()
        ).filter(String::isNotBlank).distinct()
        return ModelPricingEntry(
            id = id,
            name = displayName,
            provider = id.substringBefore('/', missingDelimiterValue = ""),
            inputPricePerMillion = input,
            outputPricePerMillion = output,
            cacheReadPricePerMillion = cacheRead,
            cacheWritePricePerMillion = cacheWrite,
            contextLength = obj.int("context_length"),
            supportsTools = "tools" in supported || "tool_choice" in supported,
            supportsReasoning = "reasoning" in supported || "include_reasoning" in supported,
            aliases = aliases,
            source = SOURCE_OPENROUTER
        )
    }

    private fun decodeSnapshot(raw: String): ModelPricingSnapshot? = runCatching {
        gson.fromJson(raw, ModelPricingSnapshot::class.java)
            ?.takeIf { saved ->
                saved.entries.isNotEmpty() &&
                    saved.entries.size <= MAX_REMOTE_ENTRIES &&
                    saved.entries.all { entry ->
                        entry.id.isNotBlank() &&
                            entry.inputPricePerMillion.isValidPrice() &&
                            entry.outputPricePerMillion.isValidPrice() &&
                            entry.cacheReadPricePerMillion.isValidPrice() &&
                            entry.cacheWritePricePerMillion.isValidPrice()
                    }
            }
    }.getOrNull()

    private fun identifierForms(raw: String): Set<String> {
        val cleaned = raw.trim()
            .substringBefore('?')
            .substringBefore('#')
            .removePrefix("models/")
            .removePrefix("/models/")
            .trim('/')
        if (cleaned.isBlank()) return emptySet()
        val afterModels = cleaned.substringAfterLast("/models/", cleaned)
        val short = afterModels.substringAfterLast('/')
        return sequenceOf(cleaned, afterModels, short)
            .map(::normalizeIdentifier)
            .filter(String::isNotBlank)
            .toSet()
    }

    private fun normalizeIdentifier(value: String): String = value
        .lowercase()
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

    private fun stripReleaseDate(value: String): String = value
        .replace(Regex("""-(?:20\d{2})-\d{2}-\d{2}$"""), "")
        .replace(Regex("""-(?:20\d{6})$"""), "")
        .removeSuffix("-latest")

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()

    private fun JsonObject.pricePerMillion(name: String): Double? {
        val raw = get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val value = raw.toDoubleOrNull()?.times(1_000_000.0) ?: return null
        return value.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun Double?.isValidPrice(): Boolean =
        this == null || (isFinite() && this >= 0.0)

    private fun bundledEntries(): List<ModelPricingEntry> = listOf(
        bundled("openai/gpt-5.5", "OpenAI GPT-5.5", 30.0, 180.0, 1_050_000, true, true),
        bundled("openai/gpt-5.4", "OpenAI GPT-5.4", 2.5, 15.0, 1_050_000, true, true),
        bundled("openai/gpt-5.2", "OpenAI GPT-5.2", 1.75, 14.0, 400_000, true, true),
        bundled("openai/gpt-5-mini", "OpenAI GPT-5 Mini", 0.25, 2.0, 400_000, true, true),
        bundled("openai/gpt-4.1", "OpenAI GPT-4.1", 2.0, 8.0, 1_047_576, true, false),
        bundled("openai/gpt-4o", "OpenAI GPT-4o", 2.5, 10.0, 128_000, true, false),
        bundled("openai/gpt-4o-mini", "OpenAI GPT-4o Mini", 0.15, 0.6, 128_000, true, false),
        bundled("anthropic/claude-opus-5", "Claude Opus 5", 5.0, 25.0, 1_000_000, true, true),
        bundled("anthropic/claude-opus-4.8", "Claude Opus 4.8", 5.0, 25.0, 1_000_000, true, true),
        bundled("anthropic/claude-opus-4.6", "Claude Opus 4.6", 5.0, 25.0, 1_000_000, true, true),
        bundled("anthropic/claude-sonnet-5", "Claude Sonnet 5", 2.0, 10.0, 1_000_000, true, true),
        bundled("anthropic/claude-sonnet-4.6", "Claude Sonnet 4.6", 3.0, 15.0, 1_000_000, true, true),
        bundled("anthropic/claude-haiku-4.5", "Claude Haiku 4.5", 1.0, 5.0, 200_000, true, true),
        bundled("google/gemini-3.6-flash", "Gemini 3.6 Flash", 1.5, 7.5, 1_048_576, true, true),
        bundled("google/gemini-3.5-flash", "Gemini 3.5 Flash", 1.5, 9.0, 1_048_576, true, true),
        bundled("google/gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview", 2.0, 12.0, 1_048_576, true, true),
        bundled("google/gemini-3-flash-preview", "Gemini 3 Flash Preview", 0.5, 3.0, 1_048_576, true, true),
        bundled("deepseek/deepseek-v4-pro", "DeepSeek V4 Pro", 0.435, 0.87, 1_048_576, true, true),
        bundled("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", 0.15, 0.6, 1_048_576, true, true),
        bundled("deepseek/deepseek-flash", "DeepSeek Flash", 0.15, 0.6, 1_048_576, true, true),
        bundled("deepseek/deepseek-v4-flash", "DeepSeek V4 Flash", 0.14, 0.28, 1_048_576, true, true),
        bundled("deepseek/deepseek-v3.2", "DeepSeek V3.2", 0.269, 0.4, 163_840, true, true),
        bundled("deepseek/deepseek-r1-0528", "DeepSeek R1 0528", 0.5, 2.15, 163_840, true, true),
        bundled("qwen/qwen3.7-flash", "Qwen 3.7 Flash", 0.03, 0.13, 1_000_000, true, true),
        bundled("qwen/qwen3.7-plus", "Qwen 3.7 Plus", 0.32, 1.28, 1_000_000, true, true),
        bundled("qwen/qwen3.7-max", "Qwen 3.7 Max", 1.475, 4.425, 1_000_000, true, true),
        bundled("qwen/qwen3.8-max", "Qwen 3.8 Max", 2.0, 6.0, 1_000_000, true, true),
        bundled("qwen/qwen3.8-flash", "Qwen 3.8 Flash", 0.15, 0.47, 1_000_000, true, true),
        bundled("qwen/qwen3-max", "Qwen 3 Max", 0.78, 3.9, 262_144, true, true),
        bundled("z-ai/glm-5.2", "GLM 5.2", 0.6769, 2.1274, 1_048_576, true, true, "zhipu/glm-5.2"),
        bundled("z-ai/glm-5.3", "GLM 5.3", 1.4, 4.4, 1_048_576, true, true, "zhipu/glm-5.3"),
        bundled("z-ai/glm-5.3-flash", "GLM 5.3 Flash", 0.15, 0.5, 1_048_576, true, true, "zhipu/glm-5.3-flash"),
        bundled("z-ai/glm-5.1", "GLM 5.1", 0.966, 3.036, 204_800, true, true, "zhipu/glm-5.1"),
        bundled("z-ai/glm-5", "GLM 5", 0.95, 2.55, 204_800, true, true, "zhipu/glm-5"),
        bundled("minimax/minimax-m3", "MiniMax M3", 0.3, 1.2, 1_048_576, true, true),
        bundled("minimax/minimax-m2.7", "MiniMax M2.7", 0.25, 1.0, 204_800, true, true),
        bundled("minimax/minimax-m2.5", "MiniMax M2.5", 0.15, 0.9, 204_800, true, true),
        bundled("x-ai/grok-4.5", "Grok 4.5", 2.0, 6.0, 500_000, true, true, "grok/grok-4.5"),
        bundled("x-ai/grok-4.6", "Grok 4.6", 2.0, 6.0, 500_000, true, true, "grok/grok-4.6"),
        bundled("x-ai/grok-4.7", "Grok 4.7", 2.0, 6.0, 500_000, true, true, "grok/grok-4.7"),
        bundled("x-ai/grok-4.20", "Grok 4.20", 1.25, 2.5, 2_000_000, true, true, "grok/grok-4.20"),
        bundled("openai/gpt-6-luna", "OpenAI GPT-6 Luna", 0.1, 0.5, 1_050_000, true, true),
        bundled("openai/gpt-5.6-luna", "OpenAI GPT-5.6 Luna", 0.2, 1.2, 1_050_000, true, true),
        bundled("moonshotai/kimi-k3", "Kimi K3", 3.0, 15.0, 1_048_576, true, true),
        bundled("moonshotai/kimi-k2.7-code", "Kimi K2.7 Code", 0.95, 4.0, 262_144, true, true),
        bundled("xiaomi/mimo-v2.6-pro", "MiMo V2.6 Pro", 0.435, 0.87, 1_048_576, true, true),
        bundled("xiaomi/mimo-v2.6-flash", "MiMo V2.6 Flash", 0.14, 0.28, 1_048_576, true, true),
        bundled("xiaomi/mimo-v2.5-pro", "MiMo V2.5 Pro", 0.435, 0.87, 1_048_576, true, true),
        bundled("xiaomi/mimo-v2.5", "MiMo V2.5", 0.14, 0.28, 1_048_576, true, true),
        bundled("meituan/longcat-2.5-preview-free", "LongCat 2.5 Preview", 0.0, 0.0, 1_048_576, true, true),
        bundled("tencent/hy3", "Hunyuan 3", 0.14, 0.58, 256_000, true, true),
        bundled("tencent/hy4-preview", "Hunyuan 4 Preview", 0.834, 2.501, 1_024_000, true, true),
        bundled("moonshotai/kimi-k2.5", "Kimi K2.5", 0.57, 2.85, 262_144, true, true),
        bundled("mistralai/mistral-large-2512", "Mistral Large 3", 0.5, 1.5, 262_144, true, false),
        bundled("mistralai/mistral-small-2603", "Mistral Small 4", 0.15, 0.6, 262_144, true, false),
        bundled("meta-llama/llama-4-maverick", "Llama 4 Maverick", 0.2, 0.8, 1_048_576, true, false),
        bundled("meta-llama/llama-4-scout", "Llama 4 Scout", 0.1, 0.3, 1_310_720, true, false),
        bundled("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B Instruct", 0.13, 0.4, 131_072, true, false),
        bundled("cohere/command-a", "Cohere Command A", 2.5, 10.0, 256_000, true, false)
    )

    private fun bundled(
        id: String,
        name: String,
        input: Double,
        output: Double,
        context: Int,
        tools: Boolean,
        reasoning: Boolean,
        vararg aliases: String
    ): ModelPricingEntry = ModelPricingEntry(
        id = id,
        name = name,
        provider = id.substringBefore('/'),
        inputPricePerMillion = input,
        outputPricePerMillion = output,
        contextLength = context,
        supportsTools = tools,
        supportsReasoning = reasoning,
        aliases = listOf(id.substringAfter('/')) + aliases,
        source = SOURCE_BUNDLED
    )
}
