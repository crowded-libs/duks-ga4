package duks.ga4.model

/**
 * Typed builders for GA4 recommended events (web / Measurement Protocol).
 *
 * Parameters follow the [official events reference](https://developers.google.com/analytics/devguides/collection/protocol/ga4/reference/events).
 * When [value] is set, [currency] should be set for accurate revenue metrics.
 */

// --- Engagement / general ---

fun loginEvent(
    method: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.LOGIN,
    params = buildMap {
        method?.let { put("method", it.toEventParam()) }
        putAll(additionalParams)
    }
)

fun signUpEvent(
    method: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.SIGN_UP,
    params = buildMap {
        method?.let { put("method", it.toEventParam()) }
        putAll(additionalParams)
    }
)

fun searchEvent(
    searchTerm: String,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.SEARCH,
    params = buildMap {
        put("search_term", searchTerm.toEventParam())
        putAll(additionalParams)
    }
)

fun shareEvent(
    method: String? = null,
    contentType: String? = null,
    itemId: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.SHARE,
    params = buildMap {
        method?.let { put("method", it.toEventParam()) }
        contentType?.let { put("content_type", it.toEventParam()) }
        itemId?.let { put("item_id", it.toEventParam()) }
        putAll(additionalParams)
    }
)

fun selectContentEvent(
    contentType: String? = null,
    contentId: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.SELECT_CONTENT,
    params = buildMap {
        contentType?.let { put("content_type", it.toEventParam()) }
        contentId?.let { put("content_id", it.toEventParam()) }
        putAll(additionalParams)
    }
)

fun joinGroupEvent(
    groupId: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = "join_group",
    params = buildMap {
        groupId?.let { put("group_id", it.toEventParam()) }
        putAll(additionalParams)
    }
)

