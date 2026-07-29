package duks.ga4.middleware

import duks.*
import duks.ga4.client.EventQueueStore
import duks.ga4.client.GA4Client
import duks.ga4.client.IGA4Client
import duks.ga4.config.GA4Config
import duks.ga4.model.ContextProvider
import duks.ga4.model.GA4Event
import duks.ga4.model.UserPropertyValue
import duks.ga4.privacy.ConsentManager
import duks.logging.*
import duks.routing.RouterMiddleware
import duks.routing.RouterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Middleware that integrates Google Analytics 4 with duks stores.
 *
 * Intercepts actions, maps them to GA4 events, and optionally tracks routing via a
 * [duks.routing.NavigationListener] registered with [trackRouting].
 * Uses the [IGA4Client] queue as the **single** batching owner (no separate middleware batcher).
 *
 * @param TState The type of the store state
 * @param config GA4 configuration
 * @param eventMapper Optional custom event mapper for actions
 * @param trackedRouter Router whose [RouterMiddleware.addNavigationListener] is used when tracking
 * @param routingListener Listener that receives router transitions (created or supplied by builder)
 * @param ownsRouterListener When true, this middleware adds/removes [routingListener] on [trackedRouter]
 * @param flushInterval How often the client queue auto-flushes (passed into [GA4Client])
 * @param clientIdProvider Optional provider for client IDs
 * @param userIdProvider Optional provider for user IDs
 * @param consentManager Optional consent manager wired into the client when privacy is enabled
 */
class GA4Middleware<TState : StateModel>(
    private val config: GA4Config,
    private val eventMapper: EventMapper<TState>? = null,
    private val trackedRouter: RouterMiddleware<*>? = null,
    private val routingListener: Ga4RoutingListener? = null,
    private val ownsRouterListener: Boolean = false,
    private val flushInterval: Duration = config.flushInterval,
    private val clientIdProvider: suspend (TState) -> String? = { null },
    private val userIdProvider: suspend (TState) -> String? = { null },
    private val userPropertiesProvider: suspend (TState) -> Map<String, UserPropertyValue>? = { null },
    private val clientFactory: (() -> IGA4Client)? = null,
    private val consentManager: ConsentManager? = null,
    private val contextProvider: ContextProvider? = null,
    private val eventQueueStore: EventQueueStore? = null,
    private val scope: CoroutineScope
) : Middleware<TState>, StoreLifecycleAware<TState> {

    private val logger = Logger.default()
    private var ga4Client: IGA4Client? = null
    private var storeRef: KStore<TState>? = null
    private var routingJob: Job? = null
    internal var isInitialized = false
    /** Once [onDetach] runs, do not re-register listeners or recreate the client. */
    private var isDetached = false
    private val initMutex = Mutex()

    private var currentScreen: String? = null
    private var screenStartTime: Instant? = null

    private val errorFlow = MutableSharedFlow<GA4MiddlewareError>(replay = 1, extraBufferCapacity = 10)
    val errors: SharedFlow<GA4MiddlewareError> = errorFlow.asSharedFlow()

    /** Expose for testing */
    internal suspend fun flushEvents() {
        ga4Client?.flush()
    }

    override suspend fun onStoreCreated(store: KStore<TState>) {
        initMutex.withLock {
            if (isInitialized || isDetached) return

            try {
                logger.info(config.measurementId, routingListener != null) {
                    "Initializing GA4Middleware for measurementId: {measurementId}, routingAnalytics: {routingAnalytics}"
                }

                storeRef = store

                val newClient = clientFactory?.invoke() ?: GA4Client(
                    config = config.copy(flushInterval = flushInterval),
                    scope = scope,
                    consentManager = consentManager,
                    contextProvider = contextProvider,
                    eventQueueStore = eventQueueStore,
                    flushInterval = flushInterval
                )
                ga4Client = newClient

                val listener = routingListener
                if (listener != null) {
                    listener.attach { previous, current, action ->
                        // Keep dispatch path non-blocking; enqueue on middleware scope
                        scope.launch {
                            try {
                                handleRouterTransition(previous, current, action)
                            } catch (e: Exception) {
                                logger.error(e) { "Error handling router state change" }
                            }
                        }
                    }
                    if (ownsRouterListener && trackedRouter != null) {
                        trackedRouter.addNavigationListener(listener)
                        logger.debug { "Registered Ga4RoutingListener on RouterMiddleware" }
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

    override suspend fun onStoreDestroyed() {
        onDetach()
    }

    override suspend fun invoke(
        store: KStore<TState>,
        next: suspend (Action) -> Action,
        action: Action
    ): Action {
        if (!isInitialized && !isDetached) {
            onStoreCreated(store)
        }

        if (isDetached) {
            return next(action)
        }

        val mapper = eventMapper
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
            if (isDetached) return

            logger.info { "Detaching GA4Middleware, cleaning up resources" }
            isDetached = true

            currentScreen?.let { screen ->
                screenStartTime?.let { startTime ->
                    val duration = Clock.System.now() - startTime
                    logger.debug(screen, duration.inWholeSeconds) {
                        "Tracking final screen time for {screen}: {duration}s"
                    }
                    trackScreenTime(screen, duration)
                }
            }
            currentScreen = null
            screenStartTime = null

            if (ownsRouterListener && trackedRouter != null && routingListener != null) {
                trackedRouter.removeNavigationListener(routingListener)
            }
            routingListener?.detach()

            routingJob?.let { job ->
                job.cancel()
                job.join()
            }
            routingJob = null

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

    private suspend fun handleRouterTransition(
        previous: RouterState,
        current: RouterState,
        action: Action
    ) {
        try {
            if (previous == current) return

            val previousScreen = currentScreen
            val newScreen = current.toScreenName()

            previousScreen?.let { screen ->
                screenStartTime?.let { startTime ->
                    val duration = Clock.System.now() - startTime
                    logger.debug(screen, duration.inWholeSeconds) {
                        "Previous screen {screen} was active for {duration}s"
                    }
                    trackScreenTime(screen, duration)
                }
            }

            logger.debug(newScreen, current.getActiveLayer().name) {
                "Router state changed to: {screen} on layer: {layer}"
            }

            val events = buildRoutingAnalyticsEvents(
                previous = previous,
                current = current,
                action = action,
                previousScreenName = previousScreen
            )

            val state = storeRef?.state?.value
            enqueueEvents(events, state)

            currentScreen = newScreen
            screenStartTime = Clock.System.now()
        } catch (e: Exception) {
            logger.warn(e) { "Error handling router state change" }
        }
    }

    private suspend fun trackScreenTime(screenName: String, duration: Duration) {
        val durationMillis = duration.inWholeMilliseconds
        if (durationMillis > 0) {
            val event = createScreenTimeEvent(
                screenName = screenName,
                durationSeconds = duration.inWholeSeconds.coerceAtLeast(0),
                durationMillis = durationMillis
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
