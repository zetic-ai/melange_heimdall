package com.zeticai.melangelm.stages

import android.content.Context
import android.util.Log
import com.zeticai.melangelm.model.ChatMessage
import com.zeticai.melangelm.model.ChatResponse
import com.zeticai.melangelm.pipeline.PipelineStage
import com.zeticai.melangelm.pipeline.ProxyRequest
import com.zeticai.mlange.core.model.ZeticMLangeModel
import com.zeticai.mlange.core.tensor.DataType
import com.zeticai.mlange.core.tensor.Tensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteOrder

private const val TAG = "AnonymizerStage"
private const val MODEL_ID = "Steve/text-anonymizer-v1"
private const val SEQ_LEN = 128
/** Minimum softmax confidence to accept a non-O prediction. */
private const val CONFIDENCE_THRESHOLD = 0.95f

/**
 * Pipeline stage that redacts PII from user messages before they reach the upstream LLM.
 *
 * On request: tokenizes user messages, runs the NER model, replaces entities with placeholders.
 * On response: optionally restores original values in the LLM's reply ([restoreInResponse]).
 *
 * The anonymization mapping is stored in [ProxyRequest.metadata] under [MAPPING_KEY],
 * so the response stage can de-anonymize the reply.
 *
 * Config:
 *  - [personalKey]: Zetic MLange personal key
 *  - [redactRoles]: which message roles to anonymize (default: ["user"])
 *  - [restoreInResponse]: de-anonymize assistant reply (default: true)
 */
