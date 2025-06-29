package duks.ga4.middleware

import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
// import duks.routing.RouterState
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Extension functions for converting RouterState to GA4 events and analytics data
 * 
 * NOTE: This file is commented out until RouterState from duks-routing is available
 */

/*
fun RouterState.toScreenName(): String {
    return when {
        // Handle modal states
        isModal && modalRoute != null -> {
            "${currentRoute}_modal_$modalRoute"
        }
        // Handle parameterized routes
        params?.isNotEmpty() == true -> {
            "$currentRoute${params.entries.joinToString("_") { "${it.key}_${it.value}" }}"
        }
        // Simple route
        else -> currentRoute
    }
}

fun RouterState.toScreenClass(): String {
    return when {
        isModal -> "Modal"
        isTab -> "Tab"
        isNested -> "Nested"
        else -> "Screen"
    }
}

fun RouterState.toScreenViewEvent(
    previousRoute: String? = null,
    customParams: Map<String, Any>? = null
): GA4Event {
    return GA4Event(
        name = "screen_view",
        params = buildMap {
            put("screen_name", EventParamValue.StringValue(toScreenName()))
            put("screen_class", EventParamValue.StringValue(toScreenClass()))
            
            // Add previous route if available
            previousRoute?.let {
                put("previous_screen", EventParamValue.StringValue(it))
            }
            
            // Add modal information
            if (isModal) {
                put("is_modal", EventParamValue.BooleanValue(true))
                modalType?.let {
                    put("modal_type", EventParamValue.StringValue(it))
                }
                modalRoute?.let {
                    put("modal_route", EventParamValue.StringValue(it))
                }
            }
            
            // Add tab information
            if (isTab) {
                put("is_tab", EventParamValue.BooleanValue(true))
                tabIndex?.let {
                    put("tab_index", EventParamValue.NumberValue(it.toDouble()))
                }
                tabName?.let {
                    put("tab_name", EventParamValue.StringValue(it))
                }
            }
            
            // Add navigation depth
            navigationDepth?.let {
                put("navigation_depth", EventParamValue.NumberValue(it.toDouble()))
            }
            
            // Add route parameters
            params?.forEach { (key, value) ->
                put("route_param_$key", EventParamValue.StringValue(value))
            }
            
            // Add query parameters
            queryParams?.forEach { (key, value) ->
                put("query_param_$key", EventParamValue.StringValue(value))
            }
            
            // Add custom parameters
            customParams?.forEach { (key, value) ->
                put(key, value.toEventParamValue())
            }
        }
    )
}

fun RouterState.toNavigationEvent(
    fromRoute: String,
    navigationType: NavigationType = NavigationType.PUSH,
    duration: Duration? = null
): GA4Event {
    return GA4Event(
        name = "navigation",
        params = buildMap {
            put("from_screen", EventParamValue.StringValue(fromRoute))
            put("to_screen", EventParamValue.StringValue(toScreenName()))
            put("navigation_type", EventParamValue.StringValue(navigationType.analyticsName))
            
            // Add transition duration if available
            duration?.let {
                put("transition_duration_ms", EventParamValue.NumberValue(it.inWholeMilliseconds))
            }
            
            // Add navigation pattern detection
            val pattern = detectNavigationPattern(fromRoute, currentRoute)
            put("navigation_pattern", EventParamValue.StringValue(pattern.analyticsName))
            
            // Add stack information
            stackSize?.let {
                put("stack_size", EventParamValue.NumberValue(it.toDouble()))
            }
            
            // Track if this is a deep link
            if (isDeepLink) {
                put("is_deep_link", EventParamValue.BooleanValue(true))
                deepLinkUrl?.let {
                    put("deep_link_url", EventParamValue.StringValue(it))
                }
            }
        }
    )
}

fun RouterState.toModalEvent(
    action: ModalAction,
    modalId: String? = null
): GA4Event {
    require(isModal) { "RouterState must represent a modal" }
    
    return GA4Event(
        name = when (action) {
            ModalAction.OPEN -> "modal_open"
            ModalAction.CLOSE -> "modal_close"
            ModalAction.DISMISS -> "modal_dismiss"
        },
        params = buildMap {
            put("modal_name", EventParamValue.StringValue(modalRoute ?: "unknown"))
            modalType?.let {
                put("modal_type", EventParamValue.StringValue(it))
            }
            modalId?.let {
                put("modal_id", EventParamValue.StringValue(it))
            }
            put("parent_screen", EventParamValue.StringValue(parentRoute ?: currentRoute))
            
            // Add modal-specific parameters
            modalParams?.forEach { (key, value) ->
                put("modal_param_$key", EventParamValue.StringValue(value))
            }
        }
    )
}

fun RouterState.toTabEvent(
    previousTabIndex: Int? = null,
    previousTabName: String? = null
): GA4Event {
    require(isTab) { "RouterState must represent a tab" }
    
    return GA4Event(
        name = "tab_switch",
        params = buildMap {
            tabIndex?.let {
                put("tab_index", EventParamValue.NumberValue(it.toDouble()))
            }
            tabName?.let {
                put("tab_name", EventParamValue.StringValue(it))
            }
            previousTabIndex?.let {
                put("previous_tab_index", EventParamValue.NumberValue(it.toDouble()))
            }
            previousTabName?.let {
                put("previous_tab_name", EventParamValue.StringValue(it))
            }
            put("tab_container", EventParamValue.StringValue(currentRoute))
        }
    )
}

fun detectNavigationPattern(fromRoute: String, toRoute: String): NavigationPattern {
    return when {
        // Back navigation to parent
        toRoute.length < fromRoute.length && fromRoute.startsWith(toRoute) -> {
            NavigationPattern.BACK_TO_PARENT
        }
        // Forward navigation to child
        fromRoute.length < toRoute.length && toRoute.startsWith(fromRoute) -> {
            NavigationPattern.FORWARD_TO_CHILD
        }
        // Lateral navigation between siblings
        fromRoute.substringBeforeLast("/") == toRoute.substringBeforeLast("/") -> {
            NavigationPattern.LATERAL_SIBLING
        }
        // Cross-branch navigation
        else -> NavigationPattern.CROSS_BRANCH
    }
}

private fun Any.toEventParamValue(): EventParamValue {
    return when (this) {
        is String -> EventParamValue.StringValue(this)
        is Number -> EventParamValue.NumberValue(this.toDouble())
        is Boolean -> EventParamValue.BooleanValue(this)
        else -> EventParamValue.StringValue(this.toString())
    }
}
*/

