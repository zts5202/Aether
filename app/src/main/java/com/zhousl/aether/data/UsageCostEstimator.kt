package com.zhousl.aether.data

import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.ChatUsageStatistics
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Official pay-as-you-go list prices for the two models this app estimates.
 *
 * DeepSeek-V4.1 Flash (`deepseek-flash`, legacy `deepseek-v4-flash`) uses the
 * published **peak** USD rates from
 * https://api-docs.deepseek.com/quick_start/pricing
 * (cache hit $0.006, cache miss $0.30, output $1.20 per 1M tokens).
 * Those USD amounts are converted with [UsdToCnyRate]. Peak hours are only
 * part of the week, and the FX rate is a fixed constant, so the popup is an
 * estimate — not an invoice.
 *
 * MiMo V2.6 Flash (`mimo-v2.6-flash`) uses the CNY realtime rates from
 * https://mimo.mi.com/docs/zh-CN/price/pay-as-you-go
 * (cache hit ¥0.02, cache miss ¥1.00, output ¥2.00 per 1M tokens).
 *
 * Cache writes are free on both price sheets and are ignored. Matching follows
 * the same case-insensitive substring style as [ModelCapabilitiesResolver], but
 * only these Flash ids are priced. Other DeepSeek / MiMo models (for example
 * `deepseek-v4-pro` or `mimo-v2.6-pro`) stay unpriced.
 *
 * pi-ai normalizes OpenAI-style usage so `input` is the cache **miss**
 * (`prompt_tokens - cacheRead - cacheWrite`) and `cacheRead` is stored
 * separately as [ChatUsageStatistics.cachedInputTokens]. Do not subtract the
 * cache hit from input again.
 */
internal val UsdToCnyRate: BigDecimal = BigDecimal("7.2")

internal enum class UsagePricingFamily {
    DeepSeekFlash,
    MimoV26Flash,
}

private data class PerMillionRates(
    val cacheHit: BigDecimal,
    val cacheMiss: BigDecimal,
    val output: BigDecimal,
)

private val MillionTokens = BigDecimal("1000000")
private val CostScale = 12
private val DeepSeekFlashPeakUsdPerMillion = PerMillionRates(
    cacheHit = BigDecimal("0.006"),
    cacheMiss = BigDecimal("0.30"),
    output = BigDecimal("1.20"),
)
private val MimoV26FlashCnyPerMillion = PerMillionRates(
    cacheHit = BigDecimal("0.02"),
    cacheMiss = BigDecimal("1.00"),
    output = BigDecimal("2.00"),
)

internal fun usagePricingFamily(modelId: String): UsagePricingFamily? {
    val model = modelId.trim().lowercase(Locale.US)
    if (model.isEmpty()) return null
    return when {
        isDeepSeekFlashModel(model) -> UsagePricingFamily.DeepSeekFlash
        isMimoV26FlashModel(model) -> UsagePricingFamily.MimoV26Flash
        else -> null
    }
}

internal fun modelIdFromProviderPayload(providerPayloadJson: String): String {
    if (providerPayloadJson.isBlank()) return ""
    return runCatching {
        JSONObject(providerPayloadJson).optString("model").trim()
    }.getOrDefault("")
}

/**
 * Prefer a model id we can price. The requested id wins when it is priced;
 * otherwise the id echoed on the provider payload is used so older turns that
 * only stored `providerPayloadJson` still estimate.
 */
internal fun preferredUsageModelId(
    requestedModelId: String,
    providerPayloadJson: String,
): String {
    val requested = requestedModelId.trim()
    val reported = modelIdFromProviderPayload(providerPayloadJson)
    return when {
        usagePricingFamily(requested) != null -> requested
        usagePricingFamily(reported) != null -> reported
        requested.isNotBlank() -> requested
        else -> reported
    }
}

