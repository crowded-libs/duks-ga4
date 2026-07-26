# duks-ga4

A Kotlin Multiplatform library for Google Analytics 4 (GA4) integration with the [duks](https://github.com/mattshoe/duks) state management library. This library provides seamless analytics tracking, privacy-compliant data collection, and powerful middleware integration for your Kotlin applications.

![duks-ga4 Logo](duks-logo.png)

## Features

- **Kotlin Multiplatform Support**: Works on Android, iOS, JVM, and JS/WASM platforms
- **GA4 Measurement Protocol**: Direct integration with Google Analytics 4
- **duks Middleware Integration**: Automatic action tracking and state-based analytics
- **Privacy & GDPR Compliance**: Built-in consent management and PII scrubbing
- **Event Batching**: Efficient event batching with configurable flush intervals
- **Routing Analytics**: Automatic screen view and navigation tracking with duks-routing
- **Type-Safe Events**: Strongly typed event parameters and validation
- **Debug Mode**: Built-in validation and debugging support
- **In-memory queuing**: Event batching with retry (process-local; not durable across restarts unless you supply storage)
- **Session params**: Automatic `session_id` and `engagement_time_msec` for Realtime / engaged sessions

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.crowded-libs:duks-ga4:0.1.0")
}
```
## Quick Start

### Basic Setup

```kotlin
// 1. Create GA4 configuration
val config = GA4Config(
    measurementId = "G-XXXXXXXXXX",  // Your GA4 Measurement ID
    apiSecret = "your-api-secret",   // Your GA4 API Secret
    debugMode = true                 // Enable for testing
)

// 2. Create GA4 client
val ga4Client = GA4Client(config)

// 3. Send events
suspend fun trackPageView(pageName: String) {
    ga4Client.sendEvent(
        event = GA4Event(
            name = "page_view",
            params = mapOf(
                "page_title" to EventParamValue.StringValue(pageName),
                "page_location" to EventParamValue.StringValue("/home")
            )
        ),
        clientId = "user123"  // Required: unique client identifier
    )
}

// 4. Clean up when done
ga4Client.close()
```

### Integration with duks Store

```kotlin
import duks.*
import duks.ga4.middleware.ga4Analytics

// Define your state and actions
data class AppState(val user: User?, val cartItems: Int = 0) : StateModel
data class User(val id: String, val name: String)

sealed class AppAction : Action {
    data class Login(val userId: String) : AppAction()
    object Logout : AppAction()
    data class AddToCart(val itemId: String) : AppAction()
}

// Create store with GA4 middleware
val store = createStore(AppState()) {
    // Add GA4 analytics
    ga4Analytics {
        config {
            measurementId("G-XXXXXXXXXX")
            apiSecret("your-api-secret")
            debugMode()
        }
        
        // Map actions to GA4 events
        patternMapper {
            pattern<AppAction.Login> { action, state ->
                listOf(
                    GA4Event(
                        name = "login",
                        params = mapOf(
                            "method" to EventParamValue.StringValue("email"),
                            "user_id" to EventParamValue.StringValue(action.userId)
                        )
                    )
                )
            }
            
            pattern<AppAction.AddToCart> { action, state ->
                listOf(
                    GA4Event(
                        name = "add_to_cart",
                        params = mapOf(
                            "item_id" to EventParamValue.StringValue(action.itemId),
                            "cart_size" to EventParamValue.NumberValue(state.cartItems + 1.0)
                        )
                    )
                )
            }
        }
        
        // Provide client ID from state
        clientIdProvider { state -> state.user?.id }
    }
}
```

## Configuration Options

### GA4Config

```kotlin
GA4Config(
    // Required
    measurementId = "G-XXXXXXXXXX",     // Your GA4 Measurement ID
    apiSecret = "your-api-secret",      // Your GA4 API Secret
    
    // Optional
    defaultClientId = "default-client", // Default client ID if none provided
    debugMode = false,                  // Enable debug validation endpoint
    autoGenerateClientId = true,        // Stable in-process auto client IDs
    attachSessionParams = true,         // Attach session_id + engagement_time_msec
    validationMode = ValidationMode.LOG,// OFF | LOG | STRICT
    preferPageViewForWeb = true,        // Warn on screen_view for web streams
    customEndpoint = null,              // Custom endpoint URL
    requestTimeoutMs = 30_000L,         // Request timeout (30 seconds)
    maxEventsPerBatch = 25,             // Max events per batch (GA4 limit)
    enableRetry = true,                 // Enable failed request retry
    maxRetries = 3,                     // Maximum retry attempts
    retryDelayMs = 1_000L,              // Initial retry delay
    
    // Privacy configuration (enforced when you enable privacy on middleware)
    privacyConfig = PrivacyConfig(
        enforceConsent = true,          // Enforce consent before tracking
        scrubPii = true,                // Automatically scrub PII
        enableEventStore = false,       // Store events for privacy actions
        eventRetentionDays = 30         // Days to retain stored events
    )
)
```

## Usage Examples

### E-commerce Tracking

Prefer the typed recommended-event helpers:

```kotlin
import duks.ga4.model.*

