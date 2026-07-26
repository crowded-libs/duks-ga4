package duks.ga4.middleware

import duks.*
import duks.ga4.client.EventQueueStore
import duks.ga4.client.IGA4Client
import duks.ga4.config.GA4Config
import duks.ga4.config.PrivacyConfig
import duks.ga4.config.ValidationMode
import duks.ga4.model.ContextProvider
import duks.ga4.model.UserPropertyValue
import duks.ga4.privacy.ConsentManager
import duks.ga4.privacy.ConsentStorage
import duks.ga4.privacy.DefaultConsentManager
import duks.ga4.privacy.InMemoryConsentStorage
import duks.ga4.util.ClientIdStore
import duks.routing.RouterMiddleware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * DSL builder for creating GA4 middleware with a fluent API
 * 
 * @param TState The type of the store state
 */
class GA4MiddlewareBuilder<TState : StateModel> {
    private var scope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job())
    private var config: GA4Config? = null
    private var eventMapper: EventMapper<TState>? = null
    private var enableRoutingAnalytics: Boolean = true
    private var flushInterval: Duration = 10.seconds
    private var clientIdProvider: suspend (TState) -> String? = { null }
    private var userIdProvider: suspend (TState) -> String? = { null }
    private var userPropertiesProvider: suspend (TState) -> Map<String, UserPropertyValue>? = { null }
    private var consentManager: ConsentManager? = null
    private var enablePrivacy: Boolean = false
    private var privacyConfig: PrivacyConfig? = null
    private var routerMiddleware: RouterMiddleware<TState>? = null
    private var clientFactory: (() -> IGA4Client)? = null
    private var contextProvider: ContextProvider? = null
    private var eventQueueStore: EventQueueStore? = null
    
    private val customMappers = mutableListOf<EventMapper<TState>>()
    private val actionFilters = mutableListOf<(Any) -> Boolean>()
    
    /**
     * Sets the GA4 configuration
     */
    fun config(config: GA4Config) = apply {
        this.config = config
    }
    
    /**
     * Creates GA4 config using a builder
     */
    fun config(builder: GA4ConfigBuilder.() -> Unit) = apply {
        this.config = GA4ConfigBuilder().apply(builder).build()
    }
    
    /**
     * Sets a custom event mapper
     */
    fun eventMapper(mapper: EventMapper<TState>) = apply {
        this.eventMapper = mapper
    }
    
    /**
     * Uses the default event mapper
     */
    fun useDefaultEventMapper() = apply {
        this.eventMapper = DefaultEventMapper()
    }
    
    /**
     * Creates a pattern-based event mapper
     */
    fun patternMapper(builder: PatternEventMapper<TState>.() -> Unit) = apply {
        this.eventMapper = PatternEventMapper<TState>().apply(builder)
    }
    
    /**
     * Adds a custom mapper that will be combined with others
     */
    fun addMapper(mapper: EventMapper<TState>) = apply {
        customMappers.add(mapper)
    }
    
    /**
     * Enables or disables routing analytics
     */
    fun enableRoutingAnalytics(enable: Boolean = true) = apply {
        this.enableRoutingAnalytics = enable
    }
    
    /**
     * Sets the flush interval for batching events
     */
    fun flushInterval(interval: Duration) = apply {
        this.flushInterval = interval
    }
    
    /**
     * Sets the client ID provider
     */
    fun clientIdProvider(provider: suspend (TState) -> String?) = apply {
        this.clientIdProvider = provider
    }
    
    /**
     * Sets the user ID provider
     */
    fun userIdProvider(provider: suspend (TState) -> String?) = apply {
        this.userIdProvider = provider
    }

    /**
     * Sets the user properties provider (attached to Measurement Protocol requests)
     */
    fun userPropertiesProvider(provider: suspend (TState) -> Map<String, UserPropertyValue>?) = apply {
        this.userPropertiesProvider = provider
    }
    
    /**
     * Sets the RouterMiddleware instance for direct integration
     */
    fun routerMiddleware(middleware: RouterMiddleware<TState>) = apply {
        this.routerMiddleware = middleware
    }
    
    /**
     * Sets a custom GA4 client factory (mainly for testing)
     */
    internal fun clientFactory(factory: () -> IGA4Client) = apply {
        this.clientFactory = factory
    }

    /**
     * Optional device / geo / IP context attached to Measurement Protocol requests.
     */
    fun contextProvider(provider: ContextProvider) = apply {
        this.contextProvider = provider
    }

    /**
     * Optional durable event queue (e.g. app-provided kotlin-lmdb adapter).
     */
    fun eventQueueStore(store: EventQueueStore) = apply {
        this.eventQueueStore = store
    }
    
    /**
     * Filters actions - only matched actions will be tracked
     */
    fun filterActions(predicate: (Any) -> Boolean) = apply {
        actionFilters.add(predicate)
    }

    fun scope(scope: CoroutineScope) {
        this.scope = scope
    }
    
    /**
     * Only tracks actions of specific types
     */
    inline fun <reified TAction : Any> trackOnly() = apply {
        filterActions { it is TAction }
    }
    
    /**
     * Excludes actions of specific types
     */
    inline fun <reified TAction : Any> exclude() = apply {
        filterActions { it !is TAction }
    }
    
    /**
     * Enables privacy features with consent management.
     *
     * Turns on consent enforcement + PII scrubbing on the client pipeline and
     * gates middleware tracking when analytics consent is denied.
     */
    fun enablePrivacy(
        consentManager: ConsentManager? = null,
        consentStorage: ConsentStorage? = null,
        scrubPii: Boolean = true
    ) = apply {
        this.enablePrivacy = true
        this.consentManager = consentManager ?: DefaultConsentManager(
            storage = consentStorage ?: InMemoryConsentStorage()
        )
        // Merge privacy flags into config when present
        config = config?.copy(
            privacyConfig = (config?.privacyConfig ?: PrivacyConfig()).copy(
                enforceConsent = true,
                scrubPii = scrubPii
            )
        ) ?: config
        privacyConfig = PrivacyConfig(enforceConsent = true, scrubPii = scrubPii)
    }
    
    /**
     * Builds the GA4 middleware
     */
    fun build(): Middleware<TState> {
        var finalConfig = config ?: throw IllegalStateException("GA4 config is required")

        if (enablePrivacy) {
            val privacy = privacyConfig ?: PrivacyConfig(enforceConsent = true, scrubPii = true)
            finalConfig = finalConfig.copy(
                privacyConfig = privacy,
                flushInterval = flushInterval
            )
        } else {
            finalConfig = finalConfig.copy(flushInterval = flushInterval)
        }
        
        // Combine all mappers
        val finalMapper = when {
            customMappers.isEmpty() && eventMapper != null -> eventMapper
            customMappers.isNotEmpty() && eventMapper == null -> CompositeEventMapper(customMappers)
            customMappers.isNotEmpty() && eventMapper != null -> {
                CompositeEventMapper(listOf(eventMapper!!) + customMappers)
            }
            else -> null
        }
        
        // Apply filters if any
        val filteredMapper = if (actionFilters.isNotEmpty() && finalMapper != null) {
            val combinedFilter: (Any) -> Boolean = { action ->
                actionFilters.all { it(action) }
            }
            FilteringEventMapper(finalMapper, combinedFilter)
        } else {
            finalMapper
        }
        
        // Create base middleware — client owns the queue
        val baseMiddleware = GA4Middleware(
            config = finalConfig,
            eventMapper = filteredMapper,
            enableRoutingAnalytics = enableRoutingAnalytics,
            routerMiddleware = routerMiddleware,
            flushInterval = flushInterval,
            clientIdProvider = clientIdProvider,
            userIdProvider = userIdProvider,
            userPropertiesProvider = userPropertiesProvider,
            clientFactory = clientFactory,
            consentManager = if (enablePrivacy) consentManager else null,
            contextProvider = contextProvider,
            eventQueueStore = eventQueueStore,
            scope = scope
        )
        
        // Wrap with privacy if needed (middleware-level gate)
        return if (enablePrivacy && consentManager != null) {
            PrivacyAwareGA4Middleware(baseMiddleware, consentManager!!)
        } else {
            baseMiddleware
        }
    }
}

