package com.zhousl.aether.data

/**
 * Pay-as-you-go keys (`sk-`) only work on `api.xiaomimimo.com`.
 * Token Plan keys (`tp-`) only work on `token-plan-*.xiaomimimo.com`.
 */
const val XiaomiTokenPlanKeyOnPayAsYouGoMessage =
    "API Key 与接口不匹配：以 tp- 开头的密钥是 Token Plan 密钥，不能用于按量付费接口 api.xiaomimimo.com。请改用 Token Plan 地址，例如 https://token-plan-cn.xiaomimimo.com/v1。"

const val XiaomiPayAsYouGoKeyOnTokenPlanMessage =
    "API Key 与接口不匹配：以 sk- 开头的密钥是按量付费密钥，不能用于 Token Plan 接口。请改用按量付费地址 https://api.xiaomimimo.com/v1。"

fun isXiaomiMiMoEndpoint(baseUrl: String, providerId: String = ""): Boolean {
    val host = endpointHost(baseUrl)
    if (host == "xiaomimimo.com" || host.endsWith(".xiaomimimo.com")) return true
    if (host == "mimo.mi.com" || host.endsWith(".mimo.mi.com")) return true
    val id = providerId.trim().lowercase()
    return id == "xiaomi" || id.startsWith("xiaomi-token-plan")
}

/** Thinking levels offered for models with an on/off thinking switch (MiMo, DeepSeek flash). */
val ThinkingToggleLevels: List<String> = listOf("off", "low", "medium", "high")

/** True for models whose API exposes a thinking switch even if the catalog omits `reasoning`. */
fun hasThinkingToggle(piProviderId: String, modelId: String, baseUrl: String): Boolean {
    if (isXiaomiMiMoEndpoint(baseUrl, piProviderId)) return true
    val model = modelId.trim().lowercase()
    if ("mimo" in model) return true
    return "deepseek" in piProviderId.trim().lowercase() && "flash" in model
}

/** Catalog levels when known; otherwise off/low/medium/high for thinking-switch models. */
fun effectiveThinkingLevels(
    catalogLevels: List<String>?,
    piProviderId: String,
    modelId: String,
    baseUrl: String,
): List<String> {
    val onlyOff = catalogLevels.orEmpty().all { it.trim().lowercase() in setOf("off", "none") }
    if (onlyOff && hasThinkingToggle(piProviderId, modelId, baseUrl)) return ThinkingToggleLevels
    return catalogLevels.orEmpty()
}

fun xiaomiApiKeyEndpointMismatchMessage(baseUrl: String, apiKey: String): String? {
    val host = endpointHost(baseUrl)
    val key = apiKey.trim()
    if (key.isEmpty() || "xiaomimimo.com" !in host) return null
    val tokenPlan = host.startsWith("token-plan-") || host.startsWith("token-plan.")
    val payAsYouGo = host == "api.xiaomimimo.com"
    return when {
        key.startsWith("tp-", ignoreCase = true) && payAsYouGo ->
            XiaomiTokenPlanKeyOnPayAsYouGoMessage
        key.startsWith("sk-", ignoreCase = true) && tokenPlan ->
            XiaomiPayAsYouGoKeyOnTokenPlanMessage
        else -> null
    }
}

internal fun endpointHost(baseUrl: String): String {
    val trimmed = baseUrl.trim()
    val withoutScheme = if ("://" in trimmed) trimmed.substringAfter("://") else trimmed
    val authority = withoutScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
    return authority.substringBefore(':').lowercase()
}
