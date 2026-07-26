package duks.ga4.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SessionManagerTest {

    @Test
    fun `touch reuses session within timeout`() {
        var now = Instant.fromEpochSeconds(1_700_000_000)
        val manager = DefaultSessionManager(
            sessionTimeout = 30.minutes,
            clock = { now }
        )

        val first = manager.touch(now)
        now = Instant.fromEpochSeconds(1_700_000_000 + 10 * 60)
        val second = manager.touch(now)
        assertEquals(first, second)
        assertEquals(1, manager.sessionNumber)
    }

    @Test
    fun `new session after idle timeout`() {
        var now = Instant.fromEpochSeconds(1_700_000_000)
        val manager = DefaultSessionManager(
            sessionTimeout = 30.minutes,
            clock = { now }
        )

        val first = manager.touch(now)
        now = Instant.fromEpochSeconds(1_700_000_000 + 31 * 60)
        val second = manager.touch(now)
        assertNotEquals(first, second)
        assertEquals(2, manager.sessionNumber)
    }

    @Test
    fun `end forces new session on next touch`() {
        var now = Instant.fromEpochSeconds(1_700_000_000)
        val manager = DefaultSessionManager(clock = { now })
        val first = manager.touch(now)
        manager.end()
        now = Instant.fromEpochSeconds(1_700_000_010)
        val second = manager.touch(now)
        assertNotEquals(first, second)
    }
}
