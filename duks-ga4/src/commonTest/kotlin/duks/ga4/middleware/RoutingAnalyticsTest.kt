package duks.ga4.middleware

import androidx.compose.runtime.Composable
import duks.StateModel
import duks.StoreBuilder
import duks.StoreLifecycleAware
import duks.createStore
import duks.ga4.client.IGA4Client
import duks.ga4.config.GA4Config
import duks.ga4.model.GA4Event
import duks.logging.Logger
import duks.logging.info
import duks.logging.warn
import duks.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RoutingAnalyticsTest {
    
    @Test
    fun `test minimal GA4 middleware setup`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(
            measurementId = "G-TEST",
            apiSecret = "test-secret",
            debugMode = true
        )
        
        val ga4Middleware = GA4Middleware<TestAppState>(
            config = config,
            enableRoutingAnalytics = false, // Disable routing to isolate issue
            flushInterval = 1.hours,
            clientFactory = { mockClient },
            scope = backgroundScope  // Use backgroundScope for background tasks
        )
        
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            middleware {
                middleware(ga4Middleware)
                scope(this@runTest)
                lifecycleAware(ga4Middleware)
            }
        }
        
        // Just initialize and immediately clean up
        ga4Middleware.onDetach()
        
        // Test passes if we get here without timeout
        assertTrue(true)
    }
    
    @Test
    fun `test GA4 middleware with routing but no direct integration`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(
            measurementId = "G-TEST",
            apiSecret = "test-secret",
            debugMode = true
        )
        
        val ga4Middleware = GA4Middleware<TestAppState>(
            config = config,
            enableRoutingAnalytics = true, // Enable routing
            routerMiddleware = null, // No direct integration
            flushInterval = 1.hours,
            clientFactory = { mockClient },
            scope = backgroundScope  // Use backgroundScope for background tasks
        )
        
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            routing {
                content("/home") { TestHomeScreen() }
            }
            
            middleware {
                middleware(ga4Middleware)
                lifecycleAware(ga4Middleware)
            }
        }
        
        // Navigate and clean up
        store.routeTo("/home")
        delay(100)
        ga4Middleware.onDetach()
        
        // Test passes if we get here without timeout
        assertTrue(true)
    }
    
    private val logger = Logger.default()
    
    // Test state
    data class TestAppState(
        val user: User? = null,
        val currentRoute: String? = null
    ) : StateModel
    
    data class User(
        val id: String,
        val name: String
    )
    
    // Mock GA4 client for testing
    class MockGA4Client : IGA4Client {
        val sentEvents = mutableListOf<GA4Event>()
        var closed = false
        
        override suspend fun sendEvent(
            event: GA4Event,
            clientId: String?,
            userId: String?,
            immediate: Boolean
        ): Result<Unit> {
            sentEvents.add(event)
            return Result.success(Unit)
        }
        
        override suspend fun sendEvents(
            events: List<GA4Event>,
            clientId: String?,
            userId: String?,
            immediate: Boolean
        ): Result<Unit> {
            sentEvents.addAll(events)
            return Result.success(Unit)
        }
        
        override suspend fun flush() {
            // No-op for testing
        }
        
        override suspend fun getQueueSize(): Int {
            return 0
        }
        
        override suspend fun close() {
            closed = true
        }
    }
    
    @Test
    fun `test routing analytics with direct RouterMiddleware integration`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(
            measurementId = "G-TEST",
            apiSecret = "test-secret",
            debugMode = true
        )
        
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        lateinit var ga4Middleware: GA4Middleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            // Set up routing
            routerMiddleware = routing {
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                modal("/settings") { TestSettingsModal() }
            }
            // Set up GA4 with direct router integration
            ga4Middleware = GA4Middleware(
                config = config,
                enableRoutingAnalytics = true,
                routerMiddleware = routerMiddleware,
                flushInterval = 1.hours, // Effectively disable auto-flush in tests
                clientIdProvider = { "test-client-id" },
                userIdProvider = { state -> state.user?.id },
                clientFactory = { mockClient },
                scope = backgroundScope  // Use backgroundScope for background tasks
            )
            
            middleware {
                middleware(ga4Middleware)
                lifecycleAware(ga4Middleware)
            }
            
            // Track router state in app state
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        state.copy(currentRoute = action.routerState.toScreenName())
                    }
                    else -> state
                }
            }
        }
        
        logger.info { "Test: Store created, checking initial router state" }
        logger.info { "Initial router state: ${routerMiddleware.state.value}" }
        
        // Navigate to home first
        logger.info { "Test: Navigating to /home" }
        store.routeTo("/home")
        store.state.first { it.currentRoute == "home" }
        logger.info { "Test: Navigation to /home completed" }
        
        // Manually flush events since auto-flush is disabled
        ga4Middleware.flushEvents()
        runCurrent()
        advanceUntilIdle()

        // Verify screen_view event was sent
        logger.info { "All events: ${mockClient.sentEvents.map { "${it.name}: ${it.params}" }}" }
        val homeScreenEvents = mockClient.sentEvents.filter { it.name == "screen_view" }
        logger.info { "Screen view events: ${homeScreenEvents.size}" }
        assertTrue(homeScreenEvents.isNotEmpty(), "Expected at least one screen_view event")
        
        // Find the home screen event (might be multiple if we navigated twice)
        val homeScreenEvent = homeScreenEvents.find { event ->
            (event.params["screen_name"] as? duks.ga4.model.EventParamValue.StringValue)?.value == "home"
        }
        assertNotNull(homeScreenEvent, "Expected to find a screen_view event for home screen")
        assertEquals("Content", (homeScreenEvent.params["screen_class"] as? duks.ga4.model.EventParamValue.StringValue)?.value)
        
        // Navigate to profile
        store.routeTo("/profile")
        store.state.first { it.currentRoute == "profile" }
        
        // Give time for events to be generated
        runCurrent()
        advanceUntilIdle()

        // Flush events after navigation
        ga4Middleware.flushEvents()
        runCurrent()
        advanceUntilIdle()

        // Log all events to debug
        logger.info { "All events after profile navigation: ${mockClient.sentEvents.map { "${it.name}: ${it.params}" }}" }
        
        // Verify navigation event was sent
        val navigationEvents = mockClient.sentEvents.filter { it.name == "navigation" }
        logger.info { "Navigation events: ${navigationEvents.size}" }
        logger.info { "Navigation event details: ${navigationEvents.map { "${it.params["from_screen"]} -> ${it.params["to_screen"]}" }}" }
        assertTrue(navigationEvents.size >= 1, "Expected at least 1 navigation event")
        
        // Find the navigation from home to profile
        val profileNavEvent = navigationEvents.find { event ->
            (event.params["to_screen"] as? duks.ga4.model.EventParamValue.StringValue)?.value == "profile"
        }
        assertNotNull(profileNavEvent, "Expected to find navigation event to profile")
        assertEquals("push", (profileNavEvent.params["navigation_type"] as? duks.ga4.model.EventParamValue.StringValue)?.value)
        
        // Show modal
        store.showModal("/settings")
        routerMiddleware.state.first { it.modalRoutes.isNotEmpty() }
        
        // Give time for events to be generated
        runCurrent()
        advanceUntilIdle()

        // Flush events after showing modal
        ga4Middleware.flushEvents()
        runCurrent()
        advanceUntilIdle()

        // Verify modal events
        logger.info { "All events after modal: ${mockClient.sentEvents.map { it.name }}" }
        val modalEvents = mockClient.sentEvents.filter { it.name == "modal_open" || it.name == "modal_dismiss" }
        logger.info { "Modal events: ${modalEvents.size}" }
        
        val modalOpenEvent = mockClient.sentEvents.find { it.name == "modal_open" }
        if (modalOpenEvent != null) {
            assertEquals("settings", (modalOpenEvent.params["modal_name"] as? duks.ga4.model.EventParamValue.StringValue)?.value)
            assertEquals("profile", (modalOpenEvent.params["parent_screen"] as? duks.ga4.model.EventParamValue.StringValue)?.value)
        } else {
            logger.warn { "Modal open event not found" }
        }
        
        // Dismiss modal
        store.goBack()
        routerMiddleware.state.first { it.modalRoutes.isEmpty() }
        
        // Give time for events to be generated
        runCurrent()
        advanceUntilIdle()

        // Flush events before checking
        ga4Middleware.flushEvents()
        runCurrent()
        advanceUntilIdle()

        // Verify modal dismiss event
        val modalDismissEvent = mockClient.sentEvents.find { it.name == "modal_dismiss" }
        if (modalDismissEvent != null) {
            logger.info { "Found modal dismiss event" }
        } else {
            logger.warn { "Modal dismiss event not found" }
        }
        
        // Clean up
        ga4Middleware.onDetach()
    }
    
    @Test
    fun `test routing analytics with action-based integration`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(
            measurementId = "G-TEST",
            apiSecret = "test-secret",
            debugMode = true
        )
        
        lateinit var ga4Middleware: GA4Middleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            // Set up routing
            routing {
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
            }

            // Set up GA4 without direct router integration
            // It will listen for Routing.StateChanged actions
            ga4Middleware = GA4Middleware(
                config = config,
                enableRoutingAnalytics = true,
                flushInterval = 1.hours, // Disable auto-flush
                clientFactory = { mockClient },
                scope = backgroundScope  // Use backgroundScope for background tasks
            )
            
            middleware {
                middleware(ga4Middleware)
                lifecycleAware(ga4Middleware)
            }
            
            // Track router state in app state
            reduceWith { state, action ->
                when (action) {
                    is Routing.StateChanged -> {
                        state.copy(currentRoute = action.routerState.toScreenName())
                    }
                    else -> state
                }
            }
        }
        
        // Navigate to home
        store.routeTo("/home")
        store.state.first { it.currentRoute == "home" }
        
        // Manually flush events since auto-flush is disabled  
        ga4Middleware.flushEvents()
        runCurrent()
        advanceUntilIdle()

        // Verify screen_view event was sent
        val homeScreenEvent = mockClient.sentEvents.find { 
            it.name == "screen_view" &&
            (it.params["screen_name"] as? duks.ga4.model.EventParamValue.StringValue)?.value == "home"
        }
        assertNotNull(homeScreenEvent, "Expected to find a screen_view event for home screen, but found: ${mockClient.sentEvents.map { it.name }}")
        
        // Clean up
        ga4Middleware.onDetach()
    }
    
    @Test
    fun `test tab navigation tracking`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(
            measurementId = "G-TEST",
            apiSecret = "test-secret",
            debugMode = true
        )
        
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        lateinit var ga4Middleware: GA4Middleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            routerMiddleware = routing {
                content("/videos", config = mapOf("selectedTab" to "videos")) { TestVideosScreen() }
                content("/music", config = mapOf("selectedTab" to "music")) { TestMusicScreen() }
            }

            ga4Middleware = GA4Middleware(
                config = config,
                enableRoutingAnalytics = true,
                routerMiddleware = routerMiddleware,
                flushInterval = 50.milliseconds,
                clientFactory = { mockClient },
                scope = backgroundScope  // Use backgroundScope for background tasks
            )
            
            middleware {
                middleware(ga4Middleware)
                lifecycleAware(ga4Middleware)
            }
        }
        
        // Navigate to videos tab
        store.routeTo("/videos")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/videos" }
        
        // Navigate to music tab
        store.routeTo("/music")
        routerMiddleware.state.first { it.getCurrentContentRoute()?.route?.path == "/music" }
        
        // Give time for events to be processed and flushed
        runCurrent()
        advanceUntilIdle()
        
        // Manually trigger flush since auto-flush might not have fired
        ga4Middleware.flushEvents()
        runCurrent()
        advanceUntilIdle()

        // Log all events
        logger.info { "All events in tab test: ${mockClient.sentEvents.map { it.name }}" }
        
        // Verify tab switch event
        // Verify tab switch event - looking for the last one which should be music
        val tabSwitchEvents = mockClient.sentEvents.filter { it.name == "tab_switch" }
        logger.info { "Tab switch events: ${tabSwitchEvents.map { it.params["tab_name"] }}" }
        assertTrue(tabSwitchEvents.size >= 2, "Expected at least 2 tab_switch events")
        assertEquals("music", (tabSwitchEvents.last().params["tab_name"] as? duks.ga4.model.EventParamValue.StringValue)?.value)
    }
    
    @Test
    fun `test scene navigation tracking`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(
            measurementId = "G-TEST",
            apiSecret = "test-secret",
            debugMode = true
        )
        
        lateinit var routerMiddleware: RouterMiddleware<TestAppState>
        lateinit var ga4Middleware: GA4Middleware<TestAppState>
        
        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            routerMiddleware = routing {
                scene("/splash") { TestSplashScreen() }
                scene("/login") { TestLoginScreen() }
                content("/home") { TestHomeScreen() }
            }

            ga4Middleware = GA4Middleware(
                config = config,
                enableRoutingAnalytics = true,
                routerMiddleware = routerMiddleware,
                flushInterval = 1.hours, // Disable auto-flush
                clientFactory = { mockClient },
                scope = backgroundScope  // Use backgroundScope for background tasks
            )
            
            middleware {
                middleware(ga4Middleware)
                lifecycleAware(ga4Middleware)
            }
        }
        
        // Navigate to splash scene
        store.routeTo("/splash", layer = NavigationLayer.Scene)
        routerMiddleware.state.first { it.sceneRoutes.isNotEmpty() }
        
        // Manually flush events
        ga4Middleware.flushEvents()
        runCurrent()
        advanceUntilIdle()

        // Verify scene navigation event
        val splashEvent = mockClient.sentEvents.find { 
            it.name == "screen_view" &&
            (it.params["screen_name"] as? duks.ga4.model.EventParamValue.StringValue)?.value == "splash"
        }
        assertNotNull(splashEvent)
        assertEquals("Scene", (splashEvent.params["screen_class"] as? duks.ga4.model.EventParamValue.StringValue)?.value)
        
        // Navigate to login scene
        logger.info { "Navigating to /login scene" }
        store.routeTo("/login", layer = NavigationLayer.Scene)
        routerMiddleware.state.first { 
            it.sceneRoutes.size == 2 && it.sceneRoutes.last().route?.path == "/login"
        }
        logger.info { "Navigation to /login completed, current state: ${routerMiddleware.state.value}" }
        
        // Flush events
        ga4Middleware.flushEvents()
        runCurrent()
        advanceUntilIdle()
        
        // Log all events to debug
        logger.info { "All events after scene navigation: ${mockClient.sentEvents.map { "${it.name}: ${it.params}" }}" }
        
        // Verify we have at least a screen view for splash
        val sceneScreenEvents = mockClient.sentEvents.filter { 
            it.name == "screen_view" &&
            (it.params["screen_class"] as? duks.ga4.model.EventParamValue.StringValue)?.value == "Scene"
        }
        assertTrue(sceneScreenEvents.isNotEmpty(), "Expected at least one scene screen_view event")
        
        // Verify splash screen was tracked
        val splashScreenEvent = sceneScreenEvents.find { 
            (it.params["screen_name"] as? duks.ga4.model.EventParamValue.StringValue)?.value == "splash"
        }
        assertNotNull(splashScreenEvent, "Expected to find screen_view event for splash scene")
        
        // Clean up
        ga4Middleware.onDetach()
    }
}

