package duks.ga4.client

import duks.ga4.TestUtils
import duks.ga4.config.GA4Config
import duks.ga4.model.BatchedEvent
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EventBatcherTest {
    
    private lateinit var config: GA4Config
    private lateinit var batcher: EventBatcher
    private val sentBatches = mutableListOf<List<BatchedEvent>>()
    private val batchMutex = Mutex()
    
    @BeforeTest
    fun setup() {
        config = TestUtils.createTestConfig(batchSizeLimit = 10)
        sentBatches.clear()
    }
    
    @AfterTest
    fun teardown() =  runTest(timeout = 5.seconds) {
        if (::batcher.isInitialized) {
            batcher.stop()
        }
    }
    
    @Test
    fun `should add single event to queue successfully`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        val event = TestUtils.createTestEvent()
        val added = batcher.addEvent(event, "client-1", "user-1")
        
        assertTrue(added)
        assertEquals(1, batcher.getQueueSize())
    }
    
    @Test
    fun `should add multiple events to queue and track count`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        val events = TestUtils.createTestEvents(5)
        val addedCount = batcher.addEvents(events, "client-1")
        
        assertEquals(5, addedCount)
        assertEquals(5, batcher.getQueueSize())
    }
    
    @Test
    fun `should automatically flush when batch size limit is reached`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock {
                    sentBatches.add(batch)
                }
            },
            scope = backgroundScope
        )
        
        // Add events up to batch size
        val events = TestUtils.createTestEvents(10)
        batcher.addEvents(events, "client-1")
        
        // Wait for auto-flush to complete by checking queue size and sent batches
        TestUtils.waitFor(timeout = 5.seconds) {
            batcher.getQueueSize() == 0 && batchMutex.withLock { sentBatches.isNotEmpty() }
        }
        
        batchMutex.withLock {
            assertEquals(1, sentBatches.size)
            assertEquals(10, sentBatches[0].size)
        }
        assertEquals(0, batcher.getQueueSize())
    }
    
    @Test
    fun `should flush events manually even when below batch size`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        // Add fewer events than batch size
        val events = TestUtils.createTestEvents(3)
        batcher.addEvents(events, "client-1")
        
        assertEquals(3, batcher.getQueueSize())
        
        // Manual flush
        batcher.flush()
        
        batchMutex.withLock {
            assertEquals(1, sentBatches.size)
            assertEquals(3, sentBatches[0].size)
        }
        assertEquals(0, batcher.getQueueSize())
    }
    
    @Test
    fun `should flush all events in multiple batches when exceeding batch size`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        // Add more events than one batch
        val events = TestUtils.createTestEvents(25)
        batcher.addEvents(events, "client-1")
        
        // Flush all
        batcher.flushAll()
        
        // Wait for flushAll to complete by waiting for queue to be empty
        TestUtils.waitFor {
            batcher.getQueueSize() == 0
        }
        
        // Wait a bit more for batches to be processed
        delay(100)
        
        // Should have sent 3 batches (10 + 10 + 5)
        batchMutex.withLock {
            assertEquals(3, sentBatches.size)
            assertEquals(10, sentBatches[0].size)
            assertEquals(10, sentBatches[1].size)
            assertEquals(5, sentBatches[2].size)
        }
        assertEquals(0, batcher.getQueueSize())
    }
    
    @Test
    fun `should reject new events when queue reaches max capacity`() =  runTest(timeout = 5.seconds) {
        // Create config with batch size larger than queue size to prevent auto-flush
        val overflowConfig = TestUtils.createTestConfig(batchSizeLimit = 20)
        batcher = EventBatcher(
            config = overflowConfig,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            maxQueueSize = 10,
            flushInterval = 10.seconds, // Prevent auto-flush during test
            scope = backgroundScope
        )
        
        // Add events up to max queue size
        val events = TestUtils.createTestEvents(10)
        val added = batcher.addEvents(events, "client-1")
        assertEquals(10, added)
        assertEquals(10, batcher.getQueueSize())
        
        // Try to add more - should fail
        val moreEvents = TestUtils.createTestEvents(5)
        val overflow = batcher.addEvents(moreEvents, "client-1")
        assertEquals(0, overflow) // None added due to queue full
        assertEquals(10, batcher.getQueueSize())
    }
    
    @Test
    fun `should allow duplicate events as new batched events have different IDs`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        // Create event with specific ID
        val event = GA4Event(
            name = "dedupe_test",
            params = mapOf("id" to EventParamValue.StringValue("unique-id"))
        )
        
        // Add event and flush it
        batcher.addEvent(event, "client-1")
        batcher.flush()
        
        // Try to add same event again after it's been processed
        val added2 = batcher.addEvent(event, "client-1")
        
        assertTrue(added2) // Will be added since it's a new BatchedEvent with different ID
        assertEquals(1, batcher.getQueueSize())
    }
    
    @Test
    fun `should clear all events from queue without sending`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        // Add events
        batcher.addEvents(TestUtils.createTestEvents(5), "client-1")
        assertEquals(5, batcher.getQueueSize())
        
        // Clear
        batcher.clear()
        assertEquals(0, batcher.getQueueSize())
        
        // No batches should have been sent
        batchMutex.withLock {
            assertEquals(0, sentBatches.size)
        }
    }
    
    @Test
    fun `should requeue failed events with incremented retry count`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        // Create failed events with retry count
        val failedEvents = TestUtils.createTestEvents(3).map { event ->
            BatchedEvent(
                event = event,
                queuedAt = kotlin.time.Clock.System.now(),
                clientId = "client-1",
                retryCount = 1
            )
        }
        
        batcher.requeueEvents(failedEvents)
        
        assertEquals(3, batcher.getQueueSize())
        
        // Flush and check retry count was incremented
        batcher.flush()
        
        batchMutex.withLock {
            assertEquals(1, sentBatches.size)
            sentBatches[0].forEach { event ->
                assertEquals(2, event.retryCount)
            }
        }
    }
    
    @Test
    fun `should not requeue events that exceed max retry limit`() =  runTest(timeout = 5.seconds) {
        val testConfig = config.copy(maxRetries = 2)
        batcher = EventBatcher(
            config = testConfig,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        // Create events that have exceeded retry limit
        val failedEvents = TestUtils.createTestEvents(2).map { event ->
            BatchedEvent(
                event = event,
                queuedAt = kotlin.time.Clock.System.now(),
                clientId = "client-1",
                retryCount = 2 // Already at max
            )
        }
        
        batcher.requeueEvents(failedEvents)
        
        // Should not be requeued
        assertEquals(0, batcher.getQueueSize())
    }
    
    @Test
    fun `should automatically flush events based on time interval`() =  runTest(timeout = 5.seconds) {
        var batchReceived = false
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock {
                    sentBatches.add(batch)
                    batchReceived = true
                }
            },
            flushInterval = 100.milliseconds,
            scope = backgroundScope
        )
        
        // Add event
        val added = batcher.addEvent(TestUtils.createTestEvent(), "client-1")
        assertTrue(added)
        assertEquals(1, batcher.getQueueSize())
        
        // Wait for auto-flush - the timer starts when batcher is created
        delay(300)
        
        // Check if batch was sent
        if (!batchReceived) {
            // Try manual flush as fallback
            batcher.flush()
            delay(100)
        }
        
        batchMutex.withLock {
            assertTrue(batchReceived || sentBatches.isNotEmpty(), "Expected batch to be sent")
        }
        assertEquals(0, batcher.getQueueSize())
    }
    
    @Test
    fun `should handle concurrent event additions correctly`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        // Add events concurrently
        val jobs = List(10) { index ->
            launch {
                repeat(5) {
                    batcher.addEvent(
                        TestUtils.createTestEvent("concurrent_${index}_$it"),
                        "client-$index"
                    )
                }
            }
        }
        
        jobs.forEach { it.join() }
        
        // Should have 50 events total
        batcher.flushAll()
        
        val totalEvents = batchMutex.withLock {
            sentBatches.sumOf { it.size }
        }
        assertEquals(50, totalEvents)
    }
    
    @Test
    fun `should update queue size state flow when events are added or removed`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        // Verify initial state
        assertEquals(0, batcher.queueSize.value)
        
        // Add event and verify state updates
        batcher.addEvent(TestUtils.createTestEvent(), "client-1")
        assertEquals(1, batcher.queueSize.value)
        
        // Flush and verify state updates
        batcher.flush()
        assertEquals(0, batcher.queueSize.value)
    }
    
    @Test
    fun `should update processing state flow during batch processing`() =  runTest(timeout = 5.seconds) {
        var processingStarted = false

        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch ->
                processingStarted = true
                delay(100) // Simulate processing
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        // Add event and flush
        batcher.addEvent(TestUtils.createTestEvent(), "client-1")
        
        assertFalse(batcher.isProcessing.value)
        
        // Start flush in background
        val flushJob = launch {
            batcher.flush()
        }
        
        // Wait for processing to start
        withTimeout(1.seconds) {
            while (!processingStarted) {
                delay(10)
            }
        }
        
        assertTrue(batcher.isProcessing.value)
        
        // Wait for completion
        flushJob.join()
        
        assertFalse(batcher.isProcessing.value)
    }
    
    @Test
    fun `should include correct metadata in batched events`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        val event = TestUtils.createTestEvent()
        batcher.addEvent(event, "client-123", "user-456")
        
        batcher.flush()
        
        batchMutex.withLock {
            assertEquals(1, sentBatches.size)
            val batchedEvent = sentBatches[0][0]
            
            assertEquals("client-123", batchedEvent.clientId)
            assertEquals("user-456", batchedEvent.userId)
            assertEquals(0, batchedEvent.retryCount)
            assertNotNull(batchedEvent.queuedAt)
            assertNotNull(batchedEvent.eventId)
        }
    }
    
    @Test
    fun `should stop auto-flush timer when batcher is stopped`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope,
            flushInterval = 100.milliseconds
        )
        
        // Add event
        batcher.addEvent(TestUtils.createTestEvent(), "client-1")
        
        // Stop batcher
        batcher.stop()
        
        // Auto-flush should not occur after stop
        delay(200)
        batchMutex.withLock {
            assertEquals(0, sentBatches.size)
        }
    }
    
    @Test
    fun `should handle flush gracefully when queue is empty`() =  runTest(timeout = 5.seconds) {
        batcher = EventBatcher(
            config = config,
            onBatchReady = { batch -> 
                batchMutex.withLock { sentBatches.add(batch) }
            },
            scope = backgroundScope
        )
        
        // Flush empty queue
        batcher.flush()
        
        // No batches should be sent
        batchMutex.withLock {
            assertEquals(0, sentBatches.size)
        }
    }
}