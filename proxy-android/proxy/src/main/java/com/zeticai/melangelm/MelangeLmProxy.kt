package com.zeticai.melangelm

import android.content.Context
import com.zeticai.melangelm.model.ChatMessage
import com.zeticai.melangelm.model.ChatRequest
import com.zeticai.melangelm.model.PipelineOnlyResult
import com.zeticai.melangelm.model.ProxyResult
import com.zeticai.melangelm.pipeline.PipelineStage
import com.zeticai.melangelm.pipeline.ProxyPipeline
import com.zeticai.melangelm.stages.AnonymizerStage
import com.zeticai.melangelm.stages.PromptGuardStage
import com.zeticai.melangelm.stages.SummarizerStage
import com.zeticai.melangelm.upstream.OpenAIUpstreamClient
import com.zeticai.melangelm.upstream.UpstreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * # Melange LM Proxy
 *
 * An on-device middleware layer that sits between your app and any OpenAI-compatible
 * LLM API. It runs a configurable pipeline of on-device models before every request:
 *
 * ```
 * User message
 *     │
 *     ▼
 * ┌─────────────────────────────────────┐
 * │  PromptGuard  (block malicious)     │  on-device
 * │  TextAnonymizer (redact PII)        │  on-device
 * │  Summarizer (reduce tokens) [soon]  │  on-device
 * └─────────────────────────────────────┘
 *     │
 *     ▼  (only clean, anonymized, compressed prompt reaches the API)
 * Upstream LLM API (OpenAI / Azure / etc.)
 *     │
 *     ▼
 * ┌─────────────────────────────────────┐
 * │  De-anonymize response              │  on-device
 * └─────────────────────────────────────┘
 *     │
 *     ▼
 * Your app
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * val proxy = MelangeLmProxy.build(context) {
 *     promptGuard {
 *         personalKey = "your_zetic_key"
 *     }
 *     anonymizer {
 *         personalKey = "your_zetic_key"
 *         restoreInResponse = true
 *     }
 *     upstream {
 *         baseUrl = "https://api.openai.com"
 *         apiKey = BuildConfig.OPENAI_API_KEY
 *     }
 * }
 *
 * // Initialize (loads on-device models) — do this at app startup
 * proxy.initialize()
 *
 * // Chat
 * val result = proxy.chat(
 *     messages = listOf(ChatMessage("user", "Hello, what's my account balance?"))
 * )
 * when (result) {
 *     is ProxyResult.Success -> println(result.response.choices.first().message.content)
 *     is ProxyResult.Blocked -> println("Blocked: ${result.reason} by ${result.stage}")
 *     is ProxyResult.Error   -> println("Error: ${result.message}")
 * }
 * ```
 */
