package com.zeticai.melangelm.pipeline

import android.util.Log
import com.zeticai.melangelm.model.BlockReason
import com.zeticai.melangelm.model.ChatRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.zeticai.melangelm.model.PipelineOnlyResult
import com.zeticai.melangelm.model.ProxyResult
import com.zeticai.melangelm.model.ProxySavings
import com.zeticai.melangelm.model.StageResult
import com.zeticai.melangelm.model.StageStatus
import com.zeticai.melangelm.stages.SummarizerStage
import com.zeticai.melangelm.upstream.UpstreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ProxyPipeline"

/**
 * Orchestrates the full request lifecycle:
 *   1. Initialize stages (once)
 *   2. For each request: run request stages in order
 *   3. If not blocked: forward to upstream LLM
 *   4. Run response stages in reverse order (unwinding)
 *   5. Return result to caller
 */
class ProxyPipeline(
    private val stages: List<PipelineStage>,
    private val upstream: UpstreamClient
) {
    @Volatile private var initialized = false

    fun updateCompressionRatio(ratio: Double) {
        stages.filterIsInstance<SummarizerStage>().forEach { it.setCompressionRatio(ratio) }
    }

    suspend fun initialize(
        onStageReady: ((String) -> Unit)? = null,
        onStageProgress: ((String, Float) -> Unit)? = null
    ) {
        if (initialized) return
        coroutineScope {
            stages.map { stage ->
                async(Dispatchers.IO) {
                    Log.d(TAG, "Initializing stage: ${stage.name}")
                    val progressForStage: ((Float) -> Unit)? = onStageProgress?.let { callback ->
                        { progress: Float -> callback(stage.name, progress) }
                    }
                    runCatching { stage.initialize(progressForStage) }
                        .onSuccess { onStageReady?.invoke(stage.name) }
                        .onFailure { Log.e(TAG, "Stage ${stage.name} init failed", it) }
                }
            }.awaitAll()
        }
        initialized = true
        Log.i(TAG, "Pipeline initialized with ${stages.size} stage(s): ${stages.map { it.name }}")
    }

    suspend fun process(chatRequest: ChatRequest): ProxyResult = withContext(Dispatchers.IO) {
        if (!initialized) initialize()

        val request = ProxyRequest(chatRequest)
        val originalCharCount = chatRequest.messages.sumOf { it.content.length }

        // --- Request phase ---
        for (stage in stages) {
            runCatching { stage.processRequest(request) }
                .onFailure { e ->
                    Log.e(TAG, "Stage ${stage.name} threw during processRequest", e)
                    return@withContext ProxyResult.Error("Stage '${stage.name}' failed: ${e.message}", e)
                }
            if (request.isBlocked) {
                Log.i(TAG, "Request blocked by stage '${stage.name}': ${request.blockReason}")
                return@withContext ProxyResult.Blocked(BlockReason.MALICIOUS_PROMPT, stage.name)
            }
        }

        // --- Upstream ---
        val upstreamResponse = runCatching { upstream.send(request.toUpstreamRequest()) }
            .getOrElse { e ->
                Log.e(TAG, "Upstream call failed", e)
                return@withContext ProxyResult.Error("Upstream failed: ${e.message}", e)
            }

        // --- Response phase (reverse order so stages can "unwind") ---
        var response = upstreamResponse
        for (stage in stages.reversed()) {
            response = runCatching { stage.processResponse(request, response) }
                .getOrElse { e ->
                    Log.w(TAG, "Stage ${stage.name} threw during processResponse, skipping", e)
                    response
                }
        }

        val processedCharCount = request.messages.sumOf { it.content.length }
        val savings = ProxySavings(
            originalCharCount = originalCharCount,
            processedCharCount = processedCharCount,
            upstreamPromptTokens = response.usage?.promptTokens,
            upstreamCompletionTokens = response.usage?.completionTokens,
            costPerInputToken = ProxySavings.GPT_4O_MINI_INPUT_PER_TOKEN,
            costPerOutputToken = ProxySavings.GPT_4O_MINI_OUTPUT_PER_TOKEN
        )

        ProxyResult.Success(response, savings)
    }

    suspend fun processOnly(chatRequest: ChatRequest): PipelineOnlyResult = withContext(Dispatchers.IO) {
        if (!initialized) initialize()

        val request = ProxyRequest(chatRequest)
        val stageResults = mutableListOf<StageResult>()

        for (stage in stages) {
            val before = request.messages.toList()
            runCatching { stage.processRequest(request) }
                .onFailure { e ->
                    stageResults.add(StageResult(stage.name, StageStatus.ERROR, detail = e.message))
                    return@withContext PipelineOnlyResult(
                        isBlocked = false,
                        blockedBy = null,
                        blockReason = e.message,
                        processedMessages = request.messages,
                        stageResults = stageResults
                    )
                }
            val detail = request.metadata["${stage.name}.detail"] as? String
            if (request.isBlocked) {
                stageResults.add(StageResult(stage.name, StageStatus.BLOCKED, detail = detail))
                return@withContext PipelineOnlyResult(
                    isBlocked = true,
                    blockedBy = stage.name,
                    blockReason = request.blockReason,
                    processedMessages = request.messages,
                    stageResults = stageResults
                )
            }
            val changed = before != request.messages
            stageResults.add(StageResult(stage.name, if (changed) StageStatus.MODIFIED else StageStatus.PASSED, detail = detail))
        }

        PipelineOnlyResult(
            isBlocked = false,
            blockedBy = null,
            blockReason = null,
            processedMessages = request.messages,
            stageResults = stageResults
        )
    }
}
