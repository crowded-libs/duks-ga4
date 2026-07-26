package duks.ga4.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecommendedEventsTest {

    private val sampleItems = listOf(
        Item(itemId = "SKU_1", itemName = "Tee", price = 10.0, quantity = 2),
        Item(itemId = "SKU_2", itemName = "Hat", price = 5.0, quantity = 1)
    )

    @Test
    fun `purchaseEvent includes transaction and items`() {
        val event = purchaseEvent(
            transactionId = "T-100",
            items = sampleItems,
            currency = "USD",
            value = sampleItems.totalValue(),
            tax = 1.5,
            shipping = 4.0
        )
        assertEquals(GA4Event.PURCHASE, event.name)
        assertEquals("T-100", (event.params["transaction_id"] as EventParamValue.StringValue).value)
        assertEquals("USD", (event.params["currency"] as EventParamValue.StringValue).value)
        assertEquals(25.0, (event.params["value"] as EventParamValue.NumberValue).value)
        val items = (event.params["items"] as EventParamValue.ItemsValue).value
        assertEquals(2, items.size)
    }

    @Test
    fun `addToCartEvent and beginCheckoutEvent shape`() {
        val cart = addToCartEvent(sampleItems, currency = "USD", value = 25.0)
        assertEquals(GA4Event.ADD_TO_CART, cart.name)
        assertTrue(cart.params.containsKey("items"))

        val checkout = beginCheckoutEvent(sampleItems, currency = "EUR", value = 20.0, coupon = "SAVE")
        assertEquals(GA4Event.BEGIN_CHECKOUT, checkout.name)
        assertEquals("SAVE", (checkout.params["coupon"] as EventParamValue.StringValue).value)
    }

    @Test
    fun `search and login engagement helpers`() {
        val search = searchEvent("kotlin multiplatform")
        assertEquals(GA4Event.SEARCH, search.name)
        assertEquals(
            "kotlin multiplatform",
            (search.params["search_term"] as EventParamValue.StringValue).value
        )

        val login = loginEvent(method = "email")
        assertEquals(GA4Event.LOGIN, login.name)
        assertEquals("email", (login.params["method"] as EventParamValue.StringValue).value)
    }

    @Test
    fun `totalValue sums price times quantity`() {
        assertEquals(25.0, sampleItems.totalValue())
        assertEquals(0.0, emptyList<Item>().totalValue())
        assertNotNull(viewItemEvent(sampleItems))
    }

    @Test
    fun `refund without items still has transaction_id`() {
        val refund = refundEvent(transactionId = "T-100", currency = "USD", value = 10.0)
        assertEquals(GA4Event.REFUND, refund.name)
        assertEquals("T-100", (refund.params["transaction_id"] as EventParamValue.StringValue).value)
        assertTrue(refund.params["items"] == null)
    }
}
