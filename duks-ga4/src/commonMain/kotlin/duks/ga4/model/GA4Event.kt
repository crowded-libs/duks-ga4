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
     * Event parameters as key-value pairs
     */
    @SerialName("params")
    val params: Map<String, EventParamValue> = emptyMap()
) {
    companion object {
        // Standard GA4 event names
        const val PAGE_VIEW = "page_view"
        const val SCREEN_VIEW = "screen_view"
        const val USER_ENGAGEMENT = "user_engagement"
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
    }
}

/**
 * Wrapper for event parameter values to handle different types
 */
@Serializable
sealed class EventParamValue {
    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : EventParamValue()
    
    @Serializable
    @SerialName("number")
    data class NumberValue(val value: Double) : EventParamValue()
    
    @Serializable
    @SerialName("boolean")
    data class BooleanValue(val value: Boolean) : EventParamValue()
    
    @Serializable
    @SerialName("items")
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