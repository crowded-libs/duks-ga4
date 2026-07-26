package duks.ga4.util

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ClientIdGeneratorTest {

    @Test
    fun `getOrCreate returns stable id within process`() = runTest(timeout = 5.seconds) {
        val generator = ClientIdGenerator()
        val first = generator.getOrCreate()
        val second = generator.getOrCreate()
        assertEquals(first, second)
        assertTrue(ClientIdGenerator.isValid(first))
    }

    @Test
    fun `getOrCreate reuses store value`() = runTest(timeout = 5.seconds) {
        val store = InMemoryClientIdStore()
        store.save("111111111.222222222")
        val generator = ClientIdGenerator(store)
        assertEquals("111111111.222222222", generator.getOrCreate())
    }

    @Test
    fun `generate matches number-dot-number format`() {
        val id = ClientIdGenerator().generate()
        assertTrue(ClientIdGenerator.isValid(id), "Generated id should be valid: $id")
    }
}
