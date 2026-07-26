package duks.ga4.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a Google Analytics 4 Measurement Protocol request
 */
@Serializable
data class GA4Request(
    /**
     * The client ID for the user (required)
     */
    @SerialName("client_id")
    val clientId: String,
    
    /**
     * The user ID (optional)
     */
    @SerialName("user_id")
    val userId: String? = null,
    
    /**
     * The timestamp of the event in microseconds (optional)
     */
    @SerialName("timestamp_micros")
    val timestampMicros: Long? = null,
    
    /**
     * User properties to be associated with the user.
     * Serialized as `{ "prop_name": { "value": ... } }`.
     */
    @SerialName("user_properties")
    @Serializable(with = UserPropertiesSerializer::class)
    val userProperties: Map<String, UserPropertyValue>? = null,
    
    /**
     * Consent state for the request (MP wire: ad_user_data + ad_personalization only).
     */
    @SerialName("consent")
    @Serializable(with = MeasurementProtocolConsentSerializer::class)
    val consent: ConsentState? = null,
    
    /**
     * List of events to send
     */
    @SerialName("events")
    val events: List<GA4Event>,
    
    /**
     * Whether events should be processed for non-personalized ads (deprecated by GA4;
     * prefer consent.ad_personalization).
     */
    @SerialName("non_personalized_ads")
    val nonPersonalizedAds: Boolean? = null
)

/**
 * Wrapper for user property values.
 * Wire format is `{ "value": <string|number> }`.
 */
@Serializable(with = UserPropertyValueSerializer::class)
sealed class UserPropertyValue {
    data class StringValue(val value: String) : UserPropertyValue()
    data class NumberValue(val value: Double) : UserPropertyValue()
    data class BooleanValue(val value: Boolean) : UserPropertyValue()
}
