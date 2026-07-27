package duks.ga4.config

import duks.ga4.model.ConsentState
import duks.ga4.privacy.PiiScrubberConfig
import duks.ga4.util.ClientIdStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How the client handles Measurement Protocol validation issues.
 */
enum class ValidationMode {
    /** Skip client-side validation. */
    OFF,

    /** Log issues; drop reserved events; sanitize overlong values. Default. */
    LOG,

    /** Fail the send with [duks.ga4.client.GA4ValidationException]. */
    STRICT
}

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
     * When true, events are sent to the live `/mp/collect` endpoint with a
     * `debug_mode=1` event parameter so they appear in GA4 DebugView.
     *
     * Note: the separate `/debug/mp/collect` URL is a validation-only server
     * and does **not** surface in DebugView — do not use it for live debugging.
     */
    val debugMode: Boolean = false,
    
    /**
     * Default consent state to apply to all requests (MP wire: ad_user_data + ad_personalization)
     */
    val defaultConsent: ConsentState? = null,
    
    /**
     * Whether to automatically generate client IDs if not provided.
     * Generated IDs are stable for the process lifetime (and [clientIdStore] if set).
     */
    val autoGenerateClientId: Boolean = true,

    /**
     * Optional store for persisting auto-generated client IDs across restarts.
     * Not required; when null, IDs are stable only within the process.
     */
    val clientIdStore: ClientIdStore? = null,
    
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
    val privacyConfig: PrivacyConfig = PrivacyConfig(),

    /**
     * Client-side event validation mode
     */
    val validationMode: ValidationMode = ValidationMode.LOG,

    /**
     * When true (default), attach session_id and engagement_time_msec to events
     * that do not already set them (required for Realtime / engaged sessions).
     */
    val attachSessionParams: Boolean = true,

    /**
     * Session idle timeout used by the default session manager.
     */
    val sessionTimeout: Duration = 30.minutes,

    /**
     * Default engagement_time_msec when not provided on the event (minimum useful value is 1).
     */
    val defaultEngagementTimeMsec: Long = 100L,

    /**
     * Prefer page_view over screen_view for web streams (logs/warns on screen_view).
     */
    val preferPageViewForWeb: Boolean = true,

    /**
     * How often the client event queue auto-flushes. Middleware should use this
     * (via the client) rather than owning a separate batcher.
     */
    val flushInterval: Duration = 10.seconds
) {
    init {
        require(measurementId.isNotBlank()) { "Measurement ID cannot be blank" }
        require(apiSecret.isNotBlank()) { "API Secret cannot be blank" }
        require(maxEventsPerBatch in 1..25) { "Max events per batch must be between 1 and 25" }
        require(requestTimeoutMs > 0) { "Request timeout must be positive" }
        require(maxRetries >= 0) { "Max retries cannot be negative" }
        require(retryDelayMs >= 0) { "Retry delay cannot be negative" }
        require(defaultEngagementTimeMsec >= 0) { "Default engagement time cannot be negative" }
    }
    
    companion object {
        /**
         * Default GA4 Measurement Protocol endpoint
         */
        const val DEFAULT_ENDPOINT = "https://www.google-analytics.com/mp/collect"

        /**
         * EU regional collect endpoint
         */
        const val EU_ENDPOINT = "https://region1.google-analytics.com/mp/collect"
        
        /**
         * Validation-only endpoint (returns validation messages; does not ingest
         * events and will not show in DebugView). Prefer [debugMode] + live collect.
         */
        const val DEBUG_ENDPOINT = "https://www.google-analytics.com/debug/mp/collect"
    }
}

/**
 * Privacy configuration for GA4.
 *
 * Defaults are **opt-in** (consent not enforced, PII not scrubbed) so the client
 * sends events without privacy wiring. Enable via [duks.ga4.middleware.GA4MiddlewareBuilder.enablePrivacy]
 * or by setting these flags explicitly.
 */
data class PrivacyConfig(
    /**
     * Whether to enforce consent before processing events (requires a ConsentManager on the client).
     */
    val enforceConsent: Boolean = false,
    
    /**
     * Whether to scrub PII from events on the send path.
     */
    val scrubPii: Boolean = false,
    
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
