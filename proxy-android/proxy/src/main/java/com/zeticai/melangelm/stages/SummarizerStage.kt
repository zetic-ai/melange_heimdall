package com.zeticai.melangelm.stages

import android.content.Context
import android.util.Log
import com.zeticai.melangelm.pipeline.PipelineStage
import com.zeticai.melangelm.pipeline.ProxyRequest
import com.zeticai.mlange.core.model.llm.ZeticMLangeLLMModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SummarizerStage"
private const val DEFAULT_MODEL_ID = "yeonseok_zeticai_ceo/LFM2-comparison"

/**
 * Pipeline stage that compresses long user prompts on-device before they reach the upstream LLM.
 *
 * Uses [ZeticMLangeLLMModel] to produce a compact restatement of the user's intent,
 * preserving key context while reducing token count and therefore API cost.
 *
 * The original messages are preserved in [ProxyRequest.metadata] under [ORIGINAL_MESSAGES_KEY].
 *
 * @param context Android context.
 * @param personalKey Zetic MLange personal key.
 * @param modelId Zetic model identifier.
 * @param llmTarget Target hardware for inference (default: [LLMTarget.LLAMA_CPP]).
 * @param llmQuantType Model quantization level (default: [LLMQuantType.Q4]).
 * @param minCharsToSummarize Only summarize messages longer than this (default: 300 chars).
 * @param summarizeRoles Message roles to summarize (default: ["user"]).
 */
class SummarizerStage(
    private val context: Context,
    private val personalKey: String,
    private val modelId: String = DEFAULT_MODEL_ID,
    private val llmTarget: LLMTarget = LLMTarget.LLAMA_CPP,
    private val llmQuantType: LLMQuantType = LLMQuantType.Q4,
    private val minCharsToSummarize: Int = 300,
    private val summarizeRoles: Set<String> = setOf("user"),
    /**
     * Target compression ratio (0.0–1.0). E.g. 0.4 means "summarize to ~40% of original length".
     * Injected into the prompt to guide the LLM. Actual ratio depends on model output.
     */
    @Volatile private var compressionTargetRatio: Double = 0.5
) : PipelineStage {

    override val name = "Summarizer"

    companion object {
        const val ORIGINAL_MESSAGES_KEY = "summarizer.originalMessages"
        const val DETAIL_KEY = "Summarizer.detail"
    }

    fun setCompressionRatio(ratio: Double) {
        compressionTargetRatio = ratio
    }

    private var model: ZeticMLangeLLMModel? = null

    override suspend fun initialize(onProgress: ((Float) -> Unit)?) {
        withContext(Dispatchers.IO) {
            val wrappedProgress: ((Float) -> Unit)? = onProgress?.let { cb ->
                { p: Float -> Log.d(TAG, "onProgress: $p"); cb(p) }
            }
            model = ZeticMLangeLLMModel(
                context.applicationContext,
                personalKey,
                modelId,
                null,
                llmTarget.toSdkTarget(),
                llmQuantType.toSdkQuantType(),
                onProgress = wrappedProgress
            )
            Log.i(TAG, "Summarizer LLM model loaded: $modelId")
        }
    }

    override suspend fun processRequest(request: ProxyRequest) {
        withContext(Dispatchers.IO) {
            val m = model ?: run { Log.w(TAG, "Model not loaded — skipping summarization"); return@withContext }

            var didSummarize = false
            val updatedMessages = request.messages.map { message ->
                if (message.role !in summarizeRoles || message.content.length <= minCharsToSummarize) {
                    return@map message
                }
                // Extract fenced code blocks — summarize prose only, preserve code as-is
                val (prose, codeBlocks) = stripCodeBlocks(message.content)
                if (prose.length <= minCharsToSummarize) return@map message

                val summary = summarize(prose, m)
                val restored = restoreCodeBlocks(summary, codeBlocks)
                Log.d(TAG, "Summarized ${message.content.length} chars → ${restored.length} chars")
                didSummarize = true
                message.copy(content = restored)
            }

            if (didSummarize) {
                request.metadata[ORIGINAL_MESSAGES_KEY] = request.messages
                request.updateMessages(updatedMessages)
                val originalLen = (request.metadata[ORIGINAL_MESSAGES_KEY] as? List<*>)
                    ?.filterIsInstance<com.zeticai.melangelm.model.ChatMessage>()
                    ?.lastOrNull { it.role == "user" }?.content?.length ?: 0
                val newLen = updatedMessages.lastOrNull { it.role == "user" }?.content?.length ?: 0
                if (originalLen > 0) {
                    val pct = (newLen.toDouble() / originalLen * 100).toInt()
                    request.metadata[DETAIL_KEY] = "$originalLen → $newLen chars ($pct% of original)"
                }
            }
        }
    }

    private fun summarize(text: String, m: ZeticMLangeLLMModel): String {
        val prompt = buildPrompt(text)
        m.run(prompt)
        val tokens = StringBuilder()
        while (true) {
            val result = m.waitForNextToken()
            if (result.token.isEmpty()) break
            tokens.append(result.token)
        }
        // Clear KV cache after generation so the next run starts fresh
        runCatching { m.cleanUp() }
        return tokens.toString().trim()
    }

    private fun buildPrompt(text: String): String {
        val targetPct = (compressionTargetRatio * 100).toInt()
        return """
            Compress the following user message to approximately $targetPct% of its original length.
            Rules:
            - Keep the user's core question or request intact.
            - If the message contains code, keep the key code structure (class/function signatures, logic) and remove only redundant or boilerplate parts. Do NOT remove all code.
            - If the message contains data or examples, keep representative samples.
            - Preserve names, numbers, and specific technical terms.
            Output only the compressed message — no preamble, no explanation.

            User message:
            $text

            Compressed:
        """.trimIndent()
    }
}

enum class LLMTarget {
    LLAMA_CPP, MLLM;
    fun toSdkTarget(): com.zeticai.mlange.core.model.llm.LLMTarget = when (this) {
        LLAMA_CPP -> com.zeticai.mlange.core.model.llm.LLMTarget.LLAMA_CPP
        MLLM -> com.zeticai.mlange.core.model.llm.LLMTarget.MLLM
    }
}

enum class LLMQuantType {
    Q4, Q8, FP16;
    fun toSdkQuantType(): com.zeticai.mlange.core.model.llm.LLMQuantType = when (this) {
        Q4  -> com.zeticai.mlange.core.model.llm.LLMQuantType.GGUF_QUANT_Q4_K_M
        Q8  -> com.zeticai.mlange.core.model.llm.LLMQuantType.GGUF_QUANT_Q8_0
        FP16 -> com.zeticai.mlange.core.model.llm.LLMQuantType.GGUF_QUANT_F16
    }
}
