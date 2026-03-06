//
//  UpstreamClient.swift
//  MelangeLmProxy
//

import Foundation

/// Abstraction over the upstream LLM API.
/// Default implementation: `OpenAIUpstreamClient` (OpenAI-compatible REST).
public protocol UpstreamClient: Sendable {
    /// Send a (post-pipeline) chat request to the upstream LLM and return the response.
    /// Should throw on HTTP/network errors.
    func send(_ request: ChatRequest) async throws -> ChatResponse
}
