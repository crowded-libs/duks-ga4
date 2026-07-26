package duks.ga4.client

import duks.ga4.TestUtils
import duks.ga4.model.BatchedEvent
import duks.ga4.model.GA4Event
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class EventQueueStoreTest {

    @Test
    fun `batcher restores and persists via EventQueueStore`() = runTest(timeout = 5.seconds) {
        val store = InMemoryEventQueueStore()
        store.persist(
            listOf(
                BatchedEvent(
                    event = GA4Event("page_view"),
                    queuedAt = Clock.System.now(),
                    clientId = "1.2"
                )
            )
        )

        val config = TestUtils.createTestConfig()
        val batcher = EventBatcher(
            config = config,
            onBatchReady = { BatchDeliveryResult.Success },
            flushInterval = 0.seconds,
            scope = backgroundScope,
            eventQueueStore = store
        )
        batcher.restoreFromStore()
        assertEquals(1, batcher.getQueueSize())

        batcher.addEvent(TestUtils.createTestEvent("second"), "1.2")
        assertEquals(2, batcher.getQueueSize())
        assertEquals(2, store.loadPending().size)

        batcher.flushAll()
        assertEquals(0, batcher.getQueueSize())
        assertEquals(0, store.loadPending().size)

        batcher.stop()
    }
}
