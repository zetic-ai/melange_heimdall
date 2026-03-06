package com.example.custom

import com.zeticai.melangelm.MelangeLmProxy
import com.zeticai.melangelm.model.ChatMessage
import com.zeticai.melangelm.model.ChatResponse
import com.zeticai.melangelm.pipeline.PipelineStage
import com.zeticai.melangelm.pipeline.ProxyRequest
import android.content.Context

/**
 * Example: Building custom pipeline stages
 *
 * The proxy pipeline is fully extensible. You can add your own stages
 * that run alongside the built-in ones (PromptGuard, Anonymizer,
 * Summarizer). Custom stages can:
 *
 *   - Inspect and modify outgoing requests
 *   - Block requests that violate your policies
 *   - Post-process LLM responses before they reach the user
 *   - Add metadata for logging and analytics
 *
 * This example shows three custom stages:
 *   1. Language detector — block non-English messages
 *   2. Rate limiter — prevent API abuse
 *   3. Response filter — redact competitor mentions from LLM output
 */

// --- Custom Stage 1: Language Gate ---
// Only allow messages in supported languages

class LanguageGateStage(
    private val allowedLanguages: Set<String> = setOf("en", "es", "fr")
) : PipelineStage {
    override val name = "LanguageGate"

    override suspend fun processRequest(request: ProxyRequest) {
        val lastUserMsg = request.messages.lastOrNull { it.role == "user" }
            ?: return

        // Simple heuristic: check for CJK characters, Cyrillic, etc.
        // In production, use a language detection model
        val text = lastUserMsg.content
        val hasCJK = text.any { it.code in 0x4E00..0x9FFF }
        val hasCyrillic = text.any { it.code in 0x0400..0x04FF }

        if (hasCJK || hasCyrillic) {
            request.block("Unsupported language detected")
        }
    }
}

// --- Custom Stage 2: Rate Limiter ---
// Prevent users from spamming the API

class RateLimiterStage(
    private val maxRequestsPerMinute: Int = 10
) : PipelineStage {
    override val name = "RateLimiter"

    private val timestamps = mutableListOf<Long>()

    override suspend fun processRequest(request: ProxyRequest) {
        val now = System.currentTimeMillis()
        val oneMinuteAgo = now - 60_000

        // Remove old timestamps
        timestamps.removeAll { it < oneMinuteAgo }

        if (timestamps.size >= maxRequestsPerMinute) {
            request.block("Rate limit exceeded: $maxRequestsPerMinute requests/minute")
            return
        }

        timestamps.add(now)
    }
}

// --- Custom Stage 3: Response Filter ---
// Post-process LLM responses (e.g. redact competitor mentions)

class CompetitorFilterStage(
    private val competitors: List<String> = listOf("CompetitorA", "CompetitorB")
) : PipelineStage {
    override val name = "CompetitorFilter"

    override suspend fun processRequest(request: ProxyRequest) {
        // No-op on request phase — this stage only processes responses
    }

    override suspend fun processResponse(
        request: ProxyRequest,
        response: ChatResponse
    ): ChatResponse {
        // Replace competitor mentions in the LLM's response
        val filtered = response.choices.map { choice ->
            var content = choice.message.content
            competitors.forEach { competitor ->
                content = content.replace(
                    competitor,
                    "[alternative solution]",
                    ignoreCase = true
                )
            }
            choice.copy(message = choice.message.copy(content = content))
        }
        return response.copy(choices = filtered)
    }
}

// --- Putting it all together ---

fun buildCustomProxy(context: Context, zeticKey: String, apiKey: String): MelangeLmProxy {
    return MelangeLmProxy.build(context) {
        // Built-in stages (order matters — they run sequentially)
        promptGuard { personalKey = zeticKey }
        anonymizer { personalKey = zeticKey; restoreInResponse = true }
        summarizer(personalKey = zeticKey, compressionTargetRatio = 0.5)

        // Your custom stages run after the built-in ones
        addStage(LanguageGateStage(allowedLanguages = setOf("en", "es")))
        addStage(RateLimiterStage(maxRequestsPerMinute = 20))
        addStage(CompetitorFilterStage(competitors = listOf("Acme Corp", "Rival Inc")))

        upstream {
            baseUrl = "https://api.openai.com"
            this.apiKey = apiKey
            defaultModel = "gpt-4o-mini"
        }
    }
}

// --- The pipeline execution order ---
//
// Request phase (in order):
//   1. PromptGuard     — block malicious prompts
//   2. Anonymizer      — redact PII
//   3. Summarizer      — compress long prompts
//   4. LanguageGate    — block unsupported languages
//   5. RateLimiter     — prevent API abuse
//   6. CompetitorFilter — (no-op on request)
//   7. → Send to upstream LLM
//
// Response phase (reverse order):
//   6. CompetitorFilter — redact competitor mentions
//   5. RateLimiter      — (no-op on response)
//   4. LanguageGate     — (no-op on response)
//   3. Summarizer       — (no-op on response)
//   2. Anonymizer       — restore PII in response
//   1. PromptGuard      — (no-op on response)
//   → Return to app
