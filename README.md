# duks-ga4

[![Build](https://github.com/crowded-libs/duks-ga4/actions/workflows/build.yml/badge.svg)](https://github.com/crowded-libs/duks-ga4/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.crowded-libs/duks-ga4.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.crowded-libs%22%20AND%20a:%22duks-ga4%22)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-blue.svg?logo=kotlin)](http://kotlinlang.org)

A Kotlin Multiplatform library for Google Analytics 4 (GA4) with the [duks](https://github.com/crowded-libs/duks) state management framework — Measurement Protocol client, action→event middleware, routing analytics, and opt-in privacy.

![duks-ga4 Logo](duks-logo.png)

## Features

- **Kotlin Multiplatform**: Android, iOS, JVM, WASM
- **GA4 Measurement Protocol**: Correct wire-format params, consent, device/geo
- **duks middleware**: Action → event mapping, single client-owned queue
- **Recommended events**: Typed helpers for ecommerce and engagement
- **Routing analytics**: Screen views, navigation, modals, tabs via duks-routing
- **Sessions**: Auto `session_id` + `engagement_time_msec` for Realtime metrics
- **Privacy (opt-in)**: Consent gating and PII scrubbing when enabled
- **Queuing**: In-memory batch + retry; optional `EventQueueStore` for durability

## Installation

```kotlin
dependencies {
    implementation("io.github.crowded-libs:duks-ga4:0.2.0")
}
```

Requires **duks 0.4** and **duks-routing 0.3.1+** when using routing analytics.

## Quick start (duks store)

Most apps should attach analytics through the store. Map actions to events, provide a stable client ID, and optionally wire routing.

```kotlin
import duks.*
import duks.ga4.middleware.ga4Analytics
import duks.ga4.model.*
import duks.routing.HasRouterState
import duks.routing.RouterState
import duks.routing.routing

data class AppState(
    val userId: String? = null,
    override val routerState: RouterState = RouterState(),
) : HasRouterState {
    override fun withRouterState(routerState: RouterState) = copy(routerState = routerState)
}

sealed class AppAction : Action {
    data class Login(val method: String) : AppAction()
    data class AddToCart(val itemId: String, val price: Double) : AppAction()
}

val store = createStore(AppState()) {
    val router = routing {
        content("/home") { HomeScreen() }
        content("/product/{id}") { ProductScreen() }
    }

    ga4Analytics {
        config {
            measurementId("G-XXXXXXXXXX")
            apiSecret("your-api-secret")
            debugMode() // DebugView while integrating
        }

        clientIdProvider { state -> state.userId }
        trackRouting(router)

        patternMapper {
            pattern<AppAction.Login> { action, _ ->
                listOf(loginEvent(method = action.method))
            }
            pattern<AppAction.AddToCart> { action, _ ->
                listOf(
                    addToCartEvent(
                        items = listOf(
                            Item(itemId = action.itemId, price = action.price, quantity = 1)
                        ),
                        currency = "USD",
                        value = action.price
                    )
                )
            }
        }
    }
}
```

`trackRouting(router)` registers a `NavigationListener` on duks-routing so every committed stack change produces routing analytics. You get:

| Event | Notable params |
|-------|----------------|
| `screen_view` | `screen_name`, `screen_class`, `previous_screen`, `modal_route`, … |
| `screen_time` | `screen_name`, `route_duration_seconds`, `engagement_time_msec` |
| `navigation` | `from_screen`, `to_screen`, `navigation_type`, `navigation_pattern`, … |
| `modal_open` / `modal_close` / `modal_dismiss` | `modal_name`, `modal_path`, `parent_screen` |
| `tab_switch` | `tab_name`, `screen_name` (when route `config` has `selectedTab`) |

App state must implement `HasRouterState` + `withRouterState` (required by duks-routing 0.3). Domain reducers should not handle routing actions — stacks are reducer-owned by the routing library.

## Standalone client

For scripts, services, or non-duks code, use `GA4Client` directly (you own the `CoroutineScope`):

```kotlin
val client = GA4Client(config = config, scope = scope)

client.sendEvent(
    pageViewEvent(pageTitle = "Home", pageLocation = "/home"),
    clientId = "stable-client-id"
)

// Critical events: send immediately. Everything else can batch.
client.sendEvent(purchaseEvent(...), clientId = id, immediate = true)
client.flush()
client.close()
```

Typed helpers live in `duks.ga4.model` (`loginEvent`, `searchEvent`, `viewItemEvent`, `purchaseEvent`, …). Prefer those over hand-built `GA4Event` maps.

## Configuration

`GA4Config` only requires `measurementId` and `apiSecret`. Useful knobs:

| Option | Default | Notes |
|--------|---------|--------|
| `debugMode` | `false` | Live collect + `debug_mode` for DebugView |
| `autoGenerateClientId` | `true` | Stable for the process; pass `clientIdStore` for restarts |
| `attachSessionParams` | `true` | Adds `session_id` + `engagement_time_msec` |
| `validationMode` | `LOG` | `OFF` / `LOG` / `STRICT` |
| `maxEventsPerBatch` | `25` | GA4 hard limit |
| `enableRetry` | `true` | Exponential backoff on network failures |

Builder form (used by middleware):

```kotlin
config {
    measurementId("G-XXXXXXXXXX")
    apiSecret("your-api-secret")
    debugMode()
    validationMode(ValidationMode.STRICT)
}
```

Optional on the client/middleware:

- **`contextProvider`** — attach device / geo / `ip_override` per request
- **`eventQueueStore`** — durable queue (e.g. app-provided kotlin-lmdb adapter); default queue is in-memory only

## Privacy (opt-in)

Privacy is off by default. Turn it on from the middleware builder:

```kotlin
ga4Analytics {
    config { /* ... */ }
    enablePrivacy() // consent gate + PII scrubbing
    // or: enablePrivacy(consentStorage = myStorage, scrubPii = true)
}
```

When enabled, events are dropped without analytics consent and PII can be scrubbed on the send path. For GDPR export/delete/anonymize of a local event store, see `GA4PrivacyActions` (requires `enableEventStore` / your own store).

## Filtering

```kotlin
ga4Analytics {
    config { /* ... */ }
    exclude<InternalAction>()
    // or: filterActions { it is UserAction || it is PurchaseAction }
    patternMapper { /* ... */ }
}
```

## Production checklist

1. **Client ID** — stable per user/device (`clientIdProvider`, `defaultClientId`, or `clientIdStore`).
2. **Session params** — leave `attachSessionParams = true` for Realtime / engaged sessions.
3. **Debug** — `debugMode = true` + GA4 DebugView while integrating.
4. **Event names** — prefer recommended events. Do **not** send reserved names (`user_engagement`, `session_start`, …) via Measurement Protocol.
5. **Web streams** — prefer `page_view` over `screen_view`.
6. **Batching** — batch by default; use `immediate = true` for purchases and other critical events.
7. **Privacy** — call `enablePrivacy()` only when you need consent gating.
8. **Durability** — queued events are lost on process death unless you supply an `EventQueueStore`.

## Troubleshooting

- **Nothing in GA4 reports** — confirm measurement ID + API secret; reports can lag up to 24h. Use DebugView for real-time checks.
- **Events dropped** — check `validationMode` / debug logs for reserved names or invalid params; with privacy enabled, confirm consent is granted.
- **Network failures** — retries are on by default; verify firewall access to `www.google-analytics.com`.

## License

Apache License 2.0. See [LICENSE](LICENSE).
