package duks.ga4.config

import duks.ga4.model.ConsentState
import duks.ga4.privacy.PiiScrubberConfig

/**
 * Configuration for Google Analytics 4 client
 */
data class GA4Config(
    /**
     * The GA4 Measurement ID (e.g., "G-XXXXXXXXXX")
     */
    val measurementId: String,
    
    /**
     * The API secret for server-side tracking
     */
    val apiSecret: String,
    
    /**
     * The default client ID to use if none is provided
     */
    val defaultClientId: String? = null,
    
    /**
     * Whether to enable debug mode for validation
     */
    val debugMode: Boolean = false,
    
    /**
     * Default consent state to apply to all requests
     */
    val defaultConsent: ConsentState? = null,
    
    /**
     * Whether to automatically generate client IDs if not provided
     */
    val autoGenerateClientId: Boolean = true,
    
    /**
     * Custom endpoint URL (optional, uses default GA4 endpoint if not specified)
     */
    val customEndpoint: String? = null,
    
    /**
     * Request timeout in milliseconds
     */
    val requestTimeoutMs: Long = 30_000L,
    
    /**
     * Maximum number of events per batch (GA4 limit is 25)
     */
    val maxEventsPerBatch: Int = 25,
    
    /**
     * Whether to retry failed requests
     */
    val enableRetry: Boolean = true,
    
    /**
     * Maximum number of retry attempts
     */
    val maxRetries: Int = 3,
    
    /**
     * Initial retry delay in milliseconds
     */
    val retryDelayMs: Long = 1_000L,
    
    /**
     * Privacy configuration
     */
    val privacyConfig: PrivacyConfig = PrivacyConfig()
) {
    init {
        require(measurementId.isNotBlank()) { "Measurement ID cannot be blank" }
        require(apiSecret.isNotBlank()) { "API Secret cannot be blank" }
        require(maxEventsPerBatch in 1..25) { "Max events per batch must be between 1 and 25" }
        require(requestTimeoutMs > 0) { "Request timeout must be positive" }
        require(maxRetries >= 0) { "Max retries cannot be negative" }
        require(retryDelayMs >= 0) { "Retry delay cannot be negative" }
    }
    
    companion object {
        /**
         * Default GA4 Measurement Protocol endpoint
         */
        const val DEFAULT_ENDPOINT = "https://www.google-analytics.com/mp/collect"
        
        /**
         * Debug validation endpoint
         */
        const val DEBUG_ENDPOINT = "https://www.google-analytics.com/debug/mp/collect"
    }
}

/**
 * Privacy configuration for GA4
 */
data class PrivacyConfig(
    /**
     * Whether to enforce consent before processing events
     */
    val enforceConsent: Boolean = true,
    
    /**
     * Whether to scrub PII from events
     */
    val scrubPii: Boolean = true,
    
    /**
     * PII scrubber configuration
     */
    val piiScrubberConfig: PiiScrubberConfig = PiiScrubberConfig(),
    
    /**
     * Whether to store events locally for privacy actions
     */
    val enableEventStore: Boolean = false,
    
    /**
     * Maximum number of events to store locally
     */
    val maxStoredEvents: Int = 10_000,
    
    /**
     * How long to retain events in days (0 = indefinite)
     */
    val eventRetentionDays: Int = 30
)