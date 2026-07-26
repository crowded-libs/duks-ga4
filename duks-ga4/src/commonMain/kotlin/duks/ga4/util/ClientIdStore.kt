package duks.ga4.util

/**
 * Optional persistence for a stable GA4 client ID across process restarts.
 *
 * The library does not require durable storage. Provide an implementation when
 * you want the same client ID after relaunch. If using a durable backend, prefer
 * kotlin-lmdb (multiplatform) over platform-specific preferences.
 */
interface ClientIdStore {
    /** Loads a previously stored client ID, or null if none. */
    suspend fun load(): String?

    /** Persists the client ID. */
    suspend fun save(clientId: String)

    /** Clears any stored client ID. */
    suspend fun clear()
}

/**
 * In-memory store: survives for the lifetime of the process only.
 */
class InMemoryClientIdStore : ClientIdStore {
    private var value: String? = null

    override suspend fun load(): String? = value

    override suspend fun save(clientId: String) {
        value = clientId
    }

    override suspend fun clear() {
        value = null
    }
}
