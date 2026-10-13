package com.shiprag.mobile.data

import org.junit.Assert.*
import org.junit.Test

/** Realistic shipboard phrasing; synthetic excerpts mirror imported manual evidence. */
class MarineQueryTest {
    private val volumes = Relevance.Candidate(
        "OIL VOLUME / OIL FILLING (per unit) Oil tank 450 liters. Slewing gear 14.0 liters. Hoisting winch 13.0 liters. Luffing winch 6.5 liters.",
        "OIL VOLUME / OIL FILLING", "Instruction Manual.pdf"
    )
    private val lubricants = Relevance.Candidate(
        "Hoisting and luffi  ng winches Synthetic oil (polyglycol type). Viscosity 220 cSt/40°C. Example: Shell Tivela S220 or similar. CAUTION: do not mix mineral oil.",
        "A. OILS / Lubricants for Deck Machinery", "Instruction Manual.pdf"
    )
    private val levelCheck = Relevance.Candidate(
        "Check the oil level and top up with fresh oil to just under the MAX mark on the dipstick.",
        "Checking oil level", "Instruction Manual.pdf"
    )
    private fun best(query: String, rows: List<Relevance.Candidate>): Int =
        Relevance.rank(query, rows).firstOrNull()?.first ?: -1

    @Test fun fillQuantityRoutesToActualLitresNotDipstick() {
        val rows = listOf(levelCheck, lubricants, volumes)
        assertEquals(MarineQuery.Intent.OilVolume, MarineQuery.profile("how much oil need to fill in luffing winch").intent)
        assertEquals(2, best("how much oil need to fill in luffing winch", rows))
        assertTrue(MarineQuery.answerEvidence(
            MarineQuery.profile("luffing winch oil volume"), volumes.body, volumes.heading))
        assertFalse(MarineQuery.answerEvidence(
            MarineQuery.profile("luffing winch oil volume"), levelCheck.body, levelCheck.heading))
    }

    @Test fun oilGradeRoutesToActualLubricantNotFillingVolume() {
        val rows = listOf(volumes, levelCheck, lubricants)
        assertEquals(MarineQuery.Intent.OilGrade, MarineQuery.profile("what is the luffing winch oil type").intent)
        assertEquals(2, best("what is the luffing winch oil type", rows))
        assertTrue(Relevance.strongEnoughForAnswer("luffing winch oil type", lubricants))
        assertFalse(Relevance.strongEnoughForAnswer("luffing winch oil type", volumes))
    }

    @Test fun slowHoistingPrefersFaultChecksOverNormalSpeedTable() {
        val speed = Relevance.Candidate(
            "Hoisting capacity low speed 10 tonne, hoisting speed high 20 m/min.", "BASIC DATA", "Instruction Manual.pdf")
        val checks = Relevance.Candidate(
            "Hoisting speed too low. Check control pressure, filters, pump displacement and valve operation for a restriction or failure.",
            "Hoisting winch fault finding", "Instruction Manual.pdf")
        val p=MarineQuery.profile("hoisting is slow what to check")
        assertEquals(MarineQuery.Intent.SlowOperation,p.intent)
        assertEquals(1,best("hoisting is slow what to check",listOf(speed,checks)))
        assertTrue(MarineQuery.answerEvidence(p,checks.body,checks.heading))
        assertFalse(MarineQuery.answerEvidence(p,speed.body,speed.heading))
    }

    @Test fun genericOilAmountWithoutEquipmentIsNotEnoughToCite() {
        val p=MarineQuery.profile("how much oil need to fill")
        assertEquals(MarineQuery.Intent.OilVolume,p.intent)
        assertFalse(p.hasEquipment)
        assertFalse(Relevance.strongEnoughForAnswer("how much oil need to fill", volumes))
    }

    @Test fun morphologicalMatchingPreservesActualEquipment() {
        val p=MarineQuery.profile("luffing winch oil type")
        assertEquals(1.0,MarineQuery.subjectRatio(p,lubricants.body,lubricants.heading),0.001)
        assertEquals(0.0,MarineQuery.subjectRatio(p,"Slewing gearbox synthetic oil","Slewing gear"),0.001)
    }
}