package com.kaliscriber.core.search

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.Normalizer
import kotlin.math.sqrt

/** Turns text into a vector whose direction reflects its meaning, for finding passages by meaning. */
interface TextEmbedder {
    /** Names the model, so vectors from different models are never compared. */
    val id: String
    val dimensions: Int
    fun embed(text: String): FloatArray
}

/**
 * A Model2Vec static embedding model (e.g. minishlab/potion-base-8M) run in plain Kotlin: the text
 * is split into word pieces, each piece has a stored vector, and the text's vector is their
 * average. It needs no native code and embeds a passage in well under a millisecond.
 *
 * Reads the model's own files: `tokenizer.json` (a WordPiece or word-level tokenizer in the
 * Hugging Face format), `model.safetensors` (the vectors, optionally with per-token weights and a
 * token-to-row mapping) and `config.json`. Matches `StaticModel.encode` from the model2vec library.
 */
class StaticEmbeddingModel private constructor(
    override val id: String,
    private val tokenizer: WordPieceTokenizer,
    private val vectors: Tensor,
    private val weights: FloatArray?,
    private val mapping: IntArray?,
    private val normalize: Boolean,
    private val maxLength: Int?,
) : TextEmbedder {

    override val dimensions: Int = vectors.shape[1]

    /** The token ids [text] is split into, unknown words included (as model2vec's tokenizer gives them). */
    fun tokenIds(text: String): List<Int> = tokenizer.encode(text)

    override fun embed(text: String): FloatArray {
        var ids = tokenizer.encode(text)
        if (maxLength != null && ids.size > maxLength) ids = ids.subList(0, maxLength)
        ids = ids.filter { it != tokenizer.unkId }
        val out = FloatArray(dimensions)
        if (ids.isNotEmpty()) {
            for (id in ids) {
                val row = mapping?.get(id) ?: id
                val weight = weights?.get(id) ?: 1f
                for (k in 0 until dimensions) out[k] += vectors.get(row, k) * weight
            }
            for (k in 0 until dimensions) out[k] /= ids.size
        }
        if (normalize) {
            val norm = sqrt(out.sumOf { (it * it).toDouble() }).toFloat() + 1e-32f
            for (k in 0 until dimensions) out[k] /= norm
        }
        return out
    }

    companion object {
        /** Loads the model from [dir], which holds `tokenizer.json`, `model.safetensors` and `config.json`. */
        fun load(dir: File, id: String = dir.name): StaticEmbeddingModel {
            val config = File(dir, "config.json").takeIf { it.exists() }
                ?.let { Json.parseToJsonElement(it.readText()).jsonObject } ?: JsonObject(emptyMap())
            val tokenizer = WordPieceTokenizer.fromJson(Json.parseToJsonElement(File(dir, "tokenizer.json").readText()).jsonObject)
            val tensors = SafeTensors.open(File(dir, "model.safetensors"))
            val vectors = tensors["embeddings"] ?: tensors["embedding.weight"]
                ?: throw IOException("The search model has no embeddings")
            if (vectors.shape.size != 2) throw IOException("Unexpected embeddings shape ${vectors.shape}")
            val weights = tensors["weights"]?.let { t -> FloatArray(t.count) { t.flat(it) } }
            val mapping = tensors["mapping"]?.let { t -> IntArray(t.count) { t.flat(it).toInt() } }
            val maxLength = config["max_length"].let { if (it == null) 512 else (it as? JsonPrimitive)?.intOrNull }
            return StaticEmbeddingModel(
                id = id,
                tokenizer = tokenizer,
                vectors = vectors,
                weights = weights,
                mapping = mapping,
                normalize = (config["normalize"] as? JsonPrimitive)?.booleanOrNull ?: false,
                maxLength = maxLength,
            )
        }

        private val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}

/**
 * The Hugging Face tokenizers pipeline for BERT-style models: BertNormalizer (clean text, space out
 * Chinese characters, strip accents, lower-case), BertPreTokenizer (split on spaces and
 * punctuation) and WordPiece (greedy longest match, "##" continuations) or a word-level vocabulary.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val unkToken: String?,
    private val prefix: String?,
    private val maxCharsPerWord: Int,
    private val normalizers: List<(String) -> String>,
    private val splitPunctuation: Boolean,
) {
    val unkId: Int? = unkToken?.let { vocab[it] }

    fun encode(text: String): List<Int> {
        var normalized = text
        for (n in normalizers) normalized = n(normalized)
        val out = ArrayList<Int>()
        for (word in preTokenize(normalized)) {
            if (prefix == null) {
                (vocab[word] ?: unkId)?.let(out::add)
            } else {
                wordPieces(word, out)
            }
        }
        return out
    }

    private fun wordPieces(word: String, out: MutableList<Int>) {
        val chars = word.codePoints().toArray()
        if (chars.size > maxCharsPerWord) {
            unkId?.let(out::add)
            return
        }
        val pieces = ArrayList<Int>()
        var start = 0
        while (start < chars.size) {
            var end = chars.size
            var found: Int? = null
            while (start < end) {
                val piece = (if (start > 0) prefix else "") + String(chars, start, end - start)
                val id = vocab[piece]
                if (id != null) {
                    found = id
                    break
                }
                end--
            }
            if (found == null) {
                unkId?.let(out::add)
                return
            }
            pieces += found
            start = end
        }
        out += pieces
    }

    private fun preTokenize(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) words += current.toString()
            current.clear()
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                isWhitespace(cp) -> flush()
                splitPunctuation && isPunctuation(cp) -> {
                    flush()
                    words += String(Character.toChars(cp))
                }
                else -> current.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        flush()
        return words
    }

    companion object {
        fun fromJson(json: JsonObject): WordPieceTokenizer {
            val model = json["model"]?.jsonObject ?: throw IOException("The tokenizer has no model")
            val type = model["type"]?.jsonPrimitive?.contentOrNull
            val vocab = model["vocab"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long.toInt() }
                ?: throw IOException("The tokenizer has no vocabulary")
            val unk = model["unk_token"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
            val (prefix, maxChars) = when (type) {
                "WordPiece" -> (model["continuing_subword_prefix"]?.jsonPrimitive?.contentOrNull ?: "##") to
                    (model["max_input_chars_per_word"]?.jsonPrimitive?.intOrNull ?: 100)
                "WordLevel" -> null to Int.MAX_VALUE
                else -> throw IOException("Unsupported tokenizer model: $type")
            }
            return WordPieceTokenizer(
                vocab = vocab,
                unkToken = unk,
                prefix = prefix,
                maxCharsPerWord = maxChars,
                normalizers = normalizers(json["normalizer"]),
                splitPunctuation = splitsPunctuation(json["pre_tokenizer"]),
            )
        }

        private fun normalizers(json: JsonElement?): List<(String) -> String> {
            if (json == null || json is JsonNull) return emptyList()
            val obj = json.jsonObject
            return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
                "BertNormalizer" -> {
                    val lowercase = obj.bool("lowercase") ?: true
                    val stripAccents = obj.bool("strip_accents") ?: lowercase
                    listOfNotNull(
                        ::cleanText.takeIf { obj.bool("clean_text") ?: true },
                        ::spaceChineseChars.takeIf { obj.bool("handle_chinese_chars") ?: true },
                        ::stripAccents.takeIf { stripAccents },
                        ::lowercase.takeIf { lowercase },
                    )
                }
                "Lowercase" -> listOf(::lowercase)
                "StripAccents" -> listOf(::stripAccents)
                "NFD" -> listOf { s: String -> Normalizer.normalize(s, Normalizer.Form.NFD) }
                "NFKD" -> listOf { s: String -> Normalizer.normalize(s, Normalizer.Form.NFKD) }
                "NFC" -> listOf { s: String -> Normalizer.normalize(s, Normalizer.Form.NFC) }
                "NFKC" -> listOf { s: String -> Normalizer.normalize(s, Normalizer.Form.NFKC) }
                "Sequence" -> obj["normalizers"]?.jsonArray.orEmpty().flatMap(::normalizers)
                else -> throw IOException("Unsupported tokenizer normalizer: $type")
            }
        }

        /** True when the pre-tokenizer splits punctuation off words (as BERT's does). */
        private fun splitsPunctuation(json: JsonElement?): Boolean {
            if (json == null || json is JsonNull) return false
            val obj = json.jsonObject
            return when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
                "BertPreTokenizer", "Punctuation" -> true
                "WhitespaceSplit" -> false
                "Sequence" -> (obj["pretokenizers"] as? JsonArray).orEmpty().any(::splitsPunctuation)
                else -> throw IOException("Unsupported tokenizer pre-tokenizer: $type")
            }
        }

        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

        /** Drops control characters and turns any whitespace into a plain space. */
        private fun cleanText(s: String): String = buildString {
            s.codePoints().forEach { cp ->
                if (cp == 0 || cp == 0xFFFD || isControl(cp)) return@forEach
                if (isWhitespace(cp)) append(' ') else appendCodePoint(cp)
            }
        }

        private fun spaceChineseChars(s: String): String = buildString {
            s.codePoints().forEach { cp -> if (isChinese(cp)) append(' ').appendCodePoint(cp).append(' ') else appendCodePoint(cp) }
        }

        private fun stripAccents(s: String): String = buildString {
            Normalizer.normalize(s, Normalizer.Form.NFD).codePoints().forEach { cp ->
                if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) appendCodePoint(cp)
            }
        }

        private fun lowercase(s: String): String = s.lowercase()

        /** Unicode White_Space, as Rust's char::is_whitespace (Java's own check leaves out no-break spaces). */
        fun isWhitespace(cp: Int): Boolean =
            cp in 9..13 || cp == 32 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A ||
                cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000

        private fun isControl(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            return when (Character.getType(cp)) {
                Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.UNASSIGNED.toInt(),
                Character.PRIVATE_USE.toInt(), Character.SURROGATE.toInt() -> true
                else -> false
            }
        }

        fun isPunctuation(cp: Int): Boolean {
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return when (Character.getType(cp)) {
                Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt() -> true
                else -> false
            }
        }

        private fun isChinese(cp: Int): Boolean =
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F ||
                cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F
    }
}

