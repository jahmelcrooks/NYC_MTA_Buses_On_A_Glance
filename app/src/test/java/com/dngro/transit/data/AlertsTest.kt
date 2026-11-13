package com.dngro.transit.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alert JSON below is written from MTA's SIRI field names, not captured from the live API.
 * Replacing it with a real captured response is the useful next step for this feature.
 */
class AlertsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun alerts(raw: String) = parseAlerts(json.parseToJsonElement(raw))

    @Test
    fun parseAlerts_readsSummaryAndAffectedLines() {
        val raw = """
        [{"Situations":{"PtSituationElement":[
          {"Summary":"B41 buses detoured\nat Church Av","Description":"long text",
           "Affects":{"VehicleJourneys":{"AffectedVehicleJourney":[
              {"LineRef":"MTA NYCT_B41"},{"LineRef":"MTA NYCT_B49"}]}}}
        ]}}]
        """.trimIndent()

        val result = alerts(raw).single()

        assertEquals("B41 buses detoured at Church Av", result.text)
        assertEquals(setOf("MTA NYCT_B41", "MTA NYCT_B49"), result.lines)
    }

    @Test
    fun parseAlerts_acceptsASingleObjectInsteadOfAnArray_andFallsBackToDescription() {
        val raw = """
        {"Situations":{"PtSituationElement":
          {"Description":["Delays on the M15"],"Affects":{"LineRef":"MTA NYCT_M15"}}}}
        """.trimIndent()

        val result = alerts(raw).single()

        assertEquals("Delays on the M15", result.text)
        assertEquals(setOf("MTA NYCT_M15"), result.lines)
    }

    @Test
    fun parseAlerts_unexpectedShapeOrNothing_isEmptyNotACrash() {
        assertTrue(parseAlerts(null).isEmpty())
        assertTrue(alerts("[]").isEmpty())
        assertTrue(alerts("""{"Situations":"none"}""").isEmpty())
        assertTrue(alerts("""[{"Situations":{"PtSituationElement":[{"Summary":""}]}}]""").isEmpty())
    }

    @Test
    fun parseAlerts_dropsDuplicatesAndTruncatesLongText() {
        val long = "x".repeat(400)
        val raw = """
        [{"Situations":{"PtSituationElement":[
          {"Summary":"Same","Affects":{"LineRef":"MTA NYCT_B1"}},
          {"Summary":"Same","Affects":{"LineRef":"MTA NYCT_B2"}},
          {"Summary":"$long"}
        ]}}]
        """.trimIndent()

        val result = alerts(raw)

        assertEquals(2, result.size)
        assertEquals(160, result[1].text.length)
    }

    @Test
    fun lineMatches_exactAndSelectBusSuffixes() {
        assertTrue(lineMatches("B41", "MTA NYCT_B41"))
        assertTrue(lineMatches("Bx12-SBS", "MTA NYCT_BX12"))
        assertTrue(lineMatches("Bx12-SBS", "MTA NYCT_BX12+"))
        assertTrue(lineMatches("M15-SBS", "MTA NYCT_M15"))
    }

    @Test
    fun lineMatches_doesNotConfuseNumbersThatShareAPrefix() {
        assertFalse(lineMatches("B41", "MTA NYCT_B4"))
        assertFalse(lineMatches("B4", "MTA NYCT_B41"))
        assertFalse(lineMatches("Q10", "MTA NYCT_Q1"))
        assertFalse(lineMatches("M1", "MTA NYCT_M15"))
        assertFalse(lineMatches("", "MTA NYCT_B41"))
        assertFalse(lineMatches("B41", ""))
    }

    @Test
    fun alertsForRoute_picksMatchingAlerts_distinct_andLimited() {
        val all = listOf(
            ServiceAlert("A", setOf("MTA NYCT_B41")),
            ServiceAlert("A", setOf("MTA NYCT_B41")),
            ServiceAlert("B", setOf("MTA NYCT_B44")),
            ServiceAlert("C", setOf("MTA NYCT_B41")),
            ServiceAlert("D", setOf("MTA NYCT_B41")),
        )

        assertEquals(listOf("A", "C"), alertsForRoute(all, "B41", max = 2))
        assertEquals(listOf("B"), alertsForRoute(all, "B44"))
        assertTrue(alertsForRoute(all, "M1").isEmpty())
    }
}