suspend fun trackProductView(product: Product) {
    val item = Item(
        itemId = product.id,
        itemName = product.name,
        itemCategory = product.category,
        price = product.price,
        quantity = 1
    )
    ga4Client.sendEvent(
        viewItemEvent(
            items = listOf(item),
            currency = "USD",
            value = product.price
        ),
        clientId = "user123",
        immediate = false
    )
}

suspend fun trackPurchase(order: Order) {
    val items = order.items.map { /* map to Item */ }
    ga4Client.sendEvent(
        purchaseEvent(
            transactionId = order.id,
            items = items,
            currency = "USD",
            value = items.totalValue(),
            tax = order.tax,
            shipping = order.shipping
        ),
        clientId = "user123",
        immediate = true
    )
}
```

Or build events manually:

```kotlin
// Track product views
suspend fun trackProductViewManual(product: Product) {
    ga4Client.sendEvent(
        GA4Event(
            name = "view_item",
            params = mapOf(
                "currency" to EventParamValue.StringValue("USD"),
                "value" to EventParamValue.NumberValue(product.price),
                "items" to EventParamValue.ItemsValue(
                    listOf(
                        Item(
                            itemId = product.id,
                            itemName = product.name,
                            itemCategory = product.category,
                            price = product.price,
                            quantity = 1
                        )
                    )
                )
            )
        )
    )
}

### Device / geo context

Supply structured `device` / `user_location` (or `ip_override`) on every request:

```kotlin
val client = GA4Client(
    config = config,
    scope = scope,
    contextProvider = ContextProvider {
        RequestContext(
            device = DeviceInfo(
                category = "desktop",
                language = "en",
                browser = "Chrome",
                screenResolution = "1920x1080"
            ),
            userLocation = UserLocation(countryId = "US", regionId = "US-CA")
        )
    }
)
```

### Durable queue (optional)

The default queue is in-memory. To survive process death, implement `EventQueueStore`
(prefer **kotlin-lmdb** in your app) and pass it to the client:

```kotlin
val client = GA4Client(
    config = config,
    scope = scope,
    eventQueueStore = myLmdbEventQueueStore
)
```

### Custom Event Tracking

```kotlin
// Track engagement on a real event (do not send reserved "user_engagement")
suspend fun trackEngagement(action: String, category: String, value: Long? = null) {
    val params = mutableMapOf<String, EventParamValue>(
        "action" to EventParamValue.StringValue(action),
        "category" to EventParamValue.StringValue(category),
        // session_id / engagement_time_msec are attached by the client when enabled
        "engagement_time_msec" to EventParamValue.NumberValue((value ?: 100L).toDouble())
    )
    
    ga4Client.sendEvent(
        GA4Event(name = "select_content", params = params)
    )
}

// Track search
suspend fun trackSearch(searchTerm: String, resultsCount: Int) {
    ga4Client.sendEvent(
        GA4Event(
            name = "search",
            params = mapOf(
                "search_term" to EventParamValue.StringValue(searchTerm),
                "results_count" to EventParamValue.NumberValue(resultsCount.toDouble())
            )
        )
    )
}
```

### Batch Event Sending

```kotlin
// Send multiple events at once
suspend fun trackUserJourney(events: List<GA4Event>) {
    ga4Client.sendEvents(
        events = events,
        clientId = "user123",
        immediate = true  // Send immediately without batching
    )
}

// Queue events for batch sending
suspend fun queueEvents(events: List<GA4Event>) {
    ga4Client.sendEvents(
        events = events,
        clientId = "user123",
        immediate = false  // Add to batch queue
    )
    
    // Manually flush when ready
    ga4Client.flush()
}
```

## Privacy and GDPR Compliance

