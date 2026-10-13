package com.shiprag.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualReferenceParserTest {
    @Test fun genericSectionFive() {
        val routes = ManualReferenceParser.parse(
            "This instruction describes sampling oil. Type of oil to be used in the system can be found in Section 5.")
        assertEquals(1, routes.size)
        assertEquals("5", routes[0].number)
        assertEquals(null, routes[0].title)
    }

    @Test fun namedLubricantsRouteTakesPriorityOverGeneric() {
        val routes = ManualReferenceParser.parse(
            "For type of oil refer to \"Lubricants for Deck Machinery\" in section 5.")
        assertEquals(1, routes.size)
        assertEquals("Lubricants for Deck Machinery", routes[0].title)
        assertEquals("5", routes[0].number)
    }

    @Test fun multipleNamedDestinationsAreExtracted() {
        val routes = ManualReferenceParser.parse(
            "For maintenance intervals refer to the \"Lubricating Chart\" and \"Maintenance Chart\" in section 5.")
        assertEquals(2, routes.size)
        assertTrue(routes.any { it.title == "Lubricating Chart" })
        assertTrue(routes.any { it.title == "Maintenance Chart" })
    }

    @Test fun noRouteForSectionHeadingAlone() {
        assertTrue(ManualReferenceParser.parse("Chapter 4 contains: 1. Safety Functions").isEmpty())
    }

    @Test fun subsectionNumbersAndSafetyProcedureRemainLiteral() {
        val routes = ManualReferenceParser.parse(
            "See instruction \"High pressure pumps\" in section 6.1. Switch off the power before working.")
        assertEquals("6.1", routes.first().number)
        assertEquals("High pressure pumps", routes.first().title)
        assertFalse(routes.any { it.number == "1" })
    }
}