//
//  AnonymizerStage.swift
//  MelangeLmProxy
//
//  On-device PII redaction using the TextAnonymizer NER model via Zetic MLange.
//  Mirrors iOS AnonymizerService.swift / AnonymizerStage.kt (Android).
//

import Foundation
@preconcurrency import ZeticMLange

private let modelId = "Steve/text-anonymizer-v1"
private let modelVersion = 1
private let seqLen = 128
/// Minimum softmax confidence to accept a non-O prediction.
/// Filters out low-confidence misclassifications (e.g. "$8.5M" → Date).
private let confidenceThreshold: Float = 0.95

public final class AnonymizerStage: PipelineStage, @unchecked Sendable {
    public let name = "TextAnonymizer"

    public static let mappingKey = "anonymizer.mapping"
    public static let detailKey = "TextAnonymizer.detail"

    private let personalKey: String
    private let redactRoles: Set<String>
    private let restoreInResponse: Bool

    private var model: ZeticMLangeModel?
    private var tokenizer: AnonymizerTokenizer?
    private var id2label: [Int: String] = [:]

    private let placeholderByLabel: [String: String] = [
        "EMAIL": "[Email]", "PHONE_NUMBER": "[Phone]", "CREDIT_CARD_NUMBER": "[Card]",
        "SSN": "[SSN]", "NRP": "[NRP]", "PERSON": "[Person]",
        "ADDRESS": "[Address]", "LOCATION": "[Location]", "DATE": "[Date]", "OTHER": "[Sensitive]"
    ]

    public init(
        personalKey: String,
        redactRoles: Set<String> = ["user"],
        restoreInResponse: Bool = true
    ) {
        self.personalKey = personalKey
        self.redactRoles = redactRoles
        self.restoreInResponse = restoreInResponse
    }

    public func initialize(onProgress: ((Float) -> Void)? = nil) async throws {
        tokenizer = AnonymizerTokenizer()
        id2label = loadLabels()
        model = try ZeticMLangeModel(personalKey: personalKey, name: modelId, version: modelVersion, onDownload: onProgress)
    }

    public func processRequest(_ request: ProxyRequest) async throws {
        guard let m = model, let tok = tokenizer else { return }
        var allMappings: [String: String] = [:]

        let updatedMessages = try request.messages.map { message -> ChatMessage in
            guard redactRoles.contains(message.role) else { return message }

            // Extract fenced code blocks — run NER on prose only, preserve code as-is
            let (prose, codeBlocks) = stripCodeBlocks(message.content)

            // Anonymize prose with NER + regex
            let (anonymizedProse, proseMapping) = try anonymize(text: prose, model: m, tokenizer: tok)
            allMappings.merge(proseMapping) { _, new in new }

            // Apply regex-only redaction to code blocks (catches PII in comments)
            var redactedBlocks = codeBlocks
            for i in 0..<redactedBlocks.count {
                let (redacted, regexMapping) = applyRegexRedaction(redactedBlocks[i])
                redactedBlocks[i] = redacted
                allMappings.merge(regexMapping) { _, new in new }
            }

            let restored = restoreCodeBlocks(anonymizedProse, blocks: redactedBlocks)
            return ChatMessage(role: message.role, content: restored)
        }

        request.updateMessages(updatedMessages)
        if !allMappings.isEmpty {
            request.metadata[Self.mappingKey] = allMappings
            let redacted = allMappings.map { "\($0.key) \u{2190} \"\($0.value)\"" }.joined(separator: ", ")
            request.metadata[Self.detailKey] = redacted
        } else {
            request.metadata[Self.detailKey] = "No PII detected"
        }
    }

    public func processResponse(_ request: ProxyRequest, response: ChatResponse) async throws -> ChatResponse {
        guard restoreInResponse,
              let mapping = request.metadata[Self.mappingKey] as? [String: String],
              !mapping.isEmpty else { return response }

        let restoredChoices = response.choices.map { choice -> ChatChoice in
            var text = choice.message.content
            mapping.forEach { placeholder, original in text = text.replacingOccurrences(of: placeholder, with: original) }
            return ChatChoice(index: choice.index, message: ChatMessage(role: choice.message.role, content: text), finishReason: choice.finishReason)
        }
        return ChatResponse(id: response.id, model: response.model, choices: restoredChoices, usage: response.usage)
    }

