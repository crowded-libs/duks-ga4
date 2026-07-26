package duks.ga4.middleware

import duks.*
import duks.ga4.client.EventBatcher
import duks.ga4.client.GA4Client
import duks.ga4.client.IGA4Client
import duks.ga4.config.GA4Config
import duks.ga4.model.BatchedEvent
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import duks.ga4.model.UserPropertyValue
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
 * This middleware intercepts actions, maps them to GA4 events, and tracks
 * routing changes when used with RouterMiddleware.
 * 
 * @param TState The type of the store state
 * @param config GA4 configuration
 * @param eventMapper Optional custom event mapper for actions
 * @param enableRoutingAnalytics Whether to enable automatic routing analytics
 * @param routerMiddleware Optional RouterMiddleware instance for direct integration
 * @param flushInterval How often to flush events to GA4
 * @param clientIdProvider Optional provider for client IDs
 * @param userIdProvider Optional provider for user IDs
 */
class GA4Middleware<TState : StateModel>(
    private val config: GA4Config,
    private val eventMapper: EventMapper<TState>? = null,
    private val enableRoutingAnalytics: Boolean = true,
    private val routerMiddleware: RouterMiddleware<TState>? = null,
    private val flushInterval: Duration = 10.seconds,
    private val clientIdProvider: suspend (TState) -> String? = { null },
    private val userIdProvider: suspend (TState) -> String? = { null },
    private val userPropertiesProvider: suspend (TState) -> Map<String, UserPropertyValue>? = { null },
    private val clientFactory: (() -> IGA4Client)? = null,
    private val scope: CoroutineScope
) : Middleware<TState>, StoreLifecycleAware<TState> {
    
    private val logger = Logger.default()
    private var ga4Client: IGA4Client? = null
    private var eventBatcher: EventBatcher? = null
    private var routerStateJob: Job? = null
    internal var isInitialized = false
    private val initMutex = Mutex()
    
    // Expose for testing
    internal suspend fun flushEvents() {
        eventBatcher?.flushAll()
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
                
                // Initialize GA4 client and batcher
                val newClient = clientFactory?.invoke() ?: GA4Client(config, scope = scope)
                ga4Client = newClient
                
                // Create custom event batcher
                eventBatcher = EventBatcher(
                    config = config,
                    onBatchReady = { batch ->
                        sendBatch(newClient, batch)
                    },
                    flushInterval = flushInterval,
                    scope = scope
                )
                
                // Subscribe to router state changes if routing analytics is enabled
                if (enableRoutingAnalytics) {
                    if (routerMiddleware != null) {
                        // Direct integration with RouterMiddleware
                        subscribeToRouterStateFlow(routerMiddleware.state)
                    } else {
                        // Action-based integration (will listen for Routing.StateChanged)
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
        // Ensure middleware is initialized
        if (!isInitialized) {
            onStoreCreated(store)
        }
        
        // Handle routing actions if analytics is enabled and no direct middleware integration
        if (enableRoutingAnalytics && routerMiddleware == null && action is Routing.StateChanged) {
            handleRoutingStateChanged(action.routerState)
        }
        
        // Process action before state change
        processAction(action, store.state.value, beforeStateChange = true)
        
        // Let the action proceed
        val result = next(action)
        
        // Process action after state change
        processAction(action, store.state.value, beforeStateChange = false)
        
        return result
    }
    
    suspend fun onDetach() {
        initMutex.withLock {
            logger.info { "Detaching GA4Middleware, cleaning up resources" }
            
            // Track final screen time if needed
            currentScreen?.let { screen ->
                screenStartTime?.let { startTime ->
                    val duration = Clock.System.now() - startTime
                    logger.debug(screen, duration.inWholeSeconds) {
                        "Tracking final screen time for {screen}: {duration}s"
                    }
                    trackScreenTime(screen, duration)
                }
            }
            
            // Cancel router state job and wait for it to complete
            routerStateJob?.let { job ->
                job.cancel()
                job.join()
            }
            routerStateJob = null
            
            // Flush remaining events
            eventBatcher?.flushAll()
            
            // Stop batcher and close client
            eventBatcher?.stop()
            ga4Client?.close()
            
            // Clear references
            ga4Client = null
            eventBatcher = null
            // Note: We don't cancel the scope as it may be shared with other components
            isInitialized = false
            
            logger.info { "GA4Middleware detached successfully" }
        }
    }
    
    private suspend fun processAction(
        action: Any,
        state: TState,
        beforeStateChange: Boolean
    ) {
        try {
            // Use custom event mapper if provided
            val events = when {
                eventMapper != null -> {
                    if (beforeStateChange) {
                        eventMapper.mapActionBefore(action, state)
                    } else {
                        eventMapper.mapActionAfter(action, state)
                    }
                }
                else -> {
                    // No event mapper: do not track actions (routing analytics still works separately).
                    // Opt in via DefaultEventMapper / patternMapper / custom EventMapper.
                    emptyList()
                }
            }
            
            // Send events if any
            if (events.isNotEmpty()) {
                logger.debug(events.size, action::class.simpleName ?: "unknown") {
                    "Mapped {eventCount} events for action: {actionType}"
                }
                
                val clientId = clientIdProvider(state)
                val userId = userIdProvider(state)
                val userProperties = userPropertiesProvider(state)
                
                events.forEach { event ->
                    if (eventBatcher != null) {
                        eventBatcher?.addEvent(event, clientId, userId, userProperties)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e, action::class.simpleName ?: "unknown") {
                "Error processing action {actionType}"
            }
            handleError(GA4MiddlewareError.MappingError(action, e))
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
            // Skip if this is the same state (no actual navigation occurred)
            if (routerState == previousRouterState) return
            
            val previousScreen = currentScreen
            val newScreen = convertToScreenName(routerState)
            
            // Track screen time for previous screen
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
            
            // Generate appropriate events based on the state change
            val events = mutableListOf<GA4Event>()
            
            // Always track screen view
            events.add(createScreenViewEvent(routerState, previousScreen))
            
            // Track navigation event if we have a previous screen
            if (previousScreen != null && previousScreen != newScreen) {
                events.add(createNavigationEvent(routerState, previousScreen))
            }
            
            // Track modal events
            if (routerState.modalRoutes.isNotEmpty()) {
                val previousModalCount = previousRouterState?.modalRoutes?.size ?: 0
                val currentModalCount = routerState.modalRoutes.size
                
                when {
                    currentModalCount > previousModalCount -> {
                        // Modal opened
                        createModalEvent(routerState, ModalAction.OPEN)?.let { events.add(it) }
                    }
                    currentModalCount < previousModalCount && routerState.lastRouteType == RouteType.Back -> {
                        // Modal dismissed via back
                        previousRouterState?.modalRoutes?.lastOrNull()?.let { dismissedModal ->
                            createModalEvent(previousRouterState!!, ModalAction.DISMISS, dismissedModal)?.let { 
                                events.add(it) 
                            }
                        }
                    }
                }
            }
            
            // Track tab events if applicable
            createTabEvent(routerState)?.let { tabEvent ->
                events.add(tabEvent)
            }
            
            // Send all events
            // Note: We don't have access to the current state here, so we pass null
            // The providers will be called with the actual state in processAction
            events.forEach { event ->
                eventBatcher?.addEvent(event, null, null)
            }
            
            // Update tracking state
            currentScreen = newScreen
            screenStartTime = Clock.System.now()
            previousRouterState = routerState
            
        } catch (e: Exception) {
            logger.warn(e) { "Error handling router state change" }
        }
    }
    
    private suspend fun handleRoutingStateChanged(routerState: RouterState) {
        // This is called when we receive a Routing.StateChanged action
        // (action-based integration)
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
            eventBatcher?.addEvent(event, null, null)
        }
    }
    
    private suspend fun sendBatch(client: IGA4Client, batch: List<BatchedEvent>) {
        try {
            // Group by client/user ID and send
            val grouped = batch.groupBy { 
                BatchKey(it.clientId, it.userId)
            }
            
            grouped.forEach { (key, events) ->
                logger.debug(events.size, key.clientId ?: "null", key.userId ?: "null") {
                    "Sending batch of {eventCount} events for clientId: {clientId}, userId: {userId}"
                }
                
                client.sendEvents(
                    events = events.map { it.event },
                    clientId = key.clientId,
                    userId = key.userId,
                    immediate = true,
                    userProperties = events.firstNotNullOfOrNull { it.userProperties }
                ).onFailure { error ->
                    logger.error(error, events.size) {
                        "Failed to send batch of {eventCount} events"
                    }
                    handleError(GA4MiddlewareError.SendError(events, error))
                }
            }
        } catch (e: Exception) {
            logger.error(e, batch.size) {
                "Error processing batch of {batchSize} events"
            }
            handleError(GA4MiddlewareError.BatchError(batch, e))
        }
    }
    
    private suspend fun handleError(error: GA4MiddlewareError) {
        try {
            errorFlow.emit(error)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to emit error to error flow" }
        }
    }
    
    private data class BatchKey(
        val clientId: String?,
        val userId: String?
    )
}

/**
 * Represents different types of GA4 middleware errors
 */
sealed class GA4MiddlewareError {
    data class MappingError(val action: Any, val cause: Throwable) : GA4MiddlewareError()
    data class SendError(val events: List<BatchedEvent>, val cause: Throwable) : GA4MiddlewareError()
    data class BatchError(val batch: List<BatchedEvent>, val cause: Throwable) : GA4MiddlewareError()
}

// Helper functions to work with RouterState
private fun convertToScreenName(routerState: RouterState): String? {
    // Priority: Modal > Content > Scene
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
        else -> NavigationLayer.Content.name // Default
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
            
            // Add previous route if available
            previousRoute?.let {
                put("previous_screen", EventParamValue.StringValue(it))
            }
            
            // Add modal information
            if (routerState.modalRoutes.isNotEmpty()) {
                put("is_modal", EventParamValue.BooleanValue(true))
                put("modal_count", EventParamValue.NumberValue(routerState.modalRoutes.size.toDouble()))
                val modal = routerState.modalRoutes.last()
                put("modal_route", EventParamValue.StringValue(modal.route?.path ?: "unknown"))
            }
            
            // Add navigation layer info
            put("navigation_layer", EventParamValue.StringValue(getActiveLayerName(routerState).lowercase()))
            
            // Add navigation depth
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
    
    return GA4Event(
        name = "navigation",
        params = buildMap {
            put("from_screen", EventParamValue.StringValue(fromRoute))
            put("to_screen", EventParamValue.StringValue(convertToScreenName(routerState) ?: "unknown"))
            put("navigation_type", EventParamValue.StringValue(navigationType.analyticsName))
            
            // Add route type info
            routerState.lastRouteType?.let { routeType ->
                put("route_type", EventParamValue.StringValue(routeType.name.lowercase()))
            }
            
            // Add stack information for each layer
            put("scene_stack_size", EventParamValue.NumberValue(routerState.sceneRoutes.size.toDouble()))
            put("content_stack_size", EventParamValue.NumberValue(routerState.contentRoutes.size.toDouble()))
            put("modal_stack_size", EventParamValue.NumberValue(routerState.modalRoutes.size.toDouble()))
            
            // Add active layer
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
            
            // Add parent screen (the content behind the modal)
            val parentScreen = routerState.contentRoutes.lastOrNull()?.route?.path 
                ?: routerState.sceneRoutes.lastOrNull()?.route?.path ?: "unknown"
            put("parent_screen", EventParamValue.StringValue(parentScreen.removePrefix("/")))
            
            // Add modal stack depth
            put("modal_stack_depth", EventParamValue.NumberValue(routerState.modalRoutes.size.toDouble()))
        }
    )
}

private fun createTabEvent(routerState: RouterState): GA4Event? {
    // Tab tracking requires a specific config format
    // For now, only support Map<String, Any> configs with selectedTab
    val config = routerState.getCurrentContentRoute()?.route?.config as? Map<*, *>
    val currentTab = config?.get("selectedTab") as? String
    
    // Only create event if we have tab information
    if (currentTab == null) return null
    
    return GA4Event(
        name = "tab_switch",
        params = buildMap {
            put("tab_name", EventParamValue.StringValue(currentTab))
            put("screen_name", EventParamValue.StringValue(convertToScreenName(routerState) ?: "unknown"))
            
            // Add current route as tab container
            routerState.getCurrentContentRoute()?.let { route ->
                put("tab_container", EventParamValue.StringValue(route.route?.path ?: "unknown"))
            }
        }
    )
}
