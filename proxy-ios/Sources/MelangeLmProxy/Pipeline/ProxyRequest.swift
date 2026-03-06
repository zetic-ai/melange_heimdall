//
//  ProxyRequest.swift
//  MelangeLmProxy
//
//  Mutable context passed through each pipeline stage.
//

import Foundation

/// Mutable context that flows through every pipeline stage.
///
/// Stages may:
/// - Read `messages` to inspect content.
/// - Call `updateMessages(_:)` to replace messages (anonymized, summarized, etc.).
/// - Store stage-local data in `metadata` for later stages (e.g. anonymization mappings).
/// - Call `block(_:)` to halt the pipeline — no further stages or upstream call will run.
public final class ProxyRequest: @unchecked Sendable {
    public let model: String
    public let temperature: Double?
    public let maxTokens: Int?
    public let stream: Bool

    /// Current working messages. Stages call `updateMessages` to mutate this.
    public private(set) var messages: [ChatMessage]

    /// Arbitrary per-request data shared between stages.
    public var metadata: [String: Any] = [:]

    private(set) var isBlocked = false
    private(set) var blockReason: String?

    init(originalRequest: ChatRequest) {
        self.model = originalRequest.model
        self.temperature = originalRequest.temperature
        self.maxTokens = originalRequest.maxTokens
        self.stream = originalRequest.stream
        self.messages = originalRequest.messages
    }

    public func updateMessages(_ newMessages: [ChatMessage]) {
        messages = newMessages
    }

    public func block(_ reason: String) {
        isBlocked = true
        blockReason = reason
    }

    func toUpstreamRequest() -> ChatRequest {
        ChatRequest(
            model: model,
            messages: messages,
            temperature: temperature,
            maxTokens: maxTokens,
            stream: stream
        )
    }
}
