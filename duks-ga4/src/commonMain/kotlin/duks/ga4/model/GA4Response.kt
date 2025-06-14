package duks.ga4.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response from GA4 Measurement Protocol API
 */
@Serializable
data class GA4Response(
    /**
     * Validation messages from the debug endpoint
     */
    @SerialName("validationMessages")
    val validationMessages: List<ValidationMessage> = emptyList()
)

/**
 * Validation message from GA4 debug endpoint
 */
@Serializable
data class ValidationMessage(
    /**
     * Field path that has the validation issue
     */
    @SerialName("fieldPath")
    val fieldPath: String? = null,
    
    /**
     * Description of the validation issue
     */
    @SerialName("description")
    val description: String? = null,
    
    /**
     * Type of validation message (e.g., "ERROR", "WARNING")
     */
    @SerialName("validationCode")
    val validationCode: String? = null
)

/**
 * Result of a batch send operation
 */
data class BatchSendResult(
    /**
     * Number of events successfully sent
     */
    val successCount: Int,
    
    /**
     * Number of events that failed to send
     */
    val failureCount: Int,
    
    /**
     * List of events that failed to send
     */
    val failedEvents: List<BatchedEvent> = emptyList(),
    
    /**
     * Any validation messages from the API
     */
    val validationMessages: List<ValidationMessage> = emptyList(),
    
    /**
     * Error message if the entire batch failed
     */
    val error: String? = null
)