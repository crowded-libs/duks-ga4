package duks.ga4.middleware

import duks.Action
import duks.AsyncError
import duks.AsyncProcessing
import duks.AsyncResultAction
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import kotlin.reflect.KClass
import kotlin.time.Clock

/**
 * Interface for mapping store actions to GA4 events
 * 
 * @param TState The type of the store state
 */
interface EventMapper<TState> {
    /**
     * When false, middleware skips the pre-reducer mapping call on the hot path.
     * Default is false (most mappers only care about post-state).
     * Override to true if you implement [mapActionBefore].
     */
    val mapsBeforeStateChange: Boolean get() = false

    /**
     * Maps an action to GA4 events before the state change
     * 
     * @param action The dispatched action
     * @param state The current state before the action is processed
     * @return List of GA4 events to track, or empty list if no tracking needed
     */
    suspend fun mapActionBefore(action: Any, state: TState): List<GA4Event> = emptyList()
    
    /**
     * Maps an action to GA4 events after the state change
     * 
     * @param action The dispatched action
     * @param state The new state after the action is processed
     * @return List of GA4 events to track, or empty list if no tracking needed
     */
    suspend fun mapActionAfter(action: Any, state: TState): List<GA4Event>
}

/**
 * Default event mapper that provides basic action mappings.
 * 
 * This mapper only handles:
 * - Async action states (loading, success, failure)
 * - Generic action tracking for all other actions
 * 
 * For application-specific actions (user, commerce, etc.), 
 * implement your own EventMapper or use PatternEventMapper.
 */
open class DefaultEventMapper<TState> : EventMapper<TState> {
    
    override suspend fun mapActionAfter(action: Any, state: TState): List<GA4Event> {
        return when (action) {
            // Map async actions
            is AsyncProcessing -> listOf(
                createAsyncEvent(action.initiatedBy, "loading")
            )
            is AsyncResultAction<*> -> listOf(
                createAsyncEvent(action.initiatedBy, "success", action.result)
            )
            is AsyncError -> listOf(
                createAsyncEvent(action.initiatedBy, "failure", errorMessage = action.error.message)
            )
            
            // Default mapping for all other actions
            else -> listOf(createGenericActionEvent(action))
        }
    }
    
    private fun createAsyncEvent(
        action: Action,
        status: String,
        data: Any? = null,
        errorMessage: String? = null
    ): GA4Event {
        return GA4Event(
            name = "async_action",
            params = buildMap {
                put("action_type", EventParamValue.StringValue(action::class.simpleName ?: "unknown"))
                put("status", EventParamValue.StringValue(status))
                put("timestamp", EventParamValue.NumberValue(Clock.System.now().toEpochMilliseconds().toDouble()))
                
                data?.let {
                    put("has_data", EventParamValue.BooleanValue(true))
                    // Add data type or size if relevant
                    when (it) {
                        is List<*> -> put("data_count", EventParamValue.NumberValue(it.size.toDouble()))
                        is Map<*, *> -> put("data_count", EventParamValue.NumberValue(it.size.toDouble()))
                        else -> put("data_type", EventParamValue.StringValue(it::class.simpleName ?: "unknown"))
                    }
                }
                
                errorMessage?.let {
                    put("error_message", EventParamValue.StringValue(it))
                }
            }
        )
    }
    
    
    private fun createGenericActionEvent(action: Any): GA4Event {
        return GA4Event(
            name = "custom_action",
            params = mapOf(
                "action_type" to EventParamValue.StringValue(action::class.simpleName ?: "unknown"),
            )
        )
    }
}

/**
 * Composable event mapper that combines multiple mappers
 */
class CompositeEventMapper<TState>(
    private val mappers: List<EventMapper<TState>>
) : EventMapper<TState> {
    override val mapsBeforeStateChange: Boolean =
        mappers.any { it.mapsBeforeStateChange }

    override suspend fun mapActionBefore(action: Any, state: TState): List<GA4Event> {
        return mappers.flatMap { it.mapActionBefore(action, state) }
    }
    
    override suspend fun mapActionAfter(action: Any, state: TState): List<GA4Event> {
        return mappers.flatMap { it.mapActionAfter(action, state) }
    }
}

/**
 * Event mapper that filters actions based on predicates
 */
class FilteringEventMapper<TState>(
    private val delegate: EventMapper<TState>,
    private val shouldMapAction: (Any) -> Boolean
) : EventMapper<TState> {
    override val mapsBeforeStateChange: Boolean
        get() = delegate.mapsBeforeStateChange

    override suspend fun mapActionBefore(action: Any, state: TState): List<GA4Event> {
        return if (shouldMapAction(action)) {
            delegate.mapActionBefore(action, state)
        } else {
            emptyList()
        }
    }
    
    override suspend fun mapActionAfter(action: Any, state: TState): List<GA4Event> {
        return if (shouldMapAction(action)) {
            delegate.mapActionAfter(action, state)
        } else {
            emptyList()
        }
    }
}

/**
 * Event mapper for specific action types using pattern matching
 */
class PatternEventMapper<TState> : EventMapper<TState> {
    private val patterns = mutableListOf<ActionPattern<TState, *>>()
    
    /**
     * Registers a pattern for a specific action type
     */
    fun <TAction : Any> registerPattern(
        actionClass: KClass<TAction>,
        mapper: suspend (TAction, TState) -> List<GA4Event>
    ) {
        @Suppress("UNCHECKED_CAST")
        patterns.add(ActionPattern(actionClass, mapper as suspend (Any, TState) -> List<GA4Event>))
    }
    
    /**
     * Registers a pattern for a specific action type (convenience method)
     */
    inline fun <reified TAction : Any> pattern(
        noinline mapper: suspend (TAction, TState) -> List<GA4Event>
    ) {
        registerPattern(TAction::class, mapper)
    }
    
    override suspend fun mapActionAfter(action: Any, state: TState): List<GA4Event> {
        for (pattern in patterns) {
            if (pattern.matches(action)) {
                @Suppress("UNCHECKED_CAST")
                return (pattern.mapper as suspend (Any, TState) -> List<GA4Event>)(action, state)
            }
        }
        return emptyList()
    }
    
    data class ActionPattern<TState, TAction : Any>(
        val actionClass: KClass<TAction>,
        val mapper: suspend (TAction, TState) -> List<GA4Event>
    ) {
        fun matches(action: Any): Boolean = actionClass.isInstance(action)
    }
}