    // MARK: - Anonymization

    /// Max content tokens per chunk (seqLen minus BOS and EOS).
    private let chunkContentLen = seqLen - 2
    /// Overlap between chunks so entities at boundaries aren't split.
    private let chunkOverlap = 10

    private func anonymize(text: String, model: ZeticMLangeModel, tokenizer: AnonymizerTokenizer) throws -> (String, [String: String]) {
        let allIds = tokenizer.encode(text)  // includes BOS ... EOS
        // Strip BOS/EOS — we'll add them per chunk
        let contentIds = Array(allIds.dropFirst().dropLast())

        // Build per-token predictions across all chunks
        var mergedPreds = [Int](repeating: 0, count: contentIds.count)  // 0 = "O"
        let stride = max(1, chunkContentLen - chunkOverlap)
        var offset = 0

        while offset < contentIds.count {
            let end = min(offset + chunkContentLen, contentIds.count)
            let chunkIds = Array(contentIds[offset..<end])
            let preds = try runNERChunk(chunkIds, tokenizer: tokenizer, model: model)

            // For overlapping region, only overwrite if current chunk found an entity (non-O)
            for (j, pred) in preds.enumerated() {
                let globalIdx = offset + j
                guard globalIdx < mergedPreds.count else { break }
                if offset == 0 || j >= chunkOverlap || pred != 0 {
                    mergedPreds[globalIdx] = pred
                }
            }

            if end >= contentIds.count { break }
            offset += stride
        }

        // Rebuild full padded arrays for buildOutput
        let fullLen = contentIds.count + 2  // BOS + content + EOS
        var fullIds = [Int](repeating: tokenizer.padId, count: fullLen)
        var fullMask = [Int](repeating: 0, count: fullLen)
        var fullPreds = [Int](repeating: 0, count: fullLen)
        fullIds[0] = tokenizer.bosId; fullMask[0] = 1
        for i in 0..<contentIds.count {
            fullIds[i + 1] = contentIds[i]
            fullMask[i + 1] = 1
            fullPreds[i + 1] = mergedPreds[i]
        }
        fullIds[contentIds.count + 1] = tokenizer.eosId; fullMask[contentIds.count + 1] = 1

        let (nerResult, nerMapping) = buildOutput(inputIds: fullIds, attentionMask: fullMask, predIds: fullPreds, tokenizer: tokenizer)

        // Apply regex redaction for SSN, email, credit card
        let (finalResult, regexMapping) = applyRegexRedaction(nerResult)
        let combinedMapping = nerMapping.merging(regexMapping) { existing, _ in existing }
        return (finalResult, combinedMapping)
    }

