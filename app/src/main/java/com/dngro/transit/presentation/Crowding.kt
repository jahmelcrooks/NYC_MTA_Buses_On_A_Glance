package com.dngro.transit.presentation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How full a bus is, as MTA reports it. Only part of the fleet reports this. */
enum class Crowding { SEATS, STANDING, FULL }

/** Crowding level and on-board head count, either of which may be unknown. */
internal data class CrowdInfo(val level: Crowding?, val passengers: Int?)

/**
 * Reads MTA's "Occupancy" category and, failing that, works it out from the passenger counter
 * (EstimatedPassengerCount / EstimatedPassengerCapacity). Never throws: odd data means "unknown".
 */
internal fun crowdInfo(occupancy: JsonElement?, capacities: JsonElement?): CrowdInfo {
    val count = capacities.intField("EstimatedPassengerCount")?.takeIf { it >= 0 }
    val capacity = capacities.intField("EstimatedPassengerCapacity")?.takeIf { it > 0 }
    val level = occupancyLevel(occupancy.text())
        ?: if (count != null && capacity != null) levelFromRatio(count.toFloat() / capacity) else null
    return CrowdInfo(level, count)
}

/** SIRI 1 values plus the finer SIRI 2 ones ("manySeatsAvailable", "standingRoomOnly"...). */
internal fun occupancyLevel(raw: String?): Crowding? {
    val value = raw?.lowercase()?.filter { it.isLetter() } ?: return null
    return when {
        value.isEmpty() -> null
        "full" in value || "notaccepting" in value || "crushed" in value -> Crowding.FULL
        "standing" in value -> Crowding.STANDING
        "seat" in value || "empty" in value -> Crowding.SEATS
        else -> null
    }
}

private fun levelFromRatio(ratio: Float): Crowding = when {
    ratio < 0.5f -> Crowding.SEATS
    ratio < 0.9f -> Crowding.STANDING
    else -> Crowding.FULL
}

/** "Seats", "Standing · 34 aboard", "12 aboard", or "" when nothing is known. */
internal fun crowdLabel(level: Crowding?, passengers: Int?): String {
    val name = when (level) {
        Crowding.SEATS -> "Seats"
        Crowding.STANDING -> "Standing"
        Crowding.FULL -> "Full"
        null -> null
    }
    val count = passengers?.let { "$it aboard" }
    return listOfNotNull(name, count).joinToString(" · ")
}

private fun JsonElement?.text(): String? = when (this) {
    is JsonPrimitive -> if (isString || content != "null") content else null
    is JsonArray -> firstOrNull().text()
    else -> null
}

private fun JsonElement?.intField(name: String): Int? {
    val obj = when (this) {
        is JsonObject -> this
        is JsonArray -> firstOrNull() as? JsonObject
        else -> null
    } ?: return null
    return obj[name].text()?.trim()?.toDoubleOrNull()?.toInt()
}
