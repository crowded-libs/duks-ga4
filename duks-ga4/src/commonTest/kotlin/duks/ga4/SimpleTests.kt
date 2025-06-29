package duks.ga4

import duks.ga4.config.GA4Config
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import duks.ga4.model.ConsentState
import duks.ga4.model.ConsentValue
import duks.ga4.privacy.PiiScrubber
import duks.ga4.privacy.PiiScrubberConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Simple tests for core functionality that should compile
 */
class SimpleTests {
    
    @Test
    fun `should create GA4Config with provided values`() {
        val config = GA4Config(
            measurementId = "G-TEST123",
            apiSecret = "test-secret",
            debugMode = true
        )
        
        assertEquals("G-TEST123", config.measurementId)
        assertEquals("test-secret", config.apiSecret)
        assertTrue(config.debugMode)
    }
    
    @Test
    fun `should create GA4Event with different parameter types`() {
        val event = GA4Event(
            name = "test_event",
            params = mapOf(
                "string_param" to EventParamValue.StringValue("test"),
                "number_param" to EventParamValue.NumberValue(42.0),
                "boolean_param" to EventParamValue.BooleanValue(true)
            )
        )
        
        assertEquals("test_event", event.name)
        assertEquals(3, event.params.size)
        
        val stringParam = event.params["string_param"] as EventParamValue.StringValue
        assertEquals("test", stringParam.value)
    }
    
    @Test
    fun `should create ConsentState with granted values and null defaults`() {
        val consent = ConsentState(
            adStorage = ConsentValue.GRANTED,
            analyticsStorage = ConsentValue.GRANTED
        )
        
        assertEquals(ConsentValue.GRANTED, consent.adStorage)
        assertEquals(ConsentValue.GRANTED, consent.analyticsStorage)
        assertNull(consent.adPersonalization) // Default is null
    }
    
    @Test
    fun `should scrub PII fields when using default PiiScrubber`() =  runTest(timeout = 5.seconds) {
        val scrubber = PiiScrubber()
        
        val event = GA4Event(
            name = "test",
            params = mapOf(
                "email" to EventParamValue.StringValue("test@example.com"),
                "safe_field" to EventParamValue.StringValue("safe data")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        val emailParam = scrubbed.params["email"] as EventParamValue.StringValue
        assertEquals("[REDACTED]", emailParam.value)
        
        val safeParam = scrubbed.params["safe_field"] as EventParamValue.StringValue
        assertEquals("safe data", safeParam.value)
    }
    
    @Test
    fun `should detect PII content in text strings`() {
        val scrubber = PiiScrubber()
        
        assertTrue(scrubber.containsPii("Contact us at support@example.com"))
        assertTrue(scrubber.containsPii("Call 555-123-4567"))
        assertFalse(scrubber.containsPii("This is safe text"))
    }
    
    @Test
    fun `should correctly store values in different EventParamValue types`() {
        val stringValue = EventParamValue.StringValue("test")
        val numberValue = EventParamValue.NumberValue(42.5)
        val boolValue = EventParamValue.BooleanValue(true)
        
        assertEquals("test", stringValue.value)
        assertEquals(42.5, numberValue.value)
        assertTrue(boolValue.value)
    }
    
    @Test
    fun `should create GA4Event with items parameter for e-commerce tracking`() {
        val items = listOf(
            TestUtils.createTestItem(
                itemId = "123",
                itemName = "Test Product",
                price = 9.99,
                quantity = 2
            )
        )
        
        val event = GA4Event(
            name = "purchase",
            params = mapOf(
                "transaction_id" to EventParamValue.StringValue("TXN123"),
                "value" to EventParamValue.NumberValue(19.98),
                "items" to EventParamValue.ItemsValue(items)
            )
        )
        
        assertEquals("purchase", event.name)
        
        val itemsParam = event.params["items"] as EventParamValue.ItemsValue
        assertEquals(1, itemsParam.value.size)
        assertEquals("123", itemsParam.value[0].itemId)
    }
    
    @Test
    fun `should use default values when creating GA4Config with minimal parameters`() {
        val config = GA4Config(
            measurementId = "G-TEST",
            apiSecret = "secret"
        )
        
        // Test default values
        assertFalse(config.debugMode)
        assertTrue(config.enableRetry)
        assertEquals(3, config.maxRetries)
        assertEquals(25, config.maxEventsPerBatch)
        assertTrue(config.autoGenerateClientId)
    }
}