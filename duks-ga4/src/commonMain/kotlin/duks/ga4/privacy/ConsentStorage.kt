package duks.ga4.privacy

import duks.ga4.model.ConsentState

/**
 * Interface for persisting consent state across app sessions
 */
interface ConsentStorage {
    /**
     * Saves the consent state to persistent storage
     */
    suspend fun saveConsent(consent: ConsentState)
    
    /**
     * Loads the consent state from persistent storage
     */
    suspend fun loadConsent(): ConsentState?
    
    /**
     * Clears the saved consent state
     */
    suspend fun clearConsent()
    
    /**
     * Gets the timestamp of the last consent update
     */
    suspend fun getLastUpdateTimestamp(): Long?
    
    /**
     * Saves the timestamp of the last consent update
     */
    suspend fun saveLastUpdateTimestamp(timestamp: Long)
}

/**
 * In-memory implementation of ConsentStorage for testing
 */
class InMemoryConsentStorage : ConsentStorage {
    private var storedConsent: ConsentState? = null
    private var lastUpdateTimestamp: Long? = null
    
    override suspend fun saveConsent(consent: ConsentState) {
        storedConsent = consent
    }
    
    override suspend fun loadConsent(): ConsentState? = storedConsent
    
    override suspend fun clearConsent() {
        storedConsent = null
        lastUpdateTimestamp = null
    }
    
    override suspend fun getLastUpdateTimestamp(): Long? = lastUpdateTimestamp
    
    override suspend fun saveLastUpdateTimestamp(timestamp: Long) {
        lastUpdateTimestamp = timestamp
    }
}