package duks.ga4.middleware

import duks.Action
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import duks.routing.NavigationLayer
import duks.routing.NavigationMode
import duks.routing.RouteInstance
import duks.routing.RouteType
import duks.routing.RouterState
import duks.routing.Routing
import duks.routing.config

/**
 * Navigation action kinds used in routing analytics events.
 */
enum class NavigationType(val analyticsName: String) {
    PUSH("push"),
    POP("pop"),
    REPLACE("replace"),
    RESET("reset"),
    DEEP_LINK("deep_link")
}

/**
 * Coarse navigation patterns for analytics (parent/child/sibling/cross-branch).
 */
enum class NavigationPattern(val analyticsName: String) {
    FORWARD_TO_CHILD("forward_child"),
    BACK_TO_PARENT("back_parent"),
    LATERAL_SIBLING("lateral_sibling"),
    CROSS_BRANCH("cross_branch")
}

/**
 * Modal open / close / dismiss.
 */
enum class ModalAction {
    OPEN,
    CLOSE,
    DISMISS
}

/**
 * Infers a navigation pattern from path strings (e.g. `/shop` → `/shop/item`).
 */
fun detectNavigationPattern(fromRoute: String, toRoute: String): NavigationPattern {
    return when {
        toRoute.length < fromRoute.length && fromRoute.startsWith(toRoute) ->
            NavigationPattern.BACK_TO_PARENT
        fromRoute.length < toRoute.length && toRoute.startsWith(fromRoute) ->
            NavigationPattern.FORWARD_TO_CHILD
        fromRoute.substringBeforeLast("/") == toRoute.substringBeforeLast("/") ->
            NavigationPattern.LATERAL_SIBLING
        else ->
            NavigationPattern.CROSS_BRANCH
    }
}

/**
 * Active route with priority Modal > Content > Scene.
 */
fun RouterState.getCurrentRoute(): RouteInstance? {
    return when {
        modalRoutes.isNotEmpty() -> modalRoutes.last()
        contentRoutes.isNotEmpty() -> contentRoutes.last()
        sceneRoutes.isNotEmpty() -> sceneRoutes.last()
        else -> null
    }
}

/**
 * Active navigation layer with priority Modal > Content > Scene.
 */
fun RouterState.getActiveLayer(): NavigationLayer {
    return when {
        modalRoutes.isNotEmpty() -> NavigationLayer.Modal
        contentRoutes.isNotEmpty() -> NavigationLayer.Content
        sceneRoutes.isNotEmpty() -> NavigationLayer.Scene
        else -> NavigationLayer.Content
    }
}

/**
 * Stable screen name for analytics (no leading slash; modals encode parent + modal path).
 */
fun RouterState.toScreenName(): String {
    return when {
        modalRoutes.isNotEmpty() -> {
            val modalPath = modalRoutes.last().path.removePrefix("/")
            val contentPath = contentRoutes.lastOrNull()?.path?.removePrefix("/") ?: "unknown"
            "${contentPath}_modal_$modalPath"
        }
        contentRoutes.isNotEmpty() -> contentRoutes.last().path.removePrefix("/")
        sceneRoutes.isNotEmpty() -> sceneRoutes.last().path.removePrefix("/")
        else -> "unknown"
    }
}

/**
 * Builds the standard routing analytics event batch for a committed router transition.
 *
 * Preserves the public measurement taxonomy used by consumers
 * (`screen_view`, `navigation`, modal events, `tab_switch`).
 * [screen_time] is handled separately by the middleware (needs wall-clock duration).
 */
fun buildRoutingAnalyticsEvents(
    previous: RouterState,
    current: RouterState,
    action: Action,
    previousScreenName: String?
): List<GA4Event> {
    if (previous == current) return emptyList()

    val newScreen = current.toScreenName()
    val events = mutableListOf<GA4Event>()

    events.add(createScreenViewEvent(current, previousScreenName))

    if (previousScreenName != null && previousScreenName != newScreen) {
        events.add(createNavigationEvent(current, action, previousScreenName, newScreen))
    }

    val previousModalCount = previous.modalRoutes.size
    val currentModalCount = current.modalRoutes.size
    when {
        currentModalCount > previousModalCount -> {
            createModalEvent(current, ModalAction.OPEN)?.let { events.add(it) }
        }
        currentModalCount < previousModalCount &&
            (current.lastRouteType == RouteType.Back ||
                action is Routing.GoBack ||
                action is Routing.DismissModal) -> {
            previous.modalRoutes.lastOrNull()?.let { dismissed ->
                createModalEvent(previous, ModalAction.DISMISS, dismissed)?.let { events.add(it) }
            }
        }
        currentModalCount < previousModalCount -> {
            previous.modalRoutes.lastOrNull()?.let { closed ->
                createModalEvent(previous, ModalAction.CLOSE, closed)?.let { events.add(it) }
            }
        }
    }

    createTabEvent(current)?.let { events.add(it) }

    return events
}

/**
 * Maps the routing [action] (and [NavigationMode] when present) to a [NavigationType].
 */
