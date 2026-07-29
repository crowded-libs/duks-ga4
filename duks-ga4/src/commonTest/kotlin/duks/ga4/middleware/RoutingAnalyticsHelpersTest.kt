package duks.ga4.middleware

import duks.routing.NavigationMode
import duks.routing.RouterState
import duks.routing.Routing
import duks.routing.SerializableRouteInstance
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingAnalyticsHelpersTest {

    @Test
    fun `detectNavigationPattern classifies parent child sibling and cross branch`() {
        assertEquals(
            NavigationPattern.FORWARD_TO_CHILD,
            detectNavigationPattern("/shop", "/shop/item")
        )
        assertEquals(
            NavigationPattern.BACK_TO_PARENT,
            detectNavigationPattern("/shop/item", "/shop")
        )
        assertEquals(
            NavigationPattern.LATERAL_SIBLING,
            detectNavigationPattern("/shop/a", "/shop/b")
        )
        assertEquals(
            NavigationPattern.CROSS_BRANCH,
            detectNavigationPattern("/shop", "/account/settings")
        )
    }

    @Test
    fun `toScreenName uses path on instances`() {
        val content = RouterState(
            contentRoutes = listOf(SerializableRouteInstance("/home"))
        )
        assertEquals("home", content.toScreenName())

        val modal = RouterState(
            contentRoutes = listOf(SerializableRouteInstance("/home")),
            modalRoutes = listOf(SerializableRouteInstance("/settings"))
        )
        assertEquals("home_modal_settings", modal.toScreenName())
    }

    @Test
    fun `resolveNavigationType maps modes and actions`() {
        val state = RouterState()
        assertEquals(
            NavigationType.PUSH,
            resolveNavigationType(Routing.NavigateTo("/a", mode = NavigationMode.Push), state)
        )
        assertEquals(
            NavigationType.RESET,
            resolveNavigationType(Routing.NavigateTo("/a", mode = NavigationMode.ClearHistory), state)
        )
        assertEquals(
            NavigationType.REPLACE,
            resolveNavigationType(Routing.NavigateTo("/a", mode = NavigationMode.SingleTop), state)
        )
        assertEquals(NavigationType.POP, resolveNavigationType(Routing.GoBack, state))
        assertEquals(NavigationType.DEEP_LINK, resolveNavigationType(Routing.DeepLink("app://x"), state))
    }
}
