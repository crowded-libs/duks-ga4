package duks.ga4.util

import kotlin.random.Random
import kotlin.time.Clock

/**
 * Utility for generating and resolving GA4-compatible client IDs.
 *
 * When auto-generating, reuses a single in-process ID (optionally backed by
 * [ClientIdStore]) so successive events share the same client identity.
 */
class ClientIdGenerator(
    private val store: ClientIdStore? = null
) {
    @kotlin.concurrent.Volatile
    private var cachedId: String? = null

    /**
     * Returns a stable client ID: cached → store → newly generated (then cached/stored).
     */
    suspend fun getOrCreate(): String {
        cachedId?.let { return it }

        val fromStore = store?.load()
        if (fromStore != null && isValidClientId(fromStore)) {
            cachedId = fromStore
            return fromStore
        }

        val generated = generate()
        cachedId = generated
        store?.save(generated)
        return generated
    }

    /**
     * Generates a new client ID in the format "random_number.timestamp".
     * Prefer [getOrCreate] for production so IDs stay stable.
     */
    fun generate(): String {
        val randomPart = Random.nextInt(100_000_000, 999_999_999)
        val timestampPart = Clock.System.now().epochSeconds
        return "$randomPart.$timestampPart"
    }

    /** Clears the in-process cache (does not clear [store] unless [clearStore] is true). */
    suspend fun reset(clearStore: Boolean = false) {
        cachedId = null
        if (clearStore) {
            store?.clear()
        }
    }

    companion object {
        /**
         * Validates if a string is a valid GA4 client ID format (number.number).
         */
        fun isValid(clientId: String): Boolean = isValidClientId(clientId)
    }
}

private fun isValidClientId(clientId: String): Boolean {
    if (clientId.isBlank()) return false

    val parts = clientId.split(".")
    if (parts.size != 2) return false

    return parts.all { part ->
        part.isNotEmpty() && part.all { it.isDigit() }
    }
}

