package duks.ga4.client

import duks.ga4.config.ValidationMode
import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import duks.logging.Logger
import duks.logging.warn

/**
 * Validates GA4 events against Measurement Protocol limits and reserved names.
 *
 * @see <a href="https://developers.google.com/analytics/devguides/collection/protocol/ga4/reference">MP reference</a>
 */
class EventValidator(
    private val mode: ValidationMode = ValidationMode.LOG,
    private val preferPageViewForWeb: Boolean = true
) {
    private val logger = Logger.default()

    /**
     * Validates and optionally sanitizes events.
     *
     * @return events that should be sent (may be empty if all were dropped)
     * @throws GA4ValidationException when [mode] is [ValidationMode.STRICT] and issues are found
     */
    fun validate(events: List<GA4Event>): List<GA4Event> {
        if (mode == ValidationMode.OFF) return events

        val result = mutableListOf<GA4Event>()
        val issues = mutableListOf<String>()

        for (event in events) {
            val eventIssues = validateEvent(event)
            if (eventIssues.isEmpty()) {
                result.add(event)
                continue
            }

            issues.addAll(eventIssues.map { "${event.name}: $it" })

            when (mode) {
                ValidationMode.STRICT -> { /* collect, throw below */ }
                ValidationMode.LOG -> {
                    eventIssues.forEach { issue ->
                        logger.warn(event.name, issue) {
                            "GA4 validation issue for event {eventName}: {issue}"
                        }
                    }
                    // Drop reserved events; keep others with warnings
                    if (eventIssues.none { it.startsWith("reserved event") }) {
                        result.add(sanitizeEvent(event))
                    }
                }
                ValidationMode.OFF -> result.add(event)
            }
        }

        if (mode == ValidationMode.STRICT && issues.isNotEmpty()) {
            throw GA4ValidationException(issues)
        }

        return result
    }

    fun validateEvent(event: GA4Event): List<String> {
        val issues = mutableListOf<String>()

        if (event.name.isBlank()) {
            issues.add("event name is blank")
        }
        if (event.name.length > MAX_EVENT_NAME_LENGTH) {
            issues.add("event name exceeds $MAX_EVENT_NAME_LENGTH characters")
        }
        if (event.name in RESERVED_EVENT_NAMES) {
            issues.add("reserved event name cannot be sent via Measurement Protocol")
        }
        if (preferPageViewForWeb && event.name == GA4Event.SCREEN_VIEW) {
            issues.add(
                "screen_view is only allowed for app streams; prefer page_view for web streams"
            )
        }

        if (event.params.size > MAX_PARAMS_PER_EVENT) {
            issues.add("more than $MAX_PARAMS_PER_EVENT parameters (${event.params.size})")
        }

        event.params.forEach { (key, value) ->
            if (key.length > MAX_PARAM_NAME_LENGTH) {
                issues.add("parameter name '$key' exceeds $MAX_PARAM_NAME_LENGTH characters")
            }
            if (isReservedParamName(key)) {
                issues.add("parameter name '$key' is reserved or has a reserved prefix")
            }
            if (value is EventParamValue.StringValue && value.value.length > MAX_PARAM_VALUE_LENGTH) {
                issues.add(
                    "parameter '$key' value exceeds $MAX_PARAM_VALUE_LENGTH characters"
                )
            }
        }

        return issues
    }

    /**
     * Truncates overlong string params; does not remove reserved names.
     */
    fun sanitizeEvent(event: GA4Event): GA4Event {
        if (event.params.isEmpty()) return event

        val sanitized = event.params.mapValues { (_, value) ->
            when (value) {
                is EventParamValue.StringValue -> {
                    if (value.value.length > MAX_PARAM_VALUE_LENGTH) {
                        EventParamValue.StringValue(value.value.take(MAX_PARAM_VALUE_LENGTH))
                    } else value
                }
                else -> value
            }
        }
        return if (sanitized != event.params) event.copy(params = sanitized) else event
    }

    companion object {
        const val MAX_EVENT_NAME_LENGTH = 40
        const val MAX_PARAM_NAME_LENGTH = 40
        const val MAX_PARAM_VALUE_LENGTH = 100
        const val MAX_PARAMS_PER_EVENT = 25
        const val MAX_EVENTS_PER_BATCH = 25

        /**
         * Event names reserved by GA4 automatic collection / Firebase.
         * @see https://developers.google.com/analytics/devguides/collection/protocol/ga4/reference
         */
        val RESERVED_EVENT_NAMES: Set<String> = setOf(
            "ad_activeview",
            "ad_click",
            "ad_exposure",
            "ad_query",
            "ad_reward",
            "adunit_exposure",
            "app_clear_data",
            "app_exception",
            "app_install",
            "app_remove",
            "app_store_refund",
            "app_update",
            "app_upgrade",
            "dynamic_link_app_open",
            "dynamic_link_app_update",
            "dynamic_link_first_open",
            "error",
            "firebase_campaign",
            "firebase_in_app_message_action",
            "firebase_in_app_message_dismiss",
            "firebase_in_app_message_impression",
            "first_open",
            "first_visit",
            "notification_dismiss",
            "notification_foreground",
            "notification_open",
            "notification_receive",
            "notification_send",
            "os_update",
            "session_start",
            "user_engagement"
        )

        private val RESERVED_PARAM_PREFIXES = listOf(
            "_",
            "firebase_",
            "ga_",
            "google_",
            "gtag."
        )

        private val RESERVED_PARAM_NAMES = setOf("firebase_conversion")

        fun isReservedParamName(name: String): Boolean {
            if (name in RESERVED_PARAM_NAMES) return true
            return RESERVED_PARAM_PREFIXES.any { name.startsWith(it) }
        }
    }
}

/**
 * Thrown when event validation fails in [ValidationMode.STRICT].
 */
class GA4ValidationException(
    val issues: List<String>
) : Exception("GA4 validation failed: ${issues.joinToString("; ")}")
