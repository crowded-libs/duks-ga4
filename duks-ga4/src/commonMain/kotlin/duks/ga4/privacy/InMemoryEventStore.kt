package duks.ga4.privacy

import duks.logging.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * In-memory implementation of event storage for testing and simple use cases
 */
class InMemoryEventStore(
    private val maxEvents: Int = 10_000
) {
    private val logger = Logger.default()
    private val events = mutableListOf<StoredEvent>()
    private val mutex = Mutex()
    
    init {
        logger.info(maxEvents) {
            "InMemoryEventStore initialized with maxEvents: {maxEvents}"
        }
    }
    
    suspend fun storeEvent(event: StoredEvent) = mutex.withLock {
        events.add(event)
        
        logger.debug(event.event.name, events.size) {
            "Stored event {eventName}, total events: {eventCount}"
        }
        
        // Trim if we exceed max events
        if (events.size > maxEvents) {
            events.removeAt(0)
            logger.debug(maxEvents) {
                "Event store reached max capacity of {maxEvents}, removed oldest event"
            }
        }
    }
    
    suspend fun deleteUserEvents(userId: String): Int = mutex.withLock {
        val toRemove = events.filter { it.userId == userId }
        events.removeAll(toRemove)
        
        if (toRemove.isNotEmpty()) {
            logger.info(userId, toRemove.size) {
                "Deleted {count} events for userId: {userId}"
            }
        }
        
        toRemove.size
    }
    
    suspend fun deleteClientEvents(clientId: String): Int = mutex.withLock {
        val toRemove = events.filter { it.clientId == clientId }
        events.removeAll(toRemove)
        
        if (toRemove.isNotEmpty()) {
            logger.info(clientId, toRemove.size) {
                "Deleted {count} events for clientId: {clientId}"
            }
        }
        
        toRemove.size
    }
    
    suspend fun getUserEvents(userId: String): List<StoredEvent> = mutex.withLock {
        val userEvents = events.filter { it.userId == userId }.toList()
        
        logger.debug(userId, userEvents.size) {
            "Retrieved {count} events for userId: {userId}"
        }
        
        userEvents
    }
    
    suspend fun getClientEvents(clientId: String): List<StoredEvent> = mutex.withLock {
        val clientEvents = events.filter { it.clientId == clientId }.toList()
        
        logger.debug(clientId, clientEvents.size) {
            "Retrieved {count} events for clientId: {clientId}"
        }
        
        clientEvents
    }
    
    suspend fun deleteEventsOlderThan(date: Instant): Int = mutex.withLock {
        val toRemove = events.filter { it.timestamp < date }
        events.removeAll(toRemove)
        
        if (toRemove.isNotEmpty()) {
            logger.info(date.toString(), toRemove.size) {
                "Deleted {count} events older than {date}"
            }
        }
        
        toRemove.size
    }
    
    suspend fun clearAllEvents(): Int = mutex.withLock {
        val count = events.size
        events.clear()
        
        logger.info(count) {
            "Cleared all {count} events from store"
        }
        
        count
    }
    
    /**
     * Gets all stored events
     */
    suspend fun getAllEvents(): List<StoredEvent> = mutex.withLock {
        events.toList()
    }
    
    /**
     * Gets the current number of stored events
     */
    suspend fun getEventCount(): Int = mutex.withLock {
        events.size
    }
}