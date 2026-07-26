package duks.ga4.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden tests for GA4 Measurement Protocol wire format.
 *
 * Params and user properties must be plain JSON values, not sealed-class envelopes.
 */
class MeasurementProtocolSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Test
    fun `event params serialize as plain JSON values`() {
        val event = GA4Event(
            name = "purchase",
            params = mapOf(
                "currency" to EventParamValue.StringValue("USD"),
                "value" to EventParamValue.NumberValue(9.99),
                "transaction_id" to EventParamValue.StringValue("T_123"),
                "items" to EventParamValue.ItemsValue(
                    listOf(
                        Item(
                            itemId = "SKU_1",
                            itemName = "Widget",
                            price = 9.99,
                            quantity = 1
                        )
                    )
                )
            )
        )

        val encoded = json.encodeToString(event)
        val obj = json.parseToJsonElement(encoded).jsonObject

        assertEquals("purchase", obj["name"]?.jsonPrimitive?.content)
        val params = obj["params"]!!.jsonObject

        // Plain string — not {"type":"string","value":"USD"}
        assertEquals("USD", params["currency"]?.jsonPrimitive?.content)
        assertFalse(params["currency"] is JsonObject)

        assertEquals(9.99, params["value"]?.jsonPrimitive?.double)
        assertEquals("T_123", params["transaction_id"]?.jsonPrimitive?.content)

        val items = params["items"]!!.jsonArray
        assertEquals(1, items.size)
        val item = items[0].jsonObject
        assertEquals("SKU_1", item["item_id"]?.jsonPrimitive?.content)
        assertEquals("Widget", item["item_name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `boolean params serialize as JSON booleans`() {
        val event = GA4Event(
            name = "custom_event",
            params = mapOf("enabled" to EventParamValue.BooleanValue(true))
        )
        val params = json.parseToJsonElement(json.encodeToString(event))
            .jsonObject["params"]!!.jsonObject
        assertTrue(params["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `user properties serialize as value objects`() {
        val request = GA4Request(
            clientId = "123456.789012",
            userProperties = mapOf(
                "membership" to UserPropertyValue.StringValue("gold"),
                "level" to UserPropertyValue.NumberValue(5.0)
            ),
            events = listOf(GA4Event(name = "login"))
        )

        val encoded = json.encodeToString(request)
        val props = json.parseToJsonElement(encoded).jsonObject["user_properties"]!!.jsonObject

        // { "membership": { "value": "gold" } } — not sealed type envelope
        assertEquals("gold", props["membership"]!!.jsonObject["value"]?.jsonPrimitive?.content)
        assertEquals(5.0, props["level"]!!.jsonObject["value"]?.jsonPrimitive?.double)
        assertNull(props["membership"]!!.jsonObject["type"])
    }

    @Test
    fun `consent serializes only MP fields with uppercase values`() {
        val request = GA4Request(
            clientId = "123456.789012",
            consent = ConsentState(
                adStorage = ConsentValue.DENIED,
                analyticsStorage = ConsentValue.GRANTED,
                adUserData = ConsentValue.GRANTED,
                adPersonalization = ConsentValue.DENIED,
                functionalityStorage = ConsentValue.GRANTED
            ),
            events = listOf(GA4Event(name = "page_view"))
        )

        val encoded = json.encodeToString(request)
        val consent = json.parseToJsonElement(encoded).jsonObject["consent"]!!.jsonObject

        assertEquals("GRANTED", consent["ad_user_data"]?.jsonPrimitive?.content)
        assertEquals("DENIED", consent["ad_personalization"]?.jsonPrimitive?.content)
        // App-side Consent Mode fields must not appear on the wire
        assertNull(consent["ad_storage"])
        assertNull(consent["analytics_storage"])
        assertNull(consent["functionality_storage"])
    }

    @Test
    fun `full request golden shape matches Measurement Protocol`() {
        val request = GA4Request(
            clientId = "123456.789012",
            userId = "user-1",
            timestampMicros = 1_700_000_000_000_000L,
            userProperties = mapOf(
                "customer_tier" to UserPropertyValue.StringValue("premium")
            ),
            consent = ConsentState(
                adUserData = ConsentValue.GRANTED,
                adPersonalization = ConsentValue.GRANTED
            ),
            events = listOf(
                GA4Event(
                    name = "page_view",
                    params = mapOf(
                        "page_location" to EventParamValue.StringValue("https://example.com/"),
                        "page_title" to EventParamValue.StringValue("Home"),
                        "session_id" to EventParamValue.StringValue("1700000000"),
                        "engagement_time_msec" to EventParamValue.NumberValue(100.0)
                    )
                )
            )
        )

        val root = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals("123456.789012", root["client_id"]?.jsonPrimitive?.content)
        assertEquals("user-1", root["user_id"]?.jsonPrimitive?.content)
        assertEquals(1_700_000_000_000_000L, root["timestamp_micros"]?.jsonPrimitive?.content?.toLong())

        val event = root["events"]!!.jsonArray.single().jsonObject
        assertEquals("page_view", event["name"]?.jsonPrimitive?.content)
        val params = event["params"]!!.jsonObject
        assertEquals("https://example.com/", params["page_location"]?.jsonPrimitive?.content)
        assertEquals("100.0", params["engagement_time_msec"]?.jsonPrimitive?.content)
        // Ensure no type discriminator pollution
        assertTrue(params.values.none { it is JsonObject && it.containsKey("type") })
    }

    @Test
    fun `round-trip deserializes plain param values`() {
        val original = GA4Event(
            name = "search",
            params = mapOf(
                "search_term" to EventParamValue.StringValue("kotlin"),
                "results" to EventParamValue.NumberValue(12.0)
            )
        )
        val decoded = json.decodeFromString<GA4Event>(json.encodeToString(original))
        assertEquals(original.name, decoded.name)
        assertEquals(
            (original.params["search_term"] as EventParamValue.StringValue).value,
            (decoded.params["search_term"] as EventParamValue.StringValue).value
        )
        assertEquals(
            (original.params["results"] as EventParamValue.NumberValue).value,
            (decoded.params["results"] as EventParamValue.NumberValue).value
        )
    }

    @Test
    fun `legacy sealed envelope still deserializes`() {
        val legacy = """
            {
              "name": "click",
              "params": {
                "link_url": { "type": "string", "value": "https://example.com" },
                "count": { "type": "number", "value": 3.0 }
              }
            }
        """.trimIndent()

        val decoded = json.decodeFromString<GA4Event>(legacy)
        assertEquals("click", decoded.name)
        assertEquals(
            "https://example.com",
            (decoded.params["link_url"] as EventParamValue.StringValue).value
        )
        assertEquals(3.0, (decoded.params["count"] as EventParamValue.NumberValue).value)
    }
}
