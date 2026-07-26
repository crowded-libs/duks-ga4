package duks.ga4.middleware

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
}
