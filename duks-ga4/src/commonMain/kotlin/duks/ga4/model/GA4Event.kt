package duks.ga4.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a Google Analytics 4 event
 */
@Serializable
data class GA4Event(
    /**
     * The name of the event
     */
    @SerialName("name")
    val name: String,
    
    /**
     * Event parameters as key-value pairs.
     * Serialized as plain GA4 Measurement Protocol values (not sealed-class envelopes).
     */
    @SerialName("params")
    @Serializable(with = EventParamsSerializer::class)
    val params: Map<String, EventParamValue> = emptyMap()
) {
    companion object {
        // Standard GA4 recommended event names (safe to send via Measurement Protocol)
        const val PAGE_VIEW = "page_view"
        const val SCREEN_VIEW = "screen_view"
        const val SCROLL = "scroll"
        const val CLICK = "click"
        const val SEARCH = "search"
        const val SHARE = "share"
        const val LOGIN = "login"
        const val SIGN_UP = "sign_up"
        const val PURCHASE = "purchase"
        const val REFUND = "refund"
        const val ADD_TO_CART = "add_to_cart"
        const val REMOVE_FROM_CART = "remove_from_cart"
        const val BEGIN_CHECKOUT = "begin_checkout"
        const val VIEW_ITEM = "view_item"
        const val VIEW_ITEM_LIST = "view_item_list"
        const val SELECT_ITEM = "select_item"
        const val SELECT_CONTENT = "select_content"
        const val VIEW_PROMOTION = "view_promotion"
        const val SELECT_PROMOTION = "select_promotion"

        /**
         * Reserved by Google Analytics automatic collection — do not send via Measurement Protocol.
         * Prefer attaching [engagement_time_msec] to real events instead.
         */
        @Deprecated(
            message = "user_engagement is a reserved GA4 event name and cannot be sent via Measurement Protocol. " +
                "Attach engagement_time_msec to your events instead.",
            level = DeprecationLevel.WARNING
        )
        const val USER_ENGAGEMENT = "user_engagement"
    }
}

/**
 * Wrapper for event parameter values to handle different types.
 * Wire format is plain JSON (string / number / boolean / items array).
 */
@Serializable(with = EventParamValueSerializer::class)
sealed class EventParamValue {
    @Serializable
    data class StringValue(val value: String) : EventParamValue()
    
    @Serializable
    data class NumberValue(val value: Double) : EventParamValue()
    
    @Serializable
    data class BooleanValue(val value: Boolean) : EventParamValue()
    
    @Serializable
    data class ItemsValue(val value: List<Item>) : EventParamValue()
}

/**
 * Represents an item in e-commerce events
 */
@Serializable
data class Item(
    @SerialName("item_id")
    val itemId: String? = null,
    
    @SerialName("item_name")
    val itemName: String? = null,
    
    @SerialName("affiliation")
    val affiliation: String? = null,
    
    @SerialName("coupon")
    val coupon: String? = null,
    
    @SerialName("discount")
    val discount: Double? = null,
    
    @SerialName("index")
    val index: Int? = null,
    
    @SerialName("item_brand")
    val itemBrand: String? = null,
    
    @SerialName("item_category")
    val itemCategory: String? = null,
    
    @SerialName("item_category2")
    val itemCategory2: String? = null,
    
    @SerialName("item_category3")
    val itemCategory3: String? = null,
    
    @SerialName("item_category4")
    val itemCategory4: String? = null,
    
    @SerialName("item_category5")
    val itemCategory5: String? = null,
    
    @SerialName("item_list_id")
    val itemListId: String? = null,
    
    @SerialName("item_list_name")
    val itemListName: String? = null,
    
    @SerialName("item_variant")
    val itemVariant: String? = null,
    
    @SerialName("location_id")
    val locationId: String? = null,
    
    @SerialName("price")
    val price: Double? = null,
    
    @SerialName("quantity")
    val quantity: Int? = null
)
