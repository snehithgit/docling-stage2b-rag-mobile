package com.shiprag.mobile.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.sqrt

/** Relevance-first lexical / hybrid ranking. No network needed for lexical mode. */
class Retriever(private val library: LibraryStore) {
    fun lexical(question: String, manual: String? = null, take: Int = 60, evidenceKind: String? = null): List<Source> {
        val words = Relevance.queryTerms(question).flatMap {
            it.split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)
        }.distinct().take(28)
        if (words.isEmpty()) return emptyList()
        val fts = words.joinToString(" OR ") { "\"$it\"" }
        val matches = LinkedHashMap<Long, Source>()
        library.db().use { db ->
            val filter = (if (manual == null) "" else " AND c.manual=?") +
                (if (evidenceKind == null) "" else " AND c.evidence_kind=?")
            val args = mutableListOf(fts)
            if (manual != null) args.add(manual)
            if (evidenceKind != null) args.add(evidenceKind)
            db.rawQuery("SELECT c.* FROM chunks_fts f JOIN chunks c ON c.id=f.docid WHERE f.content MATCH ?$filter LIMIT 15000", args.toTypedArray()).use { cur ->
                while (cur.moveToNext()) { val row = LibraryStore.source(cur); matches[row.id] = row }
            }
            // Fetch punctuation-bearing equipment part numbers omitted by FTS token splitting.
            for (id in Relevance.equipmentCodes(question).take(4)) {
                val idFilter = (if (manual == null) "" else " AND manual=?") +
                    (if (evidenceKind == null) "" else " AND evidence_kind=?")
                val binds = mutableListOf("%$id%", "%$id%", "%$id%")
                if (manual != null) binds.add(manual)
                if (evidenceKind != null) binds.add(evidenceKind)
                db.rawQuery("SELECT * FROM chunks WHERE (body LIKE ? OR headings LIKE ? OR manual LIKE ?)$idFilter LIMIT 400", binds.toTypedArray()).use { cur ->
                    while (cur.moveToNext()) { val row = LibraryStore.source(cur); matches[row.id] = row }
                }
            }
        }
        val rows = matches.values.toList()
        val ranked = Relevance.rank(question, rows.map {
            Relevance.Candidate(it.content, it.headings.joinToString(" > "), it.manual)
        })
        return diverse(ranked.map { (i, score) -> rows[i].copy(score=score, method="coverage_bm25") }, take)
    }

    private fun diverse(rows: List<Source>, take: Int): List<Source> {
        val pageCounts = HashMap<String, Int>()
        val unique = HashSet<String>()
        return rows.filter { row ->
            val chunkKey = row.manual + "|" + row.chunkId + "|" + row.evidenceKind
            val pageKey = row.manual + "|" + (row.page?.toString() ?: row.chunkId) + "|" + row.evidenceKind
            if (!unique.add(chunkKey) || pageCounts.getOrDefault(pageKey, 0) >= 2) false
            else { pageCounts[pageKey] = pageCounts.getOrDefault(pageKey,0)+1; true }
        }.take(take)
    }

    fun search(question: String, manual: String?, take: Int = 8) =
        lexical(question,manual,take,evidenceKind="text") +
        lexical(question,manual,take,evidenceKind="visual")

    fun hybrid(question: String, model: String, queryVector: FloatArray, manual: String? = null, take: Int = 8): List<Source> {
        val lexical = lexical(question, manual, 75, "text")
        val root = requireNotNull(library.root)
        val top = PriorityQueue<Source>(compareBy<Source> { it.score })
        val handles = HashMap<String, RandomAccessFile>()
        val codes = Relevance.equipmentCodes(question)
        val qnorm = sqrt(queryVector.fold(0.0) { sum, x -> sum+x*x })
        require(qnorm>0) { "Query embedding is zero" }
        try {
            library.db().use { db ->
                val sql = "SELECT * FROM chunks WHERE vec_model=? AND vec_index>=0" +
                    if (manual == null) "" else " AND manual=?"
                db.rawQuery(sql, if (manual == null) arrayOf(model) else arrayOf(model,manual)).use { cur ->
                    while (cur.moveToNext()) {
                        val row=LibraryStore.source(cur)
                        if (row.vectorDim != queryVector.size || row.vectorPath.isBlank()) continue
                        if (!Relevance.exactCodeMatch(codes, row.content+" "+row.headings.joinToString(" "),row.manual)) continue
                        val file=File(root,row.vectorPath).canonicalFile
                        require(file.path.startsWith(root.canonicalPath+File.separator)) { "Invalid vector file path" }
                        if (!file.isFile || row.vectorIndex<0) continue
                        val reader=handles.getOrPut(file.path) { RandomAccessFile(file,"r") }
                        val data=ByteArray(row.vectorDim*4)
                        val offset=row.vectorIndex.toLong()*data.size
                        if (offset<0 || offset+data.size>reader.length()) continue
                        reader.seek(offset); reader.readFully(data)
                        val floats=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        var dot=0.0; var norm=0.0
                        for (i in queryVector.indices) { val v=floats.get(i).toDouble(); dot+=v*queryVector[i]; norm+=v*v }
                        if (norm<=0) continue
                        val cosine=dot/(sqrt(norm)*qnorm)
                        if (!cosine.isFinite() || cosine<0.20) continue
                        val candidate=row.copy(score=cosine,method="cosine")
                        if (top.size<75) top.add(candidate)
                        else if (cosine>top.peek().score) { top.poll(); top.add(candidate) }
                    }
                }
            }
        } finally { handles.values.forEach { it.close() } }
        if (top.isEmpty()) error("No compatible query vectors for selected embedding model. Check exact model and prefix.")
        val score=HashMap<Long,Double>(); val found=HashMap<Long,Source>()
        lexical.forEachIndexed { i,r -> score[r.id]=(score[r.id]?:0.0)+1.0/(60+i+1);found[r.id]=r }
        top.toList().sortedByDescending { it.score }.forEachIndexed { i,r ->
            score[r.id]=(score[r.id]?:0.0)+1.0/(60+i+1);found.putIfAbsent(r.id,r)
        }
        val ranked=score.entries.sortedByDescending { it.value }.mapNotNull { (id,n) ->
            found[id]?.takeIf {
                Relevance.sufficient(Relevance.queryTerms(question),it.content,it.headings.joinToString(" > "))
            }?.copy(score=n,method="hybrid_rrf")
        }
        return diverse(ranked,take)+lexical(question,manual,take,evidenceKind="visual")
    }
}