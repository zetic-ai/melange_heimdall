package com.zeticai.melangelm.stages

/**
 * Extracts markdown fenced code blocks (``` ... ```), replacing them with
 * numbered placeholders like `<<CODE_BLOCK_0>>`.
 * Returns the stripped text and the extracted blocks (for restoration).
 */
internal fun stripCodeBlocks(text: String): Pair<String, List<String>> {
    val regex = Regex("```[\\s\\S]*?```")
    val matches = regex.findAll(text).toList()
    if (matches.isEmpty()) return text to emptyList()

    val blocks = mutableListOf<String>()
    var result = text

    // Process in reverse so earlier ranges stay valid
    for (match in matches.reversed()) {
        blocks.add(match.value)
        result = result.replaceRange(match.range, "<<CODE_BLOCK_${blocks.size - 1}>>")
    }

    return result to blocks
}

/**
 * Restores code blocks previously extracted by [stripCodeBlocks].
 */
internal fun restoreCodeBlocks(text: String, blocks: List<String>): String {
    var result = text
    for ((i, block) in blocks.withIndex()) {
        result = result.replace("<<CODE_BLOCK_$i>>", block)
    }
    return result
}