/**
 * Types of navigation actions
 */
enum class NavigationType(val analyticsName: String) {
    PUSH("push"),
    POP("pop"),
    REPLACE("replace"),
    RESET("reset"),
    DEEP_LINK("deep_link")
}

/**
 * Navigation patterns for analytics
 */
enum class NavigationPattern(val analyticsName: String) {
    FORWARD_TO_CHILD("forward_child"),
    BACK_TO_PARENT("back_parent"),
    LATERAL_SIBLING("lateral_sibling"),
    CROSS_BRANCH("cross_branch")
}

/**
 * Modal actions for analytics
 */
enum class ModalAction {
    OPEN,
    CLOSE,
    DISMISS
}

/**
 * Helper to get the current active route
 */
fun duks.routing.RouterState.getCurrentRoute(): duks.routing.RouteInstance? {
    return when {
        modalRoutes.isNotEmpty() -> modalRoutes.last()
        contentRoutes.isNotEmpty() -> contentRoutes.last()
        sceneRoutes.isNotEmpty() -> sceneRoutes.last()
        else -> null
    }
}

/**
 * Helper to get the active navigation layer
 */
fun duks.routing.RouterState.getActiveLayer(): duks.routing.NavigationLayer {
    return when {
        modalRoutes.isNotEmpty() -> duks.routing.NavigationLayer.Modal
        contentRoutes.isNotEmpty() -> duks.routing.NavigationLayer.Content
        sceneRoutes.isNotEmpty() -> duks.routing.NavigationLayer.Scene
        else -> duks.routing.NavigationLayer.Content // Default
    }
}