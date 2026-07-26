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
import kotlin.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Result of delivering a batch to the transport layer.
 */
sealed class BatchDeliveryResult {
    data object Success : BatchDeliveryResult()
    data class Failure(
        val cause: Throwable,
        /** Events that should be retried (already filtered by caller if needed). */
        val retriable: List<BatchedEvent> = emptyList()
    ) : BatchDeliveryResult()
}

/**
 * Lightweight counters for queue / send observability.
 */
data class BatcherStats(
    val sent: Long = 0,
    val failed: Long = 0,
    val requeued: Long = 0,
    val dropped: Long = 0
)

/**
 * Manages event batching and automatic flushing for GA4 events.
 *
 * Delivery callback reports success/failure; events are only considered complete
 * after a successful delivery. Failures are requeued up to [GA4Config.maxRetries].
 */
class EventBatcher(
    private val config: GA4Config,
    private val onBatchReady: suspend (List<BatchedEvent>) -> BatchDeliveryResult,
    private val flushInterval: Duration = 10.seconds,
    private val maxQueueSize: Int = 1000,
    private val scope: CoroutineScope,
    /**
     * Optional durable snapshot store. When set, the queue is restored on [restoreFromStore]
     * and rewritten after enqueue / flush / requeue / clear.
     */
    private val eventQueueStore: EventQueueStore? = null
) {
    private val logger = Logger.default()
    private val eventQueue = mutableListOf<BatchedEvent>()
    private val queueMutex = Mutex()
    private val flushMutex = Mutex()

    private var flushJob: Job? = null
    private val _queueSize = MutableStateFlow(0)
    val queueSize: StateFlow<Int> = _queueSize.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _stats = MutableStateFlow(BatcherStats())
    val stats: StateFlow<BatcherStats> = _stats.asStateFlow()

    init {
        logger.info(config.maxEventsPerBatch, flushInterval.inWholeSeconds, maxQueueSize) {
            "EventBatcher initialized with maxEventsPerBatch: {maxEventsPerBatch}, flushInterval: {flushInterval}s, maxQueueSize: {maxQueueSize}"
        }
        if (flushInterval.inWholeMilliseconds > 0) {
            startAutoFlush()
        }
    }

    /**
     * Adds an event to the queue.
     * @return false if the queue is full
     */
    /**
     * Loads pending events from [eventQueueStore] into the in-memory queue.
     * Safe to call once after construction (e.g. from [GA4Client] init).
     */
    suspend fun restoreFromStore() {
        val store = eventQueueStore ?: return
        val pending = store.loadPending()
        if (pending.isEmpty()) return
        queueMutex.withLock {
            eventQueue.clear()
            eventQueue.addAll(pending)
            _queueSize.value = eventQueue.size
        }
        logger.info(pending.size) {
            "Restored {count} events from durable queue store"
        }
        if (eventQueue.size >= config.maxEventsPerBatch) {
            scope.launch { flush() }
        }
    }

    suspend fun addEvent(
        event: GA4Event,
        clientId: String? = null,
        userId: String? = null,
        userProperties: Map<String, duks.ga4.model.UserPropertyValue>? = null
    ): Boolean {
        val shouldFlush: Boolean
        val added = queueMutex.withLock {
            if (eventQueue.size >= maxQueueSize) {
                _stats.value = _stats.value.copy(dropped = _stats.value.dropped + 1)
                return@withLock false
            }

            eventQueue.add(
                BatchedEvent(
                    event = event,
                    queuedAt = Clock.System.now(),
                    clientId = clientId,
                    userId = userId,
                    userProperties = userProperties
                )
            )
            _queueSize.value = eventQueue.size

            logger.debug(event.name, eventQueue.size) {
                "Event {eventName} added to queue, current size: {queueSize}"
            }
            true
        }
        if (!added) return false
        persistSnapshot()
        shouldFlush = queueMutex.withLock { eventQueue.size >= config.maxEventsPerBatch }
        if (shouldFlush) {
            scope.launch { flush() }
        }
        return true
    }

    /**
     * Adds multiple events to the queue.
     * @return number of events actually enqueued
     */
    suspend fun addEvents(
        events: List<GA4Event>,
        clientId: String? = null,
        userId: String? = null,
        userProperties: Map<String, duks.ga4.model.UserPropertyValue>? = null
    ): Int {
        val addedCount = queueMutex.withLock {
            var count = 0
            for (event in events) {
                if (eventQueue.size >= maxQueueSize) {
                    val remaining = events.size - count
                    _stats.value = _stats.value.copy(dropped = _stats.value.dropped + remaining)
                    break
                }
                eventQueue.add(
                    BatchedEvent(
                        event = event,
                        queuedAt = Clock.System.now(),
                        clientId = clientId,
                        userId = userId,
                        userProperties = userProperties
                    )
                )
                count++
            }
            _queueSize.value = eventQueue.size
            logger.debug(count, events.size, eventQueue.size) {
                "Added {addedCount} of {totalEvents} events to queue, current size: {queueSize}"
            }
            count
        }
        if (addedCount > 0) persistSnapshot()
        val shouldFlush = queueMutex.withLock { eventQueue.size >= config.maxEventsPerBatch }
        if (shouldFlush) {
            scope.launch { flush() }
        }
        return addedCount
    }

    /**
     * Flushes up to [GA4Config.maxEventsPerBatch] events.
     * Concurrent flushes are serialized; only successful deliveries leave the queue permanently.
     */
    suspend fun flush() {
        // Single-flight: skip if another flush is in progress
        if (!flushMutex.tryLock()) {
            logger.debug { "Flush already in progress, skipping concurrent flush" }
            return
        }

        try {
            val eventsToSend = queueMutex.withLock {
                if (eventQueue.isEmpty()) {
                    logger.debug { "Flush called but queue is empty, skipping" }
                    return
                }

                val batch = eventQueue.take(config.maxEventsPerBatch.coerceAtMost(eventQueue.size))
                eventQueue.removeAll(batch.toSet())
                _queueSize.value = eventQueue.size

                logger.debug(batch.size, eventQueue.size) {
                    "Flushing {batchSize} events, {remainingInQueue} remaining in queue"
                }
                batch
            }

            if (eventsToSend.isEmpty()) return

            _isProcessing.value = true
            try {
                logger.debug(eventsToSend.size) {
                    "Processing batch of {batchSize} events"
                }

                when (val result = onBatchReady(eventsToSend)) {
                    is BatchDeliveryResult.Success -> {
                        _stats.value = _stats.value.copy(
                            sent = _stats.value.sent + eventsToSend.size
                        )
                        persistSnapshot()
                        logger.debug(eventsToSend.size) {
                            "Successfully processed batch of {batchSize} events"
                        }
                    }
                    is BatchDeliveryResult.Failure -> {
                        _stats.value = _stats.value.copy(
                            failed = _stats.value.failed + eventsToSend.size
                        )
                        logger.error(result.cause, eventsToSend.size) {
                            "Failed to process batch of {batchSize} events"
                        }
                        if (config.enableRetry) {
                            val toRequeue = (result.retriable.ifEmpty { eventsToSend })
                                .filter { it.retryCount < config.maxRetries }
                            if (toRequeue.isNotEmpty()) {
                                requeueEvents(toRequeue)
                            } else {
                                _stats.value = _stats.value.copy(
                                    dropped = _stats.value.dropped + eventsToSend.size
                                )
                                persistSnapshot()
                            }
                        } else {
                            persistSnapshot()
                        }
                    }
                }
            } catch (e: Exception) {
                // Unexpected throw from callback — treat as failure and requeue
                _stats.value = _stats.value.copy(failed = _stats.value.failed + eventsToSend.size)
                logger.error(e, eventsToSend.size) {
                    "Failed to process batch of {batchSize} events"
                }
                if (config.enableRetry) {
                    val toRequeue = eventsToSend.filter { it.retryCount < config.maxRetries }
                    if (toRequeue.isNotEmpty()) {
                        requeueEvents(toRequeue)
                    } else {
                        persistSnapshot()
                    }
                } else {
                    persistSnapshot()
                }
            } finally {
                _isProcessing.value = false
            }
        } finally {
            flushMutex.unlock()
        }
    }

    /**
     * Flushes all remaining events in the queue (multiple batches if needed).
     */
    suspend fun flushAll() {
        logger.info { "Flushing all events from queue" }
        var guard = 0
        val maxIterations = 10_000
        while (guard++ < maxIterations) {
            val hasEvents = queueMutex.withLock { eventQueue.isNotEmpty() }
            if (!hasEvents) break
            flush()
        }
        logger.info(queueMutex.withLock { eventQueue.size }) {
            "Completed flushAll, remaining in queue: {remaining}"
        }
    }

    /**
     * Clears all events from the queue without sending.
     */
    suspend fun clear() {
        queueMutex.withLock {
            val sizeBefore = eventQueue.size
            eventQueue.clear()
            _queueSize.value = 0
            _stats.value = _stats.value.copy(dropped = _stats.value.dropped + sizeBefore)
            logger.info(sizeBefore) {
                "Cleared {clearedCount} events from queue"
            }
        }
        eventQueueStore?.clear() ?: persistSnapshot()
    }

    /**
     * Returns failed events back to the front of the queue for retry.
     */
    suspend fun requeueEvents(events: List<BatchedEvent>) {
        queueMutex.withLock {
            val eventsToRequeue = events
                .filter { it.retryCount < config.maxRetries }
                .map { it.copy(retryCount = it.retryCount + 1) }

            logger.debug(eventsToRequeue.size, events.size, config.maxRetries) {
                "Requeuing {requeueCount} of {totalEvents} events (max retries: {maxRetries})"
            }

            eventQueue.addAll(0, eventsToRequeue)
            _queueSize.value = eventQueue.size
            _stats.value = _stats.value.copy(requeued = _stats.value.requeued + eventsToRequeue.size)
        }
        persistSnapshot()
    }

    private suspend fun persistSnapshot() {
        val store = eventQueueStore ?: return
        val snapshot = queueMutex.withLock { eventQueue.toList() }
        store.persist(snapshot)
    }

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
                logger.debug { "Auto-flush job cancelled" }
                throw e
            }
        }
    }

    /**
     * Stops the batcher and cleans up resources.
     */
    suspend fun stop() {
        flushJob?.let { job ->
            job.cancel()
            job.join()
        }
        flushJob = null
        logger.debug { "EventBatcher stopped" }
    }

    /**
     * Gets the current queue size.
     */
    suspend fun getQueueSize(): Int = queueMutex.withLock {
        eventQueue.size
    }
}
