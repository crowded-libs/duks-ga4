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
     * User properties to be associated with the user
     */
    @SerialName("user_properties")
    val userProperties: Map<String, UserPropertyValue>? = null,
    
    /**
     * Consent state for the user
     */
    @SerialName("consent")
    val consent: ConsentState? = null,
    
    /**
     * List of events to send
     */
    @SerialName("events")
    val events: List<GA4Event>,
    
    /**
     * Whether events should be processed for debugging
     */
    @SerialName("non_personalized_ads")
    val nonPersonalizedAds: Boolean? = null
)

/**
 * Wrapper for user property values
 */
@Serializable
sealed class UserPropertyValue {
    @Serializable
    @SerialName("string")
    data class StringValue(@SerialName("value") val value: String) : UserPropertyValue()
    
    @Serializable
    @SerialName("number")
    data class NumberValue(@SerialName("value") val value: Double) : UserPropertyValue()
    
    @Serializable
    @SerialName("boolean")
    data class BooleanValue(@SerialName("value") val value: Boolean) : UserPropertyValue()
}