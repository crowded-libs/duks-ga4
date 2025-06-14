package duks.ga4.middleware

import duks.ga4.client.GA4Client
import duks.ga4.client.IGA4Client
import duks.ga4.config.GA4Config
import duks.ga4.model.GA4Event
import io.ktor.client.engine.*
import kotlinx.coroutines.*

/**
 * Simplified GA4 tracking function that can be used without duks middleware
 */
class SimpleGA4Tracker(
    private val config: GA4Config,
    private val eventMapper: EventMapper<Any>? = null,
    engine: HttpClientEngine? = null,
    private val scope: CoroutineScope
) {
    private val ga4Client: IGA4Client = GA4Client(config, engine, scope)

    /**
     * Track an action
     */
    fun trackAction(action: Any, state: Any? = null) {
        scope.launch {
            try {
                val events = eventMapper?.mapActionAfter(action, state ?: Unit) ?: emptyList()
                events.forEach { event ->
                    ga4Client.sendEvent(event)
                }
            } catch (e: Exception) {
                // Error tracking disabled
            }
        }
    }
    
    /**
     * Track a custom event
     */
    fun trackEvent(event: GA4Event) {
        scope.launch {
            ga4Client.sendEvent(event)
        }
    }
    
    /**
     * Flush pending events
     */
    suspend fun flush() {
        ga4Client.flush()
    }
    
    /**
     * Close the tracker
     */
    suspend fun close() {
        flush()
        ga4Client.close()
        scope.cancel()
    }
}

/**
 * Creates a simple GA4 tracker
 */
fun simpleGA4Tracker(
    config: GA4Config,
    eventMapper: EventMapper<Any>? = null,
    engine: HttpClientEngine? = null,
    scope: CoroutineScope
): SimpleGA4Tracker {
    return SimpleGA4Tracker(config, eventMapper, engine, scope)
}