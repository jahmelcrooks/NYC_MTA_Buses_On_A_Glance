package com.dngro.transit.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A service alert (detour, delay...) and the bus lines it says it affects. */
data class ServiceAlert(val text: String, val lines: Set<String>)

private const val MAX_ALERT_CHARS = 160

/**
 * Pulls alerts out of the SIRI "SituationExchangeDelivery" section.
 *
 * Deliberately tolerant: it walks the JSON looking for the field names instead of decoding a
 * fixed shape, so an unexpected layout yields "no alerts" and never breaks the arrivals.
 * NOTE: the field names come from MTA's SIRI documentation and have not been checked against
 * a live response yet.
 */
internal fun parseAlerts(element: JsonElement?): List<ServiceAlert> {
    if (element == null) return emptyList()
    return try {
        extractAlerts(element)
    } catch (e: Exception) {
        emptyList() // alerts are a bonus: never let them break the arrivals
    }
}

private fun extractAlerts(element: JsonElement): List<ServiceAlert> =
    element.findAll("PtSituationElement")
        .flatMap { if (it is JsonArray) it.toList() else listOf(it) }
        .mapNotNull { situation ->
            val obj = situation as? JsonObject ?: return@mapNotNull null
            val text = (obj["Summary"]?.asText() ?: obj["Description"]?.asText())
                ?.replace(WHITESPACE, " ")?.trim()
                ?.take(MAX_ALERT_CHARS)
            if (text.isNullOrBlank()) return@mapNotNull null
            val lines = obj.findAll("LineRef").mapNotNull { it.asText() }.toSet()
            ServiceAlert(text, lines)
        }
        .distinctBy { it.text }
}

/** Alerts that mention [route], as display text. At most [max]. */
internal fun alertsForRoute(alerts: List<ServiceAlert>, route: String, max: Int = 2): List<String> =
    alerts
        .filter { alert -> alert.lines.any { lineMatches(route, it) } }
        .map { it.text }
        .distinct()
        .take(max)

/**
 * True if an alert's line reference (e.g. "MTA NYCT_B41", "MTA NYCT_BX12") covers [route]
 * (e.g. "B41", "Bx12-SBS"). "B4" must not match "B41", but "BX12" does match "Bx12-SBS".
 */
internal fun lineMatches(route: String, lineRef: String): Boolean {
    val a = normalizeLine(route)
    val b = normalizeLine(lineRef.substringAfter('_'))
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return true
    return a.startsWith(b) && !a[b.length].isDigit()
}

private fun normalizeLine(raw: String): String = raw.filter { it.isLetterOrDigit() }.uppercase()

private val WHITESPACE = Regex("\\s+")

private fun JsonElement.asText(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    is JsonArray -> firstOrNull()?.asText()
    else -> null
}

/** Every value stored under [name], at any depth. */
private fun JsonElement.findAll(name: String): List<JsonElement> = when (this) {
    is JsonObject -> entries.flatMap { (key, value) ->
        if (key == name) listOf(value) + value.findAll(name) else value.findAll(name)
    }
    is JsonArray -> flatMap { it.findAll(name) }
    else -> emptyList()
}
