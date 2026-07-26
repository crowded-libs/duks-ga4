package duks.ga4.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Representation of an event with metadata for batching
 */
@Serializable
data class BatchedEvent(
    /**
     * The actual GA4 event
     */
    val event: GA4Event,
    
    /**
     * Timestamp when the event was queued
     */
    val queuedAt: Instant,
    
    /**
     * Optional client ID for this specific event
     */
    val clientId: String? = null,
    
    /**
     * Optional user ID for this specific event
     */
    val userId: String? = null,

    /**
     * Optional user properties captured when the event was queued
     */
    @Serializable(with = UserPropertiesSerializer::class)
    val userProperties: Map<String, UserPropertyValue>? = null,
    
    /**
     * Number of retry attempts for this event
     */
    val retryCount: Int = 0,
    
    /**
     * Unique identifier for deduplication
     */
    val eventId: String = generateEventId(),
    
    /**
     * Consent state at the time of event creation
     */
    val consentState: ConsentState? = null
) {
    companion object {
        /**
         * Generates a unique event ID for deduplication
         */
        private fun generateEventId(): String {
            return "${kotlin.time.Clock.System.now().toEpochMilliseconds()}_${(0..999999).random()}"
        }
    }
}