package duks.ga4.util

import kotlin.random.Random
import kotlin.time.Clock

/**
 * Utility for generating GA4-compatible client IDs
 */
class ClientIdGenerator {
    
    /**
     * Generates a new client ID in the format "random_number.timestamp"
     * This follows the GA4 client ID format convention
     */
    fun generate(): String {
        val randomPart = Random.nextInt(100_000_000, 999_999_999)
        val timestampPart = Clock.System.now().epochSeconds
        return "$randomPart.$timestampPart"
    }
    
    companion object {
        /**
         * Validates if a string is a valid GA4 client ID format
         */
        fun isValid(clientId: String): Boolean {
            if (clientId.isBlank()) return false
            
            // GA4 client IDs typically follow the pattern: number.number
            val parts = clientId.split(".")
            if (parts.size != 2) return false
            
            return parts.all { part ->
                part.isNotEmpty() && part.all { it.isDigit() }
            }
        }
    }
}