package com.zeticai.melangelm.model

/**
 * Tracks token and cost savings produced by the proxy pipeline for a single request.
 *
 * Populated by [ProxyPipeline] after each successful call and available on [ProxyResult.Success].
 *
 * @property originalCharCount  Character count of all user messages before pipeline processing.
 * @property processedCharCount Character count after anonymization + summarization.
 * @property upstreamPromptTokens  Actual prompt tokens reported by the upstream LLM API.
 * @property upstreamCompletionTokens Completion tokens from the upstream LLM API.
 * @property estimatedOriginalTokens  Estimated token count of the original messages (chars / 4).
 * @property compressionRatio   processedCharCount / originalCharCount (1.0 = no compression).
 * @property costPerInputToken  Cost per input token in USD (configure to match your pricing tier).
 * @property costPerOutputToken Cost per output token in USD.
 */
data class ProxySavings(
    val originalCharCount: Int,
    val processedCharCount: Int,
    val upstreamPromptTokens: Int?,
    val upstreamCompletionTokens: Int?,
    val costPerInputToken: Double,
    val costPerOutputToken: Double
) {
    /** Rough estimate: GPT-4-class models average ~4 chars per token. */
    val estimatedOriginalTokens: Int get() = originalCharCount / 4
    val estimatedProcessedTokens: Int get() = processedCharCount / 4

    /** Fraction of original size sent to the API. 0.6 = 40% reduction. */
    val compressionRatio: Double
        get() = if (originalCharCount == 0) 1.0 else processedCharCount.toDouble() / originalCharCount

    /** Tokens saved vs. sending the unprocessed prompt. */
    val tokensSaved: Int get() = maxOf(0, estimatedOriginalTokens - (upstreamPromptTokens ?: estimatedProcessedTokens))

    /** Estimated USD saved on this request. */
    val estimatedSavedUsd: Double get() = tokensSaved * costPerInputToken

    /** Human-readable compression label, e.g. "−38%" or "no change". */
    val compressionLabel: String get() {
        val pct = ((1.0 - compressionRatio) * 100).toInt()
        return if (pct <= 0) "no change" else "−$pct%"
    }

    companion object {
        // Defaults match gpt-4o-mini pricing (as of 2025-03). Update to match your model.
        const val GPT_4O_MINI_INPUT_PER_TOKEN  = 0.00000015   // $0.15 / 1M tokens
        const val GPT_4O_MINI_OUTPUT_PER_TOKEN = 0.00000060   // $0.60 / 1M tokens
        const val GPT_4O_INPUT_PER_TOKEN       = 0.000005     // $5 / 1M tokens
        const val GPT_4O_OUTPUT_PER_TOKEN      = 0.000015     // $15 / 1M tokens
    }
}