class AnonymizerStage(
    private val context: Context,
    private val personalKey: String,
    private val redactRoles: Set<String> = setOf("user"),
    private val restoreInResponse: Boolean = true
) : PipelineStage {

    override val name = "TextAnonymizer"

    companion object {
        const val MAPPING_KEY = "anonymizer.mapping"
        const val DETAIL_KEY = "TextAnonymizer.detail"
    }

    private var model: ZeticMLangeModel? = null
    private lateinit var tokenizer: AnonymizerTokenizer
    private var id2label: Map<Int, String> = emptyMap()

    private val placeholderByLabel = mapOf(
        "EMAIL" to "[Email]", "PHONE_NUMBER" to "[Phone]", "CREDIT_CARD_NUMBER" to "[Card]",
        "SSN" to "[SSN]", "NRP" to "[NRP]", "PERSON" to "[Person]",
        "ADDRESS" to "[Address]", "LOCATION" to "[Location]", "DATE" to "[Date]",
        "OTHER" to "[Sensitive]"
    )

    override suspend fun initialize(onProgress: ((Float) -> Unit)?) {
        withContext(Dispatchers.IO) {
            tokenizer = AnonymizerTokenizer(context)
            id2label = loadLabels()
            val wrappedProgress: ((Float) -> Unit)? = onProgress?.let { cb ->
                { p: Float -> Log.d(TAG, "onProgress: $p"); cb(p) }
            }
            model = ZeticMLangeModel(context.applicationContext, personalKey, MODEL_ID, null, onProgress = wrappedProgress)
            Log.i(TAG, "Anonymizer model loaded. Labels: ${id2label.size}")
        }
    }

    override suspend fun processRequest(request: ProxyRequest) {
        withContext(Dispatchers.IO) {
            val m = model ?: run { Log.w(TAG, "Model not loaded — skipping anonymization"); return@withContext }
            val allMappings = mutableMapOf<String, String>() // placeholder → original

            val updatedMessages = request.messages.map { message ->
                if (message.role !in redactRoles) return@map message

                // Extract fenced code blocks — run NER on prose only, preserve code as-is
                val (prose, codeBlocks) = stripCodeBlocks(message.content)

                // Anonymize prose with NER + regex
                val (anonymizedProse, proseMapping) = anonymize(m, prose)
                allMappings.putAll(proseMapping)

                // Apply regex-only redaction to code blocks (catches PII in comments)
                val redactedBlocks = codeBlocks.map { block ->
                    val (redacted, regexMapping) = applyRegexRedaction(block)
                    allMappings.putAll(regexMapping)
                    redacted
                }

                val restored = restoreCodeBlocks(anonymizedProse, redactedBlocks)
                message.copy(content = restored)
            }

            request.updateMessages(updatedMessages)
            if (allMappings.isNotEmpty()) {
                request.metadata[MAPPING_KEY] = allMappings
                val redacted = allMappings.entries.joinToString(", ") { "${it.key} \u2190 \"${it.value}\"" }
                request.metadata[DETAIL_KEY] = redacted
                Log.d(TAG, "Anonymized ${allMappings.size} entity/entities")
            } else {
                request.metadata[DETAIL_KEY] = "No PII detected"
            }
        }
    }

    override suspend fun processResponse(request: ProxyRequest, response: ChatResponse): ChatResponse {
        if (!restoreInResponse) return response
        @Suppress("UNCHECKED_CAST")
        val mapping = request.metadata[MAPPING_KEY] as? Map<String, String> ?: return response
        if (mapping.isEmpty()) return response

        val restoredChoices = response.choices.map { choice ->
            var text = choice.message.content
            mapping.forEach { (placeholder, original) -> text = text.replace(placeholder, original) }
            choice.copy(message = choice.message.copy(content = text))
        }
        return response.copy(choices = restoredChoices)
    }

    private val chunkContentLen = SEQ_LEN - 2  // usable tokens per chunk (minus BOS/EOS)
    private val chunkOverlap = 10

    private fun anonymize(m: ZeticMLangeModel, text: String): Pair<String, Map<String, String>> {
        val allIds = tokenizer.encode(text)  // includes BOS ... EOS
        // Strip BOS/EOS — we'll add them per chunk
        val contentIds = allIds.drop(1).dropLast(1).toLongArray()

        // Build per-token predictions across all chunks
        val mergedPreds = IntArray(contentIds.size)  // 0 = "O"
        val stride = maxOf(1, chunkContentLen - chunkOverlap)
        var offset = 0

        while (offset < contentIds.size) {
            val end = minOf(offset + chunkContentLen, contentIds.size)
            val chunkIds = contentIds.sliceArray(offset until end)
            val preds = runNERChunk(m, chunkIds)

            for ((j, pred) in preds.withIndex()) {
                val globalIdx = offset + j
                if (globalIdx >= mergedPreds.size) break
                if (offset == 0 || j >= chunkOverlap || pred != 0) {
                    mergedPreds[globalIdx] = pred
                }
            }

            if (end >= contentIds.size) break
            offset += stride
        }

        // Rebuild full arrays for buildAnonymizedText
        val fullLen = contentIds.size + 2
        val fullIds = LongArray(fullLen) { tokenizer.padId.toLong() }
        val fullMask = LongArray(fullLen)
        val fullPreds = IntArray(fullLen)
        fullIds[0] = tokenizer.bosId.toLong(); fullMask[0] = 1L
        for (i in contentIds.indices) {
            fullIds[i + 1] = contentIds[i]
            fullMask[i + 1] = 1L
            fullPreds[i + 1] = mergedPreds[i]
        }
        fullIds[contentIds.size + 1] = tokenizer.eosId.toLong(); fullMask[contentIds.size + 1] = 1L

        val (nerResult, nerMapping) = buildAnonymizedText(fullIds, fullMask, fullPreds, id2label.size)

        // Apply regex redaction for SSN, email, credit card
        val (finalResult, regexMapping) = applyRegexRedaction(nerResult)
        val combinedMapping = nerMapping.toMutableMap().apply { putAll(regexMapping) }
        return finalResult to combinedMapping
    }

    private fun runNERChunk(m: ZeticMLangeModel, contentIds: LongArray): IntArray {
        val paddedIds = LongArray(SEQ_LEN) { tokenizer.padId.toLong() }
        val mask = LongArray(SEQ_LEN)

        paddedIds[0] = tokenizer.bosId.toLong(); mask[0] = 1L
        for (i in contentIds.indices) {
            paddedIds[i + 1] = contentIds[i]; mask[i + 1] = 1L
        }
        paddedIds[contentIds.size + 1] = tokenizer.eosId.toLong(); mask[contentIds.size + 1] = 1L

        val inputs = arrayOf(
            Tensor.Companion.of(paddedIds, DataType.Companion.from("int64"), intArrayOf(1, SEQ_LEN), false),
            Tensor.Companion.of(mask, DataType.Companion.from("int64"), intArrayOf(1, SEQ_LEN), false)
        )

        val outputs = m.run(inputs)
        val classCount = id2label.size
        if (outputs.isEmpty() || classCount == 0) return IntArray(contentIds.size)

        val buffer = outputs[0].data.order(ByteOrder.LITTLE_ENDIAN)
        buffer.rewind()
        val floatBuf = buffer.asFloatBuffer()
        if (floatBuf.remaining() == 0) return IntArray(contentIds.size)

        val totalFloats = floatBuf.remaining()
        val floats = FloatArray(totalFloats)
        floatBuf.get(floats)
        val totalSeq = totalFloats / classCount

        // Extract predictions for content tokens only (skip BOS at index 0)
        // Apply softmax and reject low-confidence non-O predictions
        return IntArray(contentIds.size) { i ->
            val seqIdx = i + 1
            if (seqIdx >= totalSeq) 0
            else {
                val off = seqIdx * classCount
                // Softmax: exp(x - max) / sum(exp(x - max))
                var maxLogit = Float.NEGATIVE_INFINITY
                for (c in 0 until classCount) {
                    if (floats[off + c] > maxLogit) maxLogit = floats[off + c]
                }
                var sumExp = 0f
                for (c in 0 until classCount) {
                    sumExp += kotlin.math.exp(floats[off + c] - maxLogit)
                }
                var maxIdx = 0; var maxProb = 0f
                for (c in 0 until classCount) {
                    val prob = kotlin.math.exp(floats[off + c] - maxLogit) / sumExp
                    if (prob > maxProb) { maxProb = prob; maxIdx = c }
                }
                // Only accept non-O prediction if confidence exceeds threshold
                if (maxIdx != 0 && maxProb < CONFIDENCE_THRESHOLD) 0 else maxIdx
            }
        }
    }

    /** Regex-based redaction for SSN, email, and credit card — entity types the NER model doesn't cover. */
    private fun applyRegexRedaction(text: String): Pair<String, Map<String, String>> {
        var result = text
        val mapping = mutableMapOf<String, String>()
        val patterns = listOf(
            Triple("""\b\d{3}[-\s]?\d{2}[-\s]?\d{4}\b""", "[SSN]", "SSN"),
            Triple("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""", "[Email]", "EMAIL"),
            Triple("""\b\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}\b""", "[Card]", "CREDIT_CARD"),
        )
        for ((pattern, placeholder, _) in patterns) {
            val regex = Regex(pattern)
            val matches = regex.findAll(result).toList()
            for (match in matches.reversed()) {
                mapping[placeholder] = match.value
                result = result.replaceRange(match.range, placeholder)
            }
        }
        return result to mapping
    }

    private fun buildAnonymizedText(
        inputIds: LongArray,
        mask: LongArray,
        predIds: IntArray,
        classCount: Int
    ): Pair<String, Map<String, String>> {
        val mapping = mutableMapOf<String, String>()
        val tokens = mutableListOf<String>()
        val realLen = minOf(predIds.size, inputIds.size)
        var i = 0
        while (i < realLen) {
            if (i < mask.size && mask[i] == 0L) { i++; continue }
            val currentId = inputIds[i].toInt()
            if (currentId == tokenizer.bosId || currentId == tokenizer.eosId || currentId == tokenizer.padId) { i++; continue }

            val label = id2label[predIds[i]] ?: "O"
            val rawToken = tokenizer.idToToken(currentId, i) ?: ""

            if (label == "O") {
                tokens.add(rawToken.replace("\u0120", " "))
                i++; continue
            }

            val entityType = if (label.startsWith("B-") || label.startsWith("I-")) label.substring(2) else label
            val placeholder = placeholderByLabel[entityType] ?: "[$entityType]"
            val leadingSpace = if (rawToken.startsWith("\u0120")) " " else ""

            // Collect the full entity span to store in mapping
            val entityTokens = mutableListOf(rawToken)
            var j = i + 1
            while (j < realLen) {
                if (j < mask.size && mask[j] == 0L) break
                val nextId = inputIds[j].toInt()
                if (nextId == tokenizer.eosId || nextId == tokenizer.padId) break
                val nextLabel = id2label[predIds[j]] ?: "O"
                if (nextLabel == "I-$entityType" || nextLabel == "B-$entityType") {
                    entityTokens.add(tokenizer.idToToken(nextId, j) ?: "")
                    j++
                } else break
            }
            val originalText = entityTokens.joinToString("").replace("\u0120", " ").trim()
            // Reject entities that are too short or purely non-alphabetic (e.g. "2", "500ms", "},")
            val hasLetters = originalText.any { it.isLetter() }
            if (originalText.length < 2 || !hasLetters) {
                // Emit original tokens as-is instead of placeholder
                for (tok in entityTokens) {
                    tokens.add(tok.replace("\u0120", " "))
                }
                i = j
                continue
            }
            val fullPlaceholder = "$leadingSpace$placeholder"
            mapping[placeholder] = originalText
            tokens.add(fullPlaceholder)
            i = j
        }
        return tokens.joinToString("").trim() to mapping
    }

    private fun loadLabels(): Map<Int, String> {
        return try {
            val text = context.assets.open("labels.json").bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val map = HashMap<Int, String>()
            json.keys().forEach { k -> map[k.toInt()] = json.getString(k) }
            map
        } catch (e: Exception) {
            Log.w(TAG, "labels.json not found — anonymizer will be a no-op", e)
            emptyMap()
        }
    }
}