### Consent Management

```kotlin
// Create consent manager
val consentStorage = InMemoryConsentStorage() // Or implement your own
val consentManager = DefaultConsentManager(
    storage = consentStorage,
    defaultConsent = ConsentState(
        analyticsStorage = ConsentValue.DENIED,
        adStorage = ConsentValue.DENIED
    )
)

// Update consent based on user preferences
suspend fun updateUserConsent(analyticsAccepted: Boolean, adsAccepted: Boolean) {
    consentManager.updateConsent(
        ConsentState(
            analyticsStorage = if (analyticsAccepted) ConsentValue.GRANTED else ConsentValue.DENIED,
            adStorage = if (adsAccepted) ConsentValue.GRANTED else ConsentValue.DENIED,
            adPersonalization = if (adsAccepted) ConsentValue.GRANTED else ConsentValue.DENIED,
            adUserData = if (adsAccepted) ConsentValue.GRANTED else ConsentValue.DENIED
        )
    )
}

// Check consent before tracking
if (consentManager.shouldProcessEvent("purchase")) {
    // Track purchase event
}

// Monitor consent state
consentManager.analyticsEnabled.collect { enabled ->
    println("Analytics enabled: $enabled")
}
```

### PII Scrubbing

```kotlin
// Configure PII scrubbing
val privacyConfig = PrivacyConfig(
    scrubPii = true,
    piiScrubberConfig = PiiScrubberConfig(
        enabled = true,
        scrubEmails = true,
        scrubPhoneNumbers = true,
        scrubCreditCards = true,
        scrubSsn = true,
        scrubIpAddresses = true,
        customPiiFields = setOf("user_id", "account_number"),
        customPiiPatterns = setOf(
            Regex("""\b\d{4}-\d{4}-\d{4}\b""") // Custom pattern
        )
    )
)

// Events will automatically have PII scrubbed
val event = GA4Event(
    name = "contact_form",
    params = mapOf(
        "email" to EventParamValue.StringValue("user@example.com"), // Will be scrubbed
        "message" to EventParamValue.StringValue("Call me at 555-1234") // Phone scrubbed
    )
)
```

### Privacy Actions (GDPR Rights)

```kotlin
// Set up event store for privacy actions
val eventStore = InMemoryEventStore()
val privacyActions = GA4PrivacyActions(eventStore)

// Export user data (GDPR Right to Access)
suspend fun exportUserData(userId: String): String? {
    val result = privacyActions.exportUserData(userId)
    return if (result.success) {
        result.jsonData // JSON export of all user events
    } else {
        null
    }
}

// Delete user data (GDPR Right to Erasure)
suspend fun deleteUserData(userId: String): Boolean {
    val result = privacyActions.deleteUserData(userId)
    println("Deleted ${result.deletedEventCount} events")
    return result.success
}

// Anonymize user data (GDPR Right to Restriction)
suspend fun anonymizeUserData(userId: String): Boolean {
    val result = privacyActions.anonymizeUserData(userId)
    println("Anonymized ${result.anonymizedEventCount} events")
    return result.success
}
```

## Middleware Features

### Custom Event Mappers

```kotlin
// Create custom event mapper
class CustomEventMapper : EventMapper<AppState> {
    override suspend fun mapActionBefore(action: Any, state: AppState): List<GA4Event> {
        // Map actions before state change
        return when (action) {
            is StartLoadingAction -> listOf(
                GA4Event(
                    name = "loading_started",
                    params = mapOf(
                        "screen" to EventParamValue.StringValue(action.screen)
                    )
                )
            )
            else -> emptyList()
        }
    }
    
    override suspend fun mapActionAfter(action: Any, state: AppState): List<GA4Event> {
        // Map actions after state change
        return when (action) {
            is LoadingCompleteAction -> listOf(
                GA4Event(
                    name = "loading_complete",
                    params = mapOf(
                        "duration_ms" to EventParamValue.NumberValue(action.duration),
                        "success" to EventParamValue.BooleanValue(action.success)
                    )
                )
            )
            else -> emptyList()
        }
    }
}

// Use custom mapper
val middleware = ga4Middleware<AppState> {
    config { /* ... */ }
    eventMapper(CustomEventMapper())
}
```

### Action Filtering

