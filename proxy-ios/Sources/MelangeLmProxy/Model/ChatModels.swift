//
//  ChatModels.swift
//  MelangeLmProxy
//
//  OpenAI-compatible chat request/response data models.
//

import Foundation

// MARK: - Request / Response

public struct ChatMessage: Codable, Equatable, Sendable {
    public let role: String     // "system" | "user" | "assistant"
    public let content: String

    public init(role: String, content: String) {
        self.role = role
        self.content = content
    }
}

public struct ChatRequest: Sendable {
    public let model: String
    public let messages: [ChatMessage]
    public let temperature: Double?
    public let maxTokens: Int?
    public let stream: Bool

    public init(
        model: String,
        messages: [ChatMessage],
        temperature: Double? = nil,
        maxTokens: Int? = nil,
        stream: Bool = false
    ) {
        self.model = model
        self.messages = messages
        self.temperature = temperature
        self.maxTokens = maxTokens
        self.stream = stream
    }
}

public struct ChatChoice: Sendable {
    public let index: Int
    public let message: ChatMessage
    public let finishReason: String?
}

public struct TokenUsage: Sendable {
    public let promptTokens: Int
    public let completionTokens: Int
    public let totalTokens: Int
}

public struct ChatResponse: Sendable {
    public let id: String
    public let model: String
    public let choices: [ChatChoice]
    public let usage: TokenUsage?
}

// MARK: - Proxy Result

public enum ProxyResult: Sendable {
    /// Pipeline completed and the LLM responded.
    case success(ChatResponse)
    /// A pipeline stage blocked the request before it reached the LLM.
    case blocked(reason: BlockReason, stage: String)
    /// An error occurred in the pipeline or upstream.
    case failure(message: String, error: Error?)
}

public enum BlockReason: Sendable {
    case maliciousPrompt
    case policyViolation
    case upstreamError
}

// MARK: - Pipeline-only result (no upstream call)

/// Result of running only the on-device pipeline stages, without calling the upstream LLM.
public struct PipelineOnlyResult: Sendable {
    public let isBlocked: Bool
    public let blockedBy: String?
    public let blockReason: String?
    /// Messages after all stages processed them (anonymized, summarized, etc.)
    public let processedMessages: [ChatMessage]
    /// Per-stage results showing what each stage did.
    public let stageResults: [StageResult]
}

public struct StageResult: Sendable {
    public let name: String
    public let status: StageStatus
    public let detail: String?

    public init(name: String, status: StageStatus, detail: String? = nil) {
        self.name = name
        self.status = status
        self.detail = detail
    }
}

public enum StageStatus: Sendable {
    case passed
    case modified
    case blocked(String)
    case error(String)
}
