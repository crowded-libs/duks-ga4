package duks.ga4.model

import kotlinx.serialization.Serializable

/**
 * Represents the consent state for Google Analytics tracking.
 *
 * Used both for app-side Consent Mode gating (all fields) and as input to the
 * Measurement Protocol payload. On the wire, only [adUserData] and
 * [adPersonalization] are sent (see [MeasurementProtocolConsentSerializer]).
 *
 * Default serialization uses the full storage format (all Consent Mode fields).
 * [GA4Request] overrides this with the MP-only serializer.
 */
@Serializable(with = ConsentStateStorageSerializer::class)
data class ConsentState(
    val adStorage: ConsentValue? = null,
    val analyticsStorage: ConsentValue? = null,
    val adPersonalization: ConsentValue? = null,
    val adUserData: ConsentValue? = null,
    val functionalityStorage: ConsentValue? = null,
    val personalizationStorage: ConsentValue? = null,
    val securityStorage: ConsentValue? = null
)

/**
 * Consent values for Google Analytics.
 *
 * Measurement Protocol expects uppercase `GRANTED` / `DENIED` on the wire.
 * App-side comparisons use this enum regardless of storage casing.
 */
@Serializable
enum class ConsentValue {
    GRANTED,
    DENIED
}