```kotlin
// Filter which actions to track
val middleware = ga4Middleware<AppState> {
    config { /* ... */ }
    
    // Only track specific actions
    filterActions { action ->
        when (action) {
            is UserAction,
            is PurchaseAction,
            is NavigationAction -> true
            else -> false
        }
    }
    
    // Or exclude certain actions
    exclude<InternalAction>()
    exclude<DebugAction>()
}
```

### Routing Analytics Integration

duks-ga4 provides seamless integration with duks-routing for automatic navigation tracking:

```kotlin
// Create store with routing and GA4 analytics
val store = createStore(AppState()) {
    // Set up routing
    val router = routing {
        content("/home") { HomeScreen() }
        content("/profile", requiresAuth = true) { ProfileScreen() }
        content("/product/:id") { ProductDetailScreen() }
        modal("/search") { SearchModal() }
        
        // Tab navigation with config
        content("/videos", config = NavConfig(selectedTab = "videos")) { VideosScreen() }
        content("/music", config = NavConfig(selectedTab = "music")) { MusicScreen() }
    }
    
    // Add GA4 analytics with routing integration
    ga4Analytics {
        config {
            measurementId("G-XXXXXXXXXX")
            apiSecret("your-api-secret")
        }
        
        // Direct integration with RouterMiddleware
        routerMiddleware(router)
        enableRoutingAnalytics()
        
        // Automatically tracks:
        // - screen_view events for all navigation
        // - navigation events with from/to screens
        // - modal_open and modal_dismiss events
        // - tab_switch events when using tab navigation
        // - screen_time tracking
        // - navigation patterns (push, pop, reset)
    }
}
```

#### Automatic Tracking Features

With routing analytics enabled, the following events are automatically tracked:

1. **Screen Views**: Every route change generates a `screen_view` event with:
   - `screen_name`: The current screen identifier
   - `screen_class`: The navigation layer (Content, Modal, Scene)
   - `navigation_depth`: Current stack depth
   - Device context (screen size, orientation, device type)

2. **Navigation Events**: Route changes generate `navigation` events with:
   - `from_screen`: Previous screen
   - `to_screen`: Current screen  
   - `navigation_type`: push, pop, or reset
   - `route_type`: Content, Modal, Scene, or Back
   - Stack sizes for each navigation layer

3. **Modal Events**: Modal interactions generate:
   - `modal_open`: When a modal is shown
   - `modal_dismiss`: When a modal is dismissed
   - Includes parent screen and modal stack depth

4. **Tab Events**: Tab navigation generates `tab_switch` events with:
   - `tab_name`: Current tab identifier
   - `previous_tab_name`: Previous tab (if available)
   - `tab_container`: The containing route

5. **Screen Time**: Automatically tracks time spent on each screen

#### Advanced Routing Analytics

```kotlin
// Combine routing analytics with custom events
ga4Analytics {
    config { /* ... */ }
    
    routerMiddleware(router)
    enableRoutingAnalytics()
    
    // Add custom tracking on top of automatic routing
    patternMapper {
        // Track product views when navigating to product detail
        pattern<Routing.StateChanged> { action, state ->
            val route = action.routerState.getCurrentContentRoute()
            if (route?.route?.path?.startsWith("/product/") == true) {
                val productId = route.param as? String
                productId?.let {
                    listOf(
                        GA4Event(
                            name = "view_item",
                            params = mapOf(
                                "item_id" to EventParamValue.StringValue(it)
                            )
                        )
                    )
                }
            } else emptyList()
        }
    }
}
```

#### Alternative Integration (Action-based)

If you prefer not to pass the RouterMiddleware directly, GA4 will automatically listen for routing actions:

```kotlin
val store = createStore(AppState()) {
    // Set up routing (no need to capture router instance)
    routing {
        content("/home") { HomeScreen() }
        content("/profile") { ProfileScreen() }
    }
    
    // GA4 will automatically detect Routing.StateChanged actions
    ga4Analytics {
        config { /* ... */ }
        enableRoutingAnalytics() // No routerMiddleware() call needed
    }
}
```

## API Documentation

### GA4Client

Main client for sending events to Google Analytics 4.

```kotlin
class GA4Client(config: GA4Config) {
    // Send a single event
    suspend fun sendEvent(
        event: GA4Event,
        clientId: String? = null,
        userId: String? = null,
        immediate: Boolean = false
    ): Result<Unit>
    
    // Send multiple events
    suspend fun sendEvents(
        events: List<GA4Event>,
        clientId: String? = null,
        userId: String? = null,
        immediate: Boolean = false
    ): Result<Unit>
    
    // Flush all pending events
    suspend fun flush()
    
    // Get current queue size
    suspend fun getQueueSize(): Int
    
    // Close client and release resources
    fun close()
}
```