/**
 * Privacy-aware wrapper that gates tracking on analytics consent while
 * always forwarding store lifecycle (so detach/flush still runs).
 */
private class PrivacyAwareGA4Middleware<TState : StateModel>(
    private val baseMiddleware: GA4Middleware<TState>,
    private val consentManager: ConsentManager
) : Middleware<TState>, StoreLifecycleAware<TState> {
    
    override suspend fun onStoreCreated(store: KStore<TState>) {
        baseMiddleware.onStoreCreated(store)
    }
    
    override suspend fun invoke(
        store: KStore<TState>,
        next: suspend (Action) -> Action,
        action: Action
    ): Action {
        // Only track if analytics consent is granted; always pass the action through
        return if (consentManager.analyticsEnabled.value) {
            baseMiddleware.invoke(store, next, action)
        } else {
            next(action)
        }
    }
}

/**
 * Builder for GA4Config
 */
class GA4ConfigBuilder {
    private var measurementId: String? = null
    private var apiSecret: String? = null
    private var debugMode: Boolean = false
    private var enableRetry: Boolean = true
    private var maxRetries: Int = 3
    private var retryDelayMs: Long = 1000
    private var maxEventsPerBatch: Int = 25
    private var requestTimeoutMs: Long = 30000
    private var customEndpoint: String? = null
    private var defaultClientId: String? = null
    private var autoGenerateClientId: Boolean = true
    private var privacyConfig: PrivacyConfig? = null
    private var validationMode: ValidationMode = ValidationMode.LOG
    private var attachSessionParams: Boolean = true
    private var clientIdStore: ClientIdStore? = null
    private var preferPageViewForWeb: Boolean = true
    