/**
 * RoBERTa/BERT-style greedy tokenizer for the TextAnonymizer NER model.
 */
private class AnonymizerTokenizer(private val context: Context) {
    private val vocab = HashMap<String, Int>()
    private val idToTokenMap = HashMap<Int, String>()

    var bosId = 0; var eosId = 2; var unkId = 3; var padId = 1

    init { loadVocab() }

    private fun loadVocab() {
        try {
            val text = listOf("anonymizer_tokenizer.json", "tokenizer.json").firstNotNullOf { name ->
                try { context.assets.open(name).bufferedReader().use { it.readText() } }
                catch (_: Exception) { null }
            }
            val root = JSONObject(text)
            val vocabObj = if (root.has("model")) root.getJSONObject("model").optJSONObject("vocab")
                           else root.optJSONObject("vocab")
            vocabObj?.keys()?.forEach { k ->
                val id = vocabObj.getInt(k)
                vocab[k] = id; idToTokenMap[id] = k
            }
            bosId = vocab["<s>"] ?: vocab["[CLS]"] ?: bosId
            eosId = vocab["</s>"] ?: vocab["[SEP]"] ?: eosId
            unkId = vocab["<unk>"] ?: vocab["[UNK]"] ?: unkId
            padId = vocab["<pad>"] ?: vocab["[PAD]"] ?: padId
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load anonymizer tokenizer", e)
        }
    }

    /** Maps token position (in the ids array) to the original character for unk tokens. */
    var unkOriginals = HashMap<Int, String>()
        private set

    fun encode(text: String): LongArray {
        val ids = mutableListOf<Long>()
        unkOriginals = HashMap()
        ids.add(bosId.toLong())
        val processed = (" $text").replace(" ", "\u0120")
        var i = 0
        while (i < processed.length) {
            var found = false
            for (len in minOf(20, processed.length - i) downTo 1) {
                val sub = processed.substring(i, i + len)
                val id = vocab[sub]
                if (id != null) { ids.add(id.toLong()); i += len; found = true; break }
            }
            if (!found) {
                unkOriginals[ids.size] = processed[i].toString()
                ids.add(unkId.toLong())
                i++
            }
        }
        ids.add(eosId.toLong())
        return ids.toLongArray()
    }

    fun idToToken(id: Int, position: Int): String? {
        if (id == unkId) {
            unkOriginals[position]?.let { return it }
        }
        return idToTokenMap[id]
    }
}
