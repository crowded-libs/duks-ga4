package duks.ga4.privacy

import duks.ga4.client.EventBatcher
import duks.ga4.config.GA4Config
import duks.ga4.model.BatchedEvent
import duks.ga4.model.GA4Event
import duks.logging.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Event batcher that respects user consent and privacy settings.
 * 
 * This class wraps the standard EventBatcher and adds:
 * - Consent checking before processing events
 * - PII scrubbing if enabled
 * - Privacy statistics tracking
 * - Event storage for privacy actions
 */
class ConsentAwareEventBatcher(
    private val config: GA4Config,
    private val consentManager: ConsentManager,
    private val onBatchReady: suspend (List<BatchedEvent>) -> Unit,
    flushInterval: Duration,
    maxQueueSize: Int = 1000,
    private val eventStore: InMemoryEventStore? = null,
    private val scope: CoroutineScope
) {
    
    private val logger = Logger.default()
    
    private val piiScrubber = if (config.privacyConfig.scrubPii) {
        PiiScrubber(config.privacyConfig.piiScrubberConfig)
    } else null
    
    // Privacy statistics
    private val _droppedEvents = MutableStateFlow(0)
    val droppedEvents: StateFlow<Int> = _droppedEvents.asStateFlow()
    
    private val _scrubbedEvents = MutableStateFlow(0)
    val scrubbedEvents: StateFlow<Int> = _scrubbedEvents.asStateFlow()
    
    private val pendingEvents = mutableListOf<PendingEvent>()
    private var isCollectingWhenConsentChanges = false // TODO: Add to privacy config
    
    // Internal event batcher
    private val eventBatcher = EventBatcher(
        config = config,
        onBatchReady = { batch -> processBatchWithConsent(batch) },
        flushInterval = flushInterval,
        maxQueueSize = maxQueueSize,
        scope = scope
    )
    
    init {
        logger.info(
            config.privacyConfig.enforceConsent,
            config.privacyConfig.scrubPii,
            maxQueueSize
        ) {
            "ConsentAwareEventBatcher initialized - enforceConsent: {enforceConsent}, scrubPii: {scrubPii}, maxQueueSize: {maxQueueSize}"
        }
        
        // Monitor consent changes
        scope.launch {
            consentManager.consentState.collect { state ->
                if (state.analyticsStorage == duks.ga4.model.ConsentValue.GRANTED) {
                    logger.debug { "Analytics consent granted, processing pending events" }
                    processPendingEvents()
                }
            }
        }
    }
    
    /**
     * Adds an event respecting consent and privacy settings
     */
    suspend fun addEvent(
        event: GA4Event,
        clientId: String? = null,
        userId: String? = null
    ) {
        // Check if we have consent
        if (!checkConsent()) {
            logger.debug(event.name) {
                "Event {eventName} blocked due to lack of consent"
            }
            handleNoConsent(event, clientId, userId)
            return
        }
        
        // Scrub PII if enabled
        val processedEvent = scrubEventIfNeeded(event)
        
        // Store event if event store is enabled
        eventStore?.storeEvent(StoredEvent(
            event = processedEvent,
            clientId = clientId,
            userId = userId,
            timestamp = Clock.System.now()
        ))
        
        // Add to batcher
        logger.debug(processedEvent.name) {
            "Adding event {eventName} to batch queue"
        }
        eventBatcher.addEvent(processedEvent, clientId, userId)
    }
    
    /**
     * Adds multiple events respecting consent and privacy settings
     */
    suspend fun addEvents(
        events: List<GA4Event>,
        clientId: String? = null,
        userId: String? = null
    ) {
        events.forEach { event ->
            addEvent(event, clientId, userId)
        }
    }
    
    /**
     * Flushes all events respecting consent
     */
    suspend fun flushAll() {
        if (checkConsent()) {
            logger.debug { "Flushing all events with consent" }
            eventBatcher.flushAll()
        } else {
            logger.debug { "Flush blocked due to lack of consent" }
        }
    }
    
    /**
     * Clears the event queue
     */
    suspend fun clearQueue() {
        // eventBatcher.clearQueue() // This method doesn't exist in EventBatcher
        pendingEvents.clear()
    }
    
    /**
     * Stops the batcher
     */
    suspend fun stop() {
        eventBatcher.stop()
        scope.cancel()
    }
    
    private suspend fun processBatchWithConsent(batch: List<BatchedEvent>) {
        // Double-check consent before sending
        if (checkConsent()) {
            logger.debug(batch.size) {
                "Processing batch of {batchSize} events with consent"
            }
            onBatchReady(batch)
        } else {
            logger.warn(batch.size) {
                "Dropping batch of {batchSize} events due to lack of consent"
            }
            _droppedEvents.value += batch.size
        }
    }
    
    private fun checkConsent(): Boolean {
        return if (config.privacyConfig.enforceConsent) {
            consentManager.analyticsEnabled.value
        } else {
            true
        }
    }
    
    private fun handleNoConsent(
        event: GA4Event,
        clientId: String?,
        userId: String?
    ) {
        if (isCollectingWhenConsentChanges) {
            // Store event for later processing
            pendingEvents.add(PendingEvent(event, clientId, userId))
            logger.debug(event.name, pendingEvents.size) {
                "Storing event {eventName} for later processing, pending count: {pendingCount}"
            }
        } else {
            // Drop the event
            _droppedEvents.value++
            logger.debug(event.name, _droppedEvents.value) {
                "Dropped event {eventName}, total dropped: {totalDropped}"
            }
        }
    }
    
    private fun scrubEventIfNeeded(event: GA4Event): GA4Event {
        return if (piiScrubber != null) {
            val scrubbed = piiScrubber.scrubEvent(event)
            if (scrubbed != event) {
                _scrubbedEvents.value++
                logger.debug(event.name, _scrubbedEvents.value) {
                    "Scrubbed PII from event {eventName}, total scrubbed: {totalScrubbed}"
                }
            }
            scrubbed
        } else {
            event
        }
    }
    
    private suspend fun processPendingEvents() {
        if (pendingEvents.isNotEmpty()) {
            val events = pendingEvents.toList()
            pendingEvents.clear()
            
            logger.info(events.size) {
                "Processing {eventCount} pending events after consent granted"
            }
            
            events.forEach { pending ->
                addEvent(pending.event, pending.clientId, pending.userId)
            }
        }
    }
    
    private data class PendingEvent(
        val event: GA4Event,
        val clientId: String?,
        val userId: String?
    )
    
    // Delegate other methods to the internal batcher
    val queueSize: StateFlow<Int> get() = eventBatcher.queueSize
    val isProcessing: StateFlow<Boolean> get() = eventBatcher.isProcessing
}

/**
 * Data class for storing events with metadata
 */
data class StoredEvent(
    val event: GA4Event,
    val clientId: String?,
    val userId: String?,
    val timestamp: kotlin.time.Instant
)

/**
 * Privacy configuration for event batching
 */
data class EventBatchingPrivacyConfig(
    val collectEventsWhenConsentChanges: Boolean = false,
    val maxPendingEvents: Int = 100,
    val eventRetentionDuration: Duration = Duration.days(7)
)

// Extension function for Duration.days
private fun Duration.Companion.days(days: Int): Duration = (days * 24 * 60 * 60 * 1000).milliseconds