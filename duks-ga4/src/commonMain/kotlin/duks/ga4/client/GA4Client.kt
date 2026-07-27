package duks.ga4.client

import duks.ga4.config.GA4Config
import duks.ga4.model.*
import duks.ga4.privacy.ConsentManager
import duks.ga4.privacy.PiiScrubber
import duks.ga4.util.ClientIdGenerator
import duks.logging.*
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlin.math.pow
import kotlin.time.Duration

/**
 * Main client for sending events to Google Analytics 4.
 *
 * Owns the sole event queue ([EventBatcher]). Middleware and other callers should
 * enqueue via [sendEvent]/[sendEvents] with `immediate = false` rather than
 * maintaining a separate batcher.
 */
class GA4Client(
    private val config: GA4Config,
    engine: HttpClientEngine? = null,
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager = DefaultSessionManager(
        sessionTimeout = config.sessionTimeout
    ),
    /**
     * Optional consent manager. When [duks.ga4.config.PrivacyConfig.enforceConsent]
     * is true and this is non-null, events are dropped without analytics consent.
     */
    private val consentManager: ConsentManager? = null,
    /**
     * Optional device / geo / IP context for each Measurement Protocol request.
     */
    private val contextProvider: ContextProvider? = null,
    /**
     * Optional durable queue store (e.g. app-provided kotlin-lmdb adapter).
     */
    eventQueueStore: EventQueueStore? = null,
    flushInterval: Duration = config.flushInterval
) : IGA4Client {

    private val logger = Logger.default()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
        explicitNulls = false
    }

    private val httpClient = if (engine != null) {
        HttpClient(engine) {
            installDefaults()
        }
    } else {
        HttpClient {
            installDefaults()
        }
    }

    private fun HttpClientConfig<*>.installDefaults() {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMs
            connectTimeoutMillis = config.requestTimeoutMs / 2
            socketTimeoutMillis = config.requestTimeoutMs
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    private val clientIdGenerator = ClientIdGenerator(config.clientIdStore)
    private val eventValidator = EventValidator(
        mode = config.validationMode,
        preferPageViewForWeb = config.preferPageViewForWeb
    )
    private val piiScrubber: PiiScrubber? =
        if (config.privacyConfig.scrubPii) {
            PiiScrubber(config.privacyConfig.piiScrubberConfig)
        } else null

    private val batcher = EventBatcher(
        config = config,
        onBatchReady = { batch -> deliverBatch(batch) },
        flushInterval = flushInterval,
        scope = scope,
        eventQueueStore = eventQueueStore
    )

    /** Snapshot of batcher stats (sent / failed / requeued / dropped). */
    val batcherStats get() = batcher.stats

    init {
        logger.info(config.measurementId, config.debugMode) {
            "GA4Client initialized with measurementId: {measurementId}, debugMode: {debugMode}"
        }
        if (eventQueueStore != null) {
            scope.launch {
                try {
                    batcher.restoreFromStore()
                } catch (e: Exception) {
                    logger.error(e) { "Failed to restore durable event queue" }
                }
            }
        }
    }

    override suspend fun sendEvent(
        event: GA4Event,
        clientId: String?,
        userId: String?,
        immediate: Boolean,
        userProperties: Map<String, duks.ga4.model.UserPropertyValue>?
    ): Result<Unit> = runCatching {
        if (!hasAnalyticsConsent()) {
            logger.debug(event.name) {
                "Event dropped — analytics consent not granted: {eventName}"
            }
            return@runCatching
        }

        val finalClientId = resolveClientId(clientId)
        val prepared = prepareEvents(listOf(event))
        if (prepared.isEmpty()) {
            logger.warn(event.name) { "Event dropped after validation: {eventName}" }
            return@runCatching
        }
        val preparedEvent = prepared.single()

        logger.debug(preparedEvent.name, finalClientId, immediate) {
            "Sending event: {eventName}, clientId: {clientId}, immediate: {immediate}"
        }

        if (immediate) {
            val request = createRequest(listOf(preparedEvent), finalClientId, userId, userProperties)
            sendRequest(request)
        } else {
            val added = batcher.addEvent(preparedEvent, finalClientId, userId, userProperties)
            if (!added) {
                logger.warn(preparedEvent.name) {
                    "Event queue is full, cannot add event: {eventName}"
                }
                throw IllegalStateException("Event queue is full")
            }
        }
    }

    override suspend fun sendEvents(
        events: List<GA4Event>,
        clientId: String?,
        userId: String?,
        immediate: Boolean,
        userProperties: Map<String, duks.ga4.model.UserPropertyValue>?
    ): Result<Unit> = runCatching {
        require(events.isNotEmpty()) { "Events list cannot be empty" }

        if (!hasAnalyticsConsent()) {
            logger.debug(events.size) {
                "Batch of {count} events dropped — analytics consent not granted"
            }
            return@runCatching
        }

        val finalClientId = resolveClientId(clientId)
        val prepared = prepareEvents(events)
        if (prepared.isEmpty()) {
            logger.warn(events.size) { "All {count} events dropped after validation" }
            return@runCatching
        }

        logger.debug(prepared.size, finalClientId, immediate) {
            "Sending {eventCount} events, clientId: {clientId}, immediate: {immediate}"
        }

        if (immediate) {
            prepared.chunked(config.maxEventsPerBatch).forEach { batch ->
                val request = createRequest(batch, finalClientId, userId, userProperties)
                sendRequest(request)
            }
        } else {
            val added = batcher.addEvents(prepared, finalClientId, userId, userProperties)
            if (added < prepared.size) {
                logger.warn(added, prepared.size) {
                    "Could only add {added} of {total} events to queue"
                }
                throw IllegalStateException("Could only add $added of ${prepared.size} events to queue")
            }
        }
    }

    override suspend fun flush() {
        logger.debug { "Flushing all pending events" }
        batcher.flushAll()
    }

    override suspend fun getQueueSize(): Int = batcher.getQueueSize()

    override suspend fun close() {
        logger.info { "Closing GA4Client" }
        flush()
        batcher.stop()
        httpClient.close()
    }

    private fun hasAnalyticsConsent(): Boolean {
        if (!config.privacyConfig.enforceConsent) return true
        val manager = consentManager ?: return true
        return manager.analyticsEnabled.value
    }

    private suspend fun resolveClientId(clientId: String?): String {
        return clientId
            ?: config.defaultClientId
            ?: if (config.autoGenerateClientId) {
                clientIdGenerator.getOrCreate()
            } else {
                throw IllegalArgumentException("Client ID is required")
            }
    }

    /**
     * Scrubs (opt-in), attaches session params, validates.
     */
    private fun prepareEvents(events: List<GA4Event>): List<GA4Event> {
        val scrubbed = if (piiScrubber != null) {
            events.map { piiScrubber.scrubEvent(it) }
        } else {
            events
        }

        val withSession = if (config.attachSessionParams) {
            val sid = sessionManager.touch()
            scrubbed.map { attachSessionParams(it, sid) }
        } else {
            scrubbed
        }
        val withDebug =
            if (config.debugMode) {
                withSession.map { attachDebugMode(it) }
            } else {
                withSession
            }
        return eventValidator.validate(withDebug)
    }

    private fun attachSessionParams(event: GA4Event, sessionId: String): GA4Event {
        val params = event.params.toMutableMap()
        var changed = false

        if (!params.containsKey("session_id")) {
            params["session_id"] = EventParamValue.StringValue(sessionId)
            changed = true
        }
        if (!params.containsKey("engagement_time_msec") && config.defaultEngagementTimeMsec > 0) {
            params["engagement_time_msec"] =
                EventParamValue.NumberValue(config.defaultEngagementTimeMsec.toDouble())
            changed = true
        }

        return if (changed) event.copy(params = params) else event
    }

    private fun attachDebugMode(event: GA4Event): GA4Event {
        if (event.params.containsKey("debug_mode")) return event
        return event.copy(
            params = event.params + ("debug_mode" to EventParamValue.NumberValue(1.0)),
        )
    }

    /**
     * Transport for a dequeued batch. Does not requeue — [EventBatcher] owns retries.
     */
    private suspend fun deliverBatch(batch: List<BatchedEvent>): BatchDeliveryResult {
        val groupedEvents = mutableMapOf<String, MutableList<BatchedEvent>>()
        for (event in batch) {
            val key = event.clientId
                ?: config.defaultClientId
                ?: clientIdGenerator.getOrCreate()
            groupedEvents.getOrPut(key) { mutableListOf() }.add(event)
        }

        val failed = mutableListOf<BatchedEvent>()
        var lastError: Throwable? = null

        for ((clientId, events) in groupedEvents) {
            val ga4Events = events.map { it.event }
            val userId = events.firstOrNull { it.userId != null }?.userId
            try {
                val userProperties = events.firstNotNullOfOrNull { it.userProperties }
                val request = createRequest(ga4Events, clientId, userId, userProperties)
                sendRequest(request)
            } catch (e: Exception) {
                lastError = e
                failed.addAll(events)
                logger.error(e, ga4Events.size, clientId) {
                    "Failed to send batch of {eventCount} events for clientId: {clientId}"
                }
            }
        }

        return if (failed.isEmpty()) {
            BatchDeliveryResult.Success
        } else {
            BatchDeliveryResult.Failure(
                cause = lastError ?: GA4ClientException("Batch delivery failed"),
                retriable = failed
            )
        }
    }

    private suspend fun createRequest(
        events: List<GA4Event>,
        clientId: String,
        userId: String? = null,
        userProperties: Map<String, duks.ga4.model.UserPropertyValue>? = null
    ): GA4Request {
        val ctx = contextProvider?.context() ?: RequestContext.EMPTY
        return GA4Request(
            clientId = clientId,
            userId = userId,
            timestampMicros = Clock.System.now().toEpochMilliseconds() * 1000,
            userProperties = userProperties,
            consent = config.defaultConsent,
            device = ctx.device,
            userLocation = ctx.userLocation,
            ipOverride = ctx.ipOverride,
            userAgent = ctx.userAgent,
            events = events
        )
    }

    private suspend fun sendRequest(request: GA4Request) {
        // Live collect always (custom override wins). debugMode attaches debug_mode=1 on events
        // so hits show in GA4 DebugView; /debug/mp/collect is validation-only and never appears there.
        val endpoint = config.customEndpoint ?: GA4Config.DEFAULT_ENDPOINT

        val url = buildString {
            append(endpoint)
            append("?measurement_id=")
            append(config.measurementId)
            append("&api_secret=")
            append(config.apiSecret)
        }

        var lastException: Exception? = null
        var retryCount = 0
        val maxRetries = if (config.enableRetry) config.maxRetries else 0

        while (retryCount <= maxRetries) {
            try {
                val response = httpClient.post(url) {
                    setBody(request)
                }

                if (response.status.isSuccess()) {
                    logger.debug(request.events.size, config.debugMode) {
                        "Successfully sent {eventCount} events to GA4 (debugMode={debugMode})"
                    }
                    return
                } else {
                    val errorBody = response.bodyAsText()
                    logger.error(response.status.value, errorBody) {
                        "Request failed with status {statusCode}: {errorBody}"
                    }
                    throw GA4ClientException(
                        "Request failed with status ${response.status.value}: $errorBody"
                    )
                }
            } catch (e: Exception) {
                lastException = e

                if (retryCount < maxRetries) {
                    val delayMs = calculateRetryDelay(retryCount)
                    logger.debug(delayMs, retryCount + 1, maxRetries) {
                        "Retrying after {delay}ms (attempt {attempt}/{maxRetries})"
                    }
                    delay(delayMs)
                    retryCount++
                } else {
                    break
                }
            }
        }

        throw lastException ?: GA4ClientException("Request failed after $retryCount retries")
    }

    private fun calculateRetryDelay(retryCount: Int): Long {
        val baseDelay = config.retryDelayMs
        val exponentialDelay = baseDelay * (2.0.pow(retryCount)).toLong()
        val maxDelay = 30_000L
        return exponentialDelay.coerceAtMost(maxDelay)
    }
}

/**
 * Exception thrown by GA4Client operations
 */
class GA4ClientException(message: String, cause: Throwable? = null) : Exception(message, cause)
