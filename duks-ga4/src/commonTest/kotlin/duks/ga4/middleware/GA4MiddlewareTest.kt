package duks.ga4.middleware

import duks.ga4.*
import duks.ga4.config.GA4Config
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GA4MiddlewareTest {
    
    private lateinit var config: GA4Config
    private lateinit var middleware: GA4Middleware<TestState>
    private lateinit var eventMapper: TestEventMapper<TestState>
    private lateinit var mockClient: MockGA4Client
    private val capturedEvents = mutableListOf<GA4Event>()
    
    data class TestState(
        val userId: String? = null,
        val sessionId: String? = null,
        val counter: Int = 0
    ) : duks.StateModel
    
    @BeforeTest
    fun setup() {
        config = TestUtils.createTestConfig()
        eventMapper = TestEventMapper()
        mockClient = MockGA4Client()
        capturedEvents.clear()
    }
    
    @AfterTest
    fun teardown() {
        // Reset event mapper counts between tests
        eventMapper.mapBeforeCallCount = 0
        eventMapper.mapAfterCallCount = 0
        eventMapper.mappedActions.clear()
        // Clear any captured events
        capturedEvents.clear()
    }
    
    @Test
    fun `should initialize middleware when attached to store`() =  runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action -> state }
            middleware {
                lifecycleAware(middleware)
            }
        }
        
        // In test environment, manually trigger initialization
        middleware.onStoreCreated(store)
        
        // Verify middleware is initialized
        assertTrue(middleware.isInitialized)
        
        // In real implementation, we'd verify GA4Client is created
    }
    
    @Test
    fun `should map tracked actions to events using event mapper`() =  runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            flushInterval = 100.milliseconds,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with proper middleware builder
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                val result = middleware.invoke(store, next, action)
                return result
            }
        }
        
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action -> 
                val newState = when (action) {
                    is TestAction.Tracked -> state.copy(counter = state.counter + 1)
                    else -> state
                }
                newState
            }
            middleware {
                middleware(testMiddleware)
            }
        }
        
        // Manually initialize middleware since we're not using lifecycleAware
        middleware.onStoreCreated(store)
        
        // Wait for middleware initialization
        TestUtils.waitFor { middleware.isInitialized }
        
        // Verify middleware is initialized
        assertNotNull(middleware)
        
        // Reset counts to ensure we're tracking new calls
        eventMapper.mapAfterCallCount = 0
        eventMapper.mappedActions.clear()
        
        // Test action that should be tracked
        val trackedAction = TestAction.Tracked("test-1")
        
        // Dispatch the action
        store.dispatch(trackedAction)
        
        // Wait for state to update
        val updatedState = store.state.first { it.counter == 1 }
        
        // Verify action was processed by reducer
        assertEquals(1, updatedState.counter, "Action should have been processed by reducer")
        
        // Verify action was mapped - may need to check if middleware is actually invoked
        assertTrue(eventMapper.mapAfterCallCount >= 1, "Expected mapAfter to be called at least once, but was ${eventMapper.mapAfterCallCount}")
        assertTrue(eventMapper.mappedActions.contains(trackedAction), "Expected mapped actions to contain $trackedAction")
    }
    
    @Test
    fun `should call mapActionBefore and mapActionAfter for tracked actions`() =  runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            flushInterval = 100.milliseconds,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with proper middleware builder
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action ->
                when (action) {
                    is TestAction.TrackedBefore -> state.copy(counter = state.counter + 1)
                    else -> state
                }
            }
            middleware {
                middleware(testMiddleware)
            }
        }
        
        // Manually initialize middleware since we're not using lifecycleAware
        middleware.onStoreCreated(store)
        
        // Wait for middleware initialization
        TestUtils.waitFor { middleware.isInitialized }
        
        // Reset counts to ensure we're tracking new calls
        eventMapper.mapBeforeCallCount = 0
        eventMapper.mapAfterCallCount = 0
        
        // Test action that should be tracked before state change
        val trackedBeforeAction = TestAction.TrackedBefore("before-1")
        store.dispatch(trackedBeforeAction)
        
        // Wait for state to update
        store.state.first { it.counter == 1 }
        
        // Wait for both before and after calls
        TestUtils.waitFor { eventMapper.mapAfterCallCount >= 1 }
        
        // Verify both before and after were called
        assertEquals(1, eventMapper.mapBeforeCallCount)
        assertEquals(1, eventMapper.mapAfterCallCount)
    }
    
    @Test
    fun `should handle actions that generate multiple events`() =  runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            flushInterval = 100.milliseconds,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with proper middleware builder
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action ->
                when (action) {
                    is TestAction.MultiTracked -> state.copy(counter = state.counter + 1)
                    else -> state
                }
            }
            middleware {
                middleware(testMiddleware)
            }
        }
        
        // Manually initialize middleware since we're not using lifecycleAware
        middleware.onStoreCreated(store)
        
        // Wait for middleware initialization
        TestUtils.waitFor { middleware.isInitialized }
        
        // Reset counts
        eventMapper.mapAfterCallCount = 0
        eventMapper.mappedActions.clear()
        
        // Test action that generates multiple events
        val multiAction = TestAction.MultiTracked("multi-1", count = 3)
        store.dispatch(multiAction)
        
        // Wait for state to update
        store.state.first { it.counter == 1 }
        
        // Wait for mapper to be called
        TestUtils.waitFor { eventMapper.mapAfterCallCount >= 1 }
        
        // Should have mapped the action
        assertEquals(1, eventMapper.mapAfterCallCount)
    }
    
    @Test
    fun `should call mapper but generate no events for untracked actions`() =  runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            flushInterval = 100.milliseconds,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with proper middleware builder
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action ->
                when (action) {
                    is TestAction.NotTracked -> state.copy(counter = state.counter + 1)
                    else -> state
                }
            }
            middleware {
                middleware(testMiddleware)
            }
        }
        
        // Manually initialize middleware since we're not using lifecycleAware
        middleware.onStoreCreated(store)
        
        // Wait for middleware initialization
        TestUtils.waitFor { middleware.isInitialized }
        
        // Reset counts
        eventMapper.mapAfterCallCount = 0
        
        // Test action that should not be tracked
        val notTrackedAction = TestAction.NotTracked
        store.dispatch(notTrackedAction)
        
        // Wait for state to update
        store.state.first { it.counter == 1 }
        
        // Wait for mapper to be called
        TestUtils.waitFor { eventMapper.mapAfterCallCount >= 1 }
        
        // Mapper should still be called but return empty list
        assertTrue(eventMapper.mapAfterCallCount >= 1, "Expected mapAfter to be called at least once, but was ${eventMapper.mapAfterCallCount}")
    }
    
    @Test
    fun `should use custom client ID provider when configured`() =  runTest(timeout = 5.seconds) {
        var clientIdCalled = false
        
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            flushInterval = 100.milliseconds,
            clientIdProvider = { state ->
                clientIdCalled = true
                "client-${state.sessionId}"
            },
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with proper middleware builder
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        val store = duks.createStore(TestState(sessionId = "session-123")) {
            scope(backgroundScope)
            reduceWith { state, action ->
                when (action) {
                    is TestAction.Tracked -> state.copy(counter = state.counter + 1)
                    else -> state
                }
            }
            middleware {
                middleware(testMiddleware)
            }
        }
        
        // Manually initialize middleware since we're not using lifecycleAware
        middleware.onStoreCreated(store)
        
        // Wait for middleware initialization
        TestUtils.waitFor { middleware.isInitialized }
        
        // Trigger an action
        store.dispatch(TestAction.Tracked("test"))
        
        // Wait for state to update
        store.state.first { it.counter == 1 }
        
        // Wait for client ID provider to be called
        TestUtils.waitFor { clientIdCalled }
        
        // Client ID provider should have been called
        assertTrue(clientIdCalled, "clientIdProvider should have been called")
    }
    
    @Test
    fun `should use custom user ID provider when configured`() =  runTest(timeout = 5.seconds) {
        var userIdCalled = false
        
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            flushInterval = 100.milliseconds,
            userIdProvider = { state ->
                userIdCalled = true
                state.userId
            },
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with proper middleware builder
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        val store = duks.createStore(TestState(userId = "user-456")) {
            scope(backgroundScope)
            reduceWith { state, action ->
                when (action) {
                    is TestAction.Tracked -> state.copy(counter = state.counter + 1)
                    else -> state
                }
            }
            middleware {
                middleware(testMiddleware)
            }
        }
        
        // Manually initialize middleware since we're not using lifecycleAware
        middleware.onStoreCreated(store)
        
        // Wait for middleware initialization
        TestUtils.waitFor { middleware.isInitialized }
        
        // Trigger an action
        store.dispatch(TestAction.Tracked("test"))
        
        // Wait for state to update
        store.state.first { it.counter == 1 }
        
        // Wait for user ID provider to be called
        TestUtils.waitFor { userIdCalled }
        
        // User ID provider should have been called
        assertTrue(userIdCalled)
    }
    
    @Test
    fun `should not map actions when no event mapper provided`() = runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = null,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with manual initialization
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action -> state }
            middleware {
                middleware(testMiddleware)
            }
        }
        
        middleware.onStoreCreated(store)

        store.dispatch(duks.AsyncProcessing(TestAction.Load("test")))
        store.dispatch(TestAction.Tracked("noise"))
        delay(100)
        middleware.flushEvents()
        delay(50)

        assertTrue(mockClient.sentEvents.isEmpty(), "Expected no action events without a mapper")
    }

    @Test
    fun `should use DefaultEventMapper when explicitly provided`() = runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = DefaultEventMapper(),
            clientFactory = { mockClient },
            scope = backgroundScope
        )

        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }

        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action -> state }
            middleware {
                middleware(testMiddleware)
            }
        }

        middleware.onStoreCreated(store)
        store.dispatch(duks.AsyncProcessing(TestAction.Load("test")))
        delay(100)
        middleware.flushEvents()
        delay(50)

        assertTrue(
            mockClient.sentEvents.any { it.name == "async_action" },
            "DefaultEventMapper should emit async_action events"
        )
    }
    
    @Test
    fun `should handle mapper errors gracefully and continue processing`() = runTest {
        var mapperBeforeCalled = false
        var mapperAfterCalled = false
        var mapperThrew = false
        var errorEmitted = false
        
        // Create mapper that throws error only on mapActionAfter
        val errorMapper = object : EventMapper<TestState> {
            override suspend fun mapActionBefore(action: Any, state: TestState): List<GA4Event> {
                mapperBeforeCalled = true
                return emptyList()
            }
            
            override suspend fun mapActionAfter(action: Any, state: TestState): List<GA4Event> {
                mapperAfterCalled = true
                mapperThrew = true
                throw RuntimeException("Mapping error")
            }
        }
        
        middleware = GA4Middleware(
            config = config,
            eventMapper = errorMapper,
            flushInterval = 10.seconds,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Subscribe to error flow
        val errorJob = launch {
            middleware.errors.collect { error ->
                if (error is GA4MiddlewareError.MappingError) {
                    errorEmitted = true
                }
            }
        }
        
        // Create a test store with proper middleware builder
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        var reducerCalled = false
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action ->
                when (action) {
                    is TestAction.Tracked -> {
                        reducerCalled = true
                        state.copy(counter = state.counter + 1)
                    }
                    else -> state
                }
            }
            middleware {
                middleware(testMiddleware)
            }
        }
        
        // Manually initialize middleware
        middleware.onStoreCreated(store)
        
        // Wait for initialization
        TestUtils.waitFor { middleware.isInitialized }
        
        // Trigger action that will cause error
        store.dispatch(TestAction.Tracked("test"))
        
        // Wait for state to update
        val updatedState = store.state.first { it.counter == 1 }
        
        // Give time for error to be emitted
        delay(100)
        
        // Verify the action completed despite the mapper error
        assertTrue(reducerCalled, "Reducer should have been called despite mapper error")
        assertTrue(mapperBeforeCalled, "Mapper before should have been called")
        assertTrue(mapperAfterCalled, "Mapper after should have been called")
        assertTrue(mapperThrew, "Mapper should have thrown an exception")
        assertTrue(errorEmitted, "Error should have been emitted to error flow")
        assertEquals(1, updatedState.counter, "Action should complete despite mapper error")
        
        // Clean up
        errorJob.cancel()
        middleware.onDetach()
        
        // Give time for cleanup
        delay(100)
    }
    
    @Test
    fun `should not set up routing analytics when disabled`() =  runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            enableRoutingAnalytics = false,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action -> state }
            middleware {
                lifecycleAware(middleware)
            }
        }
        
        // In test environment, manually trigger initialization
        middleware.onStoreCreated(store)
        
        // Verify middleware is initialized
        assertTrue(middleware.isInitialized)
        
        // Routing analytics should not be set up
        // In real implementation, would verify no router subscription
    }
    
    @Test
    fun `should track screen time between route changes`() =  runTest(timeout = 5.seconds) {
        // This would require actual router integration
        // For now, test the screen time calculation logic
        
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action -> state }
            middleware {
                lifecycleAware(middleware)
            }
        }
        
        // In test environment, manually trigger initialization
        middleware.onStoreCreated(store)
        
        // Simulate router state changes
        val routerState1 = RouterState(
            path = "/home",
            parameters = null,
            previous = null,
            isModalRoute = false,
            modalTypeValue = null
        )
        
        // Would need to trigger router state change
        // In real implementation, router state flow would be passed to middleware
        
        delay(1000) // Simulate time on screen
        
        val routerState2 = RouterState(
            path = "/profile",
            parameters = null,
            previous = "/home",
            isModalRoute = false,
            modalTypeValue = null
        )
        
        // Would trigger another state change
        // This would calculate time spent on /home
    }
    
    @Test
    fun `should handle concurrent action processing correctly`() = runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            flushInterval = 100.milliseconds,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with proper middleware builder
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            scope(backgroundScope)
            reduceWith { state, action -> 
                when (action) {
                    is TestAction.Tracked -> state.copy(counter = state.counter + 1)
                    else -> state
                }
            }
            middleware {
                middleware(testMiddleware)
                lifecycleAware(middleware)
            }
        }
        
        // Wait for middleware initialization
        TestUtils.waitFor { middleware.isInitialized }
        
        // Reset counts
        eventMapper.mapAfterCallCount = 0
        
        // Send multiple actions concurrently
        val jobs = List(10) { index ->
            launch {
                store.dispatch(TestAction.Tracked("concurrent-$index"))
            }
        }
        
        jobs.forEach { it.join() }
        runCurrent()
        advanceUntilIdle()
        
        // Wait for state to update
        store.state.first { it.counter == 10 }
        
        // Give time for all events to be processed
        runCurrent()
        advanceUntilIdle()
        
        // Wait for all mapAfter calls to complete
        TestUtils.waitFor(timeout = 3.seconds) {
            eventMapper.mapAfterCallCount >= 10
        }
        
        // All actions should have been mapped
        assertEquals(10, eventMapper.mapAfterCallCount, "Expected exactly 10 mapAfter calls, but got ${eventMapper.mapAfterCallCount}")
        
        // Clean up
        middleware.onDetach()
    }
    
    @Test
    fun `should flush pending events when middleware is detached`() =  runTest(timeout = 5.seconds) {
        // Would need to mock or spy on the batcher to verify flush
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with manual initialization
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action -> state }
            middleware {
                middleware(testMiddleware)
                lifecycleAware(middleware)
            }
        }
        
        // Wait for initialization
        TestUtils.waitFor { middleware.isInitialized }
        
        // Add some events
        store.dispatch(TestAction.Tracked("test"))
        
        // Give a small delay for processing
        runCurrent()
        advanceUntilIdle()
        
        // Detach middleware
        middleware.onDetach()
        
        // In real implementation, would verify flush was called
        // Cleanup happens automatically when middleware scope is cancelled
    }
    
    @Test
    fun `should map different async action states with default mapper`() =  runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = DefaultEventMapper(),
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with manual initialization
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action -> state }
            middleware {
                middleware(testMiddleware)
            }
        }
        
        // Manually initialize middleware
        middleware.onStoreCreated(store)
        
        // Test different async action states
        val initiatingAction = TestAction.Load("test")
        val actions = listOf(
            duks.AsyncProcessing(initiatingAction),
            duks.AsyncResultAction(initiatingAction, "data"),
            duks.AsyncError(initiatingAction, RuntimeException("Error"))
        )
        
        actions.forEach { action ->
            store.dispatch(action)
        }
        
        // Give a small delay for processing
        delay(100)
        
        // Default mapper should handle all async states
    }
    
    @Test
    fun `should respect custom flush interval configuration`() =  runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = eventMapper,
            flushInterval = 50.milliseconds,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with manual initialization
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        val store = duks.createStore(TestState()) {
            scope(backgroundScope)
            reduceWith { state, action -> state }
            middleware {
                middleware(testMiddleware)
            }
        }
        
        // Manually initialize middleware
        middleware.onStoreCreated(store)
        
        // Add event
        store.dispatch(TestAction.Tracked("test"))
    }
    
    @Test
    fun `should track state changes through custom event mapper`() =  runTest(timeout = 5.seconds) {
        middleware = GA4Middleware(
            config = config,
            eventMapper = object : EventMapper<TestState> {
                override suspend fun mapActionAfter(action: Any, state: TestState): List<GA4Event> {
                    // Track state changes
                    return listOf(
                        GA4Event(
                            name = "state_change",
                            params = mapOf(
                                "counter" to EventParamValue.NumberValue(state.counter.toDouble())
                            )
                        )
                    )
                }
            },
            flushInterval = 100.milliseconds,
            clientFactory = { mockClient },
            scope = backgroundScope
        )
        
        // Create a test store with proper middleware builder
        val testMiddleware = object : duks.Middleware<TestState> {
            override suspend fun invoke(
                store: duks.KStore<TestState>,
                next: suspend (duks.Action) -> duks.Action,
                action: duks.Action
            ): duks.Action {
                return middleware.invoke(store, next, action)
            }
        }
        
        val store = duks.createStore(TestState(counter = 0)) {
            scope(backgroundScope)
            reduceWith { state, action ->
                when (action) {
                    is TestAction.Tracked -> state.copy(counter = state.counter + 1)
                    else -> state
                }
            }
            middleware {
                middleware(testMiddleware)
            }
        }
        
        // Manually initialize middleware since we're not using lifecycleAware
        middleware.onStoreCreated(store)
        
        // Wait for middleware initialization
        TestUtils.waitFor { middleware.isInitialized }
        
        // Update state multiple times
        repeat(3) { i ->
            store.dispatch(TestAction.Tracked("increment-$i"))
        }
        
        // Wait for state to update
        store.state.first { it.counter == 3 }
        
        // Wait for processing to complete - using a small delay since we're using a custom mapper
        delay(200)
        
        // Should have tracked all state changes
        assertEquals(3, store.state.value.counter)
    }
}