    /// Run NER on a single chunk of content token IDs (without BOS/EOS).
    private func runNERChunk(_ contentIds: [Int], tokenizer: AnonymizerTokenizer, model: ZeticMLangeModel) throws -> [Int] {
        let padId = tokenizer.padId
        var paddedIds = [Int](repeating: padId, count: seqLen)
        var mask = [Int](repeating: 0, count: seqLen)

        // BOS + content + EOS
        paddedIds[0] = tokenizer.bosId; mask[0] = 1
        for i in 0..<contentIds.count {
            paddedIds[i + 1] = contentIds[i]; mask[i + 1] = 1
        }
        paddedIds[contentIds.count + 1] = tokenizer.eosId; mask[contentIds.count + 1] = 1

        let int64Ids = paddedIds.map { Int64($0) }
        let int64Mask = mask.map { Int64($0) }
        let idsData = int64Ids.withUnsafeBufferPointer { Data(buffer: $0) }
        let maskData = int64Mask.withUnsafeBufferPointer { Data(buffer: $0) }

        let outputs = try model.run(inputs: [
            Tensor(data: idsData, dataType: BuiltinDataType.int64, shape: [1, seqLen]),
            Tensor(data: maskData, dataType: BuiltinDataType.int64, shape: [1, seqLen])
        ])

        let classCount = id2label.count
        guard classCount > 0, let first = outputs.first else {
            return [Int](repeating: 0, count: contentIds.count)
        }

        // Force-copy the output data — the SDK may reuse the same internal buffer across calls
        let outputData = Data(first.data)
        let floats: [Float] = outputData.withUnsafeBytes { ptr in
            guard let base = ptr.baseAddress?.assumingMemoryBound(to: Float.self) else { return [] }
            return Array(UnsafeBufferPointer(start: base, count: outputData.count / MemoryLayout<Float>.size))
        }

        let totalSeq = floats.count / classCount
        // Extract predictions for content tokens only (skip BOS at index 0)
        // Apply softmax and reject low-confidence non-O predictions
        var preds = [Int](repeating: 0, count: contentIds.count)
        for i in 0..<contentIds.count {
            let seqIdx = i + 1  // skip BOS
            guard seqIdx < totalSeq else { break }
            let off = seqIdx * classCount
            // Softmax: exp(x - max) / sum(exp(x - max))
            var maxLogit = -Float.infinity
            for c in 0..<classCount where off + c < floats.count {
                if floats[off + c] > maxLogit { maxLogit = floats[off + c] }
            }
            var sumExp: Float = 0
            var maxIdx = 0; var maxProb: Float = 0
            for c in 0..<classCount where off + c < floats.count {
                let p = exp(floats[off + c] - maxLogit)
                sumExp += p
            }
            for c in 0..<classCount where off + c < floats.count {
                let prob = exp(floats[off + c] - maxLogit) / sumExp
                if prob > maxProb { maxProb = prob; maxIdx = c }
            }
            // Only accept non-O prediction if confidence exceeds threshold
            if maxIdx != 0 && maxProb < confidenceThreshold {
                preds[i] = 0  // fall back to "O"
            } else {
                preds[i] = maxIdx
            }
        }
        return preds
    }

