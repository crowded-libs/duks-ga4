package duks.ga4.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the consent state for Google Analytics tracking
 */
@Serializable
data class ConsentState(
    @SerialName("ad_storage")
    val adStorage: ConsentValue? = null,
    
    @SerialName("analytics_storage")
    val analyticsStorage: ConsentValue? = null,
    
    @SerialName("ad_personalization")
    val adPersonalization: ConsentValue? = null,
    
    @SerialName("ad_user_data")
    val adUserData: ConsentValue? = null,
    
    @SerialName("functionality_storage")
    val functionalityStorage: ConsentValue? = null,
    
    @SerialName("personalization_storage")
    val personalizationStorage: ConsentValue? = null,
    
    @SerialName("security_storage")
    val securityStorage: ConsentValue? = null
)

/**
 * Consent values for Google Analytics
 */
@Serializable
enum class ConsentValue {
    @SerialName("granted")
    GRANTED,
    
    @SerialName("denied")
    DENIED
}