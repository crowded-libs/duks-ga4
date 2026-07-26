package duks.ga4.middleware

import duks.ga4.client.GA4Client
import duks.ga4.client.IGA4Client
import duks.ga4.config.GA4Config
import duks.ga4.model.GA4Event
import duks.logging.Logger
import duks.logging.error
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Lightweight GA4 tracker for use outside a duks store (scripts, services, tests).
 *
 * Does not cancel [scope] on [close] — the scope is owned by the caller.
 */
class SimpleGA4Tracker(
    private val config: GA4Config,
    private val eventMapper: EventMapper<Any>? = null,
    engine: HttpClientEngine? = null,
    private val scope: CoroutineScope
) {
    private val logger = Logger.default()
    private val ga4Client: IGA4Client = GA4Client(config, engine, scope)

    /**
     * Map [action] through [eventMapper] (if any) and enqueue resulting events.
     */
    fun trackAction(action: Any, state: Any? = null) {
        scope.launch {
            try {
                val events = eventMapper?.mapActionAfter(action, state ?: Unit).orEmpty()
                if (events.isNotEmpty()) {
                    ga4Client.sendEvents(events, immediate = false)
                }
            } catch (e: Exception) {
                logger.error(e) { "SimpleGA4Tracker.trackAction failed" }
            }
        }
    }

    /**
     * Enqueue a custom event (batched).
     */
    fun trackEvent(event: GA4Event) {
        scope.launch {
            ga4Client.sendEvent(event, immediate = false).onFailure { e ->
                logger.error(e) { "SimpleGA4Tracker.trackEvent failed" }
            }
        }
    }

    /**
     * Flush pending events.
     */
    suspend fun flush() {
        ga4Client.flush()
    }

    /**
     * Flush and close the underlying client. Does not cancel [scope].
     */
    suspend fun close() {
        flush()
        ga4Client.close()
    }
}

/**
 * Creates a simple GA4 tracker for non-store usage.
 */
fun simpleGA4Tracker(
    config: GA4Config,
    eventMapper: EventMapper<Any>? = null,
    engine: HttpClientEngine? = null,
    scope: CoroutineScope
): SimpleGA4Tracker {
    return SimpleGA4Tracker(config, eventMapper, engine, scope)
}
