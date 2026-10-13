package com.shiprag.mobile.data

import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Navigation only: uses the imported manual structure and Stage-3 chunks, with no OCR or LLM. */
data class ManualReference(val label: String, val number: String, val title: String? = null, val kind: String = "section")
data class ManualLink(val label: String, val target: Source)

/** Extract explicit destinations. 'Oil type: see Section 5' is a route, not a page-number guess. */
object ManualReferenceParser {
    private val numbered = Regex("""(?i)\b(section|chapter|subsection|paragraph)\s+(\d{1,2}(?:\.\d{1,3}){0,2})\b""")
    private val named = Regex("""[\"“'‘]([^\"”'’\r\n]{4,90})[\"”'’]""")
    private val direction = Regex("""(?i)\b(?:see|refer(?:red)?\s+to|refer|consult|check|described|specified|given|found|listed|shown|in accordance with|details|information|under|according\s+to)\b""")
    private val figures = Regex("""(?i)\b(?:fig(?:ure)?\.?|table)\s*(\d{1,3}[a-z]?)\b""")

    fun parse(text: String): List<ManualReference> {
        val output = linkedMapOf<String, ManualReference>()
        // A single technical chunk can contain several separate reference sentences.
        for (match in numbered.findAll(text)) {
            val start = (match.range.first - 125).coerceAtLeast(0)
            val before = text.substring(start, match.range.first)
            if (!direction.containsMatchIn(before)) continue
            val kind = match.groupValues[1].lowercase(Locale.ROOT)
            val number = match.groupValues[2]
            val quotes = named.findAll(before).map { it.groupValues[1].trim() }
                .filter { title -> title.length in 4..75 && !title.all(Char::isDigit) }
                .toList().takeLast(2)
            if (quotes.isEmpty()) {
                val ref = ManualReference("${kind.replaceFirstChar(Char::uppercase)} $number", number, kind = kind)
                output["$kind:$number"] = ref
            } else {
                for (title in quotes) {
                    val ref = ManualReference("$title · $kind $number", number, title, kind)
                    output["$kind:$number:${normalize(title)}"] = ref
                }
            }
        }
        // Figure/table numbers repeat across sections. Resolver only links those with a safe nearby caption.
        for (match in figures.findAll(text)) {
            val before = text.substring((match.range.first - 60).coerceAtLeast(0), match.range.first)
            if (!direction.containsMatchIn(before)) continue
            val type = if (match.value.startsWith("tab", true)) "table" else "figure"
            val number = match.groupValues[1]
            output["$type:$number"] = ManualReference("${type.replaceFirstChar(Char::uppercase)} $number", number, kind = type)
        }
        return output.values.take(12)
    }

    internal fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
}

class ManualReferenceResolver(private val library: LibraryStore) {
    private data class Heading(val title: String, val level: Int, val page: Int, val chunkIds: List<String>)
    private val headingCache = mutableMapOf<String, List<Heading>>()
    private val sourceCache = mutableMapOf<Long, List<ManualLink>>()

