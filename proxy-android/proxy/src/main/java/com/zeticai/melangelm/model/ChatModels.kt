package com.zeticai.melangelm.model

/**
 * OpenAI-compatible chat request/response data models.
 * Compatible with the /v1/chat/completions endpoint.
 */

data class ChatMessage(
    val role: String,       // "system" | "user" | "assistant"
    val content: String
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val stream: Boolean = false
)

data class ChatChoice(
    val index: Int,
    val message: ChatMessage,
    val finishReason: String?
)

data class ChatResponse(
    val id: String,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: TokenUsage?
)

data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * Result returned to the caller after the full proxy pipeline.
 */
sealed class ProxyResult {
    /** Pipeline completed and LLM responded. */
    data class Success(val response: ChatResponse, val savings: ProxySavings? = null) : ProxyResult()

    /** A pipeline stage blocked the request (e.g. malicious prompt detected). */
    data class Blocked(val reason: BlockReason, val stage: String) : ProxyResult()

    /** An error occurred in the pipeline or upstream. */
    data class Error(val message: String, val cause: Throwable? = null) : ProxyResult()
}

enum class BlockReason {
    MALICIOUS_PROMPT,
    POLICY_VIOLATION,
    UPSTREAM_ERROR
}

// Pipeline-only result (no upstream call)

data class PipelineOnlyResult(
    val isBlocked: Boolean,
    val blockedBy: String?,
    val blockReason: String?,
    val processedMessages: List<ChatMessage>,
    val stageResults: List<StageResult>
)

data class StageResult(
    val name: String,
    val status: StageStatus,
    val detail: String? = null
)

enum class StageStatus {
    PASSED,
    MODIFIED,
    BLOCKED,
    ERROR
}
