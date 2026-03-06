//
//  MelangeLmProxy.swift
//  MelangeLmProxy
//
//  # Melange LM Proxy (iOS)
//
//  On-device middleware for LLM API calls — safer, cheaper, and privacy-preserving.
//
//  Pipeline:
//    User message → PromptGuard → TextAnonymizer → Summarizer → Upstream LLM
//                                                             ← De-anonymize ←
//

import Foundation

/// # Melange LM Proxy
///
/// An on-device middleware layer between your iOS app and any OpenAI-compatible LLM API.
///
/// ## Usage
///
/// ```swift
/// let proxy = MelangeLmProxy.build {
///     $0.promptGuard(personalKey: "your_zetic_key")
///     $0.anonymizer(personalKey: "your_zetic_key", restoreInResponse: true)
///     $0.summarizer(personalKey: "your_zetic_key")
///     $0.upstream(baseURL: "https://api.openai.com", apiKey: "sk-...")
/// }
///
/// // Initialize once at app startup (loads on-device models)
/// try await proxy.initialize()
///
/// // Use like a normal chat client
/// let result = await proxy.chat(messages: [
///     ChatMessage(role: "system", content: "You are a helpful assistant."),
///     ChatMessage(role: "user", content: "My name is Alice. What is 2+2?")
/// ])
///
/// switch result {
/// case .success(let response):
///     print(response.choices.first?.message.content ?? "")
/// case .blocked(let reason, let stage):
///     print("Blocked by \(stage): \(reason)")
/// case .failure(let message, _):
///     print("Error: \(message)")
/// }
/// ```
public final class MelangeLmProxy: @unchecked Sendable {
    private let pipeline: ProxyPipeline
    private let defaultModel: String

    private init(pipeline: ProxyPipeline, defaultModel: String) {
        self.pipeline = pipeline
        self.defaultModel = defaultModel
    }

    // MARK: - Public API

    /// Load all on-device models. Call once at app startup.
    /// Safe to call multiple times — subsequent calls are no-ops.
    /// - Parameter onStageReady: Called with each stage's name as it finishes loading.
    /// - Parameter onStageProgress: Called with (stageName, progress 0.0–1.0) during model download.
    public func initialize(
        onStageReady: ((String) -> Void)? = nil,
        onStageProgress: ((String, Float) -> Void)? = nil
    ) async throws {
        await pipeline.initialize(onStageReady: onStageReady, onStageProgress: onStageProgress)
    }

    /// Update the summarizer's compression target ratio without rebuilding the proxy.
    public func updateCompressionRatio(_ ratio: Double) {
        pipeline.updateCompressionRatio(ratio)
    }

    /// Run only the on-device pipeline stages (PromptGuard, Anonymizer, Summarizer)
    /// without calling the upstream LLM. Useful for demo mode or previewing pipeline effects.
    public func processOnly(
        messages: [ChatMessage],
        model: String? = nil
    ) async -> PipelineOnlyResult {
        let request = ChatRequest(model: model ?? defaultModel, messages: messages)
        return await pipeline.processOnly(request)
    }

    /// Run a chat request through the full pipeline and return the result.
    public func chat(
        messages: [ChatMessage],
        model: String? = nil,
        temperature: Double? = nil,
        maxTokens: Int? = nil
    ) async -> ProxyResult {
        let request = ChatRequest(
            model: model ?? defaultModel,
            messages: messages,
            temperature: temperature,
            maxTokens: maxTokens
        )
        return await pipeline.process(request)
    }

    // MARK: - Builder

    /// Build a proxy with full control over every stage.
    public static func build(_ configure: (Builder) -> Void) -> MelangeLmProxy {
        let builder = Builder()
        configure(builder)
        return builder.build()
    }

    /// Quick setup: all three on-device stages (PromptGuard + Anonymizer + Summarizer).
    public static func allFeatures(
        zeticKey: String,
        baseURL: String = "https://api.openai.com",
        apiKey: String,
        model: String = "gpt-4o-mini",
        compressionTarget: Double = 0.5
    ) -> MelangeLmProxy {
        build {
            $0.promptGuard(personalKey: zeticKey)
            $0.anonymizer(personalKey: zeticKey, restoreInResponse: true)
            $0.summarizer(personalKey: zeticKey, compressionTargetRatio: compressionTarget)
            $0.upstream(baseURL: baseURL, apiKey: apiKey, defaultModel: model)
        }
    }

    /// Safety-only setup: PromptGuard + Anonymizer, no summarization.
    public static func safetyOnly(
        zeticKey: String,
        baseURL: String = "https://api.openai.com",
        apiKey: String,
        model: String = "gpt-4o-mini"
    ) -> MelangeLmProxy {
        build {
            $0.promptGuard(personalKey: zeticKey)
            $0.anonymizer(personalKey: zeticKey, restoreInResponse: true)
            $0.upstream(baseURL: baseURL, apiKey: apiKey, defaultModel: model)
        }
    }

    /// Cost-optimized setup: Summarizer only, no safety/privacy stages.
    public static func costOptimized(
        zeticKey: String,
        baseURL: String = "https://api.openai.com",
        apiKey: String,
        model: String = "gpt-4o-mini",
        compressionTarget: Double = 0.5
    ) -> MelangeLmProxy {
        build {
            $0.summarizer(personalKey: zeticKey, compressionTargetRatio: compressionTarget)
            $0.upstream(baseURL: baseURL, apiKey: apiKey, defaultModel: model)
        }
    }

