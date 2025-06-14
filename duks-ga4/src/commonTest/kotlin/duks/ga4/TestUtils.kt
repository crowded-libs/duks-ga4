package duks.ga4

import duks.ga4.client.IGA4Client
import duks.ga4.config.GA4Config
import duks.ga4.model.*
import duks.ga4.privacy.ConsentStorage
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test RouterState for testing routing analytics
 */
data class RouterState(
    val path: String,
    val parameters: Map<String, String>? = null,
    val previous: String? = null,
    val isModalRoute: Boolean = false,
    val modalTypeValue: String? = null
)

/**
 * Test utilities and mock implementations
 */
object TestUtils {
    
    /**
     * Creates a test GA4 configuration
     */
    fun createTestConfig(
        measurementId: String = "G-TEST123",
        apiSecret: String = "test-secret",
        debugMode: Boolean = false,
        enableRetry: Boolean = true,
        maxRetries: Int = 3,
        batchSizeLimit: Int = 25,
        customEndpoint: String? = null
    ): GA4Config {
        return GA4Config(
            measurementId = measurementId,
            apiSecret = apiSecret,
            debugMode = debugMode,
            enableRetry = enableRetry,
            maxRetries = maxRetries,
            maxEventsPerBatch = batchSizeLimit,
            customEndpoint = customEndpoint
        )
    }
    
    /**
     * Creates a test GA4 event
     */
    fun createTestEvent(
        name: String = "test_event",
        params: Map<String, EventParamValue> = mapOf(
            "test_param" to EventParamValue.StringValue("test_value")
        )
    ): GA4Event {
        return GA4Event(name = name, params = params)
    }
    
    /**
     * Creates multiple test events
     */
    fun createTestEvents(count: Int): List<GA4Event> {
        return List(count) { index ->
            createTestEvent(
                name = "test_event_$index",
                params = mapOf(
                    "index" to EventParamValue.NumberValue(index.toDouble()),
                    "random" to EventParamValue.NumberValue(Random.nextDouble())
                )
            )
        }
    }
    
    /**
     * Creates a test item
     */
    fun createTestItem(
        itemId: String = "test-item-${Random.nextInt(1000)}",
        itemName: String = "Test Item",
        price: Double = 9.99,
        quantity: Int = 1
    ): Item {
        return Item(
            itemId = itemId,
            itemName = itemName,
            price = price,
            quantity = quantity,
            itemCategory = "Test Category",
            itemBrand = "Test Brand"
        )
    }
    
