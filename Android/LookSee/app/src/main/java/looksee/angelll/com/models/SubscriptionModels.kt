package looksee.angelll.com.models

enum class SubscriptionTab {
    PLAN,
    TOKENS,
    FREE_TRIAL,
}

data class SubscriptionPlan(
    val years: Int,
    val priceCents: Int,
    val baseTokens: Int,
    val label: String,
) {
    val priceLabel: String
        get() = priceCents.asUsd()
}

data class TokenAddOn(
    val tokens: Int,
    val priceCents: Int,
    val label: String,
)

object SubscriptionCatalog {
    val plans = listOf(
        SubscriptionPlan(years = 1, priceCents = 1_000, baseTokens = 10, label = "1 Year"),
        SubscriptionPlan(years = 3, priceCents = 2_500, baseTokens = 25, label = "3 Years"),
        SubscriptionPlan(years = 5, priceCents = 3_500, baseTokens = 35, label = "5 Years"),
    )

    val addOns = listOf(
        TokenAddOn(tokens = 0, priceCents = 0, label = "None"),
        TokenAddOn(tokens = 1, priceCents = 300, label = "1 Token (+$3.00)"),
        TokenAddOn(tokens = 5, priceCents = 1_000, label = "5 Tokens (+$10.00)"),
        TokenAddOn(tokens = 10, priceCents = 1_500, label = "10 Tokens (+$15.00)"),
        TokenAddOn(tokens = 25, priceCents = 3_500, label = "25 Tokens (+$35.00)"),
        TokenAddOn(tokens = 50, priceCents = 6_000, label = "50 Tokens (+$60.00)"),
        TokenAddOn(tokens = 100, priceCents = 10_000, label = "100 Tokens (+$100.00)"),
    )

    val tokenPacks: List<TokenAddOn>
        get() = addOns.drop(1)
}

data class SubscriptionAccountState(
    val isSignedIn: Boolean = false,
    val userId: String = "",
    val userEmail: String = "",
    val hasActiveSubscription: Boolean = false,
    val stripeSubscriptionId: String = "",
    val tokenBalance: Int = 0,
    val activePlanCents: Int = 0,
    val activePlanYears: Int = 0,
    val isFreeTrial: Boolean = false,
) {
    val isFullyLoggedIn: Boolean
        get() = isSignedIn && userId.isNotBlank() && userEmail.isNotBlank()

    val isEligibleForTrial: Boolean
        get() = !hasActiveSubscription && stripeSubscriptionId.isBlank() && tokenBalance == 0

    val normalizedActivePlanCents: Int
        get() = activePlanCents.takeIf { it > 0 } ?: 1_000
}

data class PendingCheckout(
    val tab: SubscriptionTab,
    val planIndex: Int = 0,
    val addOnIndex: Int = 0,
    val tokenCount: Int = 0,
    val tokenPriceCents: Int = 0,
)

data class SubscriptionPurchaseUpdate(
    val addedTokens: Int,
    val subscriptionActivated: Boolean,
    val planCents: Int? = null,
    val planYears: Int? = null,
    val isFreeTrial: Boolean = false,
    val subscriptionId: String? = null,
)

data class BusinessProfileInput(
    val businessName: String,
    val industryType: String,
    val contactPhone: String,
) {
    val cleanPhoneDigits: String
        get() = contactPhone.filter(Char::isDigit)

    val isValid: Boolean
        get() = businessName.isNotBlank() && industryType.isNotBlank() && cleanPhoneDigits.length == 10

    companion object {
        fun formatPhone(input: String): String {
            val digits = input.filter(Char::isDigit).take(10)
            return buildString {
                digits.forEachIndexed { index, digit ->
                    if (index == 0) append('(')
                    if (index == 3) append(") ")
                    if (index == 6) append('-')
                    append(digit)
                }
            }
        }
    }
}

internal fun Int.asUsd(): String = "$" + String.format(java.util.Locale.US, "%.2f", this / 100.0)
