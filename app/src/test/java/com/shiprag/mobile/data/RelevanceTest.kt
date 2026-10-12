package com.shiprag.mobile.data

import org.junit.Assert.*
import org.junit.Test

class RelevanceTest {
    private fun rank(query: String, vararg evidence: Relevance.Candidate): List<Int> =
        Relevance.rank(query, evidence.toList()).map { it.first }

    @Test fun ignoresQuestionStopWords() {
        assertEquals(listOf("oil", "type", "hydraulic", "equipment"), Relevance.queryTerms("What is the oil type for hydraulic equipment?"))
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

    @Test fun redirectIsNotPreferredOverAnswer() {
        val redirect = Relevance.Candidate("The type of hydraulic oil can be found in Section 5. Refer to Section 5.",
            "Hydraulic oil specification", "Instruction Manual.pdf")
        val answer = Relevance.Candidate("Hydraulic oil specification: use ISO VG 46 for this system.",
            "Hydraulic oil specification", "Instruction Manual.pdf")
        assertEquals(1, rank("hydraulic oil specification", redirect, answer).first())
    }
}