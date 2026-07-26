package duks.ga4.client

import duks.ga4.config.ValidationMode
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventValidatorTest {

    @Test
    fun `LOG mode drops reserved user_engagement event`() {
        val validator = EventValidator(mode = ValidationMode.LOG)
        val events = validator.validate(
            listOf(
                GA4Event("user_engagement", mapOf("x" to EventParamValue.NumberValue(1.0))),
                GA4Event("page_view", mapOf("page_title" to EventParamValue.StringValue("Home")))
            )
        )
        assertEquals(1, events.size)
        assertEquals("page_view", events.single().name)
    }

    @Test
    fun `STRICT mode throws on reserved event`() {
        val validator = EventValidator(mode = ValidationMode.STRICT)
        assertFailsWith<GA4ValidationException> {
            validator.validate(listOf(GA4Event("session_start")))
        }
    }

    @Test
    fun `sanitizes overlong string values in LOG mode`() {
        val longValue = "a".repeat(150)
        val validator = EventValidator(mode = ValidationMode.LOG)
        val events = validator.validate(
            listOf(
                GA4Event(
                    "custom_event",
                    mapOf("desc" to EventParamValue.StringValue(longValue))
                )
            )
        )
        val desc = events.single().params["desc"] as EventParamValue.StringValue
        assertEquals(100, desc.value.length)
    }

    @Test
    fun `OFF mode passes reserved events through`() {
        val validator = EventValidator(mode = ValidationMode.OFF)
        val events = validator.validate(listOf(GA4Event("user_engagement")))
        assertEquals(1, events.size)
    }

    @Test
    fun `detects reserved parameter prefixes`() {
        val validator = EventValidator(mode = ValidationMode.STRICT)
        val ex = assertFailsWith<GA4ValidationException> {
            validator.validate(
                listOf(
                    GA4Event(
                        "click",
                        mapOf("ga_session_id" to EventParamValue.StringValue("x"))
                    )
                )
            )
        }
        assertTrue(ex.issues.any { it.contains("reserved") })
    }
}
