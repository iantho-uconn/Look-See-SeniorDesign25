package looksee.angelll.com.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionModelsTest {
    @Test
    fun catalogPreservesIosPlanPricingAndTokenRewards() {
        assertEquals(listOf(1, 3, 5), SubscriptionCatalog.plans.map { it.years })
        assertEquals(listOf(1_000, 2_500, 3_500), SubscriptionCatalog.plans.map { it.priceCents })
        assertEquals(listOf(10, 25, 35), SubscriptionCatalog.plans.map { it.baseTokens })
    }

    @Test
    fun catalogPreservesEveryTokenPack() {
        assertEquals(listOf(1, 5, 10, 25, 50, 100), SubscriptionCatalog.tokenPacks.map { it.tokens })
        assertEquals(listOf(300, 1_000, 1_500, 3_500, 6_000, 10_000), SubscriptionCatalog.tokenPacks.map { it.priceCents })
    }

    @Test
    fun accountRequiresSignInAndBothIdentifiersBeforeCheckout() {
        assertFalse(SubscriptionAccountState().isFullyLoggedIn)
        assertFalse(
            SubscriptionAccountState(isSignedIn = true, userId = "id").isFullyLoggedIn,
        )
        assertTrue(
            SubscriptionAccountState(
                isSignedIn = true,
                userId = "id",
                userEmail = "ian@example.com",
            ).isFullyLoggedIn,
        )
    }

    @Test
    fun trialRequiresNoSubscriptionNoStripeIdAndZeroTokens() {
        assertTrue(SubscriptionAccountState().isEligibleForTrial)
        assertFalse(SubscriptionAccountState(hasActiveSubscription = true).isEligibleForTrial)
        assertFalse(SubscriptionAccountState(stripeSubscriptionId = "sub_123").isEligibleForTrial)
        assertFalse(SubscriptionAccountState(tokenBalance = 1).isEligibleForTrial)
    }

    @Test
    fun missingActivePriceDefaultsToOneYearPlan() {
        assertEquals(1_000, SubscriptionAccountState().normalizedActivePlanCents)
        assertEquals(
            2_500,
            SubscriptionAccountState(activePlanCents = 2_500).normalizedActivePlanCents,
        )
    }

    @Test
    fun phoneFormatterMatchesIosAndLimitsInputToTenDigits() {
        assertEquals("(860) 555-0199", BusinessProfileInput.formatPhone("86055501991234"))
        assertEquals("(860) 5", BusinessProfileInput.formatPhone("(860)-5"))
    }

    @Test
    fun businessProfileRequiresTwoTrimmedNamesAndTenPhoneDigits() {
        assertTrue(
            BusinessProfileInput("LookSee", "Technology", "(860) 555-0199").isValid,
        )
        assertFalse(BusinessProfileInput(" ", "Technology", "8605550199").isValid)
        assertFalse(BusinessProfileInput("LookSee", "", "8605550199").isValid)
        assertFalse(BusinessProfileInput("LookSee", "Technology", "860555019").isValid)
        assertFalse(BusinessProfileInput("LookSee", "Technology", "86055501999").isValid)
    }

    @Test
    fun usdFormattingAlwaysUsesTwoDecimalPlaces() {
        assertEquals("$10.00", 1_000.asUsd())
        assertEquals("$3.00", 300.asUsd())
    }
}
