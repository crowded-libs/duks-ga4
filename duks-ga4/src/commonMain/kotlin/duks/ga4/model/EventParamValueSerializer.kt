package duks.ga4.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Serializes [EventParamValue] as plain GA4 Measurement Protocol values
 * (string / number / boolean / items array), not as a sealed-class envelope.
 */
object EventParamValueSerializer : KSerializer<EventParamValue> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("EventParamValue")

    override fun serialize(encoder: Encoder, value: EventParamValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("EventParamValue can only be serialized to JSON")

        val element: JsonElement = when (value) {
            is EventParamValue.StringValue -> JsonPrimitive(value.value)
            is EventParamValue.NumberValue -> JsonPrimitive(value.value)
            is EventParamValue.BooleanValue -> JsonPrimitive(value.value)
            is EventParamValue.ItemsValue -> jsonEncoder.json.encodeToJsonElement(
                ListSerializer(Item.serializer()),
                value.value
            )
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): EventParamValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("EventParamValue can only be deserialized from JSON")

        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                when {
                    element.isString -> EventParamValue.StringValue(element.content)
                    element.booleanOrNull != null -> EventParamValue.BooleanValue(element.boolean)
                    element.doubleOrNull != null -> EventParamValue.NumberValue(element.double)
                    else -> EventParamValue.StringValue(element.content)
                }
            }
            is JsonArray -> {
                val items = jsonDecoder.json.decodeFromJsonElement(
                    ListSerializer(Item.serializer()),
                    element
                )
                EventParamValue.ItemsValue(items)
            }
            is JsonObject -> {
                // Backward-compat for the old sealed-class envelope: {"type":"string","value":"..."}
                val type = element["type"]?.jsonPrimitive?.content
                val rawValue = element["value"]
                when (type) {
                    "string" -> EventParamValue.StringValue(rawValue?.jsonPrimitive?.content ?: "")
                    "number" -> EventParamValue.NumberValue(rawValue?.jsonPrimitive?.double ?: 0.0)
                    "boolean" -> EventParamValue.BooleanValue(rawValue?.jsonPrimitive?.boolean ?: false)
                    "items" -> {
                        val items = rawValue?.let {
                            jsonDecoder.json.decodeFromJsonElement(ListSerializer(Item.serializer()), it)
                        } ?: emptyList()
                        EventParamValue.ItemsValue(items)
                    }
                    else -> EventParamValue.StringValue(element.toString())
                }
            }
            JsonNull -> EventParamValue.StringValue("")
        }
    }
}

/**
 * Map serializer so event params encode as a flat JSON object of plain values.
 */
object EventParamsSerializer : KSerializer<Map<String, EventParamValue>> by MapSerializer(
    String.serializer(),
    EventParamValueSerializer
)

/**
 * Serializes [UserPropertyValue] as GA4 expects: `{ "value": <string|number> }`.
 * Booleans are encoded as strings for MP compatibility.
 */
object UserPropertyValueSerializer : KSerializer<UserPropertyValue> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("UserPropertyValue") {
            element<JsonElement>("value")
        }

    override fun serialize(encoder: Encoder, value: UserPropertyValue) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("UserPropertyValue can only be serialized to JSON")

        val primitive = when (value) {
            is UserPropertyValue.StringValue -> JsonPrimitive(value.value)
            is UserPropertyValue.NumberValue -> JsonPrimitive(value.value)
            is UserPropertyValue.BooleanValue -> JsonPrimitive(value.value.toString())
        }
        jsonEncoder.encodeJsonElement(
            buildJsonObject { put("value", primitive) }
        )
    }

    override fun deserialize(decoder: Decoder): UserPropertyValue {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("UserPropertyValue can only be deserialized from JSON")

        val obj = jsonDecoder.decodeJsonElement().jsonObject
        val primitive = obj["value"]?.jsonPrimitive
            ?: return UserPropertyValue.StringValue("")

        return when {
            primitive.isString -> UserPropertyValue.StringValue(primitive.content)
            primitive.booleanOrNull != null ->
                UserPropertyValue.BooleanValue(primitive.boolean)
            primitive.doubleOrNull != null ->
                UserPropertyValue.NumberValue(primitive.double)
            else -> UserPropertyValue.StringValue(primitive.content)
        }
    }
}

