package com.zeticai.melangelm.stages

import android.content.Context
import android.util.Log
import com.zeticai.melangelm.pipeline.PipelineStage
import com.zeticai.melangelm.pipeline.ProxyRequest
import com.zeticai.mlange.core.model.ZeticMLangeModel
import com.zeticai.mlange.core.tensor.DataType
import com.zeticai.mlange.core.tensor.Tensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "PromptGuardStage"
private const val MODEL_ID = "jathin-zetic/llama_prompt_guard_2"
private const val SEQ_LEN = 128

/**
 * Pipeline stage that classifies each user message using Llama Prompt Guard 2 (on-device).
 *
 * If any user message is classified as Malicious (malicious logit > benign logit),
 * the request is blocked and never reaches the upstream LLM.
 *
 * Config:
 *  - [personalKey]: Zetic MLange personal key
 *  - [maliciousThreshold]: logit gap required to block (default 0 = any malicious > benign)
 *  - [checkRoles]: which message roles to inspect (default: ["user"])
 */
class PromptGuardStage(
    private val context: Context,
    private val personalKey: String,
    private val maliciousThreshold: Float = 0f,
    private val checkRoles: Set<String> = setOf("user")
) : PipelineStage {

    override val name = "PromptGuard"

    private var model: ZeticMLangeModel? = null
    private val tokenizer = PromptGuardTokenizer(context)

    override suspend fun initialize(onProgress: ((Float) -> Unit)?) {
        withContext(Dispatchers.IO) {
            tokenizer.ensureLoaded()
            val wrappedProgress: ((Float) -> Unit)? = onProgress?.let { cb ->
                { p: Float -> Log.d(TAG, "onProgress: $p"); cb(p) }
            }
            model = ZeticMLangeModel(context.applicationContext, personalKey, MODEL_ID, null, onProgress = wrappedProgress)
            Log.i(TAG, "PromptGuard model loaded. Tokenizer ready=${tokenizer.isLoaded}")
        }
    }

    override suspend fun processRequest(request: ProxyRequest) {
        withContext(Dispatchers.IO) {
            val m = model ?: run {
                Log.w(TAG, "Model not loaded — skipping PromptGuard check")
                return@withContext
            }

            // Only check the last user message (the new one being sent).
            // Checking all history causes repeated blocks after a single malicious message.
            val lastUserMessage = request.messages.lastOrNull { it.role in checkRoles }
                ?: return@withContext

            val prompt = "User: ${lastUserMessage.content}\nAgent: "
            val inputResult = buildTensors(prompt)
            val outputs = m.run(inputResult)
            val (benign, malicious) = parseLogits(outputs[0].data)

            val scoreDetail = "benign=${"%.3f".format(benign)}, malicious=${"%.3f".format(malicious)}"
            request.metadata["PromptGuard.detail"] = scoreDetail
            Log.d(TAG, "PromptGuard: $scoreDetail | \"${lastUserMessage.content.take(60)}\"")

            if (malicious - benign > maliciousThreshold) {
                request.block("Malicious prompt detected ($scoreDetail)")
            }
        }
    }

    private fun buildTensors(prompt: String): Array<Tensor> {
        tokenizer.ensureLoaded()
        val ids = if (tokenizer.isLoaded) {
            tokenizer.encode(prompt)
        } else {
            // UTF-8 fallback
            prompt.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }.toIntArray()
        }

        val padId = tokenizer.padId
        val tokenIds = IntArray(SEQ_LEN) { if (it < ids.size) ids[it] else padId }
        val promptLength = minOf(ids.size, SEQ_LEN)
        val mask = IntArray(SEQ_LEN) { if (it < promptLength) 1 else 0 }

        return arrayOf(
            Tensor.Companion.of(tokenIds, DataType.Companion.from("int32"), intArrayOf(1, SEQ_LEN), false),
            Tensor.Companion.of(mask, DataType.Companion.from("int32"), intArrayOf(1, SEQ_LEN), false)
        )
    }

    private fun parseLogits(buffer: ByteBuffer): Pair<Float, Float> {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.rewind()
        val floatBuf = buffer.asFloatBuffer()
        val benign = if (floatBuf.remaining() > 0) floatBuf.get(0) else 0f
        val malicious = if (floatBuf.remaining() > 1) floatBuf.get(1) else 0f
        return benign to malicious
    }
}

/**
 * Minimal greedy tokenizer for Llama Prompt Guard 2 (SentencePiece / RoBERTa vocab).
 * Loads tokenizer.json from assets if present; otherwise the stage uses UTF-8 fallback.
 */
private class PromptGuardTokenizer(private val context: Context) {
    private val vocab = HashMap<String, Int>()
    var bosId = 1; var eosId = 2; var unkId = 0; var padId = 0
    var isLoaded = false
        private set

    fun ensureLoaded() {
        if (!isLoaded) loadVocab()
    }

    private fun loadVocab() {
        val text = listOf("prompt_guard_tokenizer.json", "tokenizer.json").firstNotNullOfOrNull { name ->
            try { context.assets.open(name).bufferedReader().use { it.readText() } }
            catch (_: Exception) { null }
        } ?: return
        val root = JSONObject(text)
        when {
            root.has("model") -> {
                val model = root.getJSONObject("model")
                if (model.has("vocab")) parseVocab(model.get("vocab"))
            }
            root.has("vocab") -> parseVocab(root.get("vocab"))
        }
        if (root.has("added_tokens")) {
            val added = root.getJSONArray("added_tokens")
            for (i in 0 until added.length()) {
                val item = added.getJSONObject(i)
                val content = item.optString("content")
                val id = item.optInt("id", -1)
                if (content.isNotEmpty() && id >= 0) vocab[content] = id
            }
        }
        bosId = vocab["<s>"] ?: vocab["<|begin_of_text|>"] ?: vocab["[CLS]"] ?: 1
        eosId = vocab["</s>"] ?: vocab["<|end_of_text|>"] ?: vocab["[SEP]"] ?: 2
        unkId = vocab["<unk>"] ?: vocab["[UNK]"] ?: 0
        padId = vocab["<pad>"] ?: vocab["[PAD]"] ?: 0
        isLoaded = true
        Log.d(TAG, "Tokenizer loaded: ${vocab.size} tokens")
    }

    private fun parseVocab(raw: Any) {
        when (raw) {
            is JSONObject -> raw.keys().forEach { k -> vocab[k] = raw.getInt(k) }
            is JSONArray -> for (i in 0 until raw.length()) {
                val entry = raw.getJSONArray(i); vocab[entry.getString(0)] = i
            }
        }
    }

    fun encode(text: String): IntArray {
        val ids = mutableListOf(bosId)
        val withSpace = " $text"
        val hasSP = vocab.keys.any { it.startsWith("\u2581") }
        val spaceSub = if (hasSP) "\u2581" else "\u0120"
        val processed = withSpace.replace(" ", spaceSub)
        var i = 0
        while (i < processed.length) {
            var found = false
            for (len in minOf(32, processed.length - i) downTo 1) {
                val sub = processed.substring(i, i + len)
                val id = vocab[sub]
                if (id != null) { ids.add(id); i += len; found = true; break }
            }
            if (!found) {
                ids.add(vocab["$spaceSub${processed[i]}"] ?: vocab[processed[i].toString()] ?: unkId)
                i++
            }
        }
        ids.add(eosId)
        return ids.toIntArray()
    }
}