/** One tensor in a safetensors file, read in place from the memory-mapped file. */
internal class Tensor(private val data: ByteBuffer, private val dtype: String, val shape: List<Int>) {
    val count: Int = shape.fold(1) { a, b -> a * b }

    fun get(row: Int, col: Int): Float = flat(row * shape[1] + col)

    fun flat(i: Int): Float = when (dtype) {
        "F32" -> data.getFloat(i * 4)
        "F16" -> halfToFloat(data.getShort(i * 2))
        "BF16" -> java.lang.Float.intBitsToFloat(data.getShort(i * 2).toInt() shl 16)
        "F64" -> data.getDouble(i * 8).toFloat()
        "I8" -> data.get(i).toFloat()
        "I32" -> data.getInt(i * 4).toFloat()
        "I64" -> data.getLong(i * 8).toFloat()
        else -> throw IOException("Unsupported tensor type $dtype")
    }

    private fun halfToFloat(h: Short): Float {
        val bits = h.toInt() and 0xFFFF
        val sign = bits ushr 15
        val exp = (bits ushr 10) and 0x1F
        val mant = bits and 0x3FF
        val value = when (exp) {
            0 -> mant / 1024f * Math.pow(2.0, -14.0).toFloat()
            31 -> if (mant == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1 + mant / 1024f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
        }
        return if (sign == 1) -value else value
    }
}

/** Reads the safetensors format: an 8-byte header length, a JSON header, then the raw tensors. */
internal object SafeTensors {
    fun open(file: File): Map<String, Tensor> {
        val channel = RandomAccessFile(file, "r").channel
        val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN)
        channel.close()
        val headerSize = buffer.getLong(0)
        if (headerSize <= 0 || headerSize > buffer.capacity() - 8) throw IOException("Not a safetensors file")
        val headerBytes = ByteArray(headerSize.toInt())
        buffer.position(8)
        buffer.get(headerBytes)
        val header = kotlinx.serialization.json.Json.parseToJsonElement(String(headerBytes, Charsets.UTF_8)).jsonObject
        val dataStart = 8 + headerSize.toInt()
        return header.filterKeys { it != "__metadata__" }.mapValues { (_, value) ->
            val info = value.jsonObject
            val (begin, end) = info.getValue("data_offsets").jsonArray.map { it.jsonPrimitive.long.toInt() }
            buffer.position(dataStart + begin)
            val slice = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
            slice.limit(end - begin)
            Tensor(slice, info.getValue("dtype").jsonPrimitive.content, info.getValue("shape").jsonArray.map { it.jsonPrimitive.long.toInt() })
        }
    }
}
