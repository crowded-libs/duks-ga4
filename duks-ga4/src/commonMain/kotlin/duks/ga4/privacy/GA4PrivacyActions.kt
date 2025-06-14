package duks.ga4.privacy

import duks.ga4.model.GA4Event
import duks.ga4.model.EventParamValue
import duks.ga4.model.Item
import kotlinx.datetime.Instant
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Privacy-related actions for GA4 SDK
 */
class GA4PrivacyActions(
    private val eventStore: InMemoryEventStore? = null
) {
    
    /**
     * Deletes all stored events for a specific user
     */
    suspend fun deleteUserData(userId: String): DeleteUserDataResult {
        return try {
            val deletedCount = eventStore?.deleteUserEvents(userId) ?: 0
            DeleteUserDataResult(
                success = true,
                deletedEventCount = deletedCount,
                userId = userId
            )
        } catch (e: Exception) {
            DeleteUserDataResult(
                success = false,
                deletedEventCount = 0,
                userId = userId,
                error = e.message
            )
        }
    }
    
    /**
     * Deletes all stored events for a specific client
     */
    suspend fun deleteClientData(clientId: String): DeleteClientDataResult {
        return try {
            val deletedCount = eventStore?.deleteClientEvents(clientId) ?: 0
            DeleteClientDataResult(
                success = true,
                deletedEventCount = deletedCount,
                clientId = clientId
            )
        } catch (e: Exception) {
            DeleteClientDataResult(
                success = false,
                deletedEventCount = 0,
                clientId = clientId,
                error = e.message
            )
        }
    }
    
    /**
     * Exports all events for a specific user
     */
    suspend fun exportUserData(userId: String): ExportUserDataResult {
        return try {
            val events = eventStore?.getUserEvents(userId) ?: emptyList()
            val exportData = UserDataExport(
                userId = userId,
                exportTimestamp = Clock.System.now(),
                eventCount = events.size,
                events = events.map { it.toExportFormat() }
            )
            
            ExportUserDataResult(
                success = true,
                userId = userId,
                data = exportData,
                jsonData = Json.encodeToString(exportData)
            )
        } catch (e: Exception) {
            ExportUserDataResult(
                success = false,
                userId = userId,
                data = null,
                jsonData = null,
                error = e.message
            )
        }
    }
    
    /**
     * Exports all events for a specific client
     */
    suspend fun exportClientData(clientId: String): ExportClientDataResult {
        return try {
            val events = eventStore?.getClientEvents(clientId) ?: emptyList()
            val exportData = ClientDataExport(
                clientId = clientId,
                exportTimestamp = Clock.System.now(),
                eventCount = events.size,
                events = events.map { it.toExportFormat() }
            )
            
            ExportClientDataResult(
                success = true,
                clientId = clientId,
                data = exportData,
                jsonData = Json.encodeToString(exportData)
            )
        } catch (e: Exception) {
            ExportClientDataResult(
                success = false,
                clientId = clientId,
                data = null,
                jsonData = null,
                error = e.message
            )
        }
    }
    
    /**
     * Deletes events older than the specified date
     */
    suspend fun deleteEventsOlderThan(date: Instant): DeleteOldEventsResult {
        return try {
            val deletedCount = eventStore?.deleteEventsOlderThan(date) ?: 0
            DeleteOldEventsResult(
                success = true,
                deletedEventCount = deletedCount,
                olderThan = date
            )
        } catch (e: Exception) {
            DeleteOldEventsResult(
                success = false,
                deletedEventCount = 0,
                olderThan = date,
                error = e.message
            )
        }
    }
    
    /**
     * Clears all stored events
     */
    suspend fun clearAllData(): ClearAllDataResult {
        return try {
            val deletedCount = eventStore?.clearAllEvents() ?: 0
            ClearAllDataResult(
                success = true,
                deletedEventCount = deletedCount
            )
        } catch (e: Exception) {
            ClearAllDataResult(
                success = false,
                deletedEventCount = 0,
                error = e.message
            )
        }
    }
}

/**
 * Result classes for privacy actions
 */
data class DeleteUserDataResult(
    val success: Boolean,
    val deletedEventCount: Int,
    val userId: String,
    val error: String? = null
)

data class DeleteClientDataResult(
    val success: Boolean,
    val deletedEventCount: Int,
    val clientId: String,
    val error: String? = null
)

data class ExportUserDataResult(
    val success: Boolean,
    val userId: String,
    val data: UserDataExport?,
    val jsonData: String?,
    val error: String? = null
)

data class ExportClientDataResult(
    val success: Boolean,
    val clientId: String,
    val data: ClientDataExport?,
    val jsonData: String?,
    val error: String? = null
)

data class DeleteOldEventsResult(
    val success: Boolean,
    val deletedEventCount: Int,
    val olderThan: Instant,
    val error: String? = null
)

data class ClearAllDataResult(
    val success: Boolean,
    val deletedEventCount: Int,
    val error: String? = null
)

/**
 * Data export formats
 */
@Serializable
data class UserDataExport(
    val userId: String,
    val exportTimestamp: Instant,
    val eventCount: Int,
    val events: List<ExportedEvent>
)

@Serializable
data class ClientDataExport(
    val clientId: String,
    val exportTimestamp: Instant,
    val eventCount: Int,
    val events: List<ExportedEvent>
)

@Serializable
data class ExportedEvent(
    val eventId: String,
    val eventName: String,
    val timestamp: Instant,
    val clientId: String?,
    val userId: String?,
    val params: Map<String, String>
)

/**
 * Extension to convert StoredEvent to export format
 */
private fun StoredEvent.toExportFormat(): ExportedEvent {
    return ExportedEvent(
        eventId = timestamp.toEpochMilliseconds().toString(), // Generate ID from timestamp
        eventName = event.name,
        timestamp = timestamp,
        clientId = clientId,
        userId = userId,
        params = event.params.mapValues { (_, value) ->
            when (value) {
                is EventParamValue.StringValue -> value.value
                is EventParamValue.NumberValue -> value.value.toString()
                is EventParamValue.BooleanValue -> value.value.toString()
                is EventParamValue.ItemsValue -> kotlinx.serialization.json.Json.encodeToString(value.value)
            }
        }
    )
}