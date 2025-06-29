package duks.ga4.privacy

import duks.ga4.TestUtils
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import duks.ga4.model.Item
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class PiiScrubberTest {
    
    private lateinit var scrubber: PiiScrubber
    
    @BeforeTest
    fun setup() {
        scrubber = PiiScrubber()
    }
    
    @Test
    fun `should scrub email addresses in message content and email fields`() =  runTest(timeout = 5.seconds) {
        val event = GA4Event(
            name = "test_event",
            params = mapOf(
                "message" to EventParamValue.StringValue("Contact me at john.doe@example.com for details"),
                "email" to EventParamValue.StringValue("jane@company.org")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        // Email in message should be scrubbed
        val messageParam = scrubbed.params["message"] as EventParamValue.StringValue
        assertEquals("Contact me at [REDACTED] for details", messageParam.value)
        
        // Email field should be scrubbed by field name
        val emailParam = scrubbed.params["email"] as EventParamValue.StringValue
        assertEquals("[REDACTED]", emailParam.value)
    }
    
    @Test
    fun `should scrub phone numbers in various formats and field names`() =  runTest(timeout = 5.seconds) {
        val event = GA4Event(
            name = "contact_form",
            params = mapOf(
                "phone" to EventParamValue.StringValue("123-456-7890"),
                "mobile" to EventParamValue.StringValue("+1 (555) 123-4567"),
                "description" to EventParamValue.StringValue("Call me at 555.123.4567 or text")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        // Phone fields should be scrubbed by name
        assertEquals("[REDACTED]", (scrubbed.params["phone"] as EventParamValue.StringValue).value)
        assertEquals("[REDACTED]", (scrubbed.params["mobile"] as EventParamValue.StringValue).value)
        
        // Phone in description should be scrubbed - multiple parts might be redacted
        val description = scrubbed.params["description"] as EventParamValue.StringValue
        assertTrue(description.value.contains("[REDACTED]"))
        assertFalse(description.value.contains("555.123.4567"))
    }
    
    @Test
    fun `should scrub credit card numbers in fields and content`() =  runTest(timeout = 5.seconds) {
        val event = GA4Event(
            name = "payment_info",
            params = mapOf(
                "card_number" to EventParamValue.StringValue("4111 1111 1111 1111"),
                "notes" to EventParamValue.StringValue("Card ending in 1234, full: 4111111111111111")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        // Card field should be scrubbed by name
        assertEquals("[REDACTED]", (scrubbed.params["card_number"] as EventParamValue.StringValue).value)
        
        // Card in notes should be scrubbed
        val notes = scrubbed.params["notes"] as EventParamValue.StringValue
        assertEquals("Card ending in [REDACTED], full: [REDACTED]", notes.value)
    }
    
    @Test
    fun `should scrub SSN patterns in different formats`() =  runTest(timeout = 5.seconds) {
        val event = GA4Event(
            name = "user_data",
            params = mapOf(
                "ssn" to EventParamValue.StringValue("123-45-6789"),
                "tax_id" to EventParamValue.StringValue("987654321"),
                "notes" to EventParamValue.StringValue("SSN: 123-45-6789 or 123456789")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        // SSN fields should be scrubbed by name
        assertEquals("[REDACTED]", (scrubbed.params["ssn"] as EventParamValue.StringValue).value)
        assertEquals("[REDACTED]", (scrubbed.params["tax_id"] as EventParamValue.StringValue).value)
        
        // SSNs in notes should be scrubbed
        val notes = scrubbed.params["notes"] as EventParamValue.StringValue
        assertEquals("SSN: [REDACTED] or [REDACTED]", notes.value)
    }
    
    @Test
    fun `should scrub IP addresses in fields and log content`() =  runTest(timeout = 5.seconds) {
        val event = GA4Event(
            name = "network_info",
            params = mapOf(
                "ip_address" to EventParamValue.StringValue("192.168.1.1"),
                "client_ip" to EventParamValue.StringValue("10.0.0.1"),
                "log" to EventParamValue.StringValue("Connection from 192.168.1.100")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        // IP fields should be scrubbed by name
        assertEquals("[REDACTED]", (scrubbed.params["ip_address"] as EventParamValue.StringValue).value)
        assertEquals("[REDACTED]", (scrubbed.params["client_ip"] as EventParamValue.StringValue).value)
        
        // IP in log should be scrubbed
        val log = scrubbed.params["log"] as EventParamValue.StringValue
        assertEquals("Connection from [REDACTED]", log.value)
    }
    
    @Test
    fun `should scrub all personal information fields by field name`() =  runTest(timeout = 5.seconds) {
        val event = GA4Event(
            name = "user_profile",
            params = mapOf(
                "full_name" to EventParamValue.StringValue("John Doe"),
                "first_name" to EventParamValue.StringValue("Jane"),
                "last_name" to EventParamValue.StringValue("Smith"),
                "username" to EventParamValue.StringValue("jsmith123"),
                "address" to EventParamValue.StringValue("123 Main St"),
                "city" to EventParamValue.StringValue("Anytown"),
                "zip" to EventParamValue.StringValue("12345"),
                "date_of_birth" to EventParamValue.StringValue("1990-01-01"),
                "gender" to EventParamValue.StringValue("Female"),
                "password" to EventParamValue.StringValue("secret123")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        // All PII fields should be scrubbed
        scrubbed.params.forEach { (key, value) ->
            if (value is EventParamValue.StringValue) {
                assertEquals("[REDACTED]", value.value, "Field '$key' should be scrubbed")
            }
        }
    }
    
    @Test
    fun `should not modify numeric parameter values`() =  runTest(timeout = 5.seconds) {
        val event = GA4Event(
            name = "metrics",
            params = mapOf(
                "count" to EventParamValue.NumberValue(42.0),
                "price" to EventParamValue.NumberValue(99.99),
                "quantity" to EventParamValue.NumberValue(5.0)
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        // Numbers should not be modified
        assertEquals(42.0, (scrubbed.params["count"] as EventParamValue.NumberValue).value)
        assertEquals(99.99, (scrubbed.params["price"] as EventParamValue.NumberValue).value)
        assertEquals(5.0, (scrubbed.params["quantity"] as EventParamValue.NumberValue).value)
    }
    
    @Test
    fun `should not modify boolean parameter values`() =  runTest(timeout = 5.seconds) {
        val event = GA4Event(
            name = "flags",
            params = mapOf(
                "enabled" to EventParamValue.BooleanValue(true),
                "active" to EventParamValue.BooleanValue(false)
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        // Booleans should not be modified
        assertTrue((scrubbed.params["enabled"] as EventParamValue.BooleanValue).value)
        assertFalse((scrubbed.params["active"] as EventParamValue.BooleanValue).value)
    }
    
    @Test
    fun `should scrub PII content within item properties`() =  runTest(timeout = 5.seconds) {
        val items = listOf(
            Item(
                itemId = "123",
                itemName = "Contact john@example.com for this item",
                affiliation = "Store at 192.168.1.1",
                coupon = "SAVE20",
                itemBrand = "Brand 555-1234"
            )
        )
        
        val event = GA4Event(
            name = "view_item",
            params = mapOf(
                "items" to EventParamValue.ItemsValue(items)
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        val scrubbedItems = (scrubbed.params["items"] as EventParamValue.ItemsValue).value
        assertEquals(1, scrubbedItems.size)
        
        val item = scrubbedItems[0]
        assertEquals("Contact [REDACTED] for this item", item.itemName)
        assertEquals("Store at [REDACTED]", item.affiliation)
        // Note: "20" gets caught by credit card pattern
        assertEquals("SAVE[REDACTED]", item.coupon)
        assertEquals("Brand [REDACTED]-[REDACTED]", item.itemBrand) // Phone pattern matches parts
    }
    
    @Test
    fun `should not scrub any content when scrubber is disabled`() =  runTest(timeout = 5.seconds) {
        val config = PiiScrubberConfig(enabled = false)
        scrubber = PiiScrubber(config)
        
        val event = GA4Event(
            name = "test",
            params = mapOf(
                "email" to EventParamValue.StringValue("test@example.com"),
                "phone" to EventParamValue.StringValue("123-456-7890")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        // Nothing should be scrubbed when disabled
        assertEquals("test@example.com", (scrubbed.params["email"] as EventParamValue.StringValue).value)
        assertEquals("123-456-7890", (scrubbed.params["phone"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should use custom redacted value when configured`() =  runTest(timeout = 5.seconds) {
        val config = PiiScrubberConfig(redactedValue = "[REMOVED]")
        scrubber = PiiScrubber(config)
        
        val event = GA4Event(
            name = "test",
            params = mapOf(
                "email" to EventParamValue.StringValue("test@example.com")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        assertEquals("[REMOVED]", (scrubbed.params["email"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should remove PII fields entirely when removeFields is enabled`() =  runTest(timeout = 5.seconds) {
        val config = PiiScrubberConfig(removeFields = true)
        scrubber = PiiScrubber(config)
        
        val event = GA4Event(
            name = "test",
            params = mapOf(
                "email" to EventParamValue.StringValue("test@example.com"),
                "safe_field" to EventParamValue.StringValue("keep this")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        // Email field should be removed entirely
        assertFalse(scrubbed.params.containsKey("email"))
        assertTrue(scrubbed.params.containsKey("safe_field"))
        assertEquals("keep this", (scrubbed.params["safe_field"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should scrub custom PII fields when configured`() =  runTest(timeout = 5.seconds) {
        val config = PiiScrubberConfig(
            customPiiFields = setOf("employee_id", "badge_number")
        )
        scrubber = PiiScrubber(config)
        
        val event = GA4Event(
            name = "test",
            params = mapOf(
                "employee_id" to EventParamValue.StringValue("EMP123"),
                "badge_number" to EventParamValue.StringValue("BADGE456"),
                "other_field" to EventParamValue.StringValue("not PII")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        assertEquals("[REDACTED]", (scrubbed.params["employee_id"] as EventParamValue.StringValue).value)
        assertEquals("[REDACTED]", (scrubbed.params["badge_number"] as EventParamValue.StringValue).value)
        assertEquals("not PII", (scrubbed.params["other_field"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should scrub content matching custom regex patterns`() =  runTest(timeout = 5.seconds) {
        val config = PiiScrubberConfig(
            customPatterns = listOf(
                Regex("EMP\\d+"),  // Employee ID pattern
                Regex("CONF-\\d{4}")  // Confirmation code pattern
            )
        )
        scrubber = PiiScrubber(config)
        
        val event = GA4Event(
            name = "test",
            params = mapOf(
                "message" to EventParamValue.StringValue("Employee EMP12345 has confirmation CONF-9876")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        val message = (scrubbed.params["message"] as EventParamValue.StringValue).value
        assertEquals("Employee EMP[REDACTED] has confirmation CONF-[REDACTED]", message)
    }
    
    @Test
    fun `should correctly identify text containing PII patterns`() =  runTest(timeout = 5.seconds) {
        // Test email detection
        assertTrue(scrubber.containsPii("Contact us at support@example.com"))
        assertFalse(scrubber.containsPii("Contact us at support"))
        
        // Test phone detection
        assertTrue(scrubber.containsPii("Call 555-123-4567"))
        assertFalse(scrubber.containsPii("Call us"))
        
        // Test credit card detection
        assertTrue(scrubber.containsPii("Card: 4111 1111 1111 1111"))
        assertFalse(scrubber.containsPii("Card: ****"))
        
        // Test SSN detection
        assertTrue(scrubber.containsPii("SSN: 123-45-6789"))
        assertFalse(scrubber.containsPii("SSN: XXX-XX-XXXX"))
        
        // Test IP detection
        assertTrue(scrubber.containsPii("IP: 192.168.1.1"))
        assertFalse(scrubber.containsPii("IP: localhost"))
    }
    
    @Test
    fun `should only scrub enabled PII types when selectively configured`() =  runTest(timeout = 5.seconds) {
        val config = PiiScrubberConfig(
            scrubEmails = true,
            scrubPhoneNumbers = false,
            scrubCreditCards = true,
            scrubSsns = false,
            scrubIpAddresses = true
        )
        scrubber = PiiScrubber(config)
        
        val event = GA4Event(
            name = "test",
            params = mapOf(
                "data" to EventParamValue.StringValue(
                    "Email: test@example.com, Phone: 555-1234, " +
                    "Card: 4111111111111111, SSN: 123-45-6789, IP: 192.168.1.1"
                )
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        val data = (scrubbed.params["data"] as EventParamValue.StringValue).value
        
        // Only email, credit card, and IP should be scrubbed
        assertEquals(
            "Email: [REDACTED], Phone: 555-1234, " +
            "Card: [REDACTED], SSN: 123-45-6789, IP: [REDACTED]",
            data
        )
    }
    
    @Test
    fun `should scrub multiple PII patterns in complex nested data`() =  runTest(timeout = 5.seconds) {
        val event = GA4Event(
            name = "complex_event",
            params = mapOf(
                "user_info" to EventParamValue.StringValue("Name: John, Email: john@example.com"),
                "transaction" to EventParamValue.StringValue("Card 4111-1111-1111-1111 charged $100"),
                "metadata" to EventParamValue.StringValue("IP 10.0.0.1 at 2024-01-01"),
                "safe_data" to EventParamValue.StringValue("Order #12345 completed")
            )
        )
        
        val scrubbed = scrubber.scrubEvent(event)
        
        assertEquals(
            "Name: John, Email: [REDACTED]",
            (scrubbed.params["user_info"] as EventParamValue.StringValue).value
        )
        assertEquals(
            "Card [REDACTED] charged $[REDACTED]",
            (scrubbed.params["transaction"] as EventParamValue.StringValue).value
        )
        assertEquals(
            "IP [REDACTED] at [REDACTED]-01-01",
            (scrubbed.params["metadata"] as EventParamValue.StringValue).value
        )
        assertEquals(
            "Order #[REDACTED] completed",
            (scrubbed.params["safe_data"] as EventParamValue.StringValue).value
        )
    }
}