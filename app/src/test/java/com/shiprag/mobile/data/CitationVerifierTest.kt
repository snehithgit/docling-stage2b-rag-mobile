package com.shiprag.mobile.data

import org.junit.Assert.*
import org.junit.Test

class CitationVerifierTest {
    private fun src(text: String, kind: String = "text") = Source(
        id=1, manual="Instruction Manual.pdf", chunkId="CHK-00001",
        content=text, headings=listOf("Oil volume"), pages=listOf(58),
        docItems=emptyList(), location="processed/manual", evidenceKind=kind
    )

    @Test fun exactQuantityCanBeCitedInDifferentUnitSpelling() {
        assertTrue(CitationVerifier.warnings(
            "Fill the luffing winch with 6.5 L [S1].",
            listOf(src("Luffing winch 6.5 liters"))).isEmpty())
    }

    @Test fun wrongQuantityIsFlaggedEvenWithValidCitationLabel() {
        assertTrue(CitationVerifier.warnings(
            "Fill the luffing winch with 8.0 litres [S1].",
            listOf(src("Luffing winch 6.5 liters"))).isNotEmpty())
    }

    @Test fun technicalValueWithoutCitationIsFlagged() {
        assertTrue(CitationVerifier.warnings(
            "Recommended fill is 6.5 litres.",
            listOf(src("Luffing winch 6.5 liters"))).isNotEmpty())
    }

    @Test fun hoistingVolumeMustNotBeAssignedToLuffingWinch() {
        val table = src("OIL VOLUME / OIL FILLING per unit. Hoisting winch 13.0 liters. Luffing winch 6.5 liters.")
        assertTrue(CitationVerifier.warnings(
            "Fill with 13.0 liters [S1].",
            listOf(table), "How much oil to fill in the luffing winch?").isNotEmpty())
        assertTrue(CitationVerifier.warnings(
            "Fill with 6.5 liters [S1].",
            listOf(table), "How much oil to fill in the luffing winch?").isEmpty())
    }

    @Test fun visualSummaryCannotVerifyExactOilQuantity() {
        assertTrue(CitationVerifier.warnings(
            "The oil volume is 6.5 litres [S1].",
            listOf(src("Diagram appears to show 6.5 litres", "visual"))).isNotEmpty())
    }
}