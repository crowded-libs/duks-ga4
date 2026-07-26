package duks.ga4.client

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Manages GA4 session identity for Measurement Protocol events.
 *
 * Sessions expire after [sessionTimeout] of inactivity (default 30 minutes, matching GA4).
 * State is in-memory only; a new session is created after process restart unless a custom
 * implementation is supplied.
 */
interface SessionManager {
    /** Current session ID (typically epoch seconds as a string). */
    val sessionId: String

    /** Monotonic session count for this client instance. */
    val sessionNumber: Int

    /**
     * Marks activity, refreshing the idle timer. Creates a new session if expired.
     * @return current [sessionId] after touch
     */
    fun touch(now: Instant = Clock.System.now()): String

    /** Forces the current session to end; next [touch] starts a new one. */
    fun end()
}

/**
 * Default in-memory session manager.
 */
class DefaultSessionManager(
    private val sessionTimeout: Duration = DEFAULT_SESSION_TIMEOUT,
    private val clock: () -> Instant = { Clock.System.now() }
) : SessionManager {
    private var _sessionId: String
    private var _sessionNumber: Int = 1
    private var lastActivity: Instant
    private var ended: Boolean = false

    init {
        val start = clock()
        _sessionId = newSessionId(start)
        lastActivity = start
    }

    override val sessionId: String
        get() {
            touch(clock())
            return _sessionId
        }

    override val sessionNumber: Int
        get() {
            touch(clock())
            return _sessionNumber
        }

    override fun touch(now: Instant): String {
        val at = now
        if (ended || isExpired(at)) {
            startNewSession(at)
        } else {
            lastActivity = at
        }
        ended = false
        return _sessionId
    }

    override fun end() {
        ended = true
    }

    private fun isExpired(now: Instant): Boolean {
        val idle = now - lastActivity
        return idle >= sessionTimeout
    }

    private fun startNewSession(now: Instant) {
        _sessionId = newSessionId(now)
        // First forced rotation after init increments past the initial session
        _sessionNumber += 1
        lastActivity = now
    }

    private fun newSessionId(now: Instant): String =
        now.toEpochMilliseconds().toString()

    companion object {
        val DEFAULT_SESSION_TIMEOUT: Duration = 30.minutes
    }
}
