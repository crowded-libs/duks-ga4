package duks.ga4.client

import duks.ga4.model.GA4Event

/**
 * Interface for GA4 client operations
 */
interface IGA4Client {
    /**
     * Sends a single event to GA4
     */
    suspend fun sendEvent(
        event: GA4Event,
        clientId: String? = null,
        userId: String? = null,
        immediate: Boolean = false
    ): Result<Unit>
    
    /**
     * Sends multiple events to GA4
     */
    suspend fun sendEvents(
        events: List<GA4Event>,
        clientId: String? = null,
        userId: String? = null,
        immediate: Boolean = false
    ): Result<Unit>
    
    /**
     * Flushes all pending events
     */
    suspend fun flush()
    
    /**
     * Gets the current number of events in the queue
     */
    suspend fun getQueueSize(): Int
    
    /**
     * Closes the client and releases resources
     */
    suspend fun close()
}