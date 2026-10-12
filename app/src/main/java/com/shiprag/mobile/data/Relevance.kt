package com.shiprag.mobile.data

import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Pure Kotlin relevance policy shared by lexical, hybrid and answer-source filtering.
 * Query terms must be present in the evidence, not merely common stop words or a filename.
 */
object Relevance {
    private val tokenRx = Regex("[\\p{L}\\p{N}]+(?:[._/:+~-][\\p{L}\\p{N}]+)*")
    private val stopWords = setOf(
        "a","an","the","what","which","who","is","are","was","were","be",
        "of","in","for","by","with","on","to","from","and","or","if","how",
        "do","does","did","can","could","should","would","i","we","my",
        "me","please","where","when","why","give","tell","show","find",
        "check","explain","about","information","details","manual",
        "instruction","instructions","procedure","procedures","used","using",
        "this","that","it","its","some","any","there","therefore"
    )
    private val weakWords = setOf("system","unit","part","parts","general","overview")
    private val idRx = Regex("(?i)\\b(?=[a-z0-9._/-]*[a-z])(?=[a-z0-9._/-]*\\d)[a-z0-9]+(?:[._/-][a-z0-9]+)*\\b")

    fun tokens(value: String): List<String> = tokenRx.findAll(value.lowercase(Locale.ROOT))
        .map { it.value }.toList()

    fun queryTerms(query: String): List<String> {
        val distinct = tokens(query).filterNot { it in stopWords }.distinct()
        return distinct.take(14)
    }
    fun equipmentCodes(query: String): List<String> = idRx.findAll(query)
        .map { it.value.lowercase(Locale.ROOT) }.filter { code ->
            code.any { it.isDigit() } && code.any { it.isLetter() } && code.length >= 2
        }.distinct().toList()

    fun exactCodeMatch(queryCodes: List<String>, bodyAndHeadings: String, manual: String): Boolean {
        if (queryCodes.isEmpty()) return true
        val evidence = "$bodyAndHeadings $manual".lowercase(Locale.ROOT)
        // Manuals alternate M4.3 / M 4.3 and R404A / R-404A. Match either
        // spelling while still refusing F1 vs F10 or AD136TI vs AD136T.
        val joined = evidence
            .replace(Regex("(?<=[a-z])[ \\t-]+(?=[0-9])"), "")
            .replace(Regex("(?<=[0-9])[ \\t-]+(?=[a-z])"), "")
        return queryCodes.all { code ->
            val exact = Regex("(?<![a-z0-9._/-])" + Regex.escape(code) + "(?![a-z0-9._/-])")
            val normalized = code.replace("-", "")
            val normalizedMatcher = Regex("(?<![a-z0-9._/-])" + Regex.escape(normalized) + "(?![a-z0-9._/-])")
            exact.containsMatchIn(evidence) || normalizedMatcher.containsMatchIn(joined)
        }
    }

    /** Main evidence match uses actual text and heading, not document-name alone. */
    fun coverage(terms: List<String>, body: String, heading: String): Double {
        if (terms.isEmpty()) return 0.0
        val present = (tokens(body) + tokens(heading)).toHashSet()
        return terms.count { it in present }.toDouble() / terms.size
    }

    fun sufficient(terms: List<String>, body: String, heading: String): Boolean {
        if (terms.isEmpty()) return false
        val present = (tokens(body) + tokens(heading)).toHashSet()
        val hits = terms.count { it in present }
        return when {
            terms.size <= 2 -> hits >= 1
            terms.size <= 4 -> hits >= 2
            else -> hits >= 3 && hits.toDouble() / terms.size >= 0.38
        }
    }


    private enum class Intent { Diagram, Fault, Procedure, Value, General }

