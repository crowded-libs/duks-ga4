package duks.ga4.middleware

import duks.*
import duks.ga4.TestUtils
import duks.ga4.TestAction
import duks.ga4.UserAction
import duks.ga4.CommerceAction
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class EventMapperTest {
    
    data class TestState(
        val userId: String? = null,
        val cartItems: Int = 0,
        val isLoggedIn: Boolean = false
    )
    
    @Test
    fun `should map async loading action to loading event with default mapper`() =  runTest(timeout = 5.seconds) {
        val mapper = DefaultEventMapper<TestState>()
        val state = TestState()
        
        // Test async loading action
        val initiatingAction = TestAction.Load("test")
        val loadingAction = AsyncProcessing(initiatingAction)
        val events = mapper.mapActionAfter(loadingAction, state)
        
        assertEquals(1, events.size)
        assertEquals("async_action", events[0].name)
        assertEquals("loading", (events[0].params["status"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should map async success action with list data to success event`() =  runTest(timeout = 5.seconds) {
        val mapper = DefaultEventMapper<TestState>()
        val state = TestState()
        
        // Test success with list data
        val initiatingAction = TestAction.Load("test")
        val successAction = AsyncResultAction(initiatingAction, listOf("item1", "item2", "item3"))
        val events = mapper.mapActionAfter(successAction, state)
        
        assertEquals(1, events.size)
        assertEquals("async_action", events[0].name)
        assertEquals("success", (events[0].params["status"] as EventParamValue.StringValue).value)
        assertTrue((events[0].params["has_data"] as EventParamValue.BooleanValue).value)
        assertEquals(3L, (events[0].params["data_count"] as EventParamValue.NumberValue).value.toLong())
    }
    
    @Test
    fun `should map async failure action to failure event with error message`() =  runTest(timeout = 5.seconds) {
        val mapper = DefaultEventMapper<TestState>()
        val state = TestState()
        
        // Test failure action
        val initiatingAction = TestAction.Load("test")
        val error = RuntimeException("Network error")
        val failureAction = AsyncError(initiatingAction, error)
        val events = mapper.mapActionAfter(failureAction, state)
        
        assertEquals(1, events.size)
        assertEquals("async_action", events[0].name)
        assertEquals("failure", (events[0].params["status"] as EventParamValue.StringValue).value)
        assertEquals("Network error", (events[0].params["error_message"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should map user login action to custom action event`() =  runTest(timeout = 5.seconds) {
        val mapper = DefaultEventMapper<TestState>()
        val state = TestState()
        
        val loginAction = UserAction.Login(method = "email", userId = "user123")
        val events = mapper.mapActionAfter(loginAction, state)
        
        // DefaultEventMapper now returns generic custom_action for non-async actions
        assertEquals(1, events.size)
        assertEquals("custom_action", events[0].name)
        assertEquals("Login", (events[0].params["action_type"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should map user logout action to custom action event`() =  runTest(timeout = 5.seconds) {
        val mapper = DefaultEventMapper<TestState>()
        val state = TestState(isLoggedIn = true)
        
        val events = mapper.mapActionAfter(UserAction.Logout, state)
        
        // DefaultEventMapper now returns generic custom_action for non-async actions
        assertEquals(1, events.size)
        assertEquals("custom_action", events[0].name)
        assertEquals("Logout", (events[0].params["action_type"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should map user sign up action to custom action event`() =  runTest(timeout = 5.seconds) {
        val mapper = DefaultEventMapper<TestState>()
        val state = TestState()
        
        val signUpAction = UserAction.SignUp(method = "google")
        val events = mapper.mapActionAfter(signUpAction, state)
        
        // DefaultEventMapper now returns generic custom_action for non-async actions
        assertEquals(1, events.size)
        assertEquals("custom_action", events[0].name)
        assertEquals("SignUp", (events[0].params["action_type"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should map user profile update action with updated fields to custom action event`() =  runTest(timeout = 5.seconds) {
        val mapper = DefaultEventMapper<TestState>()
        val state = TestState()
        
        val updateAction = UserAction.ProfileUpdate(
            updatedFields = listOf("name", "email", "phone")
        )
        val events = mapper.mapActionAfter(updateAction, state)
        
        // DefaultEventMapper now returns generic custom_action for non-async actions
        assertEquals(1, events.size)
        assertEquals("custom_action", events[0].name)
        assertEquals("ProfileUpdate", (events[0].params["action_type"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should map commerce view item action to custom action event`() =  runTest(timeout = 5.seconds) {
        val mapper = DefaultEventMapper<TestState>()
        val state = TestState()
        
        val viewAction = CommerceAction.ViewItem(
            itemId = "SKU123",
            itemName = "Test Product",
            category = "Electronics",
            price = 99.99
        )
        val events = mapper.mapActionAfter(viewAction, state)
        
        // DefaultEventMapper now returns generic custom_action for non-async actions
        assertEquals(1, events.size)
        assertEquals("custom_action", events[0].name)
        assertEquals("ViewItem", (events[0].params["action_type"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should map commerce add to cart action to custom action event`() =  runTest(timeout = 5.seconds) {
        val mapper = DefaultEventMapper<TestState>()
        val state = TestState()
        
        val addAction = CommerceAction.AddToCart(
            itemId = "SKU456",
            itemName = "Another Product",
            quantity = 2,
            price = 49.99
        )
        val events = mapper.mapActionAfter(addAction, state)
        
        // DefaultEventMapper now returns generic custom_action for non-async actions
        assertEquals(1, events.size)
        assertEquals("custom_action", events[0].name)
        assertEquals("AddToCart", (events[0].params["action_type"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should map commerce purchase action to custom action event`() =  runTest(timeout = 5.seconds) {
        val mapper = DefaultEventMapper<TestState>()
        val state = TestState()
        
        val purchaseAction = CommerceAction.Purchase(
            transactionId = "TXN123",
            totalValue = 299.97,
            currency = "USD",
            itemCount = 3
        )
        val events = mapper.mapActionAfter(purchaseAction, state)
        
        // DefaultEventMapper now returns generic custom_action for non-async actions
        assertEquals(1, events.size)
        assertEquals("custom_action", events[0].name)
        assertEquals("Purchase", (events[0].params["action_type"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should map unhandled custom action to generic custom action event`() =  runTest(timeout = 5.seconds) {
        val mapper = DefaultEventMapper<TestState>()
        val state = TestState()
        
        // Custom action not handled by default mapper
        data class CustomAction(val value: String)
        
        val customAction = CustomAction("test")
        val events = mapper.mapActionAfter(customAction, state)
        
        assertEquals(1, events.size)
        assertEquals("custom_action", events[0].name)
        assertEquals("CustomAction", (events[0].params["action_type"] as EventParamValue.StringValue).value)
    }
    
    @Test
    fun `should delegate mapping to appropriate mapper when using composite mapper`() =  runTest(timeout = 5.seconds) {
        val mapper1 = object : EventMapper<TestState> {
            override suspend fun mapActionAfter(action: Any, state: TestState): List<GA4Event> {
                return if (action is UserAction) {
                    listOf(GA4Event(name = "mapper1_user_action"))
                } else {
                    emptyList()
                }
            }
        }
        
        val mapper2 = object : EventMapper<TestState> {
            override suspend fun mapActionAfter(action: Any, state: TestState): List<GA4Event> {
                return if (action is CommerceAction) {
                    listOf(GA4Event(name = "mapper2_commerce_action"))
                } else {
                    emptyList()
                }
            }
        }
        
        val compositeMapper = CompositeEventMapper(listOf(mapper1, mapper2))
        val state = TestState()
        
        // Test user action - should be handled by mapper1
        val userEvents = compositeMapper.mapActionAfter(UserAction.Logout, state)
        assertEquals(1, userEvents.size)
        assertEquals("mapper1_user_action", userEvents[0].name)
        
        // Test commerce action - should be handled by mapper2
        val commerceEvents = compositeMapper.mapActionAfter(
            CommerceAction.ViewItem("1", "Item", "Cat", 10.0),
            state
        )
        assertEquals(1, commerceEvents.size)
        assertEquals("mapper2_commerce_action", commerceEvents[0].name)
    }
    
    @Test
    fun `should only map actions that pass filter predicate when using filtering mapper`() =  runTest(timeout = 5.seconds) {
        val baseMapper = DefaultEventMapper<TestState>()
        
        // Only map user actions
        val filteringMapper = FilteringEventMapper(
            delegate = baseMapper,
            shouldMapAction = { action -> action is UserAction }
        )
        
        val state = TestState()
        
        // User action should be mapped
        val userEvents = filteringMapper.mapActionAfter(
            UserAction.Login("email", "user1"),
            state
        )
        assertEquals(1, userEvents.size)
        assertEquals("custom_action", userEvents[0].name)
        assertEquals("Login", (userEvents[0].params["action_type"] as EventParamValue.StringValue).value)
        
        // Commerce action should be filtered out
        val commerceEvents = filteringMapper.mapActionAfter(
            CommerceAction.ViewItem("1", "Item", "Cat", 10.0),
            state
        )
        assertEquals(0, commerceEvents.size)
    }
    
    @Test
    fun `should map actions to events based on registered patterns`() =  runTest(timeout = 5.seconds) {
        val patternMapper = PatternEventMapper<TestState>()
        
        // Register patterns
        patternMapper.pattern<UserAction.Login> { action, state ->
            listOf(
                GA4Event(
                    name = "custom_login",
                    params = mapOf(
                        "login_method" to EventParamValue.StringValue(action.method),
                        "is_first_login" to EventParamValue.BooleanValue(!state.isLoggedIn)
                    )
                )
            )
        }
        
        patternMapper.pattern<CommerceAction.Purchase> { action, state ->
            listOf(
                GA4Event(
                    name = "custom_purchase",
                    params = mapOf(
                        "order_id" to EventParamValue.StringValue(action.transactionId),
                        "total" to EventParamValue.NumberValue(action.totalValue)
                    )
                )
            )
        }
        
        val state = TestState(isLoggedIn = false)
        
        // Test login pattern
        val loginEvents = patternMapper.mapActionAfter(
            UserAction.Login("google", "user123"),
            state
        )
        assertEquals(1, loginEvents.size)
        assertEquals("custom_login", loginEvents[0].name)
        assertEquals("google", (loginEvents[0].params["login_method"] as EventParamValue.StringValue).value)
        assertTrue((loginEvents[0].params["is_first_login"] as EventParamValue.BooleanValue).value)
        
        // Test purchase pattern
        val purchaseEvents = patternMapper.mapActionAfter(
            CommerceAction.Purchase("ORDER123", 99.99, "USD", 2),
            state
        )
        assertEquals(1, purchaseEvents.size)
        assertEquals("custom_purchase", purchaseEvents[0].name)
        assertEquals("ORDER123", (purchaseEvents[0].params["order_id"] as EventParamValue.StringValue).value)
        
        // Test unmatched pattern
        val unmatchedEvents = patternMapper.mapActionAfter(UserAction.Logout, state)
        assertEquals(0, unmatchedEvents.size)
    }
    
    @Test
    fun `should map action before state change when implementing mapActionBefore`() =  runTest(timeout = 5.seconds) {
        val mapper = object : EventMapper<TestState> {
            override suspend fun mapActionBefore(action: Any, state: TestState): List<GA4Event> {
                return if (action is CommerceAction.BeginCheckout) {
                    listOf(
                        GA4Event(
                            name = "checkout_started",
                            params = mapOf(
                                "cart_items" to EventParamValue.NumberValue(state.cartItems.toDouble())
                            )
                        )
                    )
                } else {
                    emptyList()
                }
            }
            
            override suspend fun mapActionAfter(action: Any, state: TestState): List<GA4Event> {
                return emptyList()
            }
        }
        
        val state = TestState(cartItems = 5)
        
        // Test before mapping
        val beforeEvents = mapper.mapActionBefore(
            CommerceAction.BeginCheckout(100.0, "USD", 5),
            state
        )
        
        assertEquals(1, beforeEvents.size)
        assertEquals("checkout_started", beforeEvents[0].name)
        assertEquals(5.0, (beforeEvents[0].params["cart_items"] as EventParamValue.NumberValue).value)
    }
    
    @Test
    fun `should generate multiple events based on state when mapping complex scenarios`() =  runTest(timeout = 5.seconds) {
        val mapper = object : EventMapper<TestState> {
            override suspend fun mapActionAfter(action: Any, state: TestState): List<GA4Event> {
                return when (action) {
                    is CommerceAction.AddToCart -> {
                        val events = mutableListOf<GA4Event>()
                        
                        // Track add to cart
                        events.add(
                            GA4Event(
                                name = "add_to_cart",
                                params = mapOf(
                                    "item_id" to EventParamValue.StringValue(action.itemId),
                                    "logged_in" to EventParamValue.BooleanValue(state.isLoggedIn)
                                )
                            )
                        )
                        
                        // Track milestone if first item
                        if (state.cartItems == 0) {
                            events.add(
                                GA4Event(
                                    name = "first_add_to_cart",
                                    params = mapOf(
                                        "user_id" to EventParamValue.StringValue(state.userId ?: "anonymous")
                                    )
                                )
                            )
                        }
                        
                        events
                    }
                    else -> emptyList()
                }
            }
        }
        
        // Test with empty cart
        val emptyCartState = TestState(userId = "user123", cartItems = 0, isLoggedIn = true)
        val events = mapper.mapActionAfter(
            CommerceAction.AddToCart("ITEM1", "Product", 1, 29.99),
            emptyCartState
        )
        
        assertEquals(2, events.size)
        assertEquals("add_to_cart", events[0].name)
        assertEquals("first_add_to_cart", events[1].name)
        
        // Test with existing items
        val existingCartState = TestState(userId = "user123", cartItems = 3, isLoggedIn = false)
        val events2 = mapper.mapActionAfter(
            CommerceAction.AddToCart("ITEM2", "Product2", 1, 39.99),
            existingCartState
        )
        
        assertEquals(1, events2.size)
        assertEquals("add_to_cart", events2[0].name)
        assertFalse((events2[0].params["logged_in"] as EventParamValue.BooleanValue).value)
    }
}