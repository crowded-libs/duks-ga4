package duks.ga4.client

import duks.ga4.config.GA4Config
import duks.ga4.model.BatchedEvent
import duks.ga4.model.GA4Event
import duks.logging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Manages event batching and automatic flushing for GA4 events
 */
class EventBatcher(
    private val config: GA4Config,
    private val onBatchReady: suspend (List<BatchedEvent>) -> Unit,
    private val flushInterval: Duration = 10.seconds,
    private val maxQueueSize: Int = 1000,
    private val scope: CoroutineScope
) {
    private val logger = Logger.default()
    private val eventQueue = mutableListOf<BatchedEvent>()
    private val queueMutex = Mutex()
    private val processedEventIds = mutableSetOf<String>()
    private val deduplicationWindow = 100_000 // Keep last 100k event IDs for deduplication
    
    private var flushJob: Job? = null
    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    
    init {
        logger.info(config.maxEventsPerBatch, flushInterval.inWholeSeconds, maxQueueSize) {
            "EventBatcher initialized with maxEventsPerBatch: {maxEventsPerBatch}, flushInterval: {flushInterval}s, maxQueueSize: {maxQueueSize}"
        }
        // Only start auto-flush if interval is reasonable (> 0)
        if (flushInterval.inWholeMilliseconds > 0) {
            startAutoFlush()
        }
    }
    
    /**
     * Adds an event to the queue
     */
    suspend fun addEvent(
        event: GA4Event,
        clientId: String? = null,
        userId: String? = null
    ): Boolean = queueMutex.withLock {
        if (eventQueue.size >= maxQueueSize) {
            return@withLock false
        }
        
        val batchedEvent = BatchedEvent(
            event = event,
            queuedAt = Clock.System.now(),
            clientId = clientId,
            userId = userId
        )
        
        // Check for duplicates
        if (batchedEvent.eventId in processedEventIds) {
            logger.debug(event.name, batchedEvent.eventId) {
                "Duplicate event detected, skipping: {eventName} (id: {eventId})"
            }
            return@withLock false
        }
        
        eventQueue.add(batchedEvent)
        _queueSize.value = eventQueue.size
        
        logger.debug(event.name, eventQueue.size) {
            "Event {eventName} added to queue, current size: {queueSize}"
        }
        
        // Trigger flush if we've reached the batch size limit
        if (eventQueue.size >= config.maxEventsPerBatch) {
            logger.debug(eventQueue.size, config.maxEventsPerBatch) {
                "Queue size {queueSize} reached batch limit {maxBatchSize}, triggering flush"
            }
            scope.launch { flush() }
        }
        
        true
    }
    
    /**
     * Adds multiple events to the queue
     */
    suspend fun addEvents(
        events: List<GA4Event>,
        clientId: String? = null,
        userId: String? = null
    ): Int = queueMutex.withLock {
        var addedCount = 0
        
        for (event in events) {
            if (eventQueue.size >= maxQueueSize) {
                break
            }
            
            val batchedEvent = BatchedEvent(
                event = event,
                queuedAt = Clock.System.now(),
                clientId = clientId,
                userId = userId
            )
            
            if (batchedEvent.eventId !in processedEventIds) {
                eventQueue.add(batchedEvent)
                addedCount++
            } else {
                logger.debug(event.name, batchedEvent.eventId) {
                    "Duplicate event in batch detected, skipping: {eventName} (id: {eventId})"
                }
            }
        }
        
        _queueSize.value = eventQueue.size
        
        logger.debug(addedCount, events.size, eventQueue.size) {
            "Added {addedCount} of {totalEvents} events to queue, current size: {queueSize}"
        }
        
        // Trigger flush if needed
        if (eventQueue.size >= config.maxEventsPerBatch) {
            logger.debug(eventQueue.size, config.maxEventsPerBatch) {
                "Queue size {queueSize} reached batch limit {maxBatchSize}, triggering flush"
            }
            scope.launch { flush() }
        }
        
        addedCount
    }
    
    /**
     * Manually triggers a flush of the event queue
     */
    suspend fun flush() {
        val eventsToSend = queueMutex.withLock {
            if (eventQueue.isEmpty()) {
                logger.debug { "Flush called but queue is empty, skipping" }
                return
            }
            
            // Take up to maxEventsPerBatch events
            val batch = eventQueue.take(config.maxEventsPerBatch.coerceAtMost(eventQueue.size))
            eventQueue.removeAll(batch)
            _queueSize.value = eventQueue.size
            
            logger.debug(batch.size, eventQueue.size) {
                "Flushing {batchSize} events, {remainingInQueue} remaining in queue"
            }
            
            batch
        }
        
        if (eventsToSend.isNotEmpty()) {
            _isProcessing.value = true
            try {
                logger.debug(eventsToSend.size) {
                    "Processing batch of {batchSize} events"
                }
                
                // Send the batch
                onBatchReady(eventsToSend)
                
                // Mark events as processed for deduplication
                queueMutex.withLock {
                    eventsToSend.forEach { event ->
                        processedEventIds.add(event.eventId)
                    }
                    
                    // Clean up old event IDs to prevent memory growth
                    if (processedEventIds.size > deduplicationWindow) {
                        val toRemove = processedEventIds.size - deduplicationWindow
                        logger.debug(toRemove, processedEventIds.size) {
                            "Cleaning up {toRemove} old event IDs from deduplication cache (total: {totalIds})"
                        }
                        processedEventIds.take(toRemove).forEach {
                            processedEventIds.remove(it)
                        }
                    }
                }
                
                logger.debug(eventsToSend.size) {
                    "Successfully processed batch of {batchSize} events"
                }
            } catch (e: Exception) {
                logger.error(e, eventsToSend.size) {
                    "Failed to process batch of {batchSize} events"
                }
                throw e
            } finally {
                _isProcessing.value = false
            }
        }
    }
    
    /**
     * Flushes all remaining events in the queue
     */
    suspend fun flushAll() {
        logger.info { "Flushing all events from queue" }
        var totalFlushed = 0
        while (true) {
            val hasEvents = queueMutex.withLock { eventQueue.isNotEmpty() }
            if (!hasEvents) break
            val sizeBefore = queueMutex.withLock { eventQueue.size }
            flush()
            totalFlushed += sizeBefore - queueMutex.withLock { eventQueue.size }
        }
        logger.info(totalFlushed) {
            "Completed flushing all events, total flushed: {totalFlushed}"
        }
    }
    
    /**
     * Clears all events from the queue without sending
     */
    suspend fun clear() = queueMutex.withLock {
        val sizeBefore = eventQueue.size
        eventQueue.clear()
        _queueSize.value = 0
        logger.info(sizeBefore) {
            "Cleared {clearedCount} events from queue"
        }
    }
    
    /**
     * Returns failed events back to the queue for retry
     */
    suspend fun requeueEvents(events: List<BatchedEvent>) = queueMutex.withLock {
        val eventsToRequeue = events.filter { it.retryCount < config.maxRetries }
            .map { it.copy(retryCount = it.retryCount + 1) }
        
        logger.debug(eventsToRequeue.size, events.size, config.maxRetries) {
            "Requeuing {requeueCount} of {totalEvents} events (max retries: {maxRetries})"
        }
        
        // Add to front of queue for priority processing
        eventQueue.addAll(0, eventsToRequeue)
        _queueSize.value = eventQueue.size
        
        // Remove from processed IDs so they can be retried
        eventsToRequeue.forEach {
            processedEventIds.remove(it.eventId)
        }
    }
    
    /**
     * Starts the automatic flush timer
     */
    private fun startAutoFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            try {
                while (isActive) {
                    delay(flushInterval)
                    if (isActive) {
                        flush()
                    }
                }
            } catch (e: CancellationException) {
                // Expected when stopping
                logger.debug { "Auto-flush job cancelled" }
                throw e
            }
        }
    }
    
    /**
     * Stops the batcher and cleans up resources
     */
    suspend fun stop() {
        flushJob?.let { job ->
            job.cancel()
            job.join()
        }
        flushJob = null
        logger.debug { "EventBatcher stopped" }
        // Note: We don't cancel the scope as it may be shared with other components
    }
    
    /**
     * Gets the current queue size
     */
    suspend fun getQueueSize(): Int = queueMutex.withLock {
        eventQueue.size
    }
}