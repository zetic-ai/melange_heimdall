package com.zeticai.melangelm.pipeline

import com.zeticai.melangelm.model.ChatMessage
import com.zeticai.melangelm.model.ChatRequest

/**
 * Mutable context passed through each pipeline stage.
 *
 * Stages may:
 *  - Read [messages] to inspect content
 *  - Replace [messages] with modified versions (anonymized, summarized, etc.)
 *  - Store stage-local data in [metadata] for use by later stages (e.g. anonymization mappings)
 *  - Call [block] to halt the pipeline and reject the request
 */
class ProxyRequest(
    originalRequest: ChatRequest,
    val metadata: MutableMap<String, Any> = mutableMapOf()
) {
    val model: String = originalRequest.model
    val stream: Boolean = originalRequest.stream
    val temperature: Double? = originalRequest.temperature
    val maxTokens: Int? = originalRequest.maxTokens

    /**
     * Current working messages. Stages should replace this with a new list when mutating content.
     * The upstream client sends whatever is here at the end of the pipeline.
     */
    var messages: List<ChatMessage> = originalRequest.messages
        private set

    private var _blocked = false
    private var _blockReason: String? = null

    val isBlocked: Boolean get() = _blocked
    val blockReason: String? get() = _blockReason

    fun updateMessages(newMessages: List<ChatMessage>) {
        messages = newMessages
    }

    fun block(reason: String) {
        _blocked = true
        _blockReason = reason
    }

    fun toUpstreamRequest(): ChatRequest = ChatRequest(
        model = model,
        messages = messages,
        temperature = temperature,
        maxTokens = maxTokens,
        stream = stream
    )
}
