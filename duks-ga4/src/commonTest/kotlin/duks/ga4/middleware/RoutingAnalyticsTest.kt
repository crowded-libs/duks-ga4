package duks.ga4.middleware

import androidx.compose.runtime.Composable
import duks.createStore
import duks.ga4.client.IGA4Client
import duks.ga4.config.GA4Config
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import duks.logging.Logger
import duks.logging.info
import duks.routing.HasRouterState
import duks.routing.NavigationLayer
import duks.routing.NavigationMode
import duks.routing.RouterMiddleware
import duks.routing.RouterState
import duks.routing.routeTo
import duks.routing.routing
import duks.routing.showModal
import duks.routing.goBack
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

    private val logger = Logger.default()

    data class TestAppState(
        val user: User? = null,
        override val routerState: RouterState = RouterState()
    ) : HasRouterState {
        override fun withRouterState(routerState: RouterState) = copy(routerState = routerState)
    }

    data class User(
        val id: String,
        val name: String
    )

    class MockGA4Client : IGA4Client {
        val sentEvents = mutableListOf<GA4Event>()
        var closed = false

        override suspend fun sendEvent(
            event: GA4Event,
            clientId: String?,
            userId: String?,
            immediate: Boolean,
            userProperties: Map<String, duks.ga4.model.UserPropertyValue>?
        ): Result<Unit> {
            sentEvents.add(event)
            return Result.success(Unit)
        }

        override suspend fun sendEvents(
            events: List<GA4Event>,
            clientId: String?,
            userId: String?,
            immediate: Boolean,
            userProperties: Map<String, duks.ga4.model.UserPropertyValue>?
        ): Result<Unit> {
            sentEvents.addAll(events)
            return Result.success(Unit)
        }

        override suspend fun flush() {}

        override suspend fun getQueueSize(): Int = 0

        override suspend fun close() {
            closed = true
        }
    }

    private fun stringParam(event: GA4Event, key: String): String? =
        (event.params[key] as? EventParamValue.StringValue)?.value

    @Test
    fun `middleware without trackRouting does not require router`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(measurementId = "G-TEST", apiSecret = "test-secret", debugMode = true)

        val ga4Middleware = GA4Middleware<TestAppState>(
            config = config,
            flushInterval = 1.hours,
            clientFactory = { mockClient },
            scope = backgroundScope
        )

        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            middleware {
                middleware(ga4Middleware)
                lifecycleAware(ga4Middleware)
            }
            reduceWith { state, _ -> state }
        }
        // Ensure lifecycle ran even if the store defers init until first use
        ga4Middleware.onStoreCreated(store)
        assertTrue(ga4Middleware.isInitialized)

        ga4Middleware.onDetach()
        assertTrue(mockClient.closed)
    }

    @Test
    fun `trackRouting emits screen_view navigation and modal events`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(measurementId = "G-TEST", apiSecret = "test-secret", debugMode = true)

        lateinit var router: RouterMiddleware<TestAppState>
        lateinit var ga4: GA4Middleware<TestAppState>

        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
                modal("/settings") { TestSettingsModal() }
            }
            ga4 = GA4Middleware(
                config = config,
                trackedRouter = router,
                routingListener = Ga4RoutingListener(),
                ownsRouterListener = true,
                flushInterval = 1.hours,
                clientIdProvider = { "test-client-id" },
                userIdProvider = { state -> state.user?.id },
                clientFactory = { mockClient },
                scope = backgroundScope
            )
            middleware {
                middleware(ga4)
                lifecycleAware(ga4)
            }
            reduceWith { state, _ -> state }
        }

        store.routeTo("/home")
        router.state.first { it.contentRoutes.any { r -> r.path == "/home" } }
        runCurrent()
        advanceUntilIdle()
        ga4.flushEvents()
        runCurrent()
        advanceUntilIdle()

        val homeScreenEvent = mockClient.sentEvents.find {
            it.name == "screen_view" && stringParam(it, "screen_name") == "home"
        }
        assertNotNull(homeScreenEvent, "Expected screen_view for home; got ${mockClient.sentEvents.map { it.name }}")
        assertEquals("Content", stringParam(homeScreenEvent, "screen_class"))

        store.routeTo("/profile")
        router.state.first { it.contentRoutes.any { r -> r.path == "/profile" } }
        runCurrent()
        advanceUntilIdle()
        ga4.flushEvents()
        runCurrent()
        advanceUntilIdle()

        val profileNav = mockClient.sentEvents.find {
            it.name == "navigation" && stringParam(it, "to_screen") == "profile"
        }
        assertNotNull(profileNav, "Expected navigation to profile")
        assertEquals("push", stringParam(profileNav, "navigation_type"))
        assertEquals("home", stringParam(profileNav, "from_screen"))

        store.showModal("/settings")
        router.state.first { it.modalRoutes.isNotEmpty() }
        runCurrent()
        advanceUntilIdle()
        ga4.flushEvents()
        runCurrent()
        advanceUntilIdle()

        val modalOpen = mockClient.sentEvents.find { it.name == "modal_open" }
        assertNotNull(modalOpen, "Expected modal_open")
        assertEquals("settings", stringParam(modalOpen, "modal_name"))
        assertEquals("profile", stringParam(modalOpen, "parent_screen"))

        store.goBack()
        router.state.first { it.modalRoutes.isEmpty() }
        runCurrent()
        advanceUntilIdle()
        ga4.flushEvents()
        runCurrent()
        advanceUntilIdle()

        assertNotNull(
            mockClient.sentEvents.find { it.name == "modal_dismiss" },
            "Expected modal_dismiss"
        )

        ga4.onDetach()
    }

    @Test
    fun `trackRouting with ClearHistory reports reset navigation_type`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(measurementId = "G-TEST", apiSecret = "test-secret", debugMode = true)

        lateinit var router: RouterMiddleware<TestAppState>
        lateinit var ga4: GA4Middleware<TestAppState>

        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
            }
            ga4 = GA4Middleware(
                config = config,
                trackedRouter = router,
                routingListener = Ga4RoutingListener(),
                ownsRouterListener = true,
                flushInterval = 1.hours,
                clientFactory = { mockClient },
                scope = backgroundScope
            )
            middleware {
                middleware(ga4)
                lifecycleAware(ga4)
            }
            reduceWith { state, _ -> state }
        }

        store.routeTo("/home")
        router.state.first { it.contentRoutes.any { r -> r.path == "/home" } }
        store.routeTo("/profile")
        router.state.first { it.contentRoutes.any { r -> r.path == "/profile" } }
        runCurrent()
        advanceUntilIdle()
        mockClient.sentEvents.clear()

        store.routeTo("/home", mode = NavigationMode.ClearHistory)
        router.state.first {
            it.contentRoutes.size == 1 && it.contentRoutes.single().path == "/home"
        }
        runCurrent()
        advanceUntilIdle()
        ga4.flushEvents()
        runCurrent()
        advanceUntilIdle()

        val nav = mockClient.sentEvents.find { it.name == "navigation" }
        assertNotNull(nav, "Expected navigation event after ClearHistory")
        assertEquals("reset", stringParam(nav, "navigation_type"))

        ga4.onDetach()
    }

    @Test
    fun `tab navigation emits tab_switch`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(measurementId = "G-TEST", apiSecret = "test-secret", debugMode = true)

        lateinit var router: RouterMiddleware<TestAppState>
        lateinit var ga4: GA4Middleware<TestAppState>

        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/videos", config = mapOf("selectedTab" to "videos")) { TestVideosScreen() }
                content("/music", config = mapOf("selectedTab" to "music")) { TestMusicScreen() }
            }
            ga4 = GA4Middleware(
                config = config,
                trackedRouter = router,
                routingListener = Ga4RoutingListener(),
                ownsRouterListener = true,
                flushInterval = 50.milliseconds,
                clientFactory = { mockClient },
                scope = backgroundScope
            )
            middleware {
                middleware(ga4)
                lifecycleAware(ga4)
            }
            reduceWith { state, _ -> state }
        }

        store.routeTo("/videos")
        router.state.first { it.getCurrentContentRoute()?.path == "/videos" }
        store.routeTo("/music")
        router.state.first { it.getCurrentContentRoute()?.path == "/music" }
        runCurrent()
        advanceUntilIdle()
        ga4.flushEvents()
        runCurrent()
        advanceUntilIdle()

        val tabSwitchEvents = mockClient.sentEvents.filter { it.name == "tab_switch" }
        assertTrue(tabSwitchEvents.size >= 2, "Expected at least 2 tab_switch events")
        assertEquals("music", stringParam(tabSwitchEvents.last(), "tab_name"))

        ga4.onDetach()
    }

    @Test
    fun `scene navigation emits screen_view with Scene class`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(measurementId = "G-TEST", apiSecret = "test-secret", debugMode = true)

        lateinit var router: RouterMiddleware<TestAppState>
        lateinit var ga4: GA4Middleware<TestAppState>

        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                scene("/splash") { TestSplashScreen() }
                scene("/login") { TestLoginScreen() }
                content("/home") { TestHomeScreen() }
            }
            ga4 = GA4Middleware(
                config = config,
                trackedRouter = router,
                routingListener = Ga4RoutingListener(),
                ownsRouterListener = true,
                flushInterval = 1.hours,
                clientFactory = { mockClient },
                scope = backgroundScope
            )
            middleware {
                middleware(ga4)
                lifecycleAware(ga4)
            }
            reduceWith { state, _ -> state }
        }

        store.routeTo("/splash", layer = NavigationLayer.Scene)
        router.state.first { it.sceneRoutes.isNotEmpty() }
        runCurrent()
        advanceUntilIdle()
        ga4.flushEvents()
        runCurrent()
        advanceUntilIdle()

        val splashEvent = mockClient.sentEvents.find {
            it.name == "screen_view" && stringParam(it, "screen_name") == "splash"
        }
        assertNotNull(splashEvent)
        assertEquals("Scene", stringParam(splashEvent, "screen_class"))

        store.routeTo("/login", layer = NavigationLayer.Scene)
        router.state.first { it.sceneRoutes.any { r -> r.path == "/login" } }
        runCurrent()
        advanceUntilIdle()
        ga4.flushEvents()
        runCurrent()
        advanceUntilIdle()

        val sceneViews = mockClient.sentEvents.filter {
            it.name == "screen_view" && stringParam(it, "screen_class") == "Scene"
        }
        assertTrue(sceneViews.isNotEmpty())

        ga4.onDetach()
    }

    @Test
    fun `builder trackRouting wires listener`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        lateinit var router: RouterMiddleware<TestAppState>

        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/home") { TestHomeScreen() }
            }
            ga4Analytics {
                config {
                    measurementId("G-TEST")
                    apiSecret("test-secret")
                    debugMode()
                }
                trackRouting(router)
                flushInterval(1.hours)
                clientFactory { mockClient }
                scope(backgroundScope)
            }
            reduceWith { state, _ -> state }
        }

        store.routeTo("/home")
        router.state.first { it.contentRoutes.any { r -> r.path == "/home" } }
        // Allow async listener enqueue
        delay(100)
        runCurrent()
        advanceUntilIdle()

        assertTrue(
            mockClient.sentEvents.any { it.name == "screen_view" },
            "Expected screen_view via trackRouting builder; got ${mockClient.sentEvents.map { it.name }}"
        )
    }

    @Test
    fun `remove listener on detach stops further events`() = runTest(timeout = 5.seconds) {
        val mockClient = MockGA4Client()
        val config = GA4Config(measurementId = "G-TEST", apiSecret = "test-secret", debugMode = true)

        lateinit var router: RouterMiddleware<TestAppState>
        lateinit var ga4: GA4Middleware<TestAppState>

        val store = createStore(TestAppState()) {
            scope(backgroundScope)
            router = routing {
                content("/home") { TestHomeScreen() }
                content("/profile") { TestProfileScreen() }
            }
            ga4 = GA4Middleware(
                config = config,
                trackedRouter = router,
                routingListener = Ga4RoutingListener(),
                ownsRouterListener = true,
                flushInterval = 1.hours,
                clientFactory = { mockClient },
                scope = backgroundScope
            )
            middleware {
                middleware(ga4)
                lifecycleAware(ga4)
            }
            reduceWith { state, _ -> state }
        }

        store.routeTo("/home")
        router.state.first { it.contentRoutes.any { r -> r.path == "/home" } }
        runCurrent()
        advanceUntilIdle()
        ga4.onDetach()
        mockClient.sentEvents.clear()

        store.routeTo("/profile")
        router.state.first { it.contentRoutes.any { r -> r.path == "/profile" } }
        runCurrent()
        advanceUntilIdle()
        delay(50)

        assertTrue(mockClient.sentEvents.isEmpty(), "No events after detach")
    }
}

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