fun tutorialBeginEvent(
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event("tutorial_begin", additionalParams)

fun tutorialCompleteEvent(
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event("tutorial_complete", additionalParams)

// --- E-commerce ---

/**
 * Shared monetary + items parameters used by most ecommerce events.
 */
private fun ecommerceParams(
    items: List<Item>,
    currency: String? = null,
    value: Double? = null,
    coupon: String? = null,
    transactionId: String? = null,
    shipping: Double? = null,
    tax: Double? = null,
    paymentType: String? = null,
    shippingTier: String? = null,
    itemListId: String? = null,
    itemListName: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): Map<String, EventParamValue> = buildMap {
    put("items", items.toEventParam())
    currency?.let { put("currency", it.toEventParam()) }
    value?.let { put("value", it.toEventParam()) }
    coupon?.let { put("coupon", it.toEventParam()) }
    transactionId?.let { put("transaction_id", it.toEventParam()) }
    shipping?.let { put("shipping", it.toEventParam()) }
    tax?.let { put("tax", it.toEventParam()) }
    paymentType?.let { put("payment_type", it.toEventParam()) }
    shippingTier?.let { put("shipping_tier", it.toEventParam()) }
    itemListId?.let { put("item_list_id", it.toEventParam()) }
    itemListName?.let { put("item_list_name", it.toEventParam()) }
    putAll(additionalParams)
}

fun viewItemEvent(
    items: List<Item>,
    currency: String? = null,
    value: Double? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.VIEW_ITEM,
    params = ecommerceParams(items, currency, value, additionalParams = additionalParams)
)

fun viewItemListEvent(
    items: List<Item>,
    itemListId: String? = null,
    itemListName: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.VIEW_ITEM_LIST,
    params = ecommerceParams(
        items = items,
        itemListId = itemListId,
        itemListName = itemListName,
        additionalParams = additionalParams
    )
)

fun selectItemEvent(
    items: List<Item>,
    itemListId: String? = null,
    itemListName: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.SELECT_ITEM,
    params = ecommerceParams(
        items = items,
        itemListId = itemListId,
        itemListName = itemListName,
        additionalParams = additionalParams
    )
)

fun addToCartEvent(
    items: List<Item>,
    currency: String? = null,
    value: Double? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.ADD_TO_CART,
    params = ecommerceParams(items, currency, value, additionalParams = additionalParams)
)

fun removeFromCartEvent(
    items: List<Item>,
    currency: String? = null,
    value: Double? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.REMOVE_FROM_CART,
    params = ecommerceParams(items, currency, value, additionalParams = additionalParams)
)

fun viewCartEvent(
    items: List<Item>,
    currency: String? = null,
    value: Double? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = "view_cart",
    params = ecommerceParams(items, currency, value, additionalParams = additionalParams)
)

fun beginCheckoutEvent(
    items: List<Item>,
    currency: String? = null,
    value: Double? = null,
    coupon: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.BEGIN_CHECKOUT,
    params = ecommerceParams(
        items = items,
        currency = currency,
        value = value,
        coupon = coupon,
        additionalParams = additionalParams
    )
)

fun addShippingInfoEvent(
    items: List<Item>,
    currency: String? = null,
    value: Double? = null,
    coupon: String? = null,
    shippingTier: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = "add_shipping_info",
    params = ecommerceParams(
        items = items,
        currency = currency,
        value = value,
        coupon = coupon,
        shippingTier = shippingTier,
        additionalParams = additionalParams
    )
)

fun addPaymentInfoEvent(
    items: List<Item>,
    currency: String? = null,
    value: Double? = null,
    coupon: String? = null,
    paymentType: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = "add_payment_info",
    params = ecommerceParams(
        items = items,
        currency = currency,
        value = value,
        coupon = coupon,
        paymentType = paymentType,
        additionalParams = additionalParams
    )
)

fun purchaseEvent(
    transactionId: String,
    items: List<Item>,
    currency: String? = null,
    value: Double? = null,
    tax: Double? = null,
    shipping: Double? = null,
    coupon: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.PURCHASE,
    params = ecommerceParams(
        items = items,
        currency = currency,
        value = value,
        coupon = coupon,
        transactionId = transactionId,
        shipping = shipping,
        tax = tax,
        additionalParams = additionalParams
    )
)

fun refundEvent(
    transactionId: String,
    items: List<Item> = emptyList(),
    currency: String? = null,
    value: Double? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.REFUND,
    params = buildMap {
        put("transaction_id", transactionId.toEventParam())
        if (items.isNotEmpty()) put("items", items.toEventParam())
        currency?.let { put("currency", it.toEventParam()) }
        value?.let { put("value", it.toEventParam()) }
        putAll(additionalParams)
    }
)

fun viewPromotionEvent(
    items: List<Item> = emptyList(),
    creativeName: String? = null,
    creativeSlot: String? = null,
    promotionId: String? = null,
    promotionName: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.VIEW_PROMOTION,
    params = promotionParams(items, creativeName, creativeSlot, promotionId, promotionName, additionalParams)
)

fun selectPromotionEvent(
    items: List<Item> = emptyList(),
    creativeName: String? = null,
    creativeSlot: String? = null,
    promotionId: String? = null,
    promotionName: String? = null,
    additionalParams: Map<String, EventParamValue> = emptyMap()
): GA4Event = GA4Event(
    name = GA4Event.SELECT_PROMOTION,
    params = promotionParams(items, creativeName, creativeSlot, promotionId, promotionName, additionalParams)
)

private fun promotionParams(
    items: List<Item>,
    creativeName: String?,
    creativeSlot: String?,
    promotionId: String?,
    promotionName: String?,
    additionalParams: Map<String, EventParamValue>
): Map<String, EventParamValue> = buildMap {
    if (items.isNotEmpty()) put("items", items.toEventParam())
    creativeName?.let { put("creative_name", it.toEventParam()) }
    creativeSlot?.let { put("creative_slot", it.toEventParam()) }
    promotionId?.let { put("promotion_id", it.toEventParam()) }
    promotionName?.let { put("promotion_name", it.toEventParam()) }
    putAll(additionalParams)
}

/**
 * Convenience: sum of (price * quantity) for [items], treating missing price/qty as 0/1.
 */
fun List<Item>.totalValue(): Double = sumOf { item ->
    val price = item.price ?: 0.0
    val qty = item.quantity ?: 1
    price * qty
}
