package com.shiprag.mobile.data

import java.util.Locale
import kotlin.math.max

/**
 * Offline technical-language profile, ported selectively from the server's
 * query_profile/attribute/subject-cohesion logic. No model, network or corpus
 * regeneration is required. Query expansions are *recall hints*, not facts.
 */
object MarineQuery {
    enum class Intent { OilGrade, OilVolume, SlowOperation, Fault, Procedure, Value, Diagram, General }
    data class Profile(
        val intent: Intent,
        val subject: List<String>,
        val retrieval: List<String>,
        val hasEquipment: Boolean
    )
    private val words = Regex("""[\p{L}\p{N}]+(?:[._/+-][\p{L}\p{N}]+)*""")
    private val filler = setOf(
        "a","an","the","what","which","who","where","when","why","is","are","was",
        "were","be","do","does","did","can","could","would","should","i","we",
        "my","our","me","you","your","please","tell","show","give","get","find",
        "need","needs","want","wanting","much","many","how","to","of","at","in",
        "on","from","for","with","by","and","or","it","its","this","that","there",
        "some","any","regarding","about","manual","instruction","instructions",
        "type","types","kind","kinds","amount","quantity","volume","capacity",
        "check","checking","checks","steps","step","procedure","procedures",
        "problem","problems","whatsoever","not","working","work","wrong","issue",
        "issues","something","thing","things"
    )
    private val actions = setOf(
        "fill","filling","refill","refilling","top","up","drain","change","changing",
        "replace","replacing","repair","reparing","fix","inspect","inspection",
        "test","testing","operate","operating","adjust","adjusting","reset"
    )
    private val equipment = setOf(
        "winch","hoist","hoisting","luff","luffing","slewing","crane","grab",
        "motor","pump","gear","gearbox","tank","hydraulic","compressor",
        "refrigeration","condenser","evaporator","generator","alternator",
        "engine","radar","panel","valve","brake","bearing","cooler","detector",
        "anemometer","filter","actuator"
    )
    private val tableUnits = Regex("""(?i)\b\d+(?:[.,]\d+)?\s*(?:litres?|liters?|ltr|ltrs|l|gallons?|gal)\b""")
    private val oilGrade = Regex("""(?i)\b(?:polyglycol|synthetic|tivela|viscosity|cst|iso\s*vg|sae|vg\s*\d+|gear\s*oil|lubricant|lubricating\s*oil|oil\s*(?:type|grade|specification)|oil\s*shall|oil\s*must)\b""")
    private val slowTerms = Regex("""(?i)\b(?:slow|slower|sluggish|low[- ]speed|reduced\s+speed|insufficient\s+speed|speed\s+(?:too\s+)?low|speed\s+reduced|speed\s+loss|not\s+hoisting|does\s+not\s+(?:lift|hoist)|weak\s+hoist|speed\s+range)\b""")
    private val actualSlowFault = Regex("""(?i)\b(?:slow|slower|sluggish|reduced\s+speed|insufficient\s+speed|speed\s+(?:too\s+)?low|speed\s+reduced|speed\s+loss|not\s+hoisting|weak\s+hoist)\b""")
    private val faultSection = Regex("""(?i)\b(?:fault\s+finding|troubleshooting|failures?\s*[-:]\s*symptoms?|remed(?:y|ies)|malfunction|abnormal)\b""")
    private val faultEvidence = Regex("""(?i)\b(?:cause|remedy|fault|trouble|malfunction|check|inspect|test|measure|verify|replace|pressure|pump|valve|filter|flow|blockage|wear|leakage|leak|control\s+signal|servo|overload|stuck|faulty|defective|restriction|failure)\b""")
    private val gradeQuestion = Regex("""(?i)(?:\b(?:oil|lubricant)\b.{0,35}\b(?:type|grade|viscosity|specification|recommended|which|kind)\b|\b(?:type|grade|viscosity|which|what|kind|recommended)\b.{0,35}\b(?:oil|lubricant)\b)""")
    private val volumeQuestion = Regex("""(?i)(?:\b(?:how\s+much|amount|quantity|volume|capacity|litres?|liters?|fill|filling|top\s*up|refill)\b.{0,55}\b(?:oil|lubricant)\b|\b(?:oil|lubricant)\b.{0,35}\b(?:how\s+much|amount|quantity|volume|capacity|litres?|liters?|fill|filling|top\s*up|refill)\b)""")
    private val slowQuestion = Regex("""(?i)\b(?:slow|slower|sluggish|low\s+speed|reduced\s+speed|insufficient\s+speed|not\s+lifting|not\s+hoisting|speed\s+low|speed\s+reduced)\b""")

