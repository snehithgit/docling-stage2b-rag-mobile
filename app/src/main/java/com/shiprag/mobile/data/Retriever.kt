package com.shiprag.mobile.data

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Port of the existing retrieval concepts: lexical BM25-style rank + exact ID guard + optional RRF. */
class Retriever(private val library: LibraryStore) {
    private val tokenRx = Regex("[\\p{L}\\p{N}]+(?:[._/:+~-][\\p{L}\\p{N}]+)*")
    private fun tokens(text: String) = tokenRx.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
    private val idRx = Regex("[A-Za-z]+\\d+[A-Za-z0-9_./:+~-]*")
    private fun idGuard(query: String) = idRx.findAll(query).map { it.value }.distinct().toList()

    fun lexical(question: String, manual: String? = null, take: Int = 60, evidenceKind: String? = null): List<Source> {
        val queryTokens = tokens(question).distinct().take(12)
        if (queryTokens.isEmpty()) return emptyList()
        val fts = queryTokens.flatMap { token -> token.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() } }
            .distinct().take(16).joinToString(" OR ") { "\"${it.replace("\"", "") }\"" }
        if (fts.isBlank()) return emptyList()
        val candidates = LinkedHashMap<Long, Source>()
        library.db().use { db ->
            val condition = (if (manual == null) "" else " AND c.manual=?") +
                (if (evidenceKind == null) "" else " AND c.evidence_kind=?")
            val args = mutableListOf(fts)
            if (manual != null) args.add(manual)
            if (evidenceKind != null) args.add(evidenceKind)
            db.rawQuery("SELECT c.* FROM chunks_fts f JOIN chunks c ON c.id=f.docid WHERE f.content MATCH ?$condition LIMIT 1500", args.toTypedArray()).use { c ->
                while(c.moveToNext()) { val row=LibraryStore.source(c); candidates[row.id]=row }
            }
            // Retrieve exact structured identifiers even when FTS punctuation split differs.
            for (id in idGuard(question).take(4)) {
                val sql = "SELECT * FROM chunks WHERE (body LIKE ? OR search_terms LIKE ?)" +
                    (if (manual == null) "" else " AND manual=?") +
                    (if (evidenceKind == null) "" else " AND evidence_kind=?") + " LIMIT 150"
                val pat = "%$id%"
                val bind = mutableListOf(pat, pat)
                if (manual != null) bind.add(manual)
                if (evidenceKind != null) bind.add(evidenceKind)
                db.rawQuery(sql, bind.toTypedArray()).use { c -> while(c.moveToNext()) { val row=LibraryStore.source(c); candidates[row.id]=row } }
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val rows = candidates.values.toList()
        val dt = rows.map { tokens(it.content).plus(tokens(it.headings.joinToString(" "))) }
        val avg = dt.map { it.size }.average().coerceAtLeast(1.0)
        val df = queryTokens.associateWith { term -> dt.count { terms -> term in terms } }
        val identifiers = idGuard(question)
        val phrase = question.trim().lowercase(Locale.ROOT)
        return rows.mapIndexed { idx, row ->
            val d = dt[idx]; val counts = d.groupingBy { it }.eachCount(); val len=d.size.coerceAtLeast(1)
            var score = 0.0
            for (term in queryTokens) {
                val tf = counts[term] ?: 0
                if (tf <= 0) continue
                val idf = ln(1.0 + (rows.size - (df[term] ?: 0) + 0.5) / ((df[term] ?: 0) + 0.5))
                val denom = tf + 1.4 * (0.25 + 0.75 * len / avg)
                score += idf * tf * 2.4 / denom
                if (term in tokens(row.headings.joinToString(" "))) score += 0.35 * idf
            }
            val low = row.content.lowercase(Locale.ROOT)
            if (phrase.length > 3 && low.contains(phrase)) score += 3.0
            identifiers.forEach { id ->
                val boundary = Regex("(?<![A-Za-z0-9_./:+~-])" + Regex.escape(id) + "(?![A-Za-z0-9_./:+~-])", RegexOption.IGNORE_CASE)
                if (boundary.containsMatchIn(row.content)) score += 14.0
                else if (low.contains(id.lowercase())) score += 3.0
            }
            row.copy(score=score)
        }.filter { it.score > 0.0 }.sortedWith(compareByDescending<Source> { it.score }.thenBy { it.manual }.thenBy { it.chunkId })
            .let { diverse(it, take) }
    }

    private fun diverse(rows: List<Source>, take: Int): List<Source> {
        val used = HashSet<String>(); val first = mutableListOf<Source>(); val extra = mutableListOf<Source>()
        for (r in rows) {
            val key = "${r.manual}:${r.pages}:${r.content.trim().lowercase().take(120)}"
            if (used.add(key)) first.add(r) else extra.add(r)
        }
        return (first + extra).take(take)
    }

    /** Semantic mode explicitly requires compatible model/protocol; never silently falls back. */
    fun hybrid(question: String, model: String, queryVector: FloatArray, manual: String? = null, take: Int = 8): List<Source> {
        val lexical = lexical(question, manual, 60)
        val root = requireNotNull(library.root)
        val vectorTop = PriorityQueue<Source>(compareBy<Source> { it.score })
        val streamCache = mutableMapOf<String, RandomAccessFile>()
        try {
            library.db().use { db ->
                val sql = "SELECT * FROM chunks WHERE vec_model=? AND vec_index>=0" + if (manual == null) "" else " AND manual=?"
                val args = if (manual == null) arrayOf(model) else arrayOf(model, manual)
                db.rawQuery(sql, args).use { c ->
                    while(c.moveToNext()) {
                        val row = LibraryStore.source(c)
                        if (row.vectorDim != queryVector.size || row.vectorPath.isBlank()) continue
                        val path = File(root, row.vectorPath).canonicalFile
                        require(path.path.startsWith(root.canonicalPath + File.separator)) { "Invalid vector path" }
                        if (!path.isFile) continue
                        val reader = streamCache.getOrPut(path.path) { RandomAccessFile(path, "r") }
                        val buffer = ByteArray(row.vectorDim * 4)
                        reader.seek(row.vectorIndex.toLong() * buffer.size)
                        reader.readFully(buffer)
                        val floats = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        var dot = 0.0
                        for (i in queryVector.indices) dot += floats.get(i) * queryVector[i]
                        val candidate = row.copy(score=dot,method="vector")
                        if (vectorTop.size < 60) vectorTop.add(candidate)
                        else if (dot > vectorTop.peek().score) { vectorTop.poll(); vectorTop.add(candidate) }
                    }
                }
            }
        } finally { streamCache.values.forEach { it.close() } }
        if (vectorTop.isEmpty()) error("No compatible vectors for model '$model' (matching dimension ${queryVector.size}).")
        val vector = vectorTop.toList().sortedByDescending { it.score }
        val score = mutableMapOf<Long,Double>(); val found = mutableMapOf<Long,Source>()
        lexical.forEachIndexed { i, row -> score[row.id]=(score[row.id]?:0.0)+1.0/(60+i+1); found[row.id]=row }
        vector.forEachIndexed { i, row -> score[row.id]=(score[row.id]?:0.0)+1.0/(60+i+1); found.putIfAbsent(row.id,row) }
        val fused = score.entries.sortedByDescending { it.value }.map { (id,s) -> found.getValue(id).copy(score=s,method="hybrid_rrf") }
        // An exact identifier should not be displaced by a semantically similar different device.
        val ids = idGuard(question)
        val visual = lexical(question, manual, take = 8, evidenceKind = "visual").map { it.copy(method = "visual_lexical") }
        val prioritized = if (ids.isNotEmpty() && lexical.isNotEmpty()) {
            val lex = lexical.first()
            val hasAll = ids.all { id -> lex.content.contains(Regex("(?<![A-Za-z0-9])${Regex.escape(id)}(?![A-Za-z0-9])",RegexOption.IGNORE_CASE)) }
            if (hasAll && fused.firstOrNull()?.id != lex.id) listOf(lex.copy(method="hybrid_exact_id")) + fused.filter { it.id != lex.id }
            else fused
        } else fused
        // Existing machine vectors cover text chunks; keep separate visual lexical matches visible.
        val text = diverse(prioritized.filter { it.evidenceKind != "visual" }, take)
        return text + visual
    }

    /** Independently rank each evidence channel: dense text results must not hide matching diagrams. */
    fun search(question: String, manual: String?, take: Int=8): List<Source> =
        lexical(question, manual, take, evidenceKind = "text") +
        lexical(question, manual, take, evidenceKind = "visual").map { it.copy(method = "visual_lexical") }
}