internal fun resolveUsageModelId(message: ChatMessage): String =
    preferredUsageModelId(
        requestedModelId = message.usageStatistics?.modelId.orEmpty(),
        providerPayloadJson = message.providerPayloadJson,
    )

internal fun estimateUsageCostCny(
    modelId: String,
    inputTokens: Long?,
    cachedInputTokens: Long?,
    outputTokens: Long?,
): BigDecimal? {
    val rates = cnyRatesPerMillion(modelId) ?: return null
    val cached = tokenCount(cachedInputTokens)
    val miss = tokenCount(inputTokens)
    val output = tokenCount(outputTokens)
    return sumCost(cached, rates.cacheHit)
        .add(sumCost(miss, rates.cacheMiss))
        .add(sumCost(output, rates.output))
}

internal fun estimateTurnCostCny(
    usage: ChatUsageStatistics,
    modelId: String,
): BigDecimal? = estimateUsageCostCny(
    modelId = modelId,
    inputTokens = usage.inputTokens,
    cachedInputTokens = usage.cachedInputTokens,
    outputTokens = usage.outputTokens,
)

internal fun estimateMessageCostCny(message: ChatMessage): BigDecimal? {
    val usage = message.usageStatistics ?: return null
    return estimateTurnCostCny(usage, resolveUsageModelId(message))
}

/** Sum of priced turn costs. Unpriced models are skipped; null when none are priced. */
internal fun estimateSessionCostCny(messages: List<ChatMessage>): BigDecimal? {
    var sawPricedUsage = false
    var total = BigDecimal.ZERO
    messages.forEach { message ->
        val cost = estimateMessageCostCny(message) ?: return@forEach
        sawPricedUsage = true
        total = total.add(cost)
    }
    return if (sawPricedUsage) total else null
}

/** `¥0.12` for amounts at or above one fen; smaller positive amounts keep extra digits. */
internal fun formatEstimatedCny(amount: BigDecimal): String {
    val normalized = if (amount.signum() < 0) BigDecimal.ZERO else amount
    val scale = when {
        normalized.compareTo(BigDecimal.ZERO) == 0 -> 2
        normalized >= BigDecimal("0.01") -> 2
        normalized >= BigDecimal("0.0001") -> 4
        else -> 6
    }
    val rounded = normalized.setScale(scale, RoundingMode.HALF_UP)
    val plain = if (scale == 2) {
        rounded.toPlainString()
    } else {
        val stripped = rounded.stripTrailingZeros().toPlainString()
        if ('.' in stripped) stripped else "$stripped.00"
    }
    return "¥$plain"
}

private fun isDeepSeekFlashModel(model: String): Boolean =
    "deepseek-flash" in model ||
        "deepseek-v4-flash" in model ||
        "deepseek-v4.1-flash" in model

private fun isMimoV26FlashModel(model: String): Boolean =
    "mimo-v2.6-flash" in model

private fun cnyRatesPerMillion(modelId: String): PerMillionRates? = when (usagePricingFamily(modelId)) {
    UsagePricingFamily.DeepSeekFlash -> PerMillionRates(
        cacheHit = DeepSeekFlashPeakUsdPerMillion.cacheHit.multiply(UsdToCnyRate),
        cacheMiss = DeepSeekFlashPeakUsdPerMillion.cacheMiss.multiply(UsdToCnyRate),
        output = DeepSeekFlashPeakUsdPerMillion.output.multiply(UsdToCnyRate),
    )
    UsagePricingFamily.MimoV26Flash -> MimoV26FlashCnyPerMillion
    null -> null
}

private fun tokenCount(tokens: Long?): BigDecimal =
    BigDecimal.valueOf((tokens ?: 0L).coerceAtLeast(0L))

private fun sumCost(tokens: BigDecimal, cnyPerMillion: BigDecimal): BigDecimal =
    tokens.multiply(cnyPerMillion).divide(MillionTokens, CostScale, RoundingMode.HALF_UP)
