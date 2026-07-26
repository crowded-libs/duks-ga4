package duks.ga4.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured device information for Measurement Protocol requests.
 *
 * @see <a href="https://developers.google.com/analytics/devguides/collection/protocol/ga4/reference">MP reference — device</a>
 */
@Serializable
data class DeviceInfo(
    @SerialName("category")
    val category: String? = null,

    @SerialName("language")
    val language: String? = null,

    @SerialName("screen_resolution")
    val screenResolution: String? = null,

    @SerialName("operating_system")
    val operatingSystem: String? = null,

    @SerialName("operating_system_version")
    val operatingSystemVersion: String? = null,

    @SerialName("model")
    val model: String? = null,

    @SerialName("brand")
    val brand: String? = null,

    @SerialName("browser")
    val browser: String? = null,

    @SerialName("browser_version")
    val browserVersion: String? = null
)

/**
 * Structured geographic information for Measurement Protocol requests.
 * Takes precedence over [ip_override] when both are set.
 *
 * @see <a href="https://developers.google.com/analytics/devguides/collection/protocol/ga4/reference">MP reference — user_location</a>
 */
@Serializable
data class UserLocation(
    @SerialName("city")
    val city: String? = null,

    @SerialName("region_id")
    val regionId: String? = null,

    @SerialName("country_id")
    val countryId: String? = null,

    @SerialName("subcontinent_id")
    val subcontinentId: String? = null,

    @SerialName("continent_id")
    val continentId: String? = null
)

/**
 * Optional provider of device / geo context attached to every MP request.
 * Supply from the app (platform APIs, user profile, CDN headers, etc.).
 */
fun interface ContextProvider {
    suspend fun context(): RequestContext
}

/**
 * Per-request context merged into the Measurement Protocol payload.
 */
data class RequestContext(
    val device: DeviceInfo? = null,
    val userLocation: UserLocation? = null,
    val ipOverride: String? = null,
    val userAgent: String? = null
) {
    companion object {
        val EMPTY = RequestContext()
    }
}