    private fun questionIntent(question: String): Intent {
        val q = question.lowercase(Locale.ROOT)
        return when {
            Regex("\\b(wiring|diagram|schematic|circuit|terminal|pinout|connection|connections|drawing)\\b").containsMatchIn(q) ->
                Intent.Diagram
            Regex("\\b(fault|alarm|trip|tripping|error|fails?|failure|overload|malfunction|troubleshoot|remedy|not working|wont|won't|doesn't|why)\\b").containsMatchIn(q) ->
                Intent.Fault
            Regex("\\b(how to|steps|step by step|procedure|replace|changing|change|install|remove|reset|calibrate|adjust|inspect|repair|check)\\b").containsMatchIn(q) ->
                Intent.Procedure
            Regex("\\b(oil type|value|setting|grade|viscosity|capacity|pressure|temperature|interval|voltage|current|torque|limit|clearance|specification|specifications|how much|what type)\\b").containsMatchIn(q) ->
                Intent.Value
            else -> Intent.General
        }
    }

    /** Topic overlap is not proof that a document contains the requested kind of answer. */
    private fun intentFit(question: String, body: String, heading: String): Double {
        val evidence = (body + " " + heading).lowercase(Locale.ROOT)
        return when (questionIntent(question)) {
            Intent.Diagram -> if (Regex("\\b(wiring|diagram|schematic|circuit|terminal|terminals|pinout|connection|connections|drawing|electrical plan)\\b").containsMatchIn(evidence)) 1.40 else 0.57
            Intent.Fault -> if (Regex("\\b(fault|alarm|tripping|trip|cause|remedy|symptom|error|trouble|problem|overload|malfunction)\\b").containsMatchIn(evidence)) 1.22 else 0.72
            Intent.Procedure -> if (Regex("\\b(check|replace|disconnect|connect|remove|install|measure|adjust|inspect|turn|press|restart|set|clean|tighten|loosen|steps?|procedure)\\b").containsMatchIn(evidence)) 1.22 else 0.70
            Intent.Value -> if (Regex("\\b(specification|specified|setting|value|range|rated|grade|iso|viscosity|recommended|capacity|torque)\\b").containsMatchIn(evidence) ||
                Regex("\\b\\d+(?:[.,]\\d+)?\\s*(?:v|dc|ac|bar|mpa|psi|c|hz|rpm|nm|mm|kg|l|kw|kva|vg)\\b").containsMatchIn(evidence)) 1.23 else 0.78
            Intent.General -> 1.0
        }
    }

    private fun hasAnswerStructure(question: String, body: String, heading: String): Boolean {
        val evidence = (body + " " + heading).lowercase(Locale.ROOT)
        val q = question.lowercase(Locale.ROOT)
        val redirected = Regex("\\b(?:see|refer to|found in)\\s+(?:the\\s+)?(?:section|chapter|page)\\b")
            .containsMatchIn(evidence)
        if (redirected && evidence.length < 450 &&
            !Regex("\\b(use|must|shall|should|set to|recommended|grade|[0-9]+\\s*(?:v|bar|mpa|rpm|mm|nm|vg))\\b").containsMatchIn(evidence)) return false
        return when (questionIntent(q)) {
            Intent.Diagram -> Regex("\\b(wiring|diagram|schematic|circuit|terminal|terminals|pinout|connection|connections|drawing)\\b").containsMatchIn(evidence)
            Intent.Fault -> Regex("\\b(fault|alarm|cause|remedy|symptom|error|trouble|trip|overload|problem|malfunction)\\b").containsMatchIn(evidence)
            Intent.Procedure -> Regex("\\b(check|replace|disconnect|connect|remove|install|measure|adjust|inspect|turn|press|restart|set|clean|tighten|loosen|step|procedure)\\b").containsMatchIn(evidence)
            Intent.Value -> Regex("\\b(specification|specified|setting|value|range|rated|grade|iso|viscosity|recommended|capacity|torque|[0-9]+\\s*(?:v|bar|mpa|rpm|mm|nm|vg))\\b").containsMatchIn(evidence) ||
                Regex("\\b\\d+(?:[.,]\\d+)?\\b").containsMatchIn(evidence)
            Intent.General -> true
        }
    }