    /**
     * Creates a mock HTTP client for testing
     */
    fun createMockHttpClient(
        responseHandler: MockRequestHandler = { request ->
            respond(
                content = "",
                status = HttpStatusCode.NoContent
            )
        }
    ): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = false
                    isLenient = true
                })
            }
            
            engine {
                addHandler(responseHandler)
            }
        }
    }
    
    /**
     * Creates a mock HTTP client with request capturing
     */
    fun createCapturingMockHttpClient(): Pair<HttpClient, MutableList<GA4Request>> {
        val capturedRequests = mutableListOf<GA4Request>()
        
        val client = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = false
                    isLenient = true
                })
            }
            
            engine {
                addHandler { request ->
                    val content = request.body.toByteArray().decodeToString()
                    val ga4Request = Json.decodeFromString<GA4Request>(content)
                    capturedRequests.add(ga4Request)
                    
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent
                    )
                }
            }
        }
        
        return client to capturedRequests
    }
    
    /**
     * Creates a failing mock HTTP client
     */
    fun createFailingMockHttpClient(
        statusCode: HttpStatusCode = HttpStatusCode.InternalServerError,
        errorMessage: String = "Server error"
    ): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json()
            }
            
            engine {
                addHandler { request ->
                    respond(
                        content = errorMessage,
                        status = statusCode
                    )
                }
            }
        }
    }
    
    /**
     * Creates a flaky mock HTTP client that fails intermittently
     */
    fun createFlakyMockHttpClient(
        failureRate: Double = 0.5,
        failureStatus: HttpStatusCode = HttpStatusCode.InternalServerError
    ): HttpClient {
        var requestCount = 0
        
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json()
            }
            
            engine {
                addHandler { request ->
                    requestCount++
                    if (Random.nextDouble() < failureRate) {
                        respond(
                            content = "Random failure",
                            status = failureStatus
                        )
                    } else {
                        respond(
                            content = "",
                            status = HttpStatusCode.NoContent
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Asserts that two GA4 events are equal
     */
    fun assertEventsEqual(expected: GA4Event, actual: GA4Event) {
        assertEquals(expected.name, actual.name, "Event names should match")
        assertEquals(expected.params.size, actual.params.size, "Event params size should match")
        
        expected.params.forEach { (key, expectedValue) ->
            val actualValue = actual.params[key]
            assertEquals(expectedValue, actualValue, "Param '$key' should match")
        }
    }
    
    /**
     * Asserts that a list contains an event with the given name
     */
    fun assertContainsEvent(events: List<GA4Event>, eventName: String) {
        assertTrue(
            events.any { it.name == eventName },
            "Events should contain an event named '$eventName'"
        )
    }
    
    /**
     * Waits for a condition to be true with timeout
     */
    suspend fun waitForCondition(
        timeoutMs: Long = 5000,
        intervalMs: Long = 100,
        condition: () -> Boolean
    ): Boolean {
        val startTime = Clock.System.now().toEpochMilliseconds()
        
        while (Clock.System.now().toEpochMilliseconds() - startTime < timeoutMs) {
            if (condition()) {
                return true
            }
            delay(intervalMs)
        }
        
        return false
    }
    
    /**
     * Generic waiting utility for suspend conditions with Duration support
     */
    suspend fun waitFor(
        timeout: Duration = 2.seconds,
        pollInterval: Duration = 10.milliseconds,
        condition: suspend () -> Boolean
    ) {
        withTimeout(timeout) {
            while (!condition()) {
                delay(pollInterval)
            }
        }
    }
}

/**
 * Mock consent storage for testing
 */
class MockConsentStorage : ConsentStorage {
    private var storedConsent: ConsentState? = null
    private var lastUpdateTimestamp: Long? = null
    var saveCallCount = 0
    var loadCallCount = 0
    var clearCallCount = 0
    
    override suspend fun saveConsent(consent: ConsentState) {
        saveCallCount++
        storedConsent = consent
    }
    
    override suspend fun loadConsent(): ConsentState? {
        loadCallCount++
        return storedConsent
    }
    
    override suspend fun clearConsent() {
        clearCallCount++
        storedConsent = null
        lastUpdateTimestamp = null
    }
    
    override suspend fun saveLastUpdateTimestamp(timestamp: Long) {
        lastUpdateTimestamp = timestamp
    }
    
    override suspend fun getLastUpdateTimestamp(): Long? {
        return lastUpdateTimestamp
    }
}

/**
 * Test event mapper
 */
class TestEventMapper<TState> : duks.ga4.middleware.EventMapper<TState> {
    val mappedActions = mutableListOf<Any>()
    @kotlin.concurrent.Volatile var mapBeforeCallCount = 0
    @kotlin.concurrent.Volatile var mapAfterCallCount = 0
    
    // Flow to track when mapAfter is called
    private val _mapAfterFlow = MutableSharedFlow<Unit>()
    val mapAfterFlow = _mapAfterFlow
    
    override suspend fun mapActionBefore(action: Any, state: TState): List<GA4Event> {
        mapBeforeCallCount++
        mappedActions.add(action)
        return if (action is TestAction.TrackedBefore) {
            listOf(TestUtils.createTestEvent("before_${action.id}"))
        } else {
            emptyList()
        }
    }
    
    override suspend fun mapActionAfter(action: Any, state: TState): List<GA4Event> {
        mapAfterCallCount++
        mappedActions.add(action)
        _mapAfterFlow.emit(Unit)
        return when (action) {
            is TestAction.Tracked -> listOf(
                TestUtils.createTestEvent("tracked_${action.id}")
            )
            is TestAction.MultiTracked -> List(action.count) { index ->
                TestUtils.createTestEvent("multi_${action.id}_$index")
            }
            else -> emptyList()
        }
    }
}

/**
 * Test actions for middleware testing
 */
sealed class TestAction : duks.Action {
    data class Tracked(val id: String) : TestAction()
    data class TrackedBefore(val id: String) : TestAction()
    data class MultiTracked(val id: String, val count: Int) : TestAction()
    data class Load(val id: String) : TestAction()
    object NotTracked : TestAction()
}

/**
 * Test user actions
 */
sealed class UserAction : duks.Action {
    data class Login(val method: String, val userId: String) : UserAction()
    object Logout : UserAction()
    data class SignUp(val method: String) : UserAction()
    data class ProfileUpdate(val updatedFields: List<String>) : UserAction()
}

/**
 * Test commerce actions
 */
sealed class CommerceAction : duks.Action {
    data class ViewItem(val itemId: String, val itemName: String, val category: String, val price: Double) : CommerceAction()
    data class AddToCart(val itemId: String, val itemName: String, val quantity: Int, val price: Double) : CommerceAction()
    data class Purchase(val transactionId: String, val totalValue: Double, val currency: String, val itemCount: Int) : CommerceAction()
    data class BeginCheckout(val value: Double, val currency: String, val itemCount: Int) : CommerceAction()
}

/**
 * Test state flow that can be controlled
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class TestStateFlow<T>(initial: T) : StateFlow<T> {
    private val flow = MutableStateFlow(initial)
    
    override val replayCache: List<T>
        get() = flow.replayCache
    
    override val value: T
        get() = flow.value
    
    override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<T>): Nothing {
        flow.collect(collector)
    }
    
    fun emit(value: T) {
        flow.value = value
    }
}

/**
 * Mock GA4Client for testing
 */
class MockGA4Client : duks.ga4.client.IGA4Client {
    val sentEvents = mutableListOf<GA4Event>()
    val sentBatches = mutableListOf<List<GA4Event>>()
    var failNextSend = false
    var sendEventCallCount = 0
    var sendEventsCallCount = 0
    var flushCallCount = 0
    
    // Flow to track when events are sent
    private val _eventFlow = MutableSharedFlow<GA4Event>()
    val eventFlow = _eventFlow
    
    override suspend fun sendEvent(
        event: GA4Event,
        clientId: String?,
        userId: String?,
        immediate: Boolean
    ): Result<Unit> {
        sendEventCallCount++
        if (failNextSend) {
            failNextSend = false
            return Result.failure(Exception("Mock send failure"))
        }
        sentEvents.add(event)
        _eventFlow.emit(event)
        return Result.success(Unit)
    }
    
    override suspend fun sendEvents(
        events: List<GA4Event>,
        clientId: String?,
        userId: String?,
        immediate: Boolean
    ): Result<Unit> {
        sendEventsCallCount++
        if (failNextSend) {
            failNextSend = false
            return Result.failure(Exception("Mock send failure"))
        }
        sentEvents.addAll(events)
        sentBatches.add(events)
        // Emit each event to the flow
        events.forEach { _eventFlow.emit(it) }
        return Result.success(Unit)
    }
    
    override suspend fun flush() {
        flushCallCount++
        // Do nothing in mock
    }
    
    override suspend fun getQueueSize(): Int = 0
    
    override suspend fun close() {
        // Do nothing in mock
    }
}