    fun tokens(value: String): List<String> = words.findAll(value.lowercase(Locale.ROOT)).map { it.value }.toList()

    /** Canonicalize morphology only; do not conflate different equipment names. */
    fun canonical(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "hoisting","hoists","hoisted","hoist" -> "hoist"
        "luffing","luffed","luffs","luff" -> "luff"
        "winches","winch" -> "winch"
        "slower","slowly","sluggish","slow" -> "slow"
        "lubricants","lubricant","lubricating","lubrication" -> "lubricant"
        "filling","filled","fills","fill" -> "fill"
        "checking","checked","checks","check" -> "check"
        "litre","litres","liter","liters","ltr","ltrs" -> "litre"
        "volumes","volume" -> "volume"
        "capacities","capacity" -> "capacity"
        "pressures","pressure" -> "pressure"
        "faults","fault","failure","failures" -> "fault"
        "speeds","speed" -> "speed"
        else -> value.lowercase(Locale.ROOT)
    }

    fun profile(question: String): Profile {
        val q = question.lowercase(Locale.ROOT)
        val intent = when {
            gradeQuestion.containsMatchIn(q) -> Intent.OilGrade
            volumeQuestion.containsMatchIn(q) -> Intent.OilVolume
            slowQuestion.containsMatchIn(q) -> Intent.SlowOperation
            Regex("""\b(?:wiring|diagram|schematic|circuit|terminal|pinout|drawing)\b""").containsMatchIn(q) -> Intent.Diagram
            Regex("""\b(?:fault|alarm|trip|tripping|failure|failed|not\s+working|stuck|cause|why|what\s+to\s+do|what\s+to\s+check)\b""").containsMatchIn(q) -> Intent.Fault
            Regex("""\b(?:how\s+to|how\s+do|replace|repair|change|clean|calibrat|adjust|procedure|reset|inspect|test)\b""").containsMatchIn(q) -> Intent.Procedure
            Regex("""\b(?:value|rating|setting|limit|range|pressure|temperature|voltage|torque|clearance|interval)\b""").containsMatchIn(q) -> Intent.Value
            else -> Intent.General
        }
        val raw = tokens(question)
        val subject = raw.filterNot { it in filler || it in actions || it in setOf("oil","oils","lubricant","lubricants","grade","viscosity","speed","slow","slower","sluggish") && intent != Intent.General }
            .map(::canonical).distinct().take(10)
        val hasEquipment = subject.any { word -> word in equipment || canonical(word) in equipment } ||
            raw.any { it in equipment } || raw.any { it.any(Char::isDigit) && it.any(Char::isLetter) }
        val basics = (raw.filterNot { it in filler || it in actions } + subject).distinct().take(14)
        val expansion = when (intent) {
            Intent.OilGrade -> listOf("oil","lubricant","lubricants","lubrication","viscosity","grade","polyglycol","synthetic","specification")
            Intent.OilVolume -> listOf("oil","volume","filling","quantity","litres","liters","capacity","technical","data")
            Intent.SlowOperation -> listOf("speed","slow","reduced","low","insufficient","hoist","hoisting","lifting","check","fault","troubleshooting")
            Intent.Fault -> listOf("troubleshooting","failure","fault","cause","remedy","check","inspect")
            Intent.Procedure -> listOf("procedure","steps","check","remove","replace","adjust")
            Intent.Value -> listOf("technical","data","table","setting","specification")
            Intent.Diagram -> listOf("diagram","wiring","schematic","drawing")
            Intent.General -> emptyList()
        }
        val morphology = when {
            "hoisting" in raw || "hoist" in raw -> listOf("hoisting","hoist")
            "luffing" in raw || "luff" in raw -> listOf("luffing","luff")
            else -> emptyList()
        }
        return Profile(intent, subject, (basics + expansion + morphology).distinct().take(28), hasEquipment)
    }

    fun evidenceTokens(text: String): List<String> = tokens(text.replace("luffi ng", "luffing").replace("luffi  ng", "luffing")).map(::canonical)

    private fun normalizedEvidence(body: String, heading: String): List<String> =
        evidenceTokens(body + " " + heading)

