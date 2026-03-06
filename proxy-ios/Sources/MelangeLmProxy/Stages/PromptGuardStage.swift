//
//  PromptGuardStage.swift
//  MelangeLmProxy
//
//  On-device malicious prompt detection using Llama Prompt Guard 2 via Zetic MLange.
//

import Foundation
@preconcurrency import ZeticMLange

private let modelName = "jathin-zetic/llama_prompt_guard_2"
private let modelVersion = 1
private let seqLen = 128

/// Pipeline stage that classifies each user message using Llama Prompt Guard 2 (on-device).
///
/// If any checked message is classified as Malicious (malicious logit > benign logit + threshold),
/// the request is blocked before it reaches the upstream LLM.
///
/// - Parameters:
///   - personalKey: Your Zetic MLange personal key.
///   - maliciousThreshold: Logit gap required to block (default 0 — block if malicious > benign).
///   - checkRoles: Message roles to inspect (default: `["user"]`).
public final class PromptGuardStage: PipelineStage, @unchecked Sendable {
    public let name = "PromptGuard"

    private let personalKey: String
    private let maliciousThreshold: Float
    private let checkRoles: Set<String>

    private var model: ZeticMLangeModel?
    private let tokenizer = PromptGuardTokenizer()
    private let inferenceQueue = DispatchQueue(label: "melangelm.promptguard", qos: .userInitiated)

    public init(
        personalKey: String,
        maliciousThreshold: Float = 0,
        checkRoles: Set<String> = ["user"]
    ) {
        self.personalKey = personalKey
        self.maliciousThreshold = maliciousThreshold
        self.checkRoles = checkRoles
    }

    public func initialize(onProgress: ((Float) -> Void)? = nil) async throws {
        tokenizer.ensureLoaded()
        model = try ZeticMLangeModel(personalKey: personalKey, name: modelName, version: modelVersion, onDownload: onProgress)
    }

    public static let detailKey = "PromptGuard.detail"

    public func processRequest(_ request: ProxyRequest) async throws {
        guard let m = model else { return }
        guard let lastUserMessage = request.messages.last(where: { checkRoles.contains($0.role) }) else { return }

        let prompt = "User: \(lastUserMessage.content)\nAgent: "
        let tensors = buildTensors(for: prompt)
        let outputs = try m.run(inputs: tensors)
        // Force-copy output data — the SDK may reuse the same internal buffer across calls
        let outputData = outputs.map { Data($0.data) }
        let (benign, malicious) = parseLogits(outputData)

        let scoreDetail = "benign=\(String(format: "%.3f", benign)), malicious=\(String(format: "%.3f", malicious))"
        request.metadata[Self.detailKey] = scoreDetail

        if malicious - benign > maliciousThreshold {
            request.block("Malicious prompt detected (\(scoreDetail))")
        }
    }

    // MARK: - Tensor helpers

    private func buildTensors(for prompt: String) -> [Tensor] {
        let ids: [Int32]
        if tokenizer.isLoaded {
            ids = tokenizer.encode(prompt).map { Int32(truncatingIfNeeded: $0) }
        } else {
            ids = Array(prompt.utf8).map { Int32($0) }
        }

        let padId = Int32(tokenizer.padId)
        var tokenIds = [Int32](repeating: padId, count: seqLen)
        let promptLength = min(ids.count, seqLen)
        for i in 0..<promptLength { tokenIds[i] = ids[i] }

        var mask = [Int32](repeating: 0, count: seqLen)
        for i in 0..<promptLength { mask[i] = 1 }

        let tokenData = tokenIds.withUnsafeBufferPointer { Data(buffer: $0) }
        let maskData = mask.withUnsafeBufferPointer { Data(buffer: $0) }
        return [
            Tensor(data: tokenData, dataType: BuiltinDataType.int32, shape: [1, seqLen]),
            Tensor(data: maskData, dataType: BuiltinDataType.int32, shape: [1, seqLen])
        ]
    }

    private func parseLogits(_ outputs: [Data]) -> (benign: Float, malicious: Float) {
        guard let first = outputs.first, first.count >= MemoryLayout<Float>.size * 2 else {
            return (0, 0)
        }
        let floats: [Float] = first.withUnsafeBytes { ptr in
            guard let base = ptr.baseAddress?.assumingMemoryBound(to: Float.self) else { return [] }
            return Array(UnsafeBufferPointer(start: base, count: first.count / MemoryLayout<Float>.size))
        }
        return (floats.count > 0 ? floats[0] : 0, floats.count > 1 ? floats[1] : 0)
    }
}

// MARK: - Tokenizer (inline, mirrors iOS PromptGuardTokenizer.swift)

private final class PromptGuardTokenizer: @unchecked Sendable {
    private var vocab: [String: Int] = [:]
    var bosId = 1, eosId = 2, unkId = 0, padId = 0
    private(set) var isLoaded = false
    private let lock = NSLock()

    func ensureLoaded() {
        lock.lock(); defer { lock.unlock() }
        guard !isLoaded else { return }
        loadVocab()
    }

    private func loadVocab() {
        for name in ["prompt_guard_tokenizer", "tokenizer (3)", "tokenizer"] {
            guard let url = Bundle.main.url(forResource: name, withExtension: "json"),
                  let data = try? Data(contentsOf: url),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { continue }
            parseJSON(json)
            isLoaded = true
            return
        }
    }

    private func parseJSON(_ json: [String: Any]) {
        if let model = json["model"] as? [String: Any], let v = model["vocab"] {
            parseVocabEntry(v)
        } else if let v = json["vocab"] {
            parseVocabEntry(v)
        }
        if let added = json["added_tokens"] as? [[String: Any]] {
            for item in added {
                if let content = item["content"] as? String, let id = item["id"] as? Int {
                    vocab[content] = id
                }
            }
        }
        bosId = vocab["<s>"] ?? vocab["<|begin_of_text|>"] ?? vocab["[CLS]"] ?? 1
        eosId = vocab["</s>"] ?? vocab["<|end_of_text|>"] ?? vocab["[SEP]"] ?? 2
        unkId = vocab["<unk>"] ?? vocab["[UNK]"] ?? 0
        padId = vocab["<pad>"] ?? vocab["[PAD]"] ?? 0
    }

    private func parseVocabEntry(_ v: Any) {
        if let dict = v as? [String: Any] {
            for (k, val) in dict { if let id = val as? Int { vocab[k] = id } }
        } else if let arr = v as? [[Any]] {
            for (i, entry) in arr.enumerated() { if let k = entry.first as? String { vocab[k] = i } }
        }
    }

    func encode(_ text: String) -> [Int] {
        var ids = [bosId]
        let withSpace = " " + text
        let hasSP = vocab.keys.contains { $0.hasPrefix("\u{2581}") }
        let spaceSub = hasSP ? "\u{2581}" : "\u{0120}"
        let processed = withSpace.replacingOccurrences(of: " ", with: spaceSub)
        let chars = Array(processed)
        var i = 0
        while i < chars.count {
            var found = false
            for len in stride(from: min(32, chars.count - i), through: 1, by: -1) {
                let sub = String(chars[i..<i+len])
                if let id = vocab[sub] { ids.append(id); i += len; found = true; break }
            }
            if !found {
                let single = String(chars[i])
                ids.append(vocab[spaceSub + single] ?? vocab[single] ?? unkId)
                i += 1
            }
        }
        ids.append(eosId)
        return ids
    }
}
