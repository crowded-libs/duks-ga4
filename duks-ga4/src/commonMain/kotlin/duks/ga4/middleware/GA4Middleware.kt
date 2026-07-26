package duks.ga4.middleware

import duks.*
import duks.ga4.client.GA4Client
import duks.ga4.client.IGA4Client
import duks.ga4.config.GA4Config
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import duks.ga4.model.UserPropertyValue
import duks.ga4.privacy.ConsentManager
import duks.logging.*
import duks.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Middleware that integrates Google Analytics 4 with duks stores.
 *
 * Intercepts actions, maps them to GA4 events, and tracks routing changes.
 * Uses the [IGA4Client] queue as the **single** batching owner (no separate middleware batcher).
 *
 * @param TState The type of the store state
 * @param config GA4 configuration
 * @param eventMapper Optional custom event mapper for actions
 * @param enableRoutingAnalytics Whether to enable automatic routing analytics
 * @param routerMiddleware Optional RouterMiddleware instance for direct integration
 * @param flushInterval How often the client queue auto-flushes (passed into [GA4Client])
 * @param clientIdProvider Optional provider for client IDs
 * @param userIdProvider Optional provider for user IDs
 * @param consentManager Optional consent manager wired into the client when privacy is enabled
 */
class GA4Middleware<TState : StateModel>(
    private val config: GA4Config,
    private val eventMapper: EventMapper<TState>? = null,
    private val enableRoutingAnalytics: Boolean = true,
    private val routerMiddleware: RouterMiddleware<TState>? = null,
    private val flushInterval: Duration = config.flushInterval,
    private val clientIdProvider: suspend (TState) -> String? = { null },
    private val userIdProvider: suspend (TState) -> String? = { null },
    private val userPropertiesProvider: suspend (TState) -> Map<String, UserPropertyValue>? = { null },
    private val clientFactory: (() -> IGA4Client)? = null,
    private val consentManager: ConsentManager? = null,
    private val scope: CoroutineScope
) : Middleware<TState>, StoreLifecycleAware<TState> {

    private val logger = Logger.default()
    private var ga4Client: IGA4Client? = null
    private var storeRef: KStore<TState>? = null
    private var routerStateJob: Job? = null
    internal var isInitialized = false
    private val initMutex = Mutex()

    // Expose for testing
    internal suspend fun flushEvents() {
        ga4Client?.flush()
    }

    // Track screen time and routing state
    private var currentScreen: String? = null
    private var screenStartTime: Instant? = null
    private var previousRouterState: RouterState? = null

    // Error tracking
    private val errorFlow = MutableSharedFlow<GA4MiddlewareError>(replay = 1, extraBufferCapacity = 10)
    val errors: SharedFlow<GA4MiddlewareError> = errorFlow.asSharedFlow()

    override suspend fun onStoreCreated(store: KStore<TState>) {
        initMutex.withLock {
            if (isInitialized) return

            try {
                logger.info(config.measurementId, enableRoutingAnalytics) {
                    "Initializing GA4Middleware for measurementId: {measurementId}, routingAnalytics: {routingAnalytics}"
                }

                storeRef = store

                // Client owns the only event queue
                val newClient = clientFactory?.invoke() ?: GA4Client(
                    config = config.copy(flushInterval = flushInterval),
                    scope = scope,
                    consentManager = consentManager,
                    flushInterval = flushInterval
                )
                ga4Client = newClient

                if (enableRoutingAnalytics) {
                    if (routerMiddleware != null) {
                        subscribeToRouterStateFlow(routerMiddleware.state)
                    } else {
                        logger.debug { "Routing analytics enabled via action interception" }
                    }
                }

                isInitialized = true
                logger.info { "GA4Middleware initialized successfully" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to initialize GA4Middleware" }
                throw e
            }
        }
    }

    override suspend fun invoke(
        store: KStore<TState>,
        next: suspend (Action) -> Action,
        action: Action
    ): Action {
        if (!isInitialized) {
            onStoreCreated(store)
        }

        // Action-based routing only when not subscribed to RouterMiddleware.state
        // (direct subscription avoids double-firing the same navigation).
        if (enableRoutingAnalytics && routerMiddleware == null && action is Routing.StateChanged) {
            handleRoutingStateChanged(action.routerState)
        }

        val mapper = eventMapper
        // Skip pre-state mapping when no mapper, or mapper only cares about after.
        if (mapper != null && mapper.mapsBeforeStateChange) {
            processAction(action, store.state.value, beforeStateChange = true)
        }

        val result = next(action)

        if (mapper != null) {
            processAction(action, store.state.value, beforeStateChange = false)
        }

        return result
    }

    suspend fun onDetach() {
        initMutex.withLock {
            logger.info { "Detaching GA4Middleware, cleaning up resources" }

            currentScreen?.let { screen ->
                screenStartTime?.let { startTime ->
                    val duration = Clock.System.now() - startTime
                    logger.debug(screen, duration.inWholeSeconds) {
                        "Tracking final screen time for {screen}: {duration}s"
                    }
                    trackScreenTime(screen, duration)
                }
            }

            routerStateJob?.let { job ->
                job.cancel()
                job.join()
            }
            routerStateJob = null

            ga4Client?.flush()
            ga4Client?.close()
            ga4Client = null
            storeRef = null
            isInitialized = false

            logger.info { "GA4Middleware detached successfully" }
        }
    }

    private suspend fun processAction(
        action: Any,
        state: TState,
        beforeStateChange: Boolean
    ) {
        val mapper = eventMapper ?: return
        try {
            val events = if (beforeStateChange) {
                mapper.mapActionBefore(action, state)
            } else {
                mapper.mapActionAfter(action, state)
            }

            if (events.isNotEmpty()) {
                logger.debug(events.size, action::class.simpleName ?: "unknown") {
                    "Mapped {eventCount} events for action: {actionType}"
                }
                enqueueEvents(events, state)
            }
        } catch (e: Exception) {
            logger.error(e, action::class.simpleName ?: "unknown") {
                "Error processing action {actionType}"
            }
            handleError(GA4MiddlewareError.MappingError(action, e))
        }
    }

    private suspend fun enqueueEvents(events: List<GA4Event>, state: TState?) {
        if (events.isEmpty()) return
        val client = ga4Client ?: return
        val clientId = state?.let { clientIdProvider(it) }
        val userId = state?.let { userIdProvider(it) }
        val userProperties = state?.let { userPropertiesProvider(it) }

        // Single batch enqueue is cheaper than N individual sendEvent calls
        client.sendEvents(
            events = events,
            clientId = clientId,
            userId = userId,
            immediate = false,
            userProperties = userProperties
        ).onFailure { error ->
            logger.error(error, events.size) {
                "Failed to enqueue {eventCount} events"
            }
            handleError(GA4MiddlewareError.SendError(events, error))
        }
    }

    private fun subscribeToRouterStateFlow(routerStateFlow: StateFlow<RouterState>) {
        routerStateJob = routerStateFlow
            .onEach { routerState ->
                try {
                    handleRouterStateChange(routerState)
                } catch (e: Exception) {
                    logger.error(e) { "Error handling router state change" }
                }
            }
            .launchIn(scope)
    }

    private suspend fun handleRouterStateChange(routerState: RouterState) {
        try {
            if (routerState == previousRouterState) return

            val previousScreen = currentScreen
            val newScreen = convertToScreenName(routerState)

            previousScreen?.let { screen ->
                screenStartTime?.let { startTime ->
                    val duration = Clock.System.now() - startTime
                    logger.debug(screen, duration.inWholeSeconds) {
                        "Previous screen {screen} was active for {duration}s"
                    }
                    trackScreenTime(screen, duration)
                }
            }

            logger.debug(newScreen, getActiveLayerName(routerState)) {
                "Router state changed to: {screen} on layer: {layer}"
            }

            val events = mutableListOf<GA4Event>()
            events.add(createScreenViewEvent(routerState, previousScreen))

            if (previousScreen != null && previousScreen != newScreen) {
                events.add(createNavigationEvent(routerState, previousScreen))
            }

            if (routerState.modalRoutes.isNotEmpty()) {
                val previousModalCount = previousRouterState?.modalRoutes?.size ?: 0
                val currentModalCount = routerState.modalRoutes.size

                when {
                    currentModalCount > previousModalCount -> {
                        createModalEvent(routerState, ModalAction.OPEN)?.let { events.add(it) }
                    }
                    currentModalCount < previousModalCount && routerState.lastRouteType == RouteType.Back -> {
                        previousRouterState?.modalRoutes?.lastOrNull()?.let { dismissedModal ->
                            createModalEvent(previousRouterState!!, ModalAction.DISMISS, dismissedModal)?.let {
                                events.add(it)
                            }
                        }
                    }
                }
            }

            createTabEvent(routerState)?.let { events.add(it) }

            val state = storeRef?.state?.value
            enqueueEvents(events, state)

            currentScreen = newScreen
            screenStartTime = Clock.System.now()
            previousRouterState = routerState
        } catch (e: Exception) {
            logger.warn(e) { "Error handling router state change" }
        }
    }

    private suspend fun handleRoutingStateChanged(routerState: RouterState) {
        handleRouterStateChange(routerState)
    }

    private suspend fun trackScreenTime(screenName: String, duration: Duration) {
        val durationMillis = duration.inWholeMilliseconds
        if (durationMillis > 0) {
            val event = GA4Event(
                name = "screen_time",
                params = mapOf(
                    "screen_name" to EventParamValue.StringValue(screenName),
                    "duration_seconds" to EventParamValue.NumberValue(duration.inWholeSeconds.coerceAtLeast(0).toDouble()),
                    "engagement_time_msec" to EventParamValue.NumberValue(durationMillis.toDouble())
                )
            )
            enqueueEvents(listOf(event), storeRef?.state?.value)
        }
    }

    private suspend fun handleError(error: GA4MiddlewareError) {
        try {
            errorFlow.emit(error)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to emit error to error flow" }
        }
    }
}

/**
 * Represents different types of GA4 middleware errors
 */
sealed class GA4MiddlewareError {
    data class MappingError(val action: Any, val cause: Throwable) : GA4MiddlewareError()
    data class SendError(val events: List<GA4Event>, val cause: Throwable) : GA4MiddlewareError()
}

// Helper functions to work with RouterState
private fun convertToScreenName(routerState: RouterState): String? {
    return when {
        routerState.modalRoutes.isNotEmpty() -> {
            val modal = routerState.modalRoutes.last()
            val modalPath = modal.route?.path?.removePrefix("/")
            val contentPath = routerState.contentRoutes.lastOrNull()?.route?.path?.removePrefix("/") ?: "unknown"
            "${contentPath}_modal_$modalPath"
        }
        routerState.contentRoutes.isNotEmpty() -> {
            routerState.contentRoutes.last().route?.path?.removePrefix("/")
        }
        routerState.sceneRoutes.isNotEmpty() -> {
            routerState.sceneRoutes.last().route?.path?.removePrefix("/")
        }
        else -> "unknown"
    }
}

private fun getActiveLayerName(routerState: RouterState): String {
    return when {
        routerState.modalRoutes.isNotEmpty() -> NavigationLayer.Modal.name
        routerState.contentRoutes.isNotEmpty() -> NavigationLayer.Content.name
        routerState.sceneRoutes.isNotEmpty() -> NavigationLayer.Scene.name
        else -> NavigationLayer.Content.name
    }
}

private fun createScreenViewEvent(routerState: RouterState, previousRoute: String?): GA4Event {
    val screenClass = when {
        routerState.modalRoutes.isNotEmpty() -> "Modal"
        routerState.contentRoutes.isNotEmpty() -> "Content"
        routerState.sceneRoutes.isNotEmpty() -> "Scene"
        else -> "Unknown"
    }

    return GA4Event(
        name = "screen_view",
        params = buildMap {
            put("screen_name", EventParamValue.StringValue(convertToScreenName(routerState) ?: "unknown"))
            put("screen_class", EventParamValue.StringValue(screenClass))
            previousRoute?.let {
                put("previous_screen", EventParamValue.StringValue(it))
            }
            if (routerState.modalRoutes.isNotEmpty()) {
                put("is_modal", EventParamValue.BooleanValue(true))
                put("modal_count", EventParamValue.NumberValue(routerState.modalRoutes.size.toDouble()))
                val modal = routerState.modalRoutes.last()
                put("modal_route", EventParamValue.StringValue(modal.route?.path ?: "unknown"))
            }
            put("navigation_layer", EventParamValue.StringValue(getActiveLayerName(routerState).lowercase()))
            val depth = when {
                routerState.modalRoutes.isNotEmpty() -> routerState.modalRoutes.size
                routerState.contentRoutes.isNotEmpty() -> routerState.contentRoutes.size
                routerState.sceneRoutes.isNotEmpty() -> routerState.sceneRoutes.size
                else -> 0
            }
            put("navigation_depth", EventParamValue.NumberValue(depth.toDouble()))
        }
    )
}

private fun createNavigationEvent(routerState: RouterState, fromRoute: String): GA4Event {
    val navigationType = when (routerState.lastRouteType) {
        RouteType.Back -> NavigationType.POP
        RouteType.Scene -> NavigationType.RESET
        else -> NavigationType.PUSH
    }

    val toScreen = convertToScreenName(routerState) ?: "unknown"
    val pattern = detectNavigationPattern(fromRoute, toScreen)

    return GA4Event(
        name = "navigation",
        params = buildMap {
            put("from_screen", EventParamValue.StringValue(fromRoute))
            put("to_screen", EventParamValue.StringValue(toScreen))
            put("navigation_type", EventParamValue.StringValue(navigationType.analyticsName))
            put("navigation_pattern", EventParamValue.StringValue(pattern.analyticsName))
            routerState.lastRouteType?.let { routeType ->
                put("route_type", EventParamValue.StringValue(routeType.name.lowercase()))
            }
            put("scene_stack_size", EventParamValue.NumberValue(routerState.sceneRoutes.size.toDouble()))
            put("content_stack_size", EventParamValue.NumberValue(routerState.contentRoutes.size.toDouble()))
            put("modal_stack_size", EventParamValue.NumberValue(routerState.modalRoutes.size.toDouble()))
            put("active_layer", EventParamValue.StringValue(getActiveLayerName(routerState).lowercase()))
        }
    )
}

private fun createModalEvent(routerState: RouterState, action: ModalAction, modalRoute: RouteInstance? = null): GA4Event? {
    val modal = modalRoute ?: routerState.modalRoutes.lastOrNull()
    if (modal == null) return null

    return GA4Event(
        name = when (action) {
            ModalAction.OPEN -> "modal_open"
            ModalAction.CLOSE -> "modal_close"
            ModalAction.DISMISS -> "modal_dismiss"
        },
        params = buildMap {
            put("modal_name", EventParamValue.StringValue(modal.route?.path?.removePrefix("/") ?: "unknown"))
            put("modal_path", EventParamValue.StringValue(modal.route?.path ?: "unknown"))
            val parentScreen = routerState.contentRoutes.lastOrNull()?.route?.path
                ?: routerState.sceneRoutes.lastOrNull()?.route?.path ?: "unknown"
            put("parent_screen", EventParamValue.StringValue(parentScreen.removePrefix("/")))
            put("modal_stack_depth", EventParamValue.NumberValue(routerState.modalRoutes.size.toDouble()))
        }
    )
}

private fun createTabEvent(routerState: RouterState): GA4Event? {
    val config = routerState.getCurrentContentRoute()?.route?.config as? Map<*, *>
    val currentTab = config?.get("selectedTab") as? String
    if (currentTab == null) return null

    return GA4Event(
        name = "tab_switch",
        params = buildMap {
            put("tab_name", EventParamValue.StringValue(currentTab))
            put("screen_name", EventParamValue.StringValue(convertToScreenName(routerState) ?: "unknown"))
            routerState.getCurrentContentRoute()?.let { route ->
                put("tab_container", EventParamValue.StringValue(route.route?.path ?: "unknown"))
            }
        }
    )
}
