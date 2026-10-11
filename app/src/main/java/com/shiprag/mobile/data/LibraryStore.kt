package com.shiprag.mobile.data

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipInputStream

/** ZIP files are user-supplied immutable retrieval bundles. Never perform OCR, Docling conversion or re-embedding. */
class LibraryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("shiprag", Context.MODE_PRIVATE)
    private val parent = File(context.filesDir, "libraries").apply { mkdirs() }
    val root: File? get() = prefs.getString("current", null)?.let { File(parent, it) }?.takeIf {
        it.isDirectory && File(it, ".shiprag/index.sqlite").isFile
    }
    private val secrets = Secrets(context)
    fun settings(): AppSettings = AppSettings(
        chatUrl = prefs.getString("chatUrl", null) ?: AppSettings().chatUrl,
        chatModel = prefs.getString("chatModel", null) ?: "local",
        embeddingUrl = prefs.getString("embeddingUrl", null) ?: AppSettings().embeddingUrl,
        embeddingModel = prefs.getString("embeddingModel", null) ?: "",
        queryPrefix = prefs.getString("queryPrefix", null) ?: "",
        answerSources = prefs.getInt("answerSources", 5),
        chatApiKey = secrets.get("chat"),
        embeddingApiKey = secrets.get("embeddings")
    )
    fun saveSettings(s: AppSettings) { prefs.edit()
        .putString("chatUrl", s.chatUrl).putString("chatModel", s.chatModel)
        .putString("embeddingUrl", s.embeddingUrl).putString("embeddingModel", s.embeddingModel)
        .putString("queryPrefix", s.queryPrefix).putInt("answerSources", s.answerSources).apply()
        secrets.put("chat", s.chatApiKey)
        secrets.put("embeddings", s.embeddingApiKey)
    }

    fun importZip(uri: Uri, progress: (String) -> Unit = {}): ImportReport {
        val stage = File(parent, "stage-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            progress("Unpacking ZIP locally…")
            val original = stage.canonicalFile
            var count = 0
            var bytes = 0L
            context.contentResolver.openInputStream(uri)?.use { src ->
                ZipInputStream(src.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        count++
                        require(count <= 80_000) { "ZIP has too many entries" }
                        val name = entry.name.replace('\\', '/')
                        require(name.split('/').none { it == ".shiprag" }) { "Reserved app storage path in ZIP" }
                        val dest = File(stage, name).canonicalFile
                        require(dest.path.startsWith(original.path + File.separator)) { "ZIP traversal entry rejected: ${entry.name}" }
                        if (entry.isDirectory) { dest.mkdirs(); continue }
                        dest.parentFile?.mkdirs()
                        // Limit expanded size, not reported ZIP sizes (which are untrusted).
                        FileOutputStream(dest).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var entryBytes = 0L
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                bytes += n; entryBytes += n
                                require(bytes <= 40L * 1024 * 1024 * 1024) { "ZIP expanded size exceeds 40 GiB" }
                                require(entryBytes <= 15L * 1024 * 1024 * 1024) { "ZIP entry exceeds 15 GiB" }
                                out.write(buffer, 0, n)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("Cannot open the selected ZIP")
            progress("Reading existing retrieval indexes…")
            val derived = File(stage, ".shiprag").apply { mkdirs() }
            val db = CorpusDb(context, File(derived, "index.sqlite").path).writableDatabase
            val report = try { importIndexes(stage, db, progress) } finally { db.close() }
            require(report.chunks > 0) { "No valid retrieval_index.jsonl or equipment rows.jsonl found. Supply a Stage 3 RAG ZIP." }
            val finalDir = File(parent, "lib-${UUID.randomUUID()}")
            check(stage.renameTo(finalDir)) { "Failed to save imported library" }
            prefs.edit().putString("current", finalDir.name).apply()
            // Older libraries are intentionally kept until the next explicit import; never delete user files.
            return report
        } catch (error: Exception) { stage.deleteRecursively(); throw error }
    }

    fun db(): SQLiteDatabase = CorpusDb(context, File(requireNotNull(root) { "Import ZIP first" }, ".shiprag/index.sqlite").path).readableDatabase
    fun inventory(): Map<String, Int> = root?.let { dir ->
        val files = dir.walkTopDown().filter { it.isFile && !it.relativeTo(dir).invariantSeparatorsPath.startsWith(".shiprag/") }.toList()
        mapOf("Source PDF" to files.count { it.extension.equals("pdf", true) },
            "Source images" to files.count { it.extension.lowercase() in setOf("png", "jpeg", "jpg", "webp") },
            "Converted ZIP" to files.count { it.extension.equals("zip", true) },
            "Databases" to files.count { it.extension.lowercase() in setOf("db", "sqlite", "sqlite3") },
            "Processed manuals" to files.count { it.name == "retrieval_index.jsonl" },
            "Approved visual indexes" to files.count { it.name == "visual_evidence_index.jsonl" },
            "Embedding vectors" to files.count { it.name == "vectors.f32" },
            "Docling JSON" to files.count { it.name.endsWith(".json") },
            "Total files" to files.size)
    } ?: emptyMap()
    fun manuals(): List<String> = db().use { d ->
        d.rawQuery("SELECT DISTINCT manual FROM chunks ORDER BY manual COLLATE NOCASE", null).use { c ->
            buildList { while(c.moveToNext()) add(c.getString(0)) }
        }
    }
    fun statistics(): ImportReport? = root?.let { root ->
        db().use { d ->
            val manuals = scalar(d, "SELECT COUNT(DISTINCT manual) FROM chunks")
            val chunks = scalar(d, "SELECT COUNT(*) FROM chunks")
            val vectors = scalar(d, "SELECT COUNT(*) FROM chunks WHERE vec_index >= 0")
            val docs = root.walkTopDown().filter { it.isFile }.toList()
            ImportReport(manuals, chunks, vectors, docs.count { it.extension.equals("pdf", true) },
                docs.count { it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") }, vectorModels(d))
        }
    }
    fun vectorModels(): List<String> = db().use { vectorModels(it) }
    private fun vectorModels(d: SQLiteDatabase): List<String> = d.rawQuery(
        "SELECT DISTINCT vec_model FROM chunks WHERE vec_model <> '' ORDER BY vec_model", null
    ).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }

    private fun importIndexes(root: File, db: SQLiteDatabase, progress: (String)->Unit): ImportReport {
        val all = root.walkTopDown().filter { it.isFile && !it.relativeTo(root).invariantSeparatorsPath.startsWith(".shiprag/") }.toList()
        val retrieval = all.filter { it.name == "retrieval_index.jsonl" }
        val visual = all.filter { it.name == "visual_evidence_index.jsonl" }
        val equipment = all.filter { it.name == "rows.jsonl" && it.parentFile?.name?.let { _ ->
            it.relativeTo(root).invariantSeparatorsPath.contains("equipment_embedding_index/")
        } == true }
        var imported = 0
        var vectors = 0
        db.beginTransaction()
        try {
            // Store per-book authoritative search rows first. Equipment row maps may supplement them.
            retrieval.forEachIndexed { idx, f ->
                progress("Importing lexical index ${idx + 1}/${retrieval.size}…")
                f.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if (line.isNotBlank()) imported += storeRow(db, line, f.parentFile!!.relativeTo(root).invariantSeparatorsPath)
                } }
            }
            visual.forEachIndexed { idx, f ->
                progress("Importing approved visual evidence ${idx + 1}/${visual.size}…")
                f.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if (line.isNotBlank()) { val item = JSONObject(line)
                        if (item.optBoolean("rag_eligible", false))
                            imported += storeRow(db, item, f.parentFile!!.relativeTo(root).invariantSeparatorsPath)
                    }
                } }
            }
            equipment.forEachIndexed { idx, file ->
                progress("Linking machine embeddings ${idx + 1}/${equipment.size}…")
                val metaFile = File(file.parentFile, "metadata.json")
                val vecFile = File(file.parentFile, "vectors.f32")
                if (!metaFile.isFile || !vecFile.isFile) return@forEachIndexed
                val meta = JSONObject(metaFile.readText())
                val dim = meta.optInt("dim"); val rows = meta.optInt("rows")
                if (dim <= 0 || rows <= 0 || vecFile.length() != dim.toLong() * rows * 4) return@forEachIndexed
                val model = meta.optString("model")
                val rel = vecFile.relativeTo(root).invariantSeparatorsPath
                var i = 0
                file.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if (line.isNotBlank()) {
                        if (i < rows) {
                            val row = JSONObject(line)
                            imported += storeRow(db, row, row.optString("result_dir").ifBlank { file.parentFile!!.relativeTo(root).invariantSeparatorsPath })
                            vectors += attachVector(db, row, rel, i, dim, model)
                        }
                        i++
                    }
                } }
                require(i == rows) { "Machine vector row mismatch: ${file.path}" }
            }
            // Legacy per-book embedding_index metadata maps ordinal vectors to retrieval_index.jsonl rows.
            all.filter { it.name == "metadata.json" && it.invariantSeparatorsPath.contains("/embedding_index/") &&
                    !it.invariantSeparatorsPath.contains("/equipment_embedding_index/") }.forEach { metaFile ->
                val vecFile = File(metaFile.parentFile, "vectors.f32")
                val idxFile = File(metaFile.parentFile!!.parentFile!!.parentFile, "retrieval_index.jsonl")
                if (!vecFile.isFile || !idxFile.isFile) return@forEach
                val meta = JSONObject(metaFile.readText()); val dim = meta.optInt("dim"); val rows = meta.optInt("rows")
                if (dim <= 0 || rows <= 0 || vecFile.length() != dim.toLong() * rows * 4) return@forEach
                val path = vecFile.relativeTo(root).invariantSeparatorsPath
                val model = meta.optString("model")
                var i = 0
                idxFile.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if (line.isNotBlank()) { if (i < rows) vectors += attachVector(db, JSONObject(line), path, i, dim, model); i++ }
                } }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val manualCount = scalar(db, "SELECT COUNT(DISTINCT manual) FROM chunks")
        val chunkCount = scalar(db, "SELECT COUNT(*) FROM chunks")
        val vectorCount = scalar(db, "SELECT COUNT(*) FROM chunks WHERE vec_index >= 0")
        return ImportReport(manualCount, chunkCount, vectorCount,
            all.count { it.extension.equals("pdf", true) },
            all.count { it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") }, vectorModels(db))
    }

    private fun storeRow(db: SQLiteDatabase, raw: String, folder: String): Int =
        storeRow(db, JSONObject(raw), folder)

    private fun storeRow(db: SQLiteDatabase, json: JSONObject, folder: String): Int {
        val source = json.optString("source_filename").ifBlank { folder.substringAfterLast('/') }
        val chunk = json.optString("chunk_id")
        val content = json.optString("text")
        if (chunk.isBlank() || content.isBlank()) return 0
        val key = "$source\u0000${json.optString("postprocess_job_id")}\u0000$chunk"
        val headings = json.optJSONArray("headings") ?: JSONArray()
        val pages = json.optJSONArray("page_numbers") ?: JSONArray()
        val items = json.optJSONArray("doc_items") ?: JSONArray()
        val cv = ContentValues().apply {
            put("source_key", key); put("manual", source); put("chunk_id", chunk)
            put("body", content); put("headings", headings.toString()); put("pages", pages.toString())
            put("doc_items", items.toString()); put("folder", folder)
            put("evidence_kind", json.optString("evidence_kind").ifBlank { "text" })
            val terms = json.optJSONArray("search_terms")?.let { arr -> (0 until arr.length()).joinToString(" ") { arr.optString(it) } } ?: ""
            put("search_terms", terms)
        }
        val id = db.insertWithOnConflict("chunks", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (id == -1L) return 0
        val terms = json.optJSONArray("search_terms")?.let { arr -> (0 until arr.length()).joinToString(" ") { arr.optString(it) } } ?: ""
        db.execSQL("INSERT INTO chunks_fts(docid, content) VALUES (?,?)", arrayOf(id, "$content ${headings} $terms"))
        return 1
    }

    private fun attachVector(db: SQLiteDatabase, row: JSONObject, path: String, position: Int, dim: Int, model: String): Int {
        val chunk = row.optString("chunk_id")
        val source = row.optString("source_filename")
        val key = "$source\u0000${row.optString("postprocess_job_id")}\u0000$chunk"
        // Never mix models; first complete compatible vector set wins for a duplicate chunk.
        val cv = ContentValues().apply { put("vec_path", path); put("vec_index", position); put("vec_dim", dim); put("vec_model", model) }
        return db.update("chunks", cv, "source_key=? AND vec_index=-1", arrayOf(key))
    }

    companion object {
        fun scalar(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        fun source(c: Cursor, score: Double = 0.0, method: String = "lexical"): Source {
            fun array(name: String): List<String> { val a = JSONArray(c.getString(c.getColumnIndexOrThrow(name)))
                return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
            return Source(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                manual = c.getString(c.getColumnIndexOrThrow("manual")),
                chunkId = c.getString(c.getColumnIndexOrThrow("chunk_id")),
                content = c.getString(c.getColumnIndexOrThrow("body")),
                headings = array("headings"),
                pages = array("pages").mapNotNull { it.toIntOrNull() },
                docItems = array("doc_items"),
                location = c.getString(c.getColumnIndexOrThrow("folder")),
                evidenceKind = c.getString(c.getColumnIndexOrThrow("evidence_kind")) ?: "text",
                score = score, method = method,
                vectorPath = c.getString(c.getColumnIndexOrThrow("vec_path")) ?: "",
                vectorIndex = c.getInt(c.getColumnIndexOrThrow("vec_index")),
                vectorDim = c.getInt(c.getColumnIndexOrThrow("vec_dim")),
                vectorModel = c.getString(c.getColumnIndexOrThrow("vec_model")) ?: ""
            )
        }
    }
}

class CorpusDb(context: Context, private val absolutePath: String) : SQLiteOpenHelper(context, absolutePath, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE chunks(
            id INTEGER PRIMARY KEY, source_key TEXT UNIQUE NOT NULL, manual TEXT NOT NULL,
            chunk_id TEXT NOT NULL, body TEXT NOT NULL, headings TEXT NOT NULL, pages TEXT NOT NULL,
            doc_items TEXT NOT NULL, folder TEXT NOT NULL, evidence_kind TEXT NOT NULL DEFAULT 'text', search_terms TEXT NOT NULL DEFAULT '',
            vec_path TEXT NOT NULL DEFAULT '', vec_index INTEGER NOT NULL DEFAULT -1,
            vec_dim INTEGER NOT NULL DEFAULT 0, vec_model TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE INDEX idx_manual ON chunks(manual)")
        db.execSQL("CREATE INDEX idx_vector ON chunks(vec_model,vec_index)")
        db.execSQL("CREATE VIRTUAL TABLE chunks_fts USING fts4(content, tokenize=unicode61)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE chunks ADD COLUMN evidence_kind TEXT NOT NULL DEFAULT 'text'")
    }
}