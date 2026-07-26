package duks.ga4.middleware

import duks.routing.NavigationLayer
import duks.routing.RouteInstance
import duks.routing.RouterState

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
