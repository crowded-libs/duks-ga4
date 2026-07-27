package duks.ga4.client

import duks.ga4.TestUtils
import duks.ga4.config.GA4Config
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import duks.ga4.model.GA4Request
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class GA4ClientTest {
    
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
    
    private lateinit var config: GA4Config
    private lateinit var client: GA4Client
    private val capturedRequests = mutableListOf<Pair<String, String>>() // URL to body
    
    @BeforeTest
    fun setup() {
        config = TestUtils.createTestConfig()
        capturedRequests.clear()
    }
    
    @AfterTest
    fun teardown() =  runTest(timeout = 5.seconds) {
        if (::client.isInitialized) {
            client.close()
        }
    }
    
    @Test
    fun `should send single event immediately to GA4 endpoint`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            assertEquals("https://www.google-analytics.com/mp/collect", request.url.toString().substringBefore("?"))
            assertTrue(request.url.parameters.contains("measurement_id", config.measurementId))
            assertTrue(request.url.parameters.contains("api_secret", config.apiSecret))
            
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        // Send event immediately
        val event = TestUtils.createTestEvent("test_immediate")
        val result = client.sendEvent(
            event = event,
            clientId = "test-client",
            immediate = true
        )
        
        // Verify success
        assertTrue(result.isSuccess)
    }
    
    @Test
    fun `should queue single event for batch processing`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        // Send event (batched)
        val event = TestUtils.createTestEvent("test_batched")
        val result = client.sendEvent(
            event = event,
            clientId = "test-client",
            immediate = false
        )
        
        // Verify result
        assertTrue(result.isSuccess)
        
        // Verify event is queued
        val queueSize = client.getQueueSize()
        assertEquals(1, queueSize)
        
        // Flush and verify
        client.flush()
        delay(100) // Allow async processing
        assertEquals(0, client.getQueueSize())
    }
    
    @Test
    fun `should send multiple events in batches when exceeding batch size`() =  runTest(timeout = 5.seconds) {
        var requestCount = 0
        val mockEngine = MockEngine { request ->
            requestCount++
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        // Create more events than batch size
        val events = TestUtils.createTestEvents(30)
        
        val result = client.sendEvents(
            events = events,
            clientId = "test-client",
            immediate = true
        )
        
        assertTrue(result.isSuccess)
        // Should be sent in 2 batches (25 + 5)
        assertEquals(2, requestCount)
    }
    
    @Test
    fun `should auto-generate client ID when not provided and auto-generation enabled`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            // Verify a client ID was generated
            val body = request.body.toByteArray().decodeToString()
            val ga4Request = json.decodeFromString<GA4Request>(body)
            assertTrue(ga4Request.clientId.isNotEmpty())
            
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        // Test auto-generation
        val configWithAutoGen = config.copy(autoGenerateClientId = true)
        client = GA4Client(configWithAutoGen, mockEngine, backgroundScope)
        
        val event = TestUtils.createTestEvent()
        val result = client.sendEvent(event, immediate = true)
        
        assertTrue(result.isSuccess)
    }
    
    @Test
    fun `should fail when no client ID provided and auto-generation disabled`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        // Test failure when no client ID and no auto-generation
        val configNoAutoGen = config.copy(
            autoGenerateClientId = false,
            defaultClientId = null
        )
        client = GA4Client(configNoAutoGen, mockEngine, backgroundScope)
        
        val event = TestUtils.createTestEvent()
        val result = client.sendEvent(event, immediate = true)
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
    
    @Test
    fun `should return error when sending empty events list`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        val result = client.sendEvents(
            events = emptyList(),
            clientId = "test-client"
        )
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
    
    @Test
    fun `should reject events when queue reaches maximum capacity`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        // Fill queue to capacity
        val events = TestUtils.createTestEvents(1000)
        val result = client.sendEvents(events, "test-client")
        
        assertTrue(result.isSuccess)
        
        // Try to add more - should fail
        val moreEvents = TestUtils.createTestEvents(100)
        val overflowResult = client.sendEvents(moreEvents, "test-client")
        
        assertTrue(overflowResult.isFailure)
        assertTrue(overflowResult.exceptionOrNull() is IllegalStateException)
    }
    
    @Test
    fun `should send all queued events when flush is called`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        // Add events
        repeat(5) { i ->
            client.sendEvent(
                TestUtils.createTestEvent("event_$i"),
                "test-client"
            )
        }
        
        assertEquals(5, client.getQueueSize())
        
        // Flush
        client.flush()
        
        // Wait a bit for async processing
        delay(200)
        
        assertEquals(0, client.getQueueSize())
    }
    
    @Test
    fun `should use live collect endpoint and attach debug_mode when debug mode is enabled`() =
        runTest(timeout = 5.seconds) {
            var capturedBody: String? = null
            val mockEngine =
                MockEngine { request ->
                    assertEquals(
                        "https://www.google-analytics.com/mp/collect",
                        request.url.toString().substringBefore("?"),
                    )
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(content = "", status = HttpStatusCode.NoContent)
                }

            val debugConfig = config.copy(debugMode = true)
            client = GA4Client(debugConfig, mockEngine, backgroundScope)

            val event = TestUtils.createTestEvent()
            val result = client.sendEvent(event, "test-client", immediate = true)

            assertTrue(result.isSuccess)
            assertNotNull(capturedBody)
            assertTrue(
                capturedBody!!.contains("debug_mode"),
                "debugMode should attach debug_mode event param for DebugView: $capturedBody",
            )
        }
    
    @Test
    fun `should use custom endpoint when configured`() =  runTest(timeout = 5.seconds) {
        val customEndpoint = "https://custom.analytics.example.com/collect"
        val mockEngine = MockEngine { request ->
            // Verify custom endpoint is used
            assertEquals(customEndpoint, request.url.toString().substringBefore("?"))
            
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        val customConfig = config.copy(customEndpoint = customEndpoint)
        client = GA4Client(customConfig, mockEngine, backgroundScope)
        
        val event = TestUtils.createTestEvent()
        val result = client.sendEvent(event, "test-client", immediate = true)
        
        assertTrue(result.isSuccess)
    }
    
    @Test
    fun `should retry failed requests up to configured retry limit`() =  runTest(timeout = 5.seconds) {
        var attemptCount = 0
        val mockEngine = MockEngine { request ->
            attemptCount++
            if (attemptCount <= 2) {
                respond(
                    content = "Server error",
                    status = HttpStatusCode.InternalServerError
                )
            } else {
                respond(
                    content = "",
                    status = HttpStatusCode.NoContent
                )
            }
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        val event = TestUtils.createTestEvent()
        val result = client.sendEvent(event, "test-client", immediate = true)
        
        // Should succeed after retries
        assertTrue(result.isSuccess)
        // Should have made 3 attempts (initial + 2 retries)
        assertEquals(3, attemptCount)
    }
    
    @Test
    fun `should include user ID in request when provided`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            // Verify user ID is included
            val body = request.body.toByteArray().decodeToString()
            val ga4Request = json.decodeFromString<GA4Request>(body)
            assertEquals("user-123", ga4Request.userId)
            
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        val event = TestUtils.createTestEvent()
        val result = client.sendEvent(
            event = event,
            clientId = "test-client",
            userId = "user-123",
            immediate = true
        )
        
        assertTrue(result.isSuccess)
    }
    
    @Test
    fun `should group events by client ID when batching`() =  runTest(timeout = 5.seconds) {
        val sentRequests = mutableListOf<GA4Request>()
        val mockEngine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            val ga4Request = json.decodeFromString<GA4Request>(body)
            sentRequests.add(ga4Request)
            
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        // Send events with different client IDs
        client.sendEvent(TestUtils.createTestEvent("event1"), "client1")
        client.sendEvent(TestUtils.createTestEvent("event2"), "client2")
        client.sendEvent(TestUtils.createTestEvent("event3"), "client1")
        
        assertEquals(3, client.getQueueSize())
        
        // Flush should group by client ID
        client.flush()
        
        delay(200)
        assertEquals(0, client.getQueueSize())
        
        // Should have made 2 requests (one for each client ID)
        assertEquals(2, sentRequests.size)
        
        // Verify grouping
        val client1Request = sentRequests.find { it.clientId == "client1" }
        val client2Request = sentRequests.find { it.clientId == "client2" }
        
        assertNotNull(client1Request)
        assertNotNull(client2Request)
        assertEquals(2, client1Request.events.size)
        assertEquals(1, client2Request.events.size)
    }
    
    @Test
    fun `should flush remaining events when client is closed`() =  runTest(timeout = 5.seconds) {
        var requestMade = false
        val mockEngine = MockEngine { request ->
            requestMade = true
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        // Add some events
        client.sendEvent(TestUtils.createTestEvent(), "test-client")
        
        // Close should flush remaining events
        client.close()
        
        // Verify flush was called
        delay(200)
        assertTrue(requestMade)
    }
    
    @Test
    fun `should handle large batch of events correctly`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        // Create a large batch of events
        val events = TestUtils.createTestEvents(100)
        
        val result = client.sendEvents(
            events = events,
            clientId = "test-client",
            immediate = false
        )
        
        assertTrue(result.isSuccess)
        
        // Should be added to queue
        assertTrue(client.getQueueSize() > 0)
    }
    
    @Test
    fun `should serialize complex event parameters correctly`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            // Verify complex params are serialized correctly
            val body = request.body.toByteArray().decodeToString()
            assertTrue(body.contains("complex_event"))
            assertTrue(body.contains("string_param"))
            assertTrue(body.contains("number_param"))
            assertTrue(body.contains("boolean_param"))
            assertTrue(body.contains("items"))
            
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        val complexEvent = GA4Event(
            name = "complex_event",
            params = mapOf(
                "string_param" to EventParamValue.StringValue("test"),
                "number_param" to EventParamValue.NumberValue(42.5),
                "boolean_param" to EventParamValue.BooleanValue(true),
                "items_param" to EventParamValue.ItemsValue(
                    listOf(TestUtils.createTestItem())
                )
            )
        )
        
        val result = client.sendEvent(complexEvent, "test-client", immediate = true)
        
        assertTrue(result.isSuccess)
    }
    
    @Test
    fun `should fail request when timeout is exceeded`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            // Simulate slow response
            delay(200)
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        val slowConfig = config.copy(requestTimeoutMs = 100)
        client = GA4Client(slowConfig, mockEngine, backgroundScope)
        
        val event = TestUtils.createTestEvent()
        val result = client.sendEvent(event, "test-client", immediate = true)
        
        // Should fail due to timeout
        assertTrue(result.isFailure)
    }
    
    @Test
    fun `should handle concurrent event sending without data loss`() =  runTest(timeout = 5.seconds) {
        val mockEngine = MockEngine { request ->
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
        
        client = GA4Client(config, mockEngine, backgroundScope)
        
        // Send multiple events concurrently
        val results = coroutineScope {
            List(10) { index ->
                async {
                    client.sendEvent(
                        TestUtils.createTestEvent("concurrent_$index"),
                        "test-client"
                    )
                }
            }.map { it.await() }
        }
        
        // All should succeed
        assertTrue(results.all { it.isSuccess })
    }
}