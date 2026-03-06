//
//  SummarizerStage.swift
//  MelangeLmProxy
//
//  On-device prompt summarization using ZeticMLangeLLMModel.
//  Compresses long user messages before forwarding to the upstream LLM,
//  reducing token count and therefore API cost.
//

import Foundation
@preconcurrency import ZeticMLange

/// Pipeline stage that compresses long user prompts on-device before they reach the upstream LLM.
public final class SummarizerStage: PipelineStage, @unchecked Sendable {
    public static let defaultModelId = "yeonseok_zeticai_ceo/LFM2-comparison"

    public let name = "Summarizer"

    public static let originalMessagesKey = "summarizer.originalMessages"
    public static let detailKey = "Summarizer.detail"

    private let personalKey: String
    private let modelId: String
    private let llmTarget: ProxyLLMTarget
    private let llmQuantType: ProxyLLMQuantType
    private let minCharsToSummarize: Int
    private var compressionTargetRatio: Double
    private let summarizeRoles: Set<String>

    private var model: ZeticMLangeLLMModel?

    public init(
        personalKey: String,
        modelId: String = SummarizerStage.defaultModelId,
        llmTarget: ProxyLLMTarget = .llamaCpp,
        llmQuantType: ProxyLLMQuantType = .q4,
        minCharsToSummarize: Int = 300,
        compressionTargetRatio: Double = 0.5,
        summarizeRoles: Set<String> = ["user"]
    ) {
        self.personalKey = personalKey
        self.modelId = modelId
        self.llmTarget = llmTarget
        self.llmQuantType = llmQuantType
        self.minCharsToSummarize = minCharsToSummarize
        self.compressionTargetRatio = compressionTargetRatio
        self.summarizeRoles = summarizeRoles
    }

    public func setCompressionRatio(_ ratio: Double) {
        compressionTargetRatio = ratio
    }

    public func initialize(onProgress: ((Float) -> Void)? = nil) async throws {
        model = try ZeticMLangeLLMModel(
            personalKey: personalKey,
            name: modelId,
            target: llmTarget.toSDKTarget(),
            quantType: llmQuantType.toSDKQuantType(),
            onDownload: onProgress
        )
    }

    public func processRequest(_ request: ProxyRequest) async throws {
        guard let m = model else { return }

        var didSummarize = false
        let updatedMessages = try request.messages.map { message -> ChatMessage in
            guard summarizeRoles.contains(message.role),
                  message.content.count > minCharsToSummarize
            else { return message }

            // Extract fenced code blocks — summarize prose only, preserve code as-is
            let (prose, codeBlocks) = stripCodeBlocks(message.content)
            guard prose.count > minCharsToSummarize else { return message }

            let summary = try summarize(prose, using: m)
            let restored = restoreCodeBlocks(summary, blocks: codeBlocks)
            didSummarize = true
            return ChatMessage(role: message.role, content: restored)
        }

        if didSummarize {
            request.metadata[Self.originalMessagesKey] = request.messages
            request.updateMessages(updatedMessages)
            let originalLen = request.metadata[Self.originalMessagesKey]
                .flatMap { ($0 as? [ChatMessage])?.last(where: { $0.role == "user" })?.content.count } ?? 0
            let newLen = updatedMessages.last(where: { $0.role == "user" })?.content.count ?? 0
            if originalLen > 0 {
                let pct = Int(Double(newLen) / Double(originalLen) * 100)
                request.metadata[Self.detailKey] = "\(originalLen) → \(newLen) chars (\(pct)% of original)"
            }
        }
    }

    // MARK: - Summarization

    private func summarize(_ text: String, using m: ZeticMLangeLLMModel) throws -> String {
        let prompt = buildSummarizationPrompt(text)
        _ = try m.run(prompt)

        var outputTokens: [String] = []
        while true {
            let result = m.waitForNextToken()
            if result.isFinished { break }
            outputTokens.append(result.token)
        }
        // Clear KV cache after generation so the next run starts fresh
        try? m.cleanUp()
        return outputTokens.joined().trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func buildSummarizationPrompt(_ text: String) -> String {
        let targetPct = Int(compressionTargetRatio * 100)
        return """
        Compress the following user message to approximately \(targetPct)% of its original length. \
        Rules: \
        - Keep the user's core question or request intact. \
        - If the message contains code, keep the key code structure (class/function signatures, logic) and remove only redundant or boilerplate parts. Do NOT remove all code. \
        - If the message contains data or examples, keep representative samples. \
        - Preserve names, numbers, and specific technical terms. \
        Output only the compressed message — no preamble, no explanation.

        User message:
        \(text)

        Compressed:
        """
    }
}

// MARK: - LLM config enums

/// Target hardware for on-device LLM inference.
public enum ProxyLLMTarget {
    case llamaCpp
    case mllm

    func toSDKTarget() -> LLMTarget {
        switch self {
        case .llamaCpp: return .LLAMA_CPP
        case .mllm: return .MLLM
        }
    }
}

/// Quantization level for the LLM model weights.
public enum ProxyLLMQuantType {
    case q4
    case q8
    case fp16

    func toSDKQuantType() -> LLMQuantType {
        switch self {
        case .q4:  return .GGUF_QUANT_Q4_K_M
        case .q8:  return .GGUF_QUANT_Q8_0
        case .fp16: return .GGUF_QUANT_F16
        }
    }
}