    fun measurementId(id: String) = apply { measurementId = id }
    fun apiSecret(secret: String) = apply { apiSecret = secret }
    fun debugMode(enabled: Boolean = true) = apply { debugMode = enabled }
    fun enableRetry(enabled: Boolean = true) = apply { enableRetry = enabled }
    fun maxRetries(max: Int) = apply { maxRetries = max }
    fun retryDelay(delayMs: Long) = apply { retryDelayMs = delayMs }
    fun maxEventsPerBatch(max: Int) = apply { maxEventsPerBatch = max }
    fun requestTimeout(timeoutMs: Long) = apply { requestTimeoutMs = timeoutMs }
    fun customEndpoint(endpoint: String) = apply { customEndpoint = endpoint }
    fun defaultClientId(clientId: String) = apply { defaultClientId = clientId }
    fun autoGenerateClientId(enabled: Boolean = true) = apply { autoGenerateClientId = enabled }
    fun privacyConfig(config: PrivacyConfig) = apply { privacyConfig = config }
    fun validationMode(mode: ValidationMode) = apply { validationMode = mode }
    fun attachSessionParams(enabled: Boolean = true) = apply { attachSessionParams = enabled }
    fun clientIdStore(store: ClientIdStore) = apply { clientIdStore = store }
    fun preferPageViewForWeb(enabled: Boolean = true) = apply { preferPageViewForWeb = enabled }
    