    /// Regex-based redaction for SSN, email, and credit card — entity types the NER model doesn't cover.
    private func applyRegexRedaction(_ text: String) -> (String, [String: String]) {
        var result = text
        var mapping: [String: String] = [:]
        let patterns: [(String, String, String)] = [
            // SSN: 123-45-6789 or 123 45 6789
            (#"\b\d{3}[-\s]?\d{2}[-\s]?\d{4}\b"#, "[SSN]", "SSN"),
            // Email
            (#"[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"#, "[Email]", "EMAIL"),
            // Credit card: 4520-8891-3304-7721 or 4520 8891 3304 7721 or 4520889133047721
            (#"\b\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}\b"#, "[Card]", "CREDIT_CARD"),
        ]
        for (pattern, placeholder, _) in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
            let matches = regex.matches(in: result, range: NSRange(result.startIndex..., in: result))
            // Process in reverse to preserve indices
            for match in matches.reversed() {
                guard let range = Range(match.range, in: result) else { continue }
                let original = String(result[range])
                mapping[placeholder] = original
                result.replaceSubrange(range, with: placeholder)
            }
        }
        return (result, mapping)
    }

    private func buildOutput(
        inputIds: [Int], attentionMask: [Int], predIds: [Int], tokenizer: AnonymizerTokenizer
    ) -> (String, [String: String]) {
        var tokens: [String] = []
        var mapping: [String: String] = [:]
        let realLen = min(predIds.count, inputIds.count)
        var i = 0

        while i < realLen {
            if i < attentionMask.count && attentionMask[i] == 0 { i += 1; continue }
            let currentId = inputIds[i]
            if currentId == tokenizer.bosId || currentId == tokenizer.eosId || currentId == tokenizer.padId { i += 1; continue }

            let label = id2label[predIds[i]] ?? "O"
            let rawToken = tokenizer.rawToken(for: currentId, at: i) ?? ""

            if label == "O" {
                tokens.append(rawToken.replacingOccurrences(of: "\u{0120}", with: " "))
                i += 1; continue
            }

            let entityType: String
            if label.hasPrefix("B-") || label.hasPrefix("I-") { entityType = String(label.dropFirst(2)) } else { entityType = label }
            let placeholder = placeholderByLabel[entityType] ?? "[\(entityType)]"
            let leadingSpace = rawToken.hasPrefix("\u{0120}") ? " " : ""

            var entityTokens = [rawToken]
            var j = i + 1
            while j < realLen {
                if j < attentionMask.count && attentionMask[j] == 0 { break }
                let nextId = inputIds[j]
                if nextId == tokenizer.eosId || nextId == tokenizer.padId { break }
                let nextLabel = id2label[predIds[j]] ?? "O"
                if nextLabel == "I-\(entityType)" || nextLabel == "B-\(entityType)" {
                    entityTokens.append(tokenizer.rawToken(for: nextId, at: j) ?? ""); j += 1
                } else { break }
            }
            let original = entityTokens.joined().replacingOccurrences(of: "\u{0120}", with: " ").trimmingCharacters(in: .whitespaces)
            // Reject entities that are too short or purely non-alphabetic (e.g. "2", "500ms", "},")
            let hasLetters = original.unicodeScalars.contains(where: CharacterSet.letters.contains)
            if original.count < 2 || !hasLetters {
                // Emit original tokens as-is instead of placeholder
                for tok in entityTokens {
                    tokens.append(tok.replacingOccurrences(of: "\u{0120}", with: " "))
                }
                i = j
                continue
            }
            mapping[placeholder] = original
            tokens.append("\(leadingSpace)\(placeholder)")
            i = j
        }

        let result = tokens.joined()
            .replacingOccurrences(of: "  ", with: " ")
            .trimmingCharacters(in: .whitespaces)
        return (result, mapping)
    }

    private func loadLabels() -> [Int: String] {
        guard let url = Bundle.main.url(forResource: "labels", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: String]
        else { return [:] }
        var map: [Int: String] = [:]
        for (k, v) in json { if let key = Int(k) { map[key] = v } }
        return map
    }
}

// MARK: - Tokenizer (RoBERTa-style, matches iOS Tokenizer.swift)

private final class AnonymizerTokenizer: @unchecked Sendable {
    private var vocab: [String: Int] = [:]
    private var idToTokenMap: [Int: String] = [:]
    var bosId = 0, eosId = 2, unkId = 3, padId = 1

    init() { loadVocab() }

    private func loadVocab() {
        var json: [String: Any]?
        for name in ["anonymizer_tokenizer", "tokenizer"] {
            if let url = Bundle.main.url(forResource: name, withExtension: "json"),
               let data = try? Data(contentsOf: url),
               let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                json = parsed
                break
            }
        }
        guard let json else { return }

        var vocabDict: [String: Any]?
        if let model = json["model"] as? [String: Any] { vocabDict = model["vocab"] as? [String: Any] }
        if vocabDict == nil { vocabDict = json["vocab"] as? [String: Any] }
        vocabDict?.forEach { k, v in if let id = v as? Int { vocab[k] = id; idToTokenMap[id] = k } }

        bosId = vocab["<s>"] ?? vocab["[CLS]"] ?? bosId
        eosId = vocab["</s>"] ?? vocab["[SEP]"] ?? eosId
        unkId = vocab["<unk>"] ?? vocab["[UNK]"] ?? unkId
        padId = vocab["<pad>"] ?? vocab["[PAD]"] ?? padId
    }

    /// Maps token position (in the ids array) to the original character(s) for unk tokens.
    private(set) var unkOriginals: [Int: String] = [:]

    func encode(_ text: String) -> [Int] {
        var ids = [bosId]
        unkOriginals = [:]
        let processed = (" " + text).replacingOccurrences(of: " ", with: "\u{0120}")
        let chars = Array(processed)
        var i = 0
        while i < chars.count {
            var found = false
            for len in stride(from: min(20, chars.count - i), through: 1, by: -1) {
                let sub = String(chars[i..<i+len])
                if let id = vocab[sub] { ids.append(id); i += len; found = true; break }
            }
            if !found {
                unkOriginals[ids.count] = String(chars[i])
                ids.append(unkId)
                i += 1
            }
        }
        ids.append(eosId)
        return ids
    }

    func rawToken(for id: Int, at position: Int) -> String? {
        if id == unkId, let original = unkOriginals[position] {
            return original
        }
        return idToTokenMap[id]
    }
}
