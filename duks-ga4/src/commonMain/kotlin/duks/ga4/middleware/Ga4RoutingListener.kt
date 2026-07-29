package duks.ga4.middleware

import duks.Action
import duks.routing.NavigationListener
import duks.routing.RouterState

/**
 * [NavigationListener] that forwards committed router transitions to GA4.
 *
 * Prefer wiring via [GA4MiddlewareBuilder.trackRouting] with a [duks.routing.RouterMiddleware].
 * For composition (e.g. registering inside `routing { onNavigation(...) }` before the GA4
 * middleware is built), create a listener with [ga4RoutingListener] and pass it to
 * [GA4MiddlewareBuilder.trackRouting].
 */
class Ga4RoutingListener internal constructor() : NavigationListener {
    @PublishedApi
    internal var onTransition: ((previous: RouterState, current: RouterState, action: Action) -> Unit)? =
        null

    override fun onRouterStateChanged(
        previous: RouterState,
        current: RouterState,
        action: Action
    ) {
        onTransition?.invoke(previous, current, action)
    }

    internal fun attach(
        handler: (previous: RouterState, current: RouterState, action: Action) -> Unit
    ) {
        onTransition = handler
    }

    internal fun detach() {
        onTransition = null
    }
}

/** Creates a [Ga4RoutingListener] for advanced composition with `routing { onNavigation(...) }`. */
fun ga4RoutingListener(): Ga4RoutingListener = Ga4RoutingListener()
