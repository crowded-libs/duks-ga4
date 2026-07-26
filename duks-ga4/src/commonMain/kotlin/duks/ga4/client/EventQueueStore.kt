package duks.ga4.client

import duks.ga4.model.BatchedEvent

/**
 * Optional durable backing for the in-memory event queue.
 *
 * The library does **not** require persistence. Provide an implementation when you
 * need events to survive process death. Prefer **kotlin-lmdb** (multiplatform) if
 * shipping a durable backend — do not introduce alternate storage stacks in-library.
 *
 * Typical use:
 * ```kotlin
 * class LmdbEventQueueStore(...) : EventQueueStore { ... }
 * val client = GA4Client(config, scope = scope, eventQueueStore = store)
 * ```
 */
interface EventQueueStore {
    /** Load previously persisted events (order preserved). */
    suspend fun loadPending(): List<BatchedEvent>

    /**
     * Replace the durable snapshot with [events].
     * Called after enqueue, successful send, requeue, or clear.
     */
    suspend fun persist(events: List<BatchedEvent>)

    /** Remove all durable events. */
    suspend fun clear()
}

/**
 * In-memory [EventQueueStore] (no cross-process durability). Useful for tests.
 */
class InMemoryEventQueueStore : EventQueueStore {
    private var pending: List<BatchedEvent> = emptyList()

    override suspend fun loadPending(): List<BatchedEvent> = pending

    override suspend fun persist(events: List<BatchedEvent>) {
        pending = events.toList()
    }

    override suspend fun clear() {
        pending = emptyList()
    }
}
