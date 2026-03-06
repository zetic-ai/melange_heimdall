//
//  PipelineStage.swift
//  MelangeLmProxy
//

import Foundation

/// A single processing step in the proxy pipeline.
///
/// Stages are chained sequentially. Each stage can:
/// - Inspect and mutate the request via `ProxyRequest.updateMessages(_:)`.
/// - Block the request via `ProxyRequest.block(_:)` — pipeline stops immediately.
/// - Post-process the upstream response in `processResponse` (e.g. de-anonymize text).
///
/// Heavy initialisation (model loading) belongs in `initialize()`, which is called once
/// before the first request.
public protocol PipelineStage: Sendable {
    /// Human-readable name shown in logs and `ProxyResult.blocked(stage:)`.
    var name: String { get }

    /// Called once before the first request. Load models, read files, etc.
    /// May throw — a failed stage is logged but does not prevent the proxy from running.
    /// - Parameter onProgress: Optional callback reporting download progress (0.0–1.0).
    func initialize(onProgress: ((Float) -> Void)?) async throws

    /// Process the outgoing request. Called from a background task.
    /// Mutate `request` or call `request.block(_:)` to halt the pipeline.
    func processRequest(_ request: ProxyRequest) async throws

    /// Post-process the upstream response (e.g. de-anonymize text).
    /// Only called when the request was NOT blocked.
    /// Default: returns `response` unchanged.
    func processResponse(_ request: ProxyRequest, response: ChatResponse) async throws -> ChatResponse
}

// Default no-op implementations
public extension PipelineStage {
    func initialize(onProgress: ((Float) -> Void)?) async throws {}
    func processResponse(_ request: ProxyRequest, response: ChatResponse) async throws -> ChatResponse { response }
}
