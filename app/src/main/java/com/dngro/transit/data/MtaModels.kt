package com.dngro.transit.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// Every field below has a default on purpose. MTA's JSON is inconsistent between
// buses and API versions, and one missing field must not throw away a whole response.

// --- Discovery API Models (stops-for-location.json) ---

@Serializable
data class StopResponse(
    val data: StopData? = null,
    val code: Int = 200,
    val text: String? = null,
    val version: Int = 2,
)

@Serializable
data class StopData(
    @SerialName("stops") val stops: List<MtaStop> = emptyList(),
    // OneBusAway-style responses put the stops under "list" instead.
    @SerialName("list") val list: List<MtaStop> = emptyList(),
) {
    val allStops: List<MtaStop> get() = stops.ifEmpty { list }
}

@Serializable
data class MtaStop(
    val id: String = "",
    val name: String = "",
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val direction: String? = null,
    val routes: List<MtaRouteInfo> = emptyList(),
)

@Serializable
data class MtaRouteInfo(
    val shortName: String = "",
    val longName: String? = null,
    val description: String? = null,
)

// --- SIRI StopMonitoring API Models (stop-monitoring.json) ---

@Serializable
data class SiriResponse(
    @SerialName("Siri") val siri: SiriData? = null,
)

@Serializable
data class SiriData(
    @SerialName("ServiceDelivery") val serviceDelivery: ServiceDelivery? = null,
)

@Serializable
data class ServiceDelivery(
    @SerialName("StopMonitoringDelivery") val stopMonitoringDelivery: List<StopMonitoringDelivery> = emptyList(),
    // Kept as raw JSON on purpose: its shape isn't verified, and a wrong guess here must not
    // break decoding of the arrivals. See parseAlerts().
    @SerialName("SituationExchangeDelivery") val situationExchangeDelivery: JsonElement? = null,
)

@Serializable
data class StopMonitoringDelivery(
    @SerialName("MonitoredStopVisit") val monitoredStopVisit: List<MonitoredStopVisit> = emptyList(),
)

@Serializable
data class MonitoredStopVisit(
    @SerialName("MonitoredVehicleJourney") val monitoredVehicleJourney: MonitoredVehicleJourney? = null,
)

/**
 * Custom serializer to handle fields being a String, a one-element Array, or null.
 * The MTA API is inconsistent with these types.
 */
object StringOrArraySerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringOrArray", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Only JSON is supported")
        return when (val element = input.decodeJsonElement()) {
            is JsonNull -> ""
            is JsonPrimitive -> element.content
            is JsonArray -> (element.firstOrNull() as? JsonPrimitive)
                ?.takeUnless { it is JsonNull }
                ?.content
                .orEmpty()
            else -> ""
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

@Serializable
data class MonitoredVehicleJourney(
    @Serializable(with = StringOrArraySerializer::class)
    @SerialName("PublishedLineName") val lineName: String = "",

    @Serializable(with = StringOrArraySerializer::class)
    @SerialName("DestinationName") val destinationName: String = "",

    @SerialName("VehicleRef") val vehicleRef: String? = null,

    @SerialName("MonitoredCall") val monitoredCall: MonitoredCall = MonitoredCall(),
)

@Serializable
data class MonitoredCall(
    @SerialName("Extensions") val extensions: Extensions? = null,
    @SerialName("ExpectedArrivalTime") val expectedArrivalTime: String? = null,
)

@Serializable
data class Extensions(
    @SerialName("Distances") val distances: Distances? = null,
)

@Serializable
data class Distances(
    @SerialName("PresentableDistance") val presentableDistance: String = "",
)