    /// Wrap an existing UpstreamClient with on-device stages.
    /// Use when you already have your own HTTP client and want to add Melange's pipeline.
    public static func wrap(
        zeticKey: String,
        client: any UpstreamClient,
        configure: ((Builder) -> Void)? = nil
    ) -> MelangeLmProxy {
        build { builder in
            if let configure = configure {
                configure(builder)
            } else {
                builder.promptGuard(personalKey: zeticKey)
                builder.anonymizer(personalKey: zeticKey, restoreInResponse: true)
                builder.summarizer(personalKey: zeticKey)
            }
            builder.setCustomClient(client)
        }
    }

    public final class Builder {
        private var promptGuardConfig: PromptGuardConfig?
        private var anonymizerConfig: AnonymizerConfig?
        private var summarizerConfig: SummarizerConfig?
        private var upstreamConfig = UpstreamConfig()
        private var extraStages: [any PipelineStage] = []

        public func promptGuard(
            personalKey: String,
            maliciousThreshold: Float = 0,
            checkRoles: Set<String> = ["user"]
        ) {
            promptGuardConfig = PromptGuardConfig(
                personalKey: personalKey,
                maliciousThreshold: maliciousThreshold,
                checkRoles: checkRoles
            )
        }

        public func anonymizer(
            personalKey: String,
            redactRoles: Set<String> = ["user"],
            restoreInResponse: Bool = true
        ) {
            anonymizerConfig = AnonymizerConfig(
                personalKey: personalKey,
                redactRoles: redactRoles,
                restoreInResponse: restoreInResponse
            )
        }

        public func summarizer(
            personalKey: String,
            modelId: String = "yeonseok_zeticai_ceo/LFM2-comparison",
            llmTarget: ProxyLLMTarget = .llamaCpp,
            llmQuantType: ProxyLLMQuantType = .q4,
            minCharsToSummarize: Int = 300,
            compressionTargetRatio: Double = 0.5
        ) {
            summarizerConfig = SummarizerConfig(
                personalKey: personalKey,
                modelId: modelId,
                llmTarget: llmTarget,
                llmQuantType: llmQuantType,
                minCharsToSummarize: minCharsToSummarize,
                compressionTargetRatio: compressionTargetRatio
            )
        }

        public func upstream(
            baseURL: String,
            apiKey: String,
            defaultModel: String = "gpt-4o-mini",
            timeoutSeconds: Double = 60
        ) {
            upstreamConfig = UpstreamConfig(
                baseURL: baseURL,
                apiKey: apiKey,
                defaultModel: defaultModel,
                timeoutSeconds: timeoutSeconds
            )
        }

        /// Add a fully custom pipeline stage after the built-in ones.
        public func addStage(_ stage: any PipelineStage) {
            extraStages.append(stage)
        }

        /// Use a custom upstream client instead of the built-in OpenAI client.
        public func setCustomClient(_ client: any UpstreamClient) {
            upstreamConfig.customClient = client
        }

        fileprivate func build() -> MelangeLmProxy {
            var stages: [any PipelineStage] = []

            if let cfg = promptGuardConfig {
                stages.append(PromptGuardStage(
                    personalKey: cfg.personalKey,
                    maliciousThreshold: cfg.maliciousThreshold,
                    checkRoles: cfg.checkRoles
                ))
            }

            if let cfg = anonymizerConfig {
                stages.append(AnonymizerStage(
                    personalKey: cfg.personalKey,
                    redactRoles: cfg.redactRoles,
                    restoreInResponse: cfg.restoreInResponse
                ))
            }

            if let cfg = summarizerConfig {
                stages.append(SummarizerStage(
                    personalKey: cfg.personalKey,
                    modelId: cfg.modelId,
                    llmTarget: cfg.llmTarget,
                    llmQuantType: cfg.llmQuantType,
                    minCharsToSummarize: cfg.minCharsToSummarize,
                    compressionTargetRatio: cfg.compressionTargetRatio
                ))
            }

            stages.append(contentsOf: extraStages)

            let upstream = upstreamConfig.customClient ?? OpenAIUpstreamClient(
                baseURL: upstreamConfig.baseURL,
                apiKey: upstreamConfig.apiKey,
                defaultModel: upstreamConfig.defaultModel,
                timeoutSeconds: upstreamConfig.timeoutSeconds
            )

            return MelangeLmProxy(
                pipeline: ProxyPipeline(stages: stages, upstream: upstream),
                defaultModel: upstreamConfig.defaultModel ?? "gpt-4o-mini"
            )
        }
    }

    // MARK: - Config structs

    struct PromptGuardConfig {
        var personalKey: String
        var maliciousThreshold: Float
        var checkRoles: Set<String>
    }

    struct AnonymizerConfig {
        var personalKey: String
        var redactRoles: Set<String>
        var restoreInResponse: Bool
    }

    struct SummarizerConfig {
        var personalKey: String
        var modelId: String
        var llmTarget: ProxyLLMTarget
        var llmQuantType: ProxyLLMQuantType
        var minCharsToSummarize: Int
        var compressionTargetRatio: Double
    }

    struct UpstreamConfig {
        var baseURL: String = "https://api.openai.com"
        var apiKey: String = ""
        var defaultModel: String? = nil
        var timeoutSeconds: Double = 60
        var customClient: (any UpstreamClient)? = nil
    }
}
