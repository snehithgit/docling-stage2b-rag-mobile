package com.shiprag.mobile.data

import android.content.Context
import android.net.Uri
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.LongBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/** Query-only BAAI/bge-small-en-v1.5 (384 dimensions), with no inference server. */
class OnDeviceEmbeddings(private val context: Context) {
    companion object {
        const val MODEL_ID = "BAAI/bge-small-en-v1.5"
        const val DIM = 384
        const val MODEL_SHA = "828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35"
        const val MODEL_URL = "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/onnx/model.onnx"
        const val VOCAB_URL = "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/vocab.txt"
    }
    private val dir get() = File(context.filesDir, "embedding-bge-v1.5")
    private val model get() = File(dir, "model.onnx")
    private val vocab get() = File(dir, "vocab.txt")
    private var session: OrtSession? = null
    private var dictionary: Map<String, Int>? = null

    fun available() = model.isFile && model.length() > 125_000_000 &&
        vocab.isFile && vocab.length() > 50_000
    fun description() = if (available()) "BGE-small-en-v1.5 installed · 384-d · fully offline"
        else "Install BGE-small-en-v1.5 (133 MB) once before going offline."

    fun importFile(uri: Uri, vocabulary: Boolean, progress: (String) -> Unit) {
        dir.mkdirs()
        val target = if (vocabulary) vocab else model
        val temp = File(dir, target.name + ".partial")
        try {
            context.contentResolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "Cannot open selected file" }
                writeChecked(stream, temp, vocabulary, progress)
            }
            require(temp.renameTo(target)) { "Could not install selected file" }
        } finally { temp.delete() }
        closeSession()
    }

    fun download(progress: (String) -> Unit) {
        dir.mkdirs()
        if (!model.isFile || model.length() < 125_000_000) fetch(MODEL_URL, model, false, progress)
        if (!vocab.isFile || vocab.length() < 50_000) fetch(VOCAB_URL, vocab, true, progress)
        closeSession()
        progress("BGE installed. No embedding server is needed.")
    }

    private fun fetch(uri: String, dest: File, isVocab: Boolean, progress: (String) -> Unit) {
        val temp = File(dir, dest.name + ".partial")
        val connection = (URL(uri).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            require(connection.responseCode in 200..299) { "Model download failed (HTTP " + connection.responseCode + ")" }
            connection.inputStream.use { writeChecked(it, temp, isVocab, progress) }
            require(temp.renameTo(dest)) { "Could not move downloaded model" }
        } finally { temp.delete(); connection.disconnect() }
    }

    private fun writeChecked(input: java.io.InputStream, dest: File, vocabMode: Boolean, progress: (String)->Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        FileOutputStream(dest).use { out ->
            val buffer = ByteArray(131072)
            var lastProgress = 0L
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                if (n == 0) continue
                out.write(buffer, 0, n); digest.update(buffer, 0, n); count += n
                require(count < if (vocabMode) 5_000_000L else 180_000_000L) { "Unexpected file size" }
                if (count - lastProgress > 4_000_000) {
                    progress((if (vocabMode) "Vocabulary" else "ONNX model") + ": " + count / 1_048_576 + " MiB")
                    lastProgress = count
                }
            }
            out.fd.sync()
        }
        if (vocabMode) {
            val words = dest.readLines()
            require(count in 50_000..5_000_000 && words.size in 29_000..32_000 &&
                listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]").all(words::contains)) {
                "Selected file is not the BGE WordPiece vocab.txt"
            }
        } else {
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            require(count > 125_000_000 && sha == MODEL_SHA) {
                "Wrong ONNX model. Use original BAAI/bge-small-en-v1.5/onnx/model.onnx."
            }
        }
    }

    @Synchronized private fun closeSession() {
        session?.close(); session = null; dictionary = null
    }

    @Synchronized fun embed(text: String): FloatArray {
        require(available()) { "Install BGE ONNX and vocab.txt under Models first." }
        val env = OrtEnvironment.getEnvironment()
        if (dictionary == null) dictionary = vocab.readLines().mapIndexed { index, token -> token to index }.toMap()
        if (session == null) {
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(2)
            opts.setInterOpNumThreads(1)
            try { session = env.createSession(model.absolutePath, opts) } finally { opts.close() }
        }
        val loaded = requireNotNull(session)
        val ids = wordPieces(text, requireNotNull(dictionary))
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for (name in loaded.inputNames) {
                val array = when(name) {
                    "input_ids" -> ids
                    "attention_mask" -> LongArray(ids.size) { 1 }
                    "token_type_ids" -> LongArray(ids.size)
                    else -> error("Unexpected BGE ONNX input: $name")
                }
                inputs[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(array), longArrayOf(1, ids.size.toLong()))
            }
            loaded.run(inputs).use { result ->
                val hidden = result.get("last_hidden_state").orElse(null)?.value ?: result[0].value
                val outer = hidden as? Array<*> ?: error("Unsupported BGE ONNX output")
                val cls = when (val first = outer.firstOrNull()) {
                    is FloatArray -> first
                    is Array<*> -> first.firstOrNull() as? FloatArray
                    else -> null
                } ?: error("Missing CLS embedding output")
                require(cls.size == DIM) { "Expected 384-d BGE embedding" }
                val norm = sqrt(cls.sumOf { it.toDouble() * it })
                require(norm > 0) { "Zero embedding returned" }
                return FloatArray(DIM) { i -> (cls[i] / norm).toFloat() }
            }
        } finally { inputs.values.forEach { it.close() } }
    }

    private fun wordPieces(text: String, words: Map<String,Int>): LongArray {
        val normal = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val tokens = Regex("[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}]").findAll(normal).map { it.value }
        val output = ArrayList<Long>(128)
        output.add(requireNotNull(words["[CLS]"]).toLong())
        for (token in tokens) {
            if (output.size >= 127) break
            val pieces = mutableListOf<Long>()
            if (token.length <= 100) {
                var at = 0
                while (at < token.length) {
                    var end = token.length
                    var id: Int? = null
                    while (end > at) {
                        val part = (if (at > 0) "##" else "") + token.substring(at, end)
                        id = words[part]
                        if (id != null) break
                        end--
                    }
                    if (id == null) { pieces.clear(); break }
                    pieces.add(id.toLong()); at = end
                }
            }
            if (pieces.isEmpty()) pieces.add(requireNotNull(words["[UNK]"]).toLong())
            if (output.size + pieces.size > 127) break
            output.addAll(pieces)
        }
        output.add(requireNotNull(words["[SEP]"]).toLong())
        return output.toLongArray()
    }
}