### GA4Event

Represents a Google Analytics 4 event.

```kotlin
data class GA4Event(
    val name: String,
    val params: Map<String, EventParamValue> = emptyMap()
)

// Standard event names
GA4Event.PAGE_VIEW
GA4Event.SCREEN_VIEW
GA4Event.USER_ENGAGEMENT
GA4Event.PURCHASE
GA4Event.ADD_TO_CART
// ... and more
```

### EventParamValue

Type-safe event parameter values.

```kotlin
sealed class EventParamValue {
    data class StringValue(val value: String) : EventParamValue()
    data class NumberValue(val value: Double) : EventParamValue()
    data class BooleanValue(val value: Boolean) : EventParamValue()
    data class ItemsValue(val value: List<Item>) : EventParamValue()
}
```

### GA4Middleware

Middleware for automatic analytics tracking in duks stores.

```kotlin
class GA4Middleware<TState : StateModel>(
    config: GA4Config,
    eventMapper: EventMapper<TState>? = null,
    enableRoutingAnalytics: Boolean = true,
    routerMiddleware: RouterMiddleware<TState>? = null,
    flushInterval: Duration = 10.seconds,
    clientIdProvider: suspend (TState) -> String? = { null },
    userIdProvider: suspend (TState) -> String? = { null }
) : Middleware<TState>, StoreLifecycleAware<TState>
```

## Production checklist

1. **Client ID**: Provide a stable `clientId` (or `defaultClientId` / `clientIdProvider`). Auto-generated IDs are stable for the process; pass a `ClientIdStore` if you need them across restarts (e.g. backed by kotlin-lmdb in your app).

2. **Session params**: Leave `attachSessionParams = true` (default) so every event gets `session_id` and `engagement_time_msec` — required for Realtime and engaged-session metrics.

3. **Debug validation**: Use `debugMode = true` and GA4 DebugView while integrating; the client also runs local validation (`validationMode = LOG` by default).

4. **Event names**: Prefer recommended events (`page_view`, `purchase`, …). Do **not** send reserved names such as `user_engagement` or `session_start` via Measurement Protocol.

5. **Web stream**: This library targets web/gtag-style streams (`measurement_id` + `client_id`). Prefer `page_view` over `screen_view` on web.

6. **Batching**: Prefer batching; send purchases and other critical events with `immediate = true`.

7. **Privacy (opt-in)**: Call `enablePrivacy { ... }` when you need consent gating; PII scrubbing config is available when you wire privacy features.

8. **Offline**: The default queue is **in-memory** with retries. Process death drops queued events unless you add durable storage yourself.

## Best Practices

1. **Client ID Management**: Always provide a consistent client ID for each user to ensure accurate user tracking.

2. **Event Naming**: Use standard GA4 event names when possible for better integration with GA4 reports.

3. **Parameter Limits**: GA4 has limits on event parameters:
   - Max 25 custom parameters per event
   - Parameter names: 40 characters max
   - Parameter values: 100 characters max for strings

4. **Batching**: Use event batching for better performance, but ensure important events (like purchases) are sent immediately.

5. **Error Handling**: Always handle errors from event sending, especially for critical business events.

6. **Privacy First**: Always obtain user consent before tracking and provide clear privacy controls.

7. **Routing Analytics**: When using duks-routing, prefer direct RouterMiddleware integration for the most accurate tracking.

## Troubleshooting

### Debug Mode

Enable debug mode to validate events before sending to production:

```kotlin
val config = GA4Config(
    measurementId = "G-XXXXXXXXXX",
    apiSecret = "your-api-secret",
    debugMode = true  // Enables validation endpoint
)
```

### Common Issues

1. **Events not appearing in GA4**:
   - Check that your measurement ID and API secret are correct
   - Ensure client ID is provided
   - Events may take up to 24 hours to appear in GA4 reports
   - Use DebugView in GA4 for real-time testing

2. **Invalid event parameters**:
   - Parameter names must be alphanumeric with underscores
   - Reserved parameter names cannot be used
   - Check debug mode output for validation errors

3. **Network errors**:
   - The library includes automatic retry logic
   - Check your network connectivity
   - Verify firewall rules allow GA4 endpoints

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This library is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.