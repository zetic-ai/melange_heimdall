//
//  CodeBlockHelper.swift
//  MelangeLmProxy
//
//  Extracts markdown fenced code blocks so stages can process prose
//  without touching code.
//

import Foundation

/// Extracts markdown fenced code blocks (``` ... ```), replacing them with
/// numbered placeholders like `<<CODE_BLOCK_0>>`.
/// Returns the stripped text and the extracted blocks (for restoration).
func stripCodeBlocks(_ text: String) -> (stripped: String, blocks: [String]) {
    guard let regex = try? NSRegularExpression(pattern: "```[\\s\\S]*?```") else {
        return (text, [])
    }
    let matches = regex.matches(in: text, range: NSRange(text.startIndex..., in: text))
    guard !matches.isEmpty else { return (text, []) }

    var blocks: [String] = []
    var result = text

    // Process in reverse so earlier ranges stay valid
    for match in matches.reversed() {
        guard let range = Range(match.range, in: result) else { continue }
        blocks.append(String(result[range]))
        result.replaceSubrange(range, with: "<<CODE_BLOCK_\(blocks.count - 1)>>")
    }

    return (result, blocks)
}

/// Restores code blocks previously extracted by `stripCodeBlocks`.
func restoreCodeBlocks(_ text: String, blocks: [String]) -> String {
    var result = text
    for (i, block) in blocks.enumerated() {
        result = result.replacingOccurrences(of: "<<CODE_BLOCK_\(i)>>", with: block)
    }
    return result
}