    /** Source-content-only matching: a filename is context, not answer proof. */
    fun subjectRatio(p: Profile, body: String, heading: String): Double {
        if (p.subject.isEmpty()) return 0.0
        val ts = normalizedEvidence(body, heading).toSet()
        return p.subject.count { it in ts }.toDouble() / p.subject.size
    }

    /** Query terms must occur near each other, not in unrelated sections. */
    fun cohesionBonus(p: Profile, body: String, heading: String): Double {
        val terms = p.subject.filter { it.length >= 3 }.distinct()
        if (terms.size < 2) return 0.0
        val ts = normalizedEvidence(body, heading)
        val locations = terms.map { term -> ts.indices.filter { ts[it] == term }.take(70) }
        if (locations.any { it.isEmpty() }) return 0.0
        var tightest = Int.MAX_VALUE
        for (start in locations.first()) {
            var low = start
            var high = start
            for (pos in locations.drop(1)) {
                val nearest = pos.minByOrNull { kotlin.math.abs(it - start) } ?: continue
                low = kotlin.math.min(low, nearest)
                high = kotlin.math.max(high, nearest)
            }
            tightest = kotlin.math.min(tightest, high-low)
        }
        return when {
            tightest <= 7 -> 7.0
            tightest <= 18 -> 4.0
            tightest <= 35 -> 1.5
            else -> 0.0
        }
    }

    /** Port of server's attribute-unit and action-intent evidence bonus. */
    fun intentBonus(p: Profile, body: String, heading: String): Double {
        val text = body + "\n" + heading
        val ratio = subjectRatio(p, body, heading)
        if (p.subject.isNotEmpty() && ratio < 0.5) return -5.0
        return when (p.intent) {
            Intent.OilGrade -> when {
                oilGrade.containsMatchIn(text) && Regex("""(?i)\b(?:winch|gear|oil|lubricant)\b""").containsMatchIn(text) -> 13.0
                else -> -5.0
            }
            Intent.OilVolume -> when {
                tableUnits.containsMatchIn(text) && Regex("""(?i)\b(?:oil|fill|volume|winch|gear)\b""").containsMatchIn(text) -> 17.0
                else -> -6.0
            }
            Intent.SlowOperation -> when {
                actualSlowFault.containsMatchIn(text) && faultEvidence.containsMatchIn(text) -> 17.0
                faultSection.containsMatchIn(text) && slowTerms.containsMatchIn(text) && faultEvidence.containsMatchIn(text) -> 10.0
                slowTerms.containsMatchIn(text) -> -5.0 // Low-speed mode alone is not a malfunction.
                else -> -3.0
            }
            Intent.Fault -> if (faultEvidence.containsMatchIn(text)) 9.0 else -3.0
            Intent.Procedure -> if (Regex("""(?i)\b(?:check|inspect|verify|test|remove|replace|disconnect|measure|tighten|adjust|fill|drain|switch\s+off)\b""").containsMatchIn(text)) 7.0 else -2.0
            Intent.Value -> if (Regex("""(?i)\b\d+(?:[.,]\d+)?\s*(?:bar|mpa|psi|v|a|rpm|mm|nm|hz|kw|l|kg|hours?)\b""").containsMatchIn(text)) 7.0 else 0.0
            Intent.Diagram -> if (Regex("""(?i)\b(?:wiring|schematic|diagram|drawing|terminal)\b""").containsMatchIn(text)) 7.0 else -3.0
            Intent.General -> 0.0
        }
    }

    /** A quantity request needs a real quantity; oil grade needs lubricant evidence. */
    fun answerEvidence(p: Profile, body: String, heading: String): Boolean {
        val text = body + " " + heading
        val requiredRatio = if (p.subject.size > 2) 0.66 else 0.99
        if (p.subject.isNotEmpty() && subjectRatio(p, body, heading) < requiredRatio) return false
        return when (p.intent) {
            Intent.OilVolume -> tableUnits.containsMatchIn(text)
            Intent.OilGrade -> oilGrade.containsMatchIn(text)
            Intent.SlowOperation -> (actualSlowFault.containsMatchIn(text) ||
                (faultSection.containsMatchIn(text) && slowTerms.containsMatchIn(text))) && faultEvidence.containsMatchIn(text)
            Intent.Fault -> faultEvidence.containsMatchIn(text)
            Intent.Procedure -> Regex("""(?i)\b(?:check|inspect|verify|remove|replace|disconnect|adjust|fill|drain|measure|switch\s+off)\b""").containsMatchIn(text)
            else -> true
        }
    }
}