fun resolveNavigationType(action: Action, current: RouterState): NavigationType {
    return when (action) {
        is Routing.GoBack,
        is Routing.DismissModal,
        is Routing.PopToPath -> NavigationType.POP
        is Routing.DeepLink -> NavigationType.DEEP_LINK
        is Routing.ReplaceContent -> NavigationType.REPLACE
        is Routing.ShowModal -> NavigationType.PUSH
        is Routing.NavigateTo -> when {
            action.clearHistory || action.mode == NavigationMode.ClearHistory -> NavigationType.RESET
            action.mode == NavigationMode.SingleTop -> NavigationType.REPLACE
            action.mode == NavigationMode.ReplaceLayer &&
                (action.layer == NavigationLayer.Scene || current.lastRouteType == RouteType.Scene) ->
                NavigationType.RESET
            action.mode == NavigationMode.ReplaceLayer -> NavigationType.REPLACE
            action.layer == NavigationLayer.Scene -> NavigationType.RESET
            else -> NavigationType.PUSH
        }
        else -> when (current.lastRouteType) {
            RouteType.Back -> NavigationType.POP
            RouteType.Scene -> NavigationType.RESET
            else -> NavigationType.PUSH
        }
    }
}

internal fun createScreenTimeEvent(screenName: String, durationSeconds: Long, durationMillis: Long): GA4Event {
    return GA4Event(
        name = "screen_time",
        params = mapOf(
            "screen_name" to EventParamValue.StringValue(screenName),
            "route_duration_seconds" to EventParamValue.NumberValue(durationSeconds.coerceAtLeast(0).toDouble()),
            "engagement_time_msec" to EventParamValue.NumberValue(durationMillis.toDouble())
        )
    )
}

private fun createScreenViewEvent(routerState: RouterState, previousRoute: String?): GA4Event {
    val screenClass = when {
        routerState.modalRoutes.isNotEmpty() -> "Modal"
        routerState.contentRoutes.isNotEmpty() -> "Content"
        routerState.sceneRoutes.isNotEmpty() -> "Scene"
        else -> "Unknown"
    }
    val activeLayer = routerState.getActiveLayer()

    return GA4Event(
        name = "screen_view",
        params = buildMap {
            put("screen_name", EventParamValue.StringValue(routerState.toScreenName()))
            put("screen_class", EventParamValue.StringValue(screenClass))
            previousRoute?.let {
                put("previous_screen", EventParamValue.StringValue(it))
            }
            if (routerState.modalRoutes.isNotEmpty()) {
                put("is_modal", EventParamValue.BooleanValue(true))
                put("modal_count", EventParamValue.NumberValue(routerState.modalRoutes.size.toDouble()))
                put(
                    "modal_route",
                    EventParamValue.StringValue(routerState.modalRoutes.last().path)
                )
            }
            put("navigation_layer", EventParamValue.StringValue(activeLayer.name.lowercase()))
            val depth = when {
                routerState.modalRoutes.isNotEmpty() -> routerState.modalRoutes.size
                routerState.contentRoutes.isNotEmpty() -> routerState.contentRoutes.size
                routerState.sceneRoutes.isNotEmpty() -> routerState.sceneRoutes.size
                else -> 0
            }
            put("navigation_depth", EventParamValue.NumberValue(depth.toDouble()))
        }
    )
}

private fun createNavigationEvent(
    current: RouterState,
    action: Action,
    fromRoute: String,
    toScreen: String
): GA4Event {
    val navigationType = resolveNavigationType(action, current)
    val pattern = detectNavigationPattern(fromRoute, toScreen)

    return GA4Event(
        name = "navigation",
        params = buildMap {
            put("from_screen", EventParamValue.StringValue(fromRoute))
            put("to_screen", EventParamValue.StringValue(toScreen))
            put("navigation_type", EventParamValue.StringValue(navigationType.analyticsName))
            put("navigation_pattern", EventParamValue.StringValue(pattern.analyticsName))
            current.lastRouteType?.let { routeType ->
                put("route_type", EventParamValue.StringValue(routeType.name.lowercase()))
            }
            put("scene_stack_size", EventParamValue.NumberValue(current.sceneRoutes.size.toDouble()))
            put("content_stack_size", EventParamValue.NumberValue(current.contentRoutes.size.toDouble()))
            put("modal_stack_size", EventParamValue.NumberValue(current.modalRoutes.size.toDouble()))
            put(
                "active_layer",
                EventParamValue.StringValue(current.getActiveLayer().name.lowercase())
            )
        }
    )
}

private fun createModalEvent(
    routerState: RouterState,
    action: ModalAction,
    modalRoute: RouteInstance? = null
): GA4Event? {
    val modal = modalRoute ?: routerState.modalRoutes.lastOrNull() ?: return null
    val parentScreen = routerState.contentRoutes.lastOrNull()?.path
        ?: routerState.sceneRoutes.lastOrNull()?.path
        ?: "unknown"

    return GA4Event(
        name = when (action) {
            ModalAction.OPEN -> "modal_open"
            ModalAction.CLOSE -> "modal_close"
            ModalAction.DISMISS -> "modal_dismiss"
        },
        params = buildMap {
            put("modal_name", EventParamValue.StringValue(modal.path.removePrefix("/")))
            put("modal_path", EventParamValue.StringValue(modal.path))
            put("parent_screen", EventParamValue.StringValue(parentScreen.removePrefix("/")))
            put(
                "modal_stack_depth",
                EventParamValue.NumberValue(routerState.modalRoutes.size.toDouble())
            )
        }
    )
}

private fun createTabEvent(routerState: RouterState): GA4Event? {
    val route = routerState.getCurrentContentRoute() ?: return null
    val config = route.config as? Map<*, *>
    val currentTab = config?.get("selectedTab") as? String ?: return null

    return GA4Event(
        name = "tab_switch",
        params = buildMap {
            put("tab_name", EventParamValue.StringValue(currentTab))
            put("screen_name", EventParamValue.StringValue(routerState.toScreenName()))
            put("tab_container", EventParamValue.StringValue(route.path))
        }
    )
}
