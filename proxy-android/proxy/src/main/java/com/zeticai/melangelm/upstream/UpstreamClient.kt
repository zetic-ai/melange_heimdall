package com.zeticai.melangelm.upstream

import com.zeticai.melangelm.model.ChatRequest
import com.zeticai.melangelm.model.ChatResponse

/**
 * Abstraction over the upstream LLM API.
 * Default implementation: [OpenAIUpstreamClient] (OpenAI-compatible REST).
 */
interface UpstreamClient {
    /**
     * Send a (post-pipeline) chat request to the upstream LLM and return the response.
     * Called from a background coroutine. Should throw on HTTP/network errors.
     */
    suspend fun send(request: ChatRequest): ChatResponse
}
