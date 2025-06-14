package duks.ga4.model

/**
 * Extension functions for easier event parameter creation
 */

/**
 * Converts a String to an EventParamValue
 */
fun String.toEventParam(): EventParamValue = EventParamValue.StringValue(this)

/**
 * Converts a Number to an EventParamValue
 */
fun Number.toEventParam(): EventParamValue = EventParamValue.NumberValue(this.toDouble())

/**
 * Converts a Boolean to an EventParamValue
 */
fun Boolean.toEventParam(): EventParamValue = EventParamValue.BooleanValue(this)

/**
 * Converts a List of Items to an EventParamValue
 */
fun List<Item>.toEventParam(): EventParamValue = EventParamValue.ItemsValue(this)

/**
 * Creates event parameters from a vararg of pairs
 */
fun eventParams(vararg params: Pair<String, Any?>): Map<String, EventParamValue> {
    return params.mapNotNull { (key, value) ->
        when (value) {
            null -> null
            is String -> key to value.toEventParam()
            is Number -> key to value.toEventParam()
            is Boolean -> key to value.toEventParam()
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                val items = value as? List<Item>
                if (items != null) {
                    key to items.toEventParam()
                } else {
                    null
                }
            }
            else -> null
        }
    }.toMap()
}

/**
 * Creates a page view event
 */
fun pageViewEvent(
    pageLocation: String? = null,
    pageTitle: String? = null,
    pageReferrer: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event {
    val params = buildMap {
        pageLocation?.let { put("page_location", it.toEventParam()) }
        pageTitle?.let { put("page_title", it.toEventParam()) }
        pageReferrer?.let { put("page_referrer", it.toEventParam()) }
        putAll(additionalParams)
    }
    return GA4Event(GA4Event.PAGE_VIEW, params)
}

/**
 * Creates a screen view event
 */
fun screenViewEvent(
    screenName: String,
    screenClass: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event {
    val params = buildMap {
        put("screen_name", screenName.toEventParam())
        screenClass?.let { put("screen_class", it.toEventParam()) }
        putAll(additionalParams)
    }
    return GA4Event(GA4Event.SCREEN_VIEW, params)
}

/**
 * Creates a custom event
 */
fun customEvent(
    name: String,
    vararg params: Pair<String, Any?>
): GA4Event {
    return GA4Event(name, eventParams(*params))
}