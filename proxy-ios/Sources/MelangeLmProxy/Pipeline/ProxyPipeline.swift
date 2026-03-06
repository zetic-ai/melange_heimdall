//
//  ProxyPipeline.swift
//  MelangeLmProxy
//
//  Orchestrates the full request lifecycle through all stages.
//

import Foundation

/// Orchestrates the full request lifecycle:
///  1. Initialize all stages (once, lazily)
///  2. Run each stage's `processRequest` in order
///  3. If not blocked, forward to the upstream LLM
///  4. Run `processResponse` in reverse order (unwinding)
///  5. Return `ProxyResult` to the caller
actor ProxyPipeline {
    private let stages: [any PipelineStage]
    private let upstream: any UpstreamClient
    private var initialized = false

    init(stages: [any PipelineStage], upstream: any UpstreamClient) {
        self.stages = stages
        self.upstream = upstream
    }

    nonisolated func updateCompressionRatio(_ ratio: Double) {
        for stage in stages {
            if let summarizer = stage as? SummarizerStage {
                summarizer.setCompressionRatio(ratio)
            }
        }
    }

    func initialize(
        onStageReady: ((String) -> Void)? = nil,
        onStageProgress: ((String, Float) -> Void)? = nil
    ) async {
        guard !initialized else { return }
        await withTaskGroup(of: Void.self) { group in
            for stage in stages {
                group.addTask {
                    do {
                        let progressForStage: ((Float) -> Void)? = onStageProgress.map { callback in
                            return { progress in callback(stage.name, progress) }
                        }
                        try await stage.initialize(onProgress: progressForStage)
                        onStageReady?(stage.name)
                    } catch {
                        // logged below
                    }
                }
            }
        }
        initialized = true
        log("Pipeline ready with \(stages.count) stage(s): \(stages.map(\.name).joined(separator: ", "))")
    }

    func process(_ chatRequest: ChatRequest) async -> ProxyResult {
        if !initialized { await initialize() }

        let request = ProxyRequest(originalRequest: chatRequest)

        // --- Request phase ---
        for stage in stages {
            do {
                try await stage.processRequest(request)
            } catch {
                log("Stage '\(stage.name)' threw during processRequest: \(error)", level: .error)
                return .failure(message: "Stage '\(stage.name)' failed: \(error.localizedDescription)", error: error)
            }
            if request.isBlocked {
                log("Request blocked by '\(stage.name)': \(request.blockReason ?? "")")
                return .blocked(reason: .maliciousPrompt, stage: stage.name)
            }
        }

        // --- Upstream ---
        let upstreamResponse: ChatResponse
        do {
            upstreamResponse = try await upstream.send(request.toUpstreamRequest())
        } catch {
            log("Upstream failed: \(error)", level: .error)
            return .failure(message: "Upstream failed: \(error.localizedDescription)", error: error)
        }

        // --- Response phase (reverse order) ---
        var response = upstreamResponse
        for stage in stages.reversed() {
            do {
                response = try await stage.processResponse(request, response: response)
            } catch {
                log("Stage '\(stage.name)' threw during processResponse — skipping: \(error)", level: .warning)
            }
        }

        return .success(response)
    }

    /// Run only the on-device pipeline stages (no upstream call).
    /// Returns the processed request so the caller can inspect what each stage did.
    func processOnly(_ chatRequest: ChatRequest) async -> PipelineOnlyResult {
        if !initialized { await initialize() }

        let request = ProxyRequest(originalRequest: chatRequest)

        var stageResults: [StageResult] = []

        for stage in stages {
            let before = request.messages
            do {
                try await stage.processRequest(request)
            } catch {
                stageResults.append(StageResult(name: stage.name, status: .error(error.localizedDescription)))
                continue
            }
            let detailKey = "\(stage.name).detail"
            let detail = request.metadata[detailKey] as? String

            if request.isBlocked {
                stageResults.append(StageResult(name: stage.name, status: .blocked(request.blockReason ?? "blocked"), detail: detail))
                return PipelineOnlyResult(
                    isBlocked: true,
                    blockedBy: stage.name,
                    blockReason: request.blockReason,
                    processedMessages: request.messages,
                    stageResults: stageResults
                )
            }
            let changed = before != request.messages
            stageResults.append(StageResult(name: stage.name, status: changed ? .modified : .passed, detail: detail))
        }

        return PipelineOnlyResult(
            isBlocked: false,
            blockedBy: nil,
            blockReason: nil,
            processedMessages: request.messages,
            stageResults: stageResults
        )
    }

    // MARK: - Logging

    private enum LogLevel { case info, warning, error }

    private func log(_ message: String, level: LogLevel = .info) {
        #if DEBUG
        let prefix: String
        switch level {
        case .info:    prefix = "[MelangeLmProxy]"
        case .warning: prefix = "[MelangeLmProxy ⚠️]"
        case .error:   prefix = "[MelangeLmProxy ❌]"
        }
        print("\(prefix) \(message)")
        #endif
    }
}