    data class Candidate(val body: String, val heading: String, val manual: String,
                         val score: Double = 0.0, val kind: String = "text")
    /**
     * Scoring is independent of SQLite and uses corpus candidate-document frequencies.
     * The record set must be selected with meaningful OR terms, without limiting to
     * the first 1500 arbitrary rows.
     */
    fun rank(question: String, candidates: List<Candidate>): List<Pair<Int, Double>> {
        val terms = queryTerms(question)
        if (terms.isEmpty() || candidates.isEmpty()) return emptyList()
        val codes = equipmentCodes(question)
        val bodyTokens = candidates.map { tokens(it.body).take(1500) }
        val headTokens = candidates.map { tokens(it.heading).take(160) }
        val docs = bodyTokens.indices.map { i -> bodyTokens[i] + headTokens[i] }
        val averageLength = docs.map { it.size }.average().coerceAtLeast(1.0)
        val freq = terms.associateWith { term -> docs.count { term in it } }
        val phrase = terms.joinToString(" ")
        val result = ArrayList<Pair<Int, Double>>(candidates.size)
        for (i in candidates.indices) {
            val c = candidates[i]
            val documentTokens = docs[i]
            val count = documentTokens.groupingBy { it }.eachCount()
            val found = terms.count { (count[it] ?: 0) > 0 }
            val required = when {
                terms.size <= 2 -> 1
                terms.size <= 4 -> 2
                else -> 3
            }
            if (found < required || (terms.size > 4 && found.toDouble() / terms.size < 0.38)) continue
            val combined = c.body + " " + c.heading
            if (!exactCodeMatch(codes, combined, c.manual)) continue
            val coverage = found.toDouble() / terms.size
            val headings = headTokens[i].toSet()
            val manualTokens = tokens(c.manual.replace('_', ' ').replace('-', ' ')).toSet()
            val evidenceLength = documentTokens.size.coerceAtLeast(1)
            var bm25 = 0.0
            for (term in terms) {
                val tf = count[term] ?: 0
                val df = freq[term] ?: 0
                val idf = ln(1.0 + (candidates.size - df + 0.5) / (df + 0.5))
                if (tf > 0) {
                    bm25 += idf * tf * 2.4 / (tf + 1.4 * (0.25 + 0.75 * evidenceLength / averageLength))
                }
                if (term in headings) bm25 += 0.85 * idf
                // Filename is a small tie-breaker, NEVER enough to produce a source alone.
                if (term !in weakWords && term.length >= 4 && term in manualTokens) bm25 += 0.50 * idf
            }
            // Multiple technical details are worth more than a document with one generic word.
            var score = bm25 * (0.35 + 1.9 * coverage.pow(2.0))
            if (coverage >= 0.999) score += 4.0
            if (terms.size > 1 && phrase.length > 5) {
                val text = combined.lowercase(Locale.ROOT)
                if (text.contains(phrase)) score += 4.0
                if (headings.isNotEmpty() && c.heading.lowercase(Locale.ROOT).contains(phrase)) score += 3.0
            }
            if (c.body.length > 5500 && coverage < 0.80) score *= 0.80
            val lower = c.body.lowercase(Locale.ROOT)
            // A pointer to a different section is useful as navigation, not proof of a fact.
            val redirects = listOf("refer to section", "see section", "refer to chapter", "see chapter")
            if (redirects.any { it in lower }) score *= 0.67
            score *= intentFit(question, c.body, c.heading)
            if (score.isFinite() && score > 0.0) result.add(i to score)
        }
        return result.sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
    }

    fun strongEnoughForAnswer(question: String, candidate: Candidate): Boolean {
        val terms = queryTerms(question)
        // A lone generic keyword such as "oil" is search input, not enough evidence for a factual answer.
        if (terms.size == 1 && equipmentCodes(question).isEmpty()) return false
        val hits = coverage(terms, candidate.body, candidate.heading)
        return sufficient(terms, candidate.body, candidate.heading) &&
            hits >= (if (terms.size >= 4) 0.75 else if (terms.size == 3) 2.0 / 3.0 else if (terms.size == 2) 0.999 else 1.0) &&
            exactCodeMatch(equipmentCodes(question), candidate.body + " " + candidate.heading, candidate.manual) && hasAnswerStructure(question, candidate.body, candidate.heading)
    }
}