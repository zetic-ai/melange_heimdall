package com.zeticai.melangelm.pipeline

import com.zeticai.melangelm.model.ChatResponse

/**
 * A single processing step in the proxy pipeline.
 *
 * Stages are chained sequentially. Each stage can:
 *  - Inspect and mutate the request (call [ProxyRequest.updateMessages])
 *  - Block the request (call [ProxyRequest.block]) — pipeline stops immediately
 *  - Post-process the upstream response via [onResponse]
 *
 * Stages are initialized lazily via [initialize]. Heavy resources (model loading)
 * should happen there, not in the constructor.
 */
interface PipelineStage {
    /** Human-readable name shown in logs and [ProxyResult.Blocked.stage]. */
    val name: String

    /**
     * Called once before the first request. Load models, read tokenizer files, etc.
     * May be called from a background coroutine.
     * @param onProgress Optional callback reporting download progress (0.0–1.0).
     */
    suspend fun initialize(onProgress: ((Float) -> Unit)? = null) {}

    /**
     * Process the outgoing request. Mutate [request] in place or call [request.block].
     * Called from a background coroutine (IO dispatcher).
     */
    suspend fun processRequest(request: ProxyRequest)

    /**
     * Optional: post-process the upstream response (e.g. de-anonymize text).
     * Only called if the request was NOT blocked and the upstream returned successfully.
     * Default is a no-op (returns [response] unchanged).
     */
    suspend fun processResponse(request: ProxyRequest, response: ChatResponse): ChatResponse = response
}