    @Synchronized private fun headings(source: Source): List<Heading> {
        return headingCache.getOrPut(source.location) {
            val root = library.root ?: return@getOrPut emptyList()
            val file = File(root, source.location + "/manual_structure.json")
            if (!file.isFile) return@getOrPut emptyList()
            runCatching {
                val sections = JSONObject(file.readText()).optJSONArray("sections") ?: return@runCatching emptyList()
                (0 until sections.length()).mapNotNull { i ->
                    val item = sections.optJSONObject(i) ?: return@mapNotNull null
                    val page = item.optInt("start_page", -1)
                    if (page <= 0) return@mapNotNull null
                    val chunkIds = item.optJSONArray("chunk_ids")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    }.orEmpty()
                    Heading(item.optString("title"), item.optInt("level", 9), page, chunkIds)
                }
            }.getOrDefault(emptyList())
        }
    }

    fun resolve(source: Source): List<ManualLink> {
        synchronized(this) { sourceCache[source.id]?.let { return it } }
        val references = ManualReferenceParser.parse(source.content)
        if (references.isEmpty()) return emptyList()
        val structure = headings(source)
        val result = mutableListOf<ManualLink>()
        library.db().use { db ->
            for (ref in references) {
                val route = when(ref.kind) {
                    "figure", "table" -> findIllustration(db, source, ref)
                    else -> findSection(db, source, ref, structure)
                }
                if (route != null && route.id != source.id && result.none { it.target.id == route.id && it.label == ref.label }) {
                    result += ManualLink(ref.label, route)
                }
                if (result.size >= 8) break
            }
        }
        synchronized(this) { sourceCache[source.id] = result }
        return result
    }

    private fun findSection(db: android.database.sqlite.SQLiteDatabase, source: Source, ref: ManualReference,
                            sections: List<Heading>): Source? {
        val title = ref.title?.let(ManualReferenceParser::normalize)
        if (!title.isNullOrBlank()) {
            // A quoted instruction title is more exact than a numbered parent chapter.
            val named = sections.filter { item -> ManualReferenceParser.normalize(item.title) == title }
                .sortedWith(compareBy<Heading> { it.level }.thenBy { it.page })
            for (section in named) findAtPage(db, source, section.page, title, section.chunkIds)?.let { return it }
            findNamedHeading(db, source, title)?.let { return it }
            // Do not silently jump to a generic Section 5 when an explicit named subsection cannot be located.
            return null
        }
        val prefix = ref.number + "."
        val candidates = sections.filter { item ->
            val name = item.title.trimStart()
            name.startsWith(prefix + " ") || name == prefix || name.startsWith(ref.number + " ")
        }.sortedWith(compareBy<Heading> { it.level }.thenBy { it.page })
        for (candidate in candidates) {
            // The exact Section 5 bookmark is more authoritative than a heading '5. Up button'.
            if (candidate.level > 2 && candidates.any { it.level <= 2 }) continue
            findAtPage(db, source, candidate.page, null, candidate.chunkIds)?.let { return it }
        }
        // For manuals without a structure index, identify a numbered heading from the imported chunk headings.
        return findNumberedHeading(db, source, ref.number)
    }

    private fun findAtPage(db: android.database.sqlite.SQLiteDatabase, source: Source, page: Int,
                           title: String?, chunkIds: List<String>): Source? {
        // SQLite pages are JSON arrays. LIKE is a prefilter only; validate the actual page integers.
        val candidates = mutableListOf<Source>()
        db.rawQuery("SELECT * FROM chunks WHERE manual=? AND pages LIKE ? LIMIT 350",
            arrayOf(source.manual, "%$page%") ).use { cur ->
            while (cur.moveToNext()) {
                val s = LibraryStore.source(cur)
                if (page in s.pages) candidates += s
            }
        }
        return candidates.maxWithOrNull(compareBy<Source> { row ->
            var score = if (row.evidenceKind == "text") 30 else 0
            if (row.chunkId in chunkIds) score += 45
            if (title != null && row.headings.any { ManualReferenceParser.normalize(it) == title }) score += 200
            if (title != null && ManualReferenceParser.normalize(row.content).startsWith(title)) score += 25
            score
        }.thenByDescending { it.content.length.coerceAtMost(1800) })
    }

    private fun findNamedHeading(db: android.database.sqlite.SQLiteDatabase, source: Source, normalized: String): Source? {
        val token = normalized.split(' ').firstOrNull { it.length >= 4 } ?: return null
        val results = mutableListOf<Source>()
        db.rawQuery("SELECT * FROM chunks WHERE manual=? AND headings LIKE ? LIMIT 250",
            arrayOf(source.manual, "%$token%")).use { cur ->
            while (cur.moveToNext()) {
                val candidate = LibraryStore.source(cur)
                if (candidate.headings.any { ManualReferenceParser.normalize(it) == normalized }) results += candidate
            }
        }
        return results.sortedWith(compareBy<Source> { it.page ?: Int.MAX_VALUE }.thenBy { it.id }).firstOrNull()
    }

    private fun findNumberedHeading(db: android.database.sqlite.SQLiteDatabase, source: Source, number: String): Source? {
        val prefix = Regex("^${Regex.escape(number)}(?:\\.|\\s|$)")
        db.rawQuery("SELECT * FROM chunks WHERE manual=? AND headings LIKE ? LIMIT 800",
            arrayOf(source.manual, "%$number%")).use { cur ->
            val candidates = mutableListOf<Source>()
            while (cur.moveToNext()) {
                val row = LibraryStore.source(cur)
                if (row.headings.any { prefix.containsMatchIn(it.trim()) }) candidates += row
            }
            return candidates.sortedWith(compareBy<Source> { it.headings.size }.thenBy { it.page ?: Int.MAX_VALUE }).firstOrNull()
        }
    }

    private fun findIllustration(db: android.database.sqlite.SQLiteDatabase, source: Source,
                                 ref: ManualReference): Source? {
        val number = Regex.escape(ref.number)
        val caption = if (ref.kind == "table") Regex("(?i)\\bTable\\s*$number\\b")
            else Regex("(?i)\\b(?:Fig\\.?|Figure)\\s*$number\\b")
        val sourcePage = source.page ?: return null
        val minPage = (sourcePage - 2).coerceAtLeast(1)
        val maxPage = sourcePage + 2
        val results = mutableListOf<Source>()
        db.rawQuery("SELECT * FROM chunks WHERE manual=? AND (body LIKE ? OR body LIKE ?) LIMIT 1000",
            arrayOf(source.manual, "%${ref.number}%", "%${ref.number}%")).use { c ->
            while (c.moveToNext()) {
                val s = LibraryStore.source(c)
                if (s.id == source.id || s.pages.none { it in minPage..maxPage }) continue
                if (caption.containsMatchIn(s.content)) results += s
            }
        }
        // Repeating figure numbers make distant pages unsafe; only auto-link a single nearby caption.
        val closest = results.minOfOrNull { kotlin.math.abs((it.page ?: sourcePage) - sourcePage) } ?: return null
        val nearest = results.filter { kotlin.math.abs((it.page ?: sourcePage) - sourcePage) == closest }
        return nearest.singleOrNull()
    }
}