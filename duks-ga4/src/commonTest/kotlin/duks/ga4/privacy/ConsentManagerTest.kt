package duks.ga4.privacy

import duks.ga4.MockConsentStorage
import duks.ga4.model.ConsentState
import duks.ga4.model.ConsentValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ConsentManagerTest {
    
    private lateinit var storage: MockConsentStorage
    private lateinit var consentManager: ConsentManager
    
    @BeforeTest
    fun setup() {
        storage = MockConsentStorage()
    }
    
    @Test
    fun `should have null consent values by default when no explicit consent given`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        val consent = consentManager.getCurrentConsent()
        
        // Default should be null for all (no explicit consent given)
        assertNull(consent.adStorage)
        assertNull(consent.analyticsStorage)
        assertNull(consent.adPersonalization)
        assertNull(consent.adUserData)
    }
    
    @Test
    fun `should use custom default consent values when provided`() = runTest {
        val customDefault = ConsentState(
            analyticsStorage = ConsentValue.GRANTED,
            functionalityStorage = ConsentValue.GRANTED
        )
        
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, customDefault, testDispatcher)
        
        val consent = consentManager.getCurrentConsent()
        assertEquals(ConsentValue.GRANTED, consent.analyticsStorage)
        assertEquals(ConsentValue.GRANTED, consent.functionalityStorage)
        assertNull(consent.adStorage) // Not set in custom default
    }
    
    @Test
    fun `should update consent state and persist to storage`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        val newConsent = ConsentState(
            adStorage = ConsentValue.GRANTED,
            analyticsStorage = ConsentValue.GRANTED
        )
        
        consentManager.updateConsent(newConsent)
        
        // Verify state updated
        assertEquals(newConsent, consentManager.getCurrentConsent())
        assertEquals(newConsent, consentManager.consentState.value)
        
        // Verify saved to storage
        assertEquals(1, storage.saveCallCount)
    }
    
    @Test
    fun `should update individual consent types and save to storage`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        // Wait for initialization to complete
        
        // Update individual consent types
        consentManager.updateConsentType(ConsentType.ANALYTICS_STORAGE, ConsentValue.GRANTED)
        assertEquals(ConsentValue.GRANTED, consentManager.getCurrentConsent().analyticsStorage)
        
        consentManager.updateConsentType(ConsentType.AD_STORAGE, ConsentValue.GRANTED)
        assertEquals(ConsentValue.GRANTED, consentManager.getCurrentConsent().adStorage)
        
        // Other types should remain null (not set)
        assertNull(consentManager.getCurrentConsent().adPersonalization)
        
        // Should have saved twice
        assertEquals(2, storage.saveCallCount)
    }
    
    @Test
    fun `should grant all consent types when grantAll is called`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        consentManager.grantAll()
        
        val consent = consentManager.getCurrentConsent()
        assertEquals(ConsentValue.GRANTED, consent.adStorage)
        assertEquals(ConsentValue.GRANTED, consent.analyticsStorage)
        assertEquals(ConsentValue.GRANTED, consent.adPersonalization)
        assertEquals(ConsentValue.GRANTED, consent.adUserData)
        assertEquals(ConsentValue.GRANTED, consent.functionalityStorage)
        assertEquals(ConsentValue.GRANTED, consent.personalizationStorage)
        assertEquals(ConsentValue.GRANTED, consent.securityStorage)
    }
    
    @Test
    fun `should deny all consent types when denyAll is called`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        // First grant all
        consentManager.grantAll()
        
        // Wait for state to update
        consentManager.consentState.first { 
            it.adStorage == ConsentValue.GRANTED
        }
        
        // Then deny all
        consentManager.denyAll()
        
        // Wait for state to update to denied
        val consent = consentManager.consentState.first {
            it.adStorage == ConsentValue.DENIED
        }
        
        assertEquals(ConsentValue.DENIED, consent.adStorage)
        assertEquals(ConsentValue.DENIED, consent.analyticsStorage)
        assertEquals(ConsentValue.DENIED, consent.adPersonalization)
        assertEquals(ConsentValue.DENIED, consent.adUserData)
        assertEquals(ConsentValue.DENIED, consent.functionalityStorage)
        assertEquals(ConsentValue.DENIED, consent.personalizationStorage)
        assertEquals(ConsentValue.DENIED, consent.securityStorage)
    }
    
    @Test
    fun `should reset consent to default values and clear storage`() = runTest {
        val defaultConsent = ConsentState(
            analyticsStorage = ConsentValue.GRANTED
        )
        
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, defaultConsent, testDispatcher)
        
        // Change consent
        consentManager.updateConsentType(ConsentType.AD_STORAGE, ConsentValue.GRANTED)
        
        // Wait for state to update
        consentManager.consentState.first {
            it.adStorage == ConsentValue.GRANTED
        }
        
        // Reset
        consentManager.reset()
        
        // Wait for reset to complete
        val resetConsent = consentManager.consentState.first {
            it.adStorage == null && it.analyticsStorage == ConsentValue.GRANTED
        }
        
        // Should be back to default
        assertEquals(defaultConsent, resetConsent)
        
        // Storage should be cleared
        assertEquals(1, storage.clearCallCount)
    }
    
    @Test
    fun `should update analytics enabled flow based on analytics consent`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        // Initially false
        assertFalse(consentManager.analyticsEnabled.value)
        
        // Grant analytics
        consentManager.updateConsentType(ConsentType.ANALYTICS_STORAGE, ConsentValue.GRANTED)
        
        // Wait for state to update
        consentManager.analyticsEnabled.first { it }
        assertTrue(consentManager.analyticsEnabled.value)
        
        // Deny analytics
        consentManager.updateConsentType(ConsentType.ANALYTICS_STORAGE, ConsentValue.DENIED)
        
        // Wait for state to update
        consentManager.analyticsEnabled.first { !it }
        assertFalse(consentManager.analyticsEnabled.value)
    }
    
    @Test
    fun `should update advertising enabled flow based on ad storage consent`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        // Initially false
        assertFalse(consentManager.advertisingEnabled.value)
        
        // Grant advertising
        consentManager.updateConsentType(ConsentType.AD_STORAGE, ConsentValue.GRANTED)
        
        // Wait for state to update
        consentManager.advertisingEnabled.first { it }
        assertTrue(consentManager.advertisingEnabled.value)
        
        // Deny advertising
        consentManager.updateConsentType(ConsentType.AD_STORAGE, ConsentValue.DENIED)
        
        // Wait for state to update
        consentManager.advertisingEnabled.first { !it }
        assertFalse(consentManager.advertisingEnabled.value)
    }
    
    @Test
    fun `should determine event processing based on consent and event type`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        // Without analytics consent, no events should be processed
        assertFalse(consentManager.shouldProcessEvent("page_view"))
        assertFalse(consentManager.shouldProcessEvent("ad_click"))
        
        // Grant analytics consent
        consentManager.updateConsentType(ConsentType.ANALYTICS_STORAGE, ConsentValue.GRANTED)
        
        // Wait for state to update
        consentManager.consentState.first {
            it.analyticsStorage == ConsentValue.GRANTED
        }
        
        // Now general events should be processed
        assertTrue(consentManager.shouldProcessEvent("page_view"))
        assertTrue(consentManager.shouldProcessEvent("user_engagement"))
        
        // But ad events still require ad storage consent
        assertFalse(consentManager.shouldProcessEvent("ad_click"))
        assertFalse(consentManager.shouldProcessEvent("ad_impression"))
        
        // Grant ad storage
        consentManager.updateConsentType(ConsentType.AD_STORAGE, ConsentValue.GRANTED)
        
        // Wait for state to update
        consentManager.consentState.first {
            it.adStorage == ConsentValue.GRANTED
        }
        
        // Now ad events should be processed
        assertTrue(consentManager.shouldProcessEvent("ad_click"))
        assertTrue(consentManager.shouldProcessEvent("ad_impression"))
        assertTrue(consentManager.shouldProcessEvent("ad_query"))
        assertTrue(consentManager.shouldProcessEvent("ad_exposure"))
    }
    
    @Test
    fun `should process purchase events with only analytics consent`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        // Grant only analytics
        consentManager.updateConsentType(ConsentType.ANALYTICS_STORAGE, ConsentValue.GRANTED)
        
        // Wait for state to update
        consentManager.consentState.first {
            it.analyticsStorage == ConsentValue.GRANTED
        }
        
        // Purchase events should be processed with just analytics
        assertTrue(consentManager.shouldProcessEvent("purchase"))
        assertTrue(consentManager.shouldProcessEvent("add_to_cart"))
        assertTrue(consentManager.shouldProcessEvent("begin_checkout"))
    }
    
    @Test
    fun `should load saved consent from storage on initialization`() = runTest {
        // Pre-save consent in storage
        val savedConsent = ConsentState(
            adStorage = ConsentValue.GRANTED,
            analyticsStorage = ConsentValue.GRANTED,
            functionalityStorage = ConsentValue.GRANTED
        )
        storage.saveConsent(savedConsent)
        
        // Create manager - should load saved consent
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        // Wait for consent state to be loaded from storage
        val loadedConsent = consentManager.consentState.first { 
            it.adStorage == ConsentValue.GRANTED && 
            it.analyticsStorage == ConsentValue.GRANTED &&
            it.functionalityStorage == ConsentValue.GRANTED
        }
        
        // Should have loaded saved consent
        assertEquals(savedConsent, loadedConsent)
        assertEquals(1, storage.loadCallCount)
    }
    
    @Test
    fun `should emit consent state changes to flow subscribers`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        // Collect consent state changes
        val states = mutableListOf<ConsentState>()
        val job = launch {
            consentManager.consentState.collect { state ->
                states.add(state)
            }
        }
        
        // Wait for collector to start
        kotlinx.coroutines.delay(50)
        
        // Make changes
        consentManager.updateConsentType(ConsentType.ANALYTICS_STORAGE, ConsentValue.GRANTED)
        kotlinx.coroutines.delay(100)
        
        consentManager.updateConsentType(ConsentType.AD_STORAGE, ConsentValue.GRANTED)
        kotlinx.coroutines.delay(100)
        
        job.cancel()
        
        // Should have captured state changes
        assertTrue(states.size >= 2) // May have initial + 2 updates, but at least 2
    }
    
    @Test
    fun `should correctly update all individual consent types`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        consentManager = DefaultConsentManager(storage, dispatcher = testDispatcher)
        
        // Test updating each consent type
        val types = listOf(
            ConsentType.AD_STORAGE,
            ConsentType.ANALYTICS_STORAGE,
            ConsentType.AD_PERSONALIZATION,
            ConsentType.AD_USER_DATA,
            ConsentType.FUNCTIONALITY_STORAGE,
            ConsentType.PERSONALIZATION_STORAGE,
            ConsentType.SECURITY_STORAGE
        )
        
        types.forEach { type ->
            consentManager.updateConsentType(type, ConsentValue.GRANTED)
            
            // Verify the specific consent was updated
            val consent = consentManager.getCurrentConsent()
            when (type) {
                ConsentType.AD_STORAGE -> assertEquals(ConsentValue.GRANTED, consent.adStorage)
                ConsentType.ANALYTICS_STORAGE -> assertEquals(ConsentValue.GRANTED, consent.analyticsStorage)
                ConsentType.AD_PERSONALIZATION -> assertEquals(ConsentValue.GRANTED, consent.adPersonalization)
                ConsentType.AD_USER_DATA -> assertEquals(ConsentValue.GRANTED, consent.adUserData)
                ConsentType.FUNCTIONALITY_STORAGE -> assertEquals(ConsentValue.GRANTED, consent.functionalityStorage)
                ConsentType.PERSONALIZATION_STORAGE -> assertEquals(ConsentValue.GRANTED, consent.personalizationStorage)
                ConsentType.SECURITY_STORAGE -> assertEquals(ConsentValue.GRANTED, consent.securityStorage)
            }
        }
    }
}