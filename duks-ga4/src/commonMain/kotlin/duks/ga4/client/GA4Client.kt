package duks.ga4.client

import duks.ga4.config.GA4Config
import duks.ga4.model.*
import duks.ga4.util.ClientIdGenerator
import duks.logging.*
import io.ktor.client.*
import io.ktor.client.call.*
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

/**
 * Main client for sending events to Google Analytics 4
 */
class GA4Client(
    private val config: GA4Config,
    engine: HttpClientEngine? = null,
    private val scope: CoroutineScope
) : IGA4Client {
    
    private val logger = Logger.default()
    
    private val httpClient = HttpClient(engine ?: HttpClient().engine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = false
                    isLenient = true
                })
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
    
    private val clientIdGenerator = ClientIdGenerator()
    
    private val batcher = EventBatcher(
        config = config,
        onBatchReady = { batch -> sendBatch(batch) },
        scope = scope
    )
    
    init {
        logger.info(config.measurementId, config.debugMode) { 
            "GA4Client initialized with measurementId: {measurementId}, debugMode: {debugMode}" 
        }
    }
    
    /**
     * Sends a single event to GA4
     */
    override suspend fun sendEvent(
        event: GA4Event,
        clientId: String?,
        userId: String?,
        immediate: Boolean
    ): Result<Unit> = runCatching {
        val finalClientId = clientId 
            ?: config.defaultClientId 
            ?: if (config.autoGenerateClientId) clientIdGenerator.generate() else throw IllegalArgumentException("Client ID is required")
        
        logger.debug(event.name, finalClientId, immediate) { 
            "Sending event: {eventName}, clientId: {clientId}, immediate: {immediate}" 
        }
        
        if (immediate) {
            // Send immediately without batching
            val request = createRequest(listOf(event), finalClientId, userId)
            sendRequest(request)
        } else {
            // Add to batch
            val added = batcher.addEvent(event, finalClientId, userId)
            if (!added) {
                logger.warn(event.name) { 
                    "Event queue is full, cannot add event: {eventName}" 
                }
                throw IllegalStateException("Event queue is full")
            }
        }
    }
    
    /**
     * Sends multiple events to GA4
     */
    override suspend fun sendEvents(
        events: List<GA4Event>,
        clientId: String?,
        userId: String?,
        immediate: Boolean
    ): Result<Unit> = runCatching {
        require(events.isNotEmpty()) { "Events list cannot be empty" }
        
        val finalClientId = clientId 
            ?: config.defaultClientId 
            ?: if (config.autoGenerateClientId) clientIdGenerator.generate() else throw IllegalArgumentException("Client ID is required")
        
        logger.debug(events.size, finalClientId, immediate) { 
            "Sending {eventCount} events, clientId: {clientId}, immediate: {immediate}" 
        }
        
        if (immediate) {
            // Send immediately in batches
            events.chunked(config.maxEventsPerBatch).forEach { batch ->
                val request = createRequest(batch, finalClientId, userId)
                sendRequest(request)
            }
        } else {
            // Add to batch
            val added = batcher.addEvents(events, finalClientId, userId)
            if (added < events.size) {
                logger.warn(added, events.size) { 
                    "Could only add {added} of {total} events to queue" 
                }
                throw IllegalStateException("Could only add $added of ${events.size} events to queue")
            }
        }
    }
    
    /**
     * Flushes all pending events
     */
    override suspend fun flush() {
        logger.debug { "Flushing all pending events" }
        batcher.flushAll()
    }
    
    /**
     * Gets the current number of events in the queue
     */
    override suspend fun getQueueSize(): Int = batcher.getQueueSize()
    
    /**
     * Closes the client and releases resources
     */
    override suspend fun close() {
        logger.info { "Closing GA4Client" }
        flush()
        batcher.stop()
        httpClient.close()
        // Note: We don't cancel the scope as it may be shared with other components
    }
    
    /**
     * Sends a batch of events
     */
    private suspend fun sendBatch(batch: List<BatchedEvent>) {
        // Group events by client ID
        val groupedEvents = batch.groupBy { it.clientId ?: config.defaultClientId ?: clientIdGenerator.generate() }
        
        for ((clientId, events) in groupedEvents) {
            val ga4Events = events.map { it.event }
            val userId = events.firstOrNull { it.userId != null }?.userId
            
            try {
                val request = createRequest(ga4Events, clientId, userId)
                sendRequest(request)
            } catch (e: Exception) {
                // Handle retry for failed events
                if (config.enableRetry) {
                    val failedEvents = events.filter { it.retryCount < config.maxRetries }
                    if (failedEvents.isNotEmpty()) {
                        batcher.requeueEvents(failedEvents)
                    }
                }
                
                logger.error(e, ga4Events.size, clientId) { 
                    "Failed to send batch of {eventCount} events for clientId: {clientId}" 
                }
            }
        }
    }
    
    /**
     * Creates a GA4 request from events
     */
    private fun createRequest(
        events: List<GA4Event>,
        clientId: String,
        userId: String? = null
    ): GA4Request {
        return GA4Request(
            clientId = clientId,
            userId = userId,
            timestampMicros = Clock.System.now().toEpochMilliseconds() * 1000,
            consent = config.defaultConsent,
            events = events
        )
    }
    
    /**
     * Sends a request to GA4 with retry logic
     */
    private suspend fun sendRequest(request: GA4Request) {
        val endpoint = when {
            config.customEndpoint != null -> config.customEndpoint
            config.debugMode -> GA4Config.DEBUG_ENDPOINT
            else -> GA4Config.DEFAULT_ENDPOINT
        }
        
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
                    logger.debug(request.events.size) { 
                        "Successfully sent {eventCount} events to GA4" 
                    }
                    // In debug mode, parse validation messages
                    if (config.debugMode) {
                        try {
                            val ga4Response = response.body<GA4Response>()
                            if (ga4Response.validationMessages.isNotEmpty()) {
                                ga4Response.validationMessages.forEach { message ->
                                    when (message.validationCode?.uppercase()) {
                                        "ERROR" -> logger.error(
                                            message.fieldPath ?: "Unknown field", 
                                            message.description ?: "No description"
                                        ) { "GA4 Validation - {fieldPath}: {description}" }
                                        "WARNING" -> logger.warn(
                                            message.fieldPath ?: "Unknown field", 
                                            message.description ?: "No description"
                                        ) { "GA4 Validation - {fieldPath}: {description}" }
                                        else -> logger.info(
                                            message.fieldPath ?: "Unknown field", 
                                            message.description ?: "No description"
                                        ) { "GA4 Validation - {fieldPath}: {description}" }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Debug endpoint might return empty response on success
                            logger.debug { "Debug response parsing failed, likely successful" }
                        }
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
                    val delay = calculateRetryDelay(retryCount)
                    logger.debug(delay, retryCount + 1, maxRetries) { 
                        "Retrying after {delay}ms (attempt {attempt}/{maxRetries})" 
                    }
                    delay(delay)
                    retryCount++
                } else {
                    break
                }
            }
        }
        
        throw lastException ?: GA4ClientException("Request failed after $retryCount retries")
    }
    
    /**
     * Calculates exponential backoff delay for retries
     */
    private fun calculateRetryDelay(retryCount: Int): Long {
        val baseDelay = config.retryDelayMs
        val exponentialDelay = baseDelay * (2.0.pow(retryCount)).toLong()
        val maxDelay = 30_000L // 30 seconds max
        return exponentialDelay.coerceAtMost(maxDelay)
    }
    
}

/**
 * Exception thrown by GA4Client operations
 */
class GA4ClientException(message: String, cause: Throwable? = null) : Exception(message, cause)