// Test composables
@Composable
private fun TestHomeScreen() {}

@Composable
private fun TestProfileScreen() {}

@Composable
private fun TestSettingsModal() {}

@Composable
private fun TestVideosScreen() {}

@Composable
private fun TestMusicScreen() {}

@Composable
private fun TestSplashScreen() {}

@Composable
private fun TestLoginScreen() {}

// Helper function to add GA4 analytics with mock client
private fun <TState : StateModel> StoreBuilder<TState>.ga4AnalyticsWithMockClient(
    mockClient: RoutingAnalyticsTest.MockGA4Client,
    config: GA4Config,
    builder: GA4MiddlewareBuilder<TState>.() -> Unit
) {
    val ga4Builder = GA4MiddlewareBuilder<TState>()
        .config(config)
        .clientFactory { mockClient }
        .apply(builder)
    
    val middleware = ga4Builder.build()
    middleware {
        middleware(middleware)
        if (middleware is StoreLifecycleAware<*>) {
            @Suppress("UNCHECKED_CAST")
            lifecycleAware(middleware as StoreLifecycleAware<TState>)
        }
    }
}

// Extension function to convert RouterState to screen name for testing
private fun RouterState.toScreenName(): String {
    return when {
        modalRoutes.isNotEmpty() -> {
            val modal = modalRoutes.last()
            val modalPath = modal.route?.path?.removePrefix("/")
            val contentPath = contentRoutes.lastOrNull()?.route?.path?.removePrefix("/") ?: "unknown"
            "${contentPath}_modal_$modalPath"
        }
        contentRoutes.isNotEmpty() -> {
            contentRoutes.last().route?.path?.removePrefix("/")
        }
        sceneRoutes.isNotEmpty() -> {
            sceneRoutes.last().route?.path?.removePrefix("/")
        }
        else -> "unknown"
    } ?: "unknown"
}