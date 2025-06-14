package duks.ga4.privacy

import duks.ga4.model.EventParamValue
import duks.ga4.model.GA4Event
import duks.ga4.model.Item
import duks.logging.*

/**
 * Scrubs personally identifiable information (PII) from GA4 events
 */
class PiiScrubber(
    private val config: PiiScrubberConfig = PiiScrubberConfig()
) {
    private val logger = Logger.default()
    
    init {
        logger.info(
            config.enabled,
            config.scrubEmails,
            config.scrubPhoneNumbers,
            config.scrubCreditCards,
            config.scrubSsns
        ) {
            "PiiScrubber initialized - enabled: {enabled}, emails: {emails}, phones: {phones}, credit cards: {creditCards}, ssns: {ssns}"
        }
    }
    
    // Common PII field patterns
    private val emailPattern = Regex(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
    )
    
    private val phonePattern = Regex(
        "\\+?[1-9]\\d{1,14}|\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}"
    )
    
    private val creditCardPattern = Regex(
        "\\b(?:\\d{4}[\\s-]?){3}\\d{3,4}\\b|\\b(?<!\\d)\\d{13,19}(?!\\d)\\b"
    )
    
    private val ssnPattern = Regex(
        "\\b\\d{3}-\\d{2}-\\d{4}\\b|(?<!\\d)\\d{9}(?!\\d)"
    )
    
    private val ipPattern = Regex(
        "\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b"
    )
    
    // PII field names to check
    private val piiFieldNames = setOf(
        "email", "e_mail", "email_address", "user_email",
        "phone", "phone_number", "telephone", "mobile",
        "name", "full_name", "first_name", "last_name", "username",
        "address", "street", "city", "zip", "postal_code",
        "ssn", "social_security", "tax_id",
        "credit_card", "card_number", "cc_number",
        "password", "pwd", "pass",
        "ip", "ip_address", "client_ip",
        "lat", "latitude", "lon", "longitude", "location",
        "date_of_birth", "dob", "birthday",
        "gender", "sex"
    )
    
    /**
     * Scrubs PII from a GA4 event
     */
    fun scrubEvent(event: GA4Event): GA4Event {
        if (!config.enabled) return event
        
        logger.debug(event.name) {
            "Scrubbing PII from event: {eventName}"
        }
        
        val scrubbedParams = scrubParams(event.params)
        
        if (scrubbedParams.size < event.params.size) {
            logger.debug(event.name, event.params.size - scrubbedParams.size) {
                "Removed {removedCount} PII fields from event: {eventName}"
            }
        }
        
        return event.copy(
            params = scrubbedParams
        )
    }
    
    /**
     * Scrubs PII from event parameters
     */
    private fun scrubParams(params: Map<String, EventParamValue>): Map<String, EventParamValue> {
        return params.mapNotNull { (key, value) ->
            when {
                // Check if field name indicates PII
                shouldScrubFieldName(key) && config.scrubByFieldName -> {
                    logger.debug(key) {
                        "Field name {fieldName} identified as PII"
                    }
                    if (config.removeFields) null
                    else key to scrubValue(value, forceRedact = true)
                }
                
                // Check field value for PII patterns
                else -> key to scrubValue(value)
            }
        }.toMap()
    }
    
    /**
     * Checks if a field name indicates PII
     */
    private fun shouldScrubFieldName(fieldName: String): Boolean {
        val normalizedName = fieldName.lowercase()
        return piiFieldNames.any { piiField ->
            normalizedName.contains(piiField)
        } || config.customPiiFields.any { customField ->
            normalizedName.contains(customField.lowercase())
        }
    }
    
    /**
     * Scrubs PII from a value
     */
    private fun scrubValue(value: EventParamValue, forceRedact: Boolean = false): EventParamValue {
        return when (value) {
            is EventParamValue.StringValue -> {
                EventParamValue.StringValue(
                    if (forceRedact) config.redactedValue
                    else scrubString(value.value)
                )
            }
            
            is EventParamValue.ItemsValue -> {
                EventParamValue.ItemsValue(
                    value.value.map { scrubItem(it) }
                )
            }
            
            else -> value // Numbers and booleans are not scrubbed
        }
    }
    
    /**
     * Scrubs PII from a string value
     */
    private fun scrubString(value: String): String {
        var scrubbed = value
        
        // Apply patterns in order of specificity to avoid overlapping matches
        // Scrub IP addresses first (most specific pattern)
        if (config.scrubIpAddresses) {
            val ipMatches = ipPattern.findAll(scrubbed).count()
            if (ipMatches > 0) {
                logger.debug(ipMatches) {
                    "Found and scrubbed {count} IP addresses"
                }
                scrubbed = scrubbed.replace(ipPattern, config.redactedValue)
            }
        }
        
        // Scrub SSNs
        if (config.scrubSsns) {
            val ssnMatches = ssnPattern.findAll(scrubbed).count()
            if (ssnMatches > 0) {
                logger.debug(ssnMatches) {
                    "Found and scrubbed {count} SSNs"
                }
                scrubbed = scrubbed.replace(ssnPattern, config.redactedValue)
            }
        }
        
        // Scrub credit card numbers
        if (config.scrubCreditCards) {
            val ccMatches = creditCardPattern.findAll(scrubbed).count()
            if (ccMatches > 0) {
                logger.warn(ccMatches) {
                    "Found and scrubbed {count} credit card numbers"
                }
                scrubbed = scrubbed.replace(creditCardPattern, config.redactedValue)
            }
        }
        
        // Scrub phone numbers
        if (config.scrubPhoneNumbers) {
            val phoneMatches = phonePattern.findAll(scrubbed).count()
            if (phoneMatches > 0) {
                logger.debug(phoneMatches) {
                    "Found and scrubbed {count} phone numbers"
                }
                scrubbed = scrubbed.replace(phonePattern, config.redactedValue)
            }
        }
        
        // Scrub email addresses
        if (config.scrubEmails) {
            val emailMatches = emailPattern.findAll(scrubbed).count()
            if (emailMatches > 0) {
                logger.debug(emailMatches) {
                    "Found and scrubbed {count} email addresses"
                }
                scrubbed = scrubbed.replace(emailPattern, config.redactedValue)
            }
        }
        
        // Apply custom patterns
        config.customPatterns.forEach { pattern ->
            val customMatches = pattern.findAll(scrubbed).count()
            if (customMatches > 0) {
                logger.debug(customMatches, pattern.pattern) {
                    "Found and scrubbed {count} matches for custom pattern: {pattern}"
                }
                scrubbed = scrubbed.replace(pattern, config.redactedValue)
            }
        }
        
        return scrubbed
    }
    
    /**
     * Scrubs PII from an item
     */
    private fun scrubItem(item: Item): Item {
        // Items typically don't contain PII, but we'll check string fields
        return item.copy(
            itemName = item.itemName?.let { scrubString(it) },
            affiliation = item.affiliation?.let { scrubString(it) },
            coupon = item.coupon?.let { scrubString(it) },
            itemBrand = item.itemBrand?.let { scrubString(it) }
        )
    }
    
    /**
     * Detects if a string contains potential PII
     */
    fun containsPii(value: String): Boolean {
        return when {
            config.scrubEmails && emailPattern.containsMatchIn(value) -> true
            config.scrubPhoneNumbers && phonePattern.containsMatchIn(value) -> true
            config.scrubCreditCards && creditCardPattern.containsMatchIn(value) -> true
            config.scrubSsns && ssnPattern.containsMatchIn(value) -> true
            config.scrubIpAddresses && ipPattern.containsMatchIn(value) -> true
            else -> config.customPatterns.any { it.containsMatchIn(value) }
        }
    }
}

/**
 * Configuration for PII scrubbing
 */
data class PiiScrubberConfig(
    val enabled: Boolean = true,
    val redactedValue: String = "[REDACTED]",
    val scrubEmails: Boolean = true,
    val scrubPhoneNumbers: Boolean = true,
    val scrubCreditCards: Boolean = true,
    val scrubSsns: Boolean = true,
    val scrubIpAddresses: Boolean = true,
    val scrubByFieldName: Boolean = true,
    val removeFields: Boolean = false,
    val customPiiFields: Set<String> = emptySet(),
    val customPatterns: List<Regex> = emptyList()
)