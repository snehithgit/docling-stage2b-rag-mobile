package com.shiprag.mobile.data

import org.junit.Assert.*
import org.junit.Test

class RelevanceTest {
    private fun rank(query: String, vararg evidence: Relevance.Candidate): List<Int> =
        Relevance.rank(query, evidence.toList()).map { it.first }

    @Test fun ignoresQuestionStopWords() {
        assertEquals(listOf("hydraulic", "equipment", "oil"), Relevance.queryTerms("What is the oil type for hydraulic equipment?"))
        assertTrue(Relevance.queryTerms("What is it and how?").isEmpty())
    }

    @Test fun technicalTermsBeatOnlyGenericOverlap() {
        val bad = Relevance.Candidate(
            "The equipment manual contains general pressure alarm and safety notices.",
            "General information", "Other equipment.pdf"
        )
        val good = Relevance.Candidate(
            "For hydraulic oil, use the specified ISO VG 46 grade.",
            "Hydraulic oil specification", "Grab manual.pdf"
        )
        assertEquals(1, rank("Which hydraulic oil grade?", bad, good).first())
    }

    @Test fun headingAndExactPhraseImproveRanking() {
        val generic = Relevance.Candidate("The detector senses heat and uses a conventional circuit.",
            "Operation", "Fire alarm panel.pdf")
        val named = Relevance.Candidate("HC100 A2 conventional heat detector alarm connection.",
            "HC100 A2 heat detector", "Fire alarm panel.pdf")
        assertEquals(1, rank("HC100 A2 heat detector", generic, named).first())
    }

    @Test fun exactEquipmentIdsAreNotPartiallyMatched() {
        assertFalse(Relevance.exactCodeMatch(listOf("f1"), "Fuse F10 is fitted", "Crane.pdf"))
        assertTrue(Relevance.exactCodeMatch(listOf("f1"), "Fuse F1 is fitted", "Crane.pdf"))
        assertFalse(Relevance.exactCodeMatch(listOf("ad136ti"), "Engine AD136T operating instruction", "Engine.pdf"))
    }

    @Test fun equivalentDeviceNotationResolvesWithoutFuzzyNumberMatches() {
        assertTrue(Relevance.exactCodeMatch(listOf("r404a"), "R-404A refrigerant is listed", "REF PROV.pdf"))
        assertTrue(Relevance.exactCodeMatch(listOf("m4.3"), "Panel M 4.3 wiring", "Fire alarm panel.pdf"))
        assertFalse(Relevance.exactCodeMatch(listOf("f1"), "Fuse F10", "Crane.pdf"))
    }

    @Test fun filenameAloneCannotCreateAnEvidenceMatch() {
        val unrelated = Relevance.Candidate("Disconnect the AC supply before opening enclosure.",
            "Maintenance", "Anemometer alarm reset.pdf")
        assertTrue(rank("anemometer alarm reset", unrelated).isEmpty())
    }

    @Test fun rejectPartialEvidenceForGeneratedCitations() {
        val partial = Relevance.Candidate("General engine oil information only.",
            "Lubrication", "AD136TI.pdf")
        val real = Relevance.Candidate("The engine oil dipstick level must be checked before starting.",
            "Oil level check", "AD136TI.pdf")
        assertFalse(Relevance.strongEnoughForAnswer("engine oil dipstick level", partial))
        assertTrue(Relevance.strongEnoughForAnswer("engine oil dipstick level", real))
    }

    @Test fun procedureEvidenceNeedsAnActualAction() {
        val description = Relevance.Candidate("The device is a mechanical pump with a motor.",
            "Equipment description", "Instruction Manual.pdf")
        val steps = Relevance.Candidate("To replace the pump, disconnect the supply and remove the mounting bolts.",
            "Pump replacement", "Instruction Manual.pdf")
        assertFalse(Relevance.strongEnoughForAnswer("How to replace pump motor?", description))
        assertTrue(Relevance.strongEnoughForAnswer("How to replace pump motor?", steps))
    }

    @Test fun singleGenericKeywordCannotGenerateAnAnswer() {
        val broad = Relevance.Candidate("Hydraulic oil is present in the machinery.",
            "General information", "Instruction Manual.pdf")
        assertFalse(Relevance.strongEnoughForAnswer("oil", broad))
    }

    @Test fun partNumberDescriptionDoesNotProveWiring() {
        val catalog = Relevance.Candidate("Heat Detector Salwico HC100 A2. Part no 38000.",
            "Heat Detector Salwico HC100 A2", "Fire alarm panel.pdf")
        val drawing = Relevance.Candidate("Heat detector HC100 A2 wiring diagram with terminals 1 and 2.",
            "HC100 A2 wiring diagram", "Fire alarm panel.pdf")
        assertFalse(Relevance.strongEnoughForAnswer("HC100 A2 heat detector wiring", catalog))
        assertTrue(Relevance.strongEnoughForAnswer("HC100 A2 heat detector wiring", drawing))
    }

    @Test fun alarmQueryMustNotBeCitedFromUnrelatedSpecification() {
        val irrelevant = Relevance.Candidate("The motor nominal voltage is 24 V.",
            "Motor specification", "Crane.pdf")
        assertFalse(Relevance.strongEnoughForAnswer("Motor overload trip alarm cause", irrelevant))
    }

    @Test fun redirectIsNotPreferredOverAnswer() {
        val redirect = Relevance.Candidate("The type of hydraulic oil can be found in Section 5. Refer to Section 5.",
            "Hydraulic oil specification", "Instruction Manual.pdf")
        val answer = Relevance.Candidate("Hydraulic oil specification: use ISO VG 46 for this system.",
            "Hydraulic oil specification", "Instruction Manual.pdf")
        assertEquals(1, rank("hydraulic oil specification", redirect, answer).first())
    }
}