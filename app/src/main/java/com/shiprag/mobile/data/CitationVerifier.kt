package com.shiprag.mobile.data

/**
 * Lightweight deterministic numeric-citation audit adapted from the original
 * server's claim_support_audit. It checks literal associations, not semantic
 * entailment: a passing check is NOT proof the instruction is correct.
 */
object CitationVerifier {
    private val cite = Regex("""(?i)\[S(\d+)]""")
    private val measurement = Regex(
        """(?i)(?<![a-z0-9])[-+]?\d+(?:[.,]\d+)?\s*(?:litres?|liters?|ltrs?|l\b|cst\b|bar\b|mpa\b|psi\b|v(?:olts?)?\b|a\b|mm\b|rpm\b|hz\b|kw\b|nm\b|°c\b|%)"""
    )
    private fun canonical(raw: String): String {
        val low = raw.lowercase().replace(" ", "").replace(",", ".")
        return low.replace(Regex("""(?:litres?|liters?|ltrs?)$"""), "l")
            .replace("volts", "v").replace("volt", "v")
    }

    private val equipment = Regex("""(?i)\b(?:luffing\s+winch|hoisting\s+winch|slewing\s+gear|oil\s+tank)\b""")
    /** Preserve equipment/value association in compact oil-filling tables. */
    private fun valuesNearEquipment(evidence: String, name: String): Set<String> {
        val markers = equipment.findAll(evidence).toList()
        val found = linkedSetOf<String>()
        for ((i, marker) in markers.withIndex()) {
            if (!marker.value.equals(name, ignoreCase = true)) continue
            val end = if (i+1 < markers.size) markers[i+1].range.first else evidence.length
            val segment = evidence.substring(marker.range.first, end.coerceAtMost(marker.range.first+160))
            for (m in measurement.findAll(segment)) found.add(canonical(m.value))
        }
        return found
    }

    fun warnings(answer: String, sources: List<Source>, question: String = ""): List<String> {
        val problems = linkedSetOf<String>()
        val segments = answer.lines().flatMap {
            it.split(Regex("""(?<=[.!?])\s+(?=[A-Z])"""))
        }.filter { it.isNotBlank() }
        for (segment in segments) {
            val values = measurement.findAll(segment).map { canonical(it.value) }.distinct().toList()
            if (values.isEmpty()) continue
            val labels = cite.findAll(segment).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
            if (labels.isEmpty()) {
                problems += "A technical value is stated without a source citation."
                continue
            }
            val cited = labels.mapNotNull { i -> sources.getOrNull(i-1) }
            if (cited.size != labels.size) {
                problems += "A technical value uses an invalid source label."
                continue
            }
            // Visual summaries are AI descriptions. Numeric values require real source
            // text; do not accept a generated image summary as a verified reading.
            val literals = cited.filter { it.evidenceKind != "visual" }.flatMap { source ->
                measurement.findAll(source.content).map { canonical(it.value) }.toList()
            }.toSet()
            if (values.any { it !in literals }) {
                problems += "At least one stated technical quantity/unit is absent from its cited source."
            } else {
                val requested = equipment.find(segment)?.value ?: equipment.find(question)?.value
                if (requested != null && cited.any { it.evidenceKind != "visual" } &&
                    cited.none { src -> values.all { it in valuesNearEquipment(src.content, requested) } }) {
                    problems += "The quantity occurs in the source, but not with the requested equipment."
                }
            }
        }
        return problems.toList()
    }
}
