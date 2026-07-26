package duks.ga4.client

import duks.ga4.TestUtils
import duks.ga4.config.GA4Config
import duks.ga4.config.ValidationMode
import duks.ga4.model.ConsentState
import duks.ga4.model.ConsentValue
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import duks.ga4.model.UserPropertyValue
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end checks that GA4Client emits correct Measurement Protocol payloads.
 */
class GA4ClientProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `sendEvent attaches session params and plain param wire format`() = runTest(timeout = 5.seconds) {
        var body: String? = null
        val mockEngine = MockEngine { request ->
            body = request.body.toByteArray().decodeToString()
            respond(content = "", status = HttpStatusCode.NoContent)
        }

        val config = TestUtils.createTestConfig().copy(
            attachSessionParams = true,
            defaultEngagementTimeMsec = 100L,
            validationMode = ValidationMode.LOG
        )
        val client = GA4Client(config, mockEngine, backgroundScope)

        val result = client.sendEvent(
            event = GA4Event(
                name = "page_view",
                params = mapOf(
                    "page_title" to EventParamValue.StringValue("Home")
                )
            ),
            clientId = "123456.789012",
            immediate = true
        )
        assertTrue(result.isSuccess)
        assertNotNull(body)

        val root = json.parseToJsonElement(body!!).jsonObject
        val event = root["events"]!!.jsonArray.single().jsonObject
        val params = event["params"]!!.jsonObject

        assertEquals("Home", params["page_title"]?.jsonPrimitive?.content)
        assertNotNull(params["session_id"])
        assertEquals("100.0", params["engagement_time_msec"]?.jsonPrimitive?.content)
        // Plain values, not type envelopes
        assertTrue(params["page_title"]?.jsonPrimitive != null)

        client.close()
    }

    @Test
    fun `auto-generated client ids are stable across sends`() = runTest(timeout = 5.seconds) {
        val clientIds = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            val root = json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            clientIds.add(root["client_id"]!!.jsonPrimitive.content)
            respond(content = "", status = HttpStatusCode.NoContent)
        }

        val config = TestUtils.createTestConfig().copy(autoGenerateClientId = true)
        val client = GA4Client(config, mockEngine, backgroundScope)

        client.sendEvent(GA4Event("page_view"), immediate = true)
        client.sendEvent(GA4Event("search"), immediate = true)

        assertEquals(2, clientIds.size)
        assertEquals(clientIds[0], clientIds[1])
        client.close()
    }

    @Test
    fun `user properties and consent use MP wire format`() = runTest(timeout = 5.seconds) {
        var body: String? = null
        val mockEngine = MockEngine { request ->
            body = request.body.toByteArray().decodeToString()
            respond(content = "", status = HttpStatusCode.NoContent)
        }

        val config = GA4Config(
            measurementId = "G-TEST",
            apiSecret = "secret",
            defaultConsent = ConsentState(
                adUserData = ConsentValue.GRANTED,
                adPersonalization = ConsentValue.DENIED,
                analyticsStorage = ConsentValue.GRANTED
            ),
            attachSessionParams = false,
            validationMode = ValidationMode.OFF
        )
        val client = GA4Client(config, mockEngine, backgroundScope)

        client.sendEvent(
            event = GA4Event("login"),
            clientId = "1.2",
            immediate = true,
            userProperties = mapOf("tier" to UserPropertyValue.StringValue("gold"))
        ).getOrThrow()

        val root = json.parseToJsonElement(body!!).jsonObject
        val consent = root["consent"]!!.jsonObject
        assertEquals("GRANTED", consent["ad_user_data"]?.jsonPrimitive?.content)
        assertEquals("DENIED", consent["ad_personalization"]?.jsonPrimitive?.content)
        assertEquals(null, consent["analytics_storage"])

        val props = root["user_properties"]!!.jsonObject
        assertEquals("gold", props["tier"]!!.jsonObject["value"]?.jsonPrimitive?.content)

        client.close()
    }

    @Test
    fun `reserved events are dropped in LOG mode`() = runTest(timeout = 5.seconds) {
        var requestCount = 0
        val mockEngine = MockEngine {
            requestCount++
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val config = TestUtils.createTestConfig().copy(validationMode = ValidationMode.LOG)
        val client = GA4Client(config, mockEngine, backgroundScope)

        val result = client.sendEvent(
            GA4Event("user_engagement"),
            clientId = "1.2",
            immediate = true
        )
        assertTrue(result.isSuccess)
        assertEquals(0, requestCount)
        client.close()
    }
}
