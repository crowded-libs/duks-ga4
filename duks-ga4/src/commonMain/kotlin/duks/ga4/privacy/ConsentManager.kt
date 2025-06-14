package duks.ga4.privacy

import duks.ga4.model.ConsentState
import duks.ga4.model.ConsentValue
import duks.logging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Interface for managing user consent for analytics and advertising
 */
interface ConsentManager {
    /**
     * Current consent state
     */
    val consentState: StateFlow<ConsentState>
    
    /**
     * Whether analytics events should be processed
     */
    val analyticsEnabled: StateFlow<Boolean>
    
    /**
     * Whether advertising features are enabled
     */
    val advertisingEnabled: StateFlow<Boolean>
    
    /**
     * Updates the consent state
     */
    suspend fun updateConsent(consent: ConsentState)
    
    /**
     * Updates a specific consent type
     */
    suspend fun updateConsentType(type: ConsentType, value: ConsentValue)
    
    /**
     * Grants all consent types
     */
    suspend fun grantAll()
    
    /**
     * Denies all consent types
     */
    suspend fun denyAll()
    
    /**
     * Resets consent to default state
     */
    suspend fun reset()
    
    /**
     * Checks if a specific event should be processed based on consent
     */
    fun shouldProcessEvent(eventName: String): Boolean
    
    /**
     * Gets the current consent state synchronously
     */
    fun getCurrentConsent(): ConsentState
}

/**
 * Types of consent that can be managed
 */
enum class ConsentType {
    AD_STORAGE,
    ANALYTICS_STORAGE,
    AD_PERSONALIZATION,
    AD_USER_DATA,
    FUNCTIONALITY_STORAGE,
    PERSONALIZATION_STORAGE,
    SECURITY_STORAGE
}

/**
 * Default implementation of ConsentManager
 */
class DefaultConsentManager(
    private val storage: ConsentStorage,
    private val defaultConsent: ConsentState = ConsentState(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : ConsentManager {
    
    private val logger = Logger.default()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    
    private val _consentState = MutableStateFlow(defaultConsent)
    override val consentState: StateFlow<ConsentState> = _consentState
    
    private val _analyticsEnabled = MutableStateFlow(
        defaultConsent.analyticsStorage == ConsentValue.GRANTED
    )
    override val analyticsEnabled: StateFlow<Boolean> = _analyticsEnabled
    
    private val _advertisingEnabled = MutableStateFlow(
        defaultConsent.adStorage == ConsentValue.GRANTED
    )
    override val advertisingEnabled: StateFlow<Boolean> = _advertisingEnabled
    
    init {
        // Load saved consent state
        scope.launch {
            logger.debug { "Loading saved consent state from storage" }
            val savedConsent = storage.loadConsent()
            if (savedConsent != null) {
                logger.info { "Loaded saved consent state from storage" }
                _consentState.value = savedConsent
                updateDerivedStates(savedConsent)
            } else {
                logger.debug { "No saved consent state found, using defaults" }
            }
        }
    }
    
    override suspend fun updateConsent(consent: ConsentState) {
        logger.info(
            consent.analyticsStorage?.name,
            consent.adStorage?.name,
            consent.adPersonalization?.name
        ) {
            "Updating consent state - analytics: {analytics}, ads: {ads}, personalization: {personalization}"
        }
        _consentState.value = consent
        updateDerivedStates(consent)
        storage.saveConsent(consent)
    }
    
    override suspend fun updateConsentType(type: ConsentType, value: ConsentValue) {
        logger.debug(type.name, value.name) {
            "Updating consent type {type} to {value}"
        }
        val currentConsent = _consentState.value
        val updatedConsent = when (type) {
            ConsentType.AD_STORAGE -> currentConsent.copy(adStorage = value)
            ConsentType.ANALYTICS_STORAGE -> currentConsent.copy(analyticsStorage = value)
            ConsentType.AD_PERSONALIZATION -> currentConsent.copy(adPersonalization = value)
            ConsentType.AD_USER_DATA -> currentConsent.copy(adUserData = value)
            ConsentType.FUNCTIONALITY_STORAGE -> currentConsent.copy(functionalityStorage = value)
            ConsentType.PERSONALIZATION_STORAGE -> currentConsent.copy(personalizationStorage = value)
            ConsentType.SECURITY_STORAGE -> currentConsent.copy(securityStorage = value)
        }
        updateConsent(updatedConsent)
    }
    
    override suspend fun grantAll() {
        logger.info { "Granting all consent types" }
        updateConsent(
            ConsentState(
                adStorage = ConsentValue.GRANTED,
                analyticsStorage = ConsentValue.GRANTED,
                adPersonalization = ConsentValue.GRANTED,
                adUserData = ConsentValue.GRANTED,
                functionalityStorage = ConsentValue.GRANTED,
                personalizationStorage = ConsentValue.GRANTED,
                securityStorage = ConsentValue.GRANTED
            )
        )
    }
    
    override suspend fun denyAll() {
        logger.info { "Denying all consent types" }
        updateConsent(
            ConsentState(
                adStorage = ConsentValue.DENIED,
                analyticsStorage = ConsentValue.DENIED,
                adPersonalization = ConsentValue.DENIED,
                adUserData = ConsentValue.DENIED,
                functionalityStorage = ConsentValue.DENIED,
                personalizationStorage = ConsentValue.DENIED,
                securityStorage = ConsentValue.DENIED
            )
        )
    }
    
    override suspend fun reset() {
        logger.info { "Resetting consent to default state" }
        updateConsent(defaultConsent)
        storage.clearConsent()
    }
    
    override fun shouldProcessEvent(eventName: String): Boolean {
        val consent = _consentState.value
        
        // Analytics storage must be granted for any event processing
        if (consent.analyticsStorage != ConsentValue.GRANTED) {
            logger.debug(eventName) {
                "Event {eventName} blocked - analytics consent not granted"
            }
            return false
        }
        
        // Additional checks for specific event types
        val allowed = when (eventName) {
            // Ad-related events require ad storage consent
            "ad_click", "ad_impression", "ad_query", "ad_exposure" -> {
                val hasAdConsent = consent.adStorage == ConsentValue.GRANTED
                if (!hasAdConsent) {
                    logger.debug(eventName) {
                        "Ad event {eventName} blocked - ad storage consent not granted"
                    }
                }
                hasAdConsent
            }
            
            // Purchase events might require ad_user_data for enhanced conversions
            "purchase", "add_to_cart", "begin_checkout" -> 
                consent.analyticsStorage == ConsentValue.GRANTED
            
            // All other events just need analytics storage
            else -> true
        }
        
        if (allowed) {
            logger.debug(eventName) { "Event {eventName} allowed by consent" }
        }
        
        return allowed
    }
    
    override fun getCurrentConsent(): ConsentState = _consentState.value
    
    private fun updateDerivedStates(consent: ConsentState) {
        _analyticsEnabled.value = consent.analyticsStorage == ConsentValue.GRANTED
        _advertisingEnabled.value = consent.adStorage == ConsentValue.GRANTED
    }
}