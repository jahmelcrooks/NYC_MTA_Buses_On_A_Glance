package com.dngro.transit.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

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
)

@Serializable
data class MtaStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val direction: String? = null,
    val routes: List<MtaRouteInfo> = emptyList(),
)

@Serializable
data class MtaRouteInfo(
    val shortName: String,
    val longName: String? = null,
    val description: String? = null,
)

// --- SIRI StopMonitoring API Models (stop-monitoring.json) ---

@Serializable
data class SiriResponse(
    @SerialName("Siri") val siri: SiriData,
)

@Serializable
data class SiriData(
    @SerialName("ServiceDelivery") val serviceDelivery: ServiceDelivery,
)

@Serializable
data class ServiceDelivery(
    @SerialName("StopMonitoringDelivery") val stopMonitoringDelivery: List<StopMonitoringDelivery>,
)

@Serializable
data class StopMonitoringDelivery(
    @SerialName("MonitoredStopVisit") val monitoredStopVisit: List<MonitoredStopVisit> = emptyList(),
)

@Serializable
data class MonitoredStopVisit(
    @SerialName("MonitoredVehicleJourney") val monitoredVehicleJourney: MonitoredVehicleJourney,
)

/**
 * Custom serializer to handle fields being either a String or an Array.
 * The MTA API is inconsistent with these types.
 */
object StringOrArraySerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringOrArray", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val input = (decoder as? JsonDecoder) ?: throw Exception("Only JSON is supported")
        val element = input.decodeJsonElement()
        return try {
            (element as? JsonPrimitive)?.content ?: element.jsonArray.first().toString().replace("\"", "")
        } catch (_: Exception) {
            ""
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

@Serializable
data class MonitoredVehicleJourney(
    @Serializable(with = StringOrArraySerializer::class)
    @SerialName("PublishedLineName") val lineName: String,

    @Serializable(with = StringOrArraySerializer::class)
    @SerialName("DestinationName") val destinationName: String = "",

    @SerialName("MonitoredCall") val monitoredCall: MonitoredCall,
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