    fun build(): GA4Config {
        return GA4Config(
            measurementId = measurementId ?: throw IllegalStateException("measurementId is required"),
            apiSecret = apiSecret ?: throw IllegalStateException("apiSecret is required"),
            debugMode = debugMode,
            enableRetry = enableRetry,
            maxRetries = maxRetries,
            retryDelayMs = retryDelayMs,
            maxEventsPerBatch = maxEventsPerBatch,
            requestTimeoutMs = requestTimeoutMs,
            customEndpoint = customEndpoint,
            defaultClientId = defaultClientId,
            autoGenerateClientId = autoGenerateClientId,
            clientIdStore = clientIdStore,
            privacyConfig = privacyConfig ?: PrivacyConfig(),
            validationMode = validationMode,
            attachSessionParams = attachSessionParams,
            preferPageViewForWeb = preferPageViewForWeb
        )
    }
}

/**
 * Creates a GA4 middleware using a builder DSL
 * 
 * Example:
 * ```kotlin
 * val middleware = ga4Middleware<AppState> {
 *     config {
 *         measurementId("G-XXXXXXXXXX")
 *         apiSecret("your-api-secret")
 *         debugMode()
 *     }
 *     
 *     useDefaultEventMapper()
 *     enableRoutingAnalytics()
 *     
 *     clientIdProvider { state -> state.user?.id }
 *     userIdProvider { state -> state.user?.analyticsId }
 *     
 *     exclude<InternalAction>()
 *     
 *     flushInterval(5.seconds)
 * }
 * ```
 */
fun <TState : StateModel> ga4Middleware(
    builder: GA4MiddlewareBuilder<TState>.() -> Unit
): Middleware<TState> {
    return GA4MiddlewareBuilder<TState>().apply(builder).build()
}

/**
 * Creates a GA4 middleware with pattern-based event mapping
 * 
 * Example:
 * ```kotlin
 * val middleware = ga4MiddlewareWithPatterns<AppState> {
 *     config {
 *         measurementId("G-XXXXXXXXXX")
 *         apiSecret("your-api-secret")
 *     }
 *     
 *     pattern<UserAction.Login> { action, state ->
 *         listOf(
 *             GA4Event(
 *                 name = "login",
 *                 params = mapOf(
 *                     "method" to action.method,
 *                     "user_id" to action.userId
 *                 )
 *             )
 *         )
 *     }
 *     
 *     pattern<CommerceAction.Purchase> { action, state ->
 *         listOf(
 *             GA4Event(
 *                 name = "purchase",
 *                 params = mapOf(
 *                     "transaction_id" to action.transactionId,
 *                     "value" to action.totalValue
 *                 )
 *             )
 *         )
 *     }
 * }
 * ```
 */
fun <TState : StateModel> ga4MiddlewareWithPatterns(
    config: GA4Config,
    builder: PatternEventMapper<TState>.() -> Unit
): Middleware<TState> {
    return ga4Middleware {
        config(config)
        patternMapper(builder)
    }
}

/**
 * Extension function for StoreBuilder to add GA4 middleware with automatic RouterMiddleware integration
 * 
 * Example:
 * ```kotlin
 * val store = createStore(AppState()) {
 *     val router = routing {
 *         content("/home") { HomeScreen() }
 *         content("/profile") { ProfileScreen() }
 *     }
 *     
 *     ga4Analytics {
 *         config {
 *             measurementId("G-XXXXXXXXXX")
 *             apiSecret("your-api-secret")
 *         }
 *         routerMiddleware(router) // Automatic integration
 *     }
 * }
 * ```
 */
fun <TState : StateModel> StoreBuilder<TState>.ga4Analytics(
    builder: GA4MiddlewareBuilder<TState>.() -> Unit
) {
    val ga4Middleware = GA4MiddlewareBuilder<TState>().apply(builder).build()
    
    middleware {
        middleware(ga4Middleware)
        if (ga4Middleware is StoreLifecycleAware<*>) {
            @Suppress("UNCHECKED_CAST")
            lifecycleAware(ga4Middleware as StoreLifecycleAware<TState>)
        }
    }
}