/**
 * Map serializer for user_properties on the Measurement Protocol payload.
 */
object UserPropertiesSerializer : KSerializer<Map<String, UserPropertyValue>> by MapSerializer(
    String.serializer(),
    UserPropertyValueSerializer
)

/**
 * Serializes consent for the Measurement Protocol wire format.
 *
 * Only [ad_user_data] and [ad_personalization] are valid on MP payloads,
 * with values `GRANTED` / `DENIED`.
 */
object MeasurementProtocolConsentSerializer : KSerializer<ConsentState> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("MeasurementProtocolConsent") {
            element<String>("ad_user_data", isOptional = true)
            element<String>("ad_personalization", isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: ConsentState) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("ConsentState wire format requires JSON")

        val obj = buildJsonObject {
            value.adUserData?.let {
                put("ad_user_data", JsonPrimitive(it.toMeasurementProtocolValue()))
            }
            value.adPersonalization?.let {
                put("ad_personalization", JsonPrimitive(it.toMeasurementProtocolValue()))
            }
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): ConsentState {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("ConsentState wire format requires JSON")

        val obj = jsonDecoder.decodeJsonElement().jsonObject
        return ConsentState(
            adUserData = obj["ad_user_data"]?.jsonPrimitive?.content?.toConsentValue(),
            adPersonalization = obj["ad_personalization"]?.jsonPrimitive?.content?.toConsentValue(),
            // App-side fields may appear in storage JSON; accept lowercase or uppercase
            adStorage = obj["ad_storage"]?.jsonPrimitive?.content?.toConsentValue(),
            analyticsStorage = obj["analytics_storage"]?.jsonPrimitive?.content?.toConsentValue(),
            functionalityStorage = obj["functionality_storage"]?.jsonPrimitive?.content?.toConsentValue(),
            personalizationStorage = obj["personalization_storage"]?.jsonPrimitive?.content?.toConsentValue(),
            securityStorage = obj["security_storage"]?.jsonPrimitive?.content?.toConsentValue()
        )
    }
}

/**
 * Full ConsentState serializer for app storage (all Consent Mode fields, lowercase values).
 * Use this for privacy storage, not for Measurement Protocol requests.
 */
object ConsentStateStorageSerializer : KSerializer<ConsentState> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ConsentStateStorage")

    override fun serialize(encoder: Encoder, value: ConsentState) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("ConsentState storage format requires JSON")

        val obj = buildJsonObject {
            value.adStorage?.let { put("ad_storage", JsonPrimitive(it.name.lowercase())) }
            value.analyticsStorage?.let { put("analytics_storage", JsonPrimitive(it.name.lowercase())) }
            value.adPersonalization?.let { put("ad_personalization", JsonPrimitive(it.name.lowercase())) }
            value.adUserData?.let { put("ad_user_data", JsonPrimitive(it.name.lowercase())) }
            value.functionalityStorage?.let { put("functionality_storage", JsonPrimitive(it.name.lowercase())) }
            value.personalizationStorage?.let { put("personalization_storage", JsonPrimitive(it.name.lowercase())) }
            value.securityStorage?.let { put("security_storage", JsonPrimitive(it.name.lowercase())) }
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): ConsentState {
        return MeasurementProtocolConsentSerializer.deserialize(decoder)
    }
}

private fun ConsentValue.toMeasurementProtocolValue(): String = when (this) {
    ConsentValue.GRANTED -> "GRANTED"
    ConsentValue.DENIED -> "DENIED"
}

private fun String.toConsentValue(): ConsentValue? = when (uppercase()) {
    "GRANTED" -> ConsentValue.GRANTED
    "DENIED" -> ConsentValue.DENIED
    else -> null
}