class MelangeLmProxy private constructor(
    private val pipeline: ProxyPipeline,
    private val defaultModel: String
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Load all on-device models. Call this once at app startup (e.g. in Application.onCreate).
     * Subsequent calls are no-ops.
     * @param onStageReady Called with each stage's name as it finishes loading.
     * @param onStageProgress Called with (stageName, progress 0.0–1.0) during model download.
     */
    suspend fun initialize(
        onStageReady: ((String) -> Unit)? = null,
        onStageProgress: ((String, Float) -> Unit)? = null
    ) = pipeline.initialize(onStageReady, onStageProgress)

    /** Update the summarizer's compression target ratio without rebuilding the proxy. */
    fun updateCompressionRatio(ratio: Double) = pipeline.updateCompressionRatio(ratio)

    /**
     * Run only the on-device pipeline stages (no upstream LLM call).
     * Useful for demo mode or previewing what the pipeline does to messages.
     */
    suspend fun processOnly(
        messages: List<ChatMessage>,
        model: String = defaultModel
    ): PipelineOnlyResult = pipeline.processOnly(
        ChatRequest(model = model, messages = messages)
    )

    /**
     * Run a chat request through the full pipeline and return the result.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        model: String = defaultModel,
        temperature: Double? = null,
        maxTokens: Int? = null
    ): ProxyResult = pipeline.process(
        ChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens
        )
    )

    // -------------------------------------------------------------------------
    // Builder DSL
    // -------------------------------------------------------------------------

    class Builder(private val context: Context) {
        private var promptGuardConfig: PromptGuardConfig? = null
        private var anonymizerConfig: AnonymizerConfig? = null
        private var summarizerConfig: SummarizerConfig? = null
        private var upstreamConfig: UpstreamConfig = UpstreamConfig()
        private val extraStages = mutableListOf<PipelineStage>()

        fun promptGuard(block: PromptGuardConfig.() -> Unit) {
            promptGuardConfig = PromptGuardConfig().apply(block)
        }

        fun anonymizer(block: AnonymizerConfig.() -> Unit) {
            anonymizerConfig = AnonymizerConfig().apply(block)
        }

        fun summarizer(
            personalKey: String,
            modelId: String = "yeonseok_zeticai_ceo/LFM2-comparison",
            llmTarget: com.zeticai.melangelm.stages.LLMTarget = com.zeticai.melangelm.stages.LLMTarget.LLAMA_CPP,
            llmQuantType: com.zeticai.melangelm.stages.LLMQuantType = com.zeticai.melangelm.stages.LLMQuantType.Q4,
            minCharsToSummarize: Int = 300,
            compressionTargetRatio: Double = 0.5
        ) {
            summarizerConfig = SummarizerConfig(
                personalKey = personalKey,
                modelId = modelId,
                llmTarget = llmTarget,
                llmQuantType = llmQuantType,
                minCharsToSummarize = minCharsToSummarize,
                compressionTargetRatio = compressionTargetRatio
            )
        }

        fun upstream(block: UpstreamConfig.() -> Unit) {
            upstreamConfig = UpstreamConfig().apply(block)
        }

        /** Add a custom pipeline stage. Stages run in insertion order after built-in stages. */
        fun addStage(stage: PipelineStage) {
            extraStages.add(stage)
        }

        fun build(): MelangeLmProxy {
            val stages = mutableListOf<PipelineStage>()

            promptGuardConfig?.let { cfg ->
                stages.add(
                    PromptGuardStage(
                        context = context,
                        personalKey = cfg.personalKey,
                        maliciousThreshold = cfg.maliciousThreshold,
                        checkRoles = cfg.checkRoles
                    )
                )
            }

            anonymizerConfig?.let { cfg ->
                stages.add(
                    AnonymizerStage(
                        context = context,
                        personalKey = cfg.personalKey,
                        redactRoles = cfg.redactRoles,
                        restoreInResponse = cfg.restoreInResponse
                    )
                )
            }

            summarizerConfig?.let { cfg ->
                stages.add(
                    SummarizerStage(
                        context = context,
                        personalKey = cfg.personalKey,
                        modelId = cfg.modelId,
                        llmTarget = cfg.llmTarget,
                        llmQuantType = cfg.llmQuantType,
                        minCharsToSummarize = cfg.minCharsToSummarize,
                        compressionTargetRatio = cfg.compressionTargetRatio
                    )
                )
            }

            stages.addAll(extraStages)

            val upstream: UpstreamClient = upstreamConfig.customClient
                ?: OpenAIUpstreamClient(
                    baseUrl = upstreamConfig.baseUrl,
                    apiKey = upstreamConfig.apiKey,
                    defaultModel = upstreamConfig.defaultModel,
                    timeoutSeconds = upstreamConfig.timeoutSeconds
                )

            return MelangeLmProxy(
                pipeline = ProxyPipeline(stages, upstream),
                defaultModel = upstreamConfig.defaultModel ?: "gpt-4o-mini"
            )
        }
    }

    data class PromptGuardConfig(
        var personalKey: String = "",
        var maliciousThreshold: Float = 0f,
        var checkRoles: Set<String> = setOf("user")
    )

    data class AnonymizerConfig(
        var personalKey: String = "",
        var redactRoles: Set<String> = setOf("user"),
        var restoreInResponse: Boolean = true
    )

    data class SummarizerConfig(
        var personalKey: String = "",
        var modelId: String = "yeonseok_zeticai_ceo/LFM2-comparison",
        var llmTarget: com.zeticai.melangelm.stages.LLMTarget = com.zeticai.melangelm.stages.LLMTarget.LLAMA_CPP,
        var llmQuantType: com.zeticai.melangelm.stages.LLMQuantType = com.zeticai.melangelm.stages.LLMQuantType.Q4,
        var minCharsToSummarize: Int = 300,
        var compressionTargetRatio: Double = 0.5
    )

    data class UpstreamConfig(
        var baseUrl: String = "https://api.openai.com",
        var apiKey: String = "",
        var defaultModel: String? = null,
        var timeoutSeconds: Long = 60,
        var customClient: UpstreamClient? = null
    )

    companion object {
        /**
         * Build a proxy with full control over every stage.
         */
        fun build(context: Context, block: Builder.() -> Unit): MelangeLmProxy =
            Builder(context).apply(block).build()

        /**
         * Quick setup: all three on-device stages (PromptGuard + Anonymizer + Summarizer).
         * Best balance of safety, privacy, and cost savings.
         */
        fun allFeatures(
            context: Context,
            zeticKey: String,
            baseUrl: String = "https://api.openai.com",
            apiKey: String,
            model: String = "gpt-4o-mini",
            compressionTarget: Double = 0.5
        ): MelangeLmProxy = build(context) {
            promptGuard { personalKey = zeticKey }
            anonymizer { personalKey = zeticKey; restoreInResponse = true }
            summarizer(personalKey = zeticKey, compressionTargetRatio = compressionTarget)
            upstream { this.baseUrl = baseUrl; this.apiKey = apiKey; defaultModel = model }
        }

        /**
         * Safety-only setup: PromptGuard + Anonymizer, no summarization.
         * No token compression — use when response quality matters more than cost.
         */
        fun safetyOnly(
            context: Context,
            zeticKey: String,
            baseUrl: String = "https://api.openai.com",
            apiKey: String,
            model: String = "gpt-4o-mini"
        ): MelangeLmProxy = build(context) {
            promptGuard { personalKey = zeticKey }
            anonymizer { personalKey = zeticKey; restoreInResponse = true }
            upstream { this.baseUrl = baseUrl; this.apiKey = apiKey; defaultModel = model }
        }

        /**
         * Cost-optimized setup: Summarizer only, no safety/privacy stages.
         * Maximum token savings with no PII redaction overhead.
         */
        fun costOptimized(
            context: Context,
            zeticKey: String,
            baseUrl: String = "https://api.openai.com",
            apiKey: String,
            model: String = "gpt-4o-mini",
            compressionTarget: Double = 0.5
        ): MelangeLmProxy = build(context) {
            summarizer(personalKey = zeticKey, compressionTargetRatio = compressionTarget)
            upstream { this.baseUrl = baseUrl; this.apiKey = apiKey; defaultModel = model }
        }

        /**
         * Wrap an existing UpstreamClient with on-device stages.
         * Use this when you already have your own HTTP client / Retrofit service
         * and just want to add Melange's pipeline in front of it.
         */
        fun wrap(
            context: Context,
            zeticKey: String,
            client: UpstreamClient,
            block: Builder.() -> Unit = {
                promptGuard { personalKey = zeticKey }
                anonymizer { personalKey = zeticKey; restoreInResponse = true }
                summarizer(personalKey = zeticKey)
            }
        ): MelangeLmProxy = build(context) {
            block()
            upstream { customClient = client }
        }
    }
}
