package looksee.angelll.com.models

import com.google.gson.Gson

enum class CheckoutPurchaseType(val wireValue: String) {
    YEARLY_SUBSCRIPTION("yearly_subscription"),
    TOKEN_PACK("token_pack"),
    CONFIRM_SUCCESS("confirm_success"),
}

data class CheckoutPrepareRequest(
    val purchaseType: String,
    val userId: String,
    val userEmail: String? = null,
    val amountCents: Int? = null,
    val tokenCount: Int? = null,
    val planYears: Int? = null,
    val planCents: Int? = null,
    val addOnCents: Int? = null,
    val isFreeTrial: Boolean? = null,
    val selectedPlanIndex: Int? = null,
    val stripeSubscriptionId: String? = null,
) {
    companion object {
        fun yearly(
            account: SubscriptionAccountState,
            plan: SubscriptionPlan,
            addOn: TokenAddOn,
        ) = CheckoutPrepareRequest(
            purchaseType = CheckoutPurchaseType.YEARLY_SUBSCRIPTION.wireValue,
            userId = account.userId,
            userEmail = account.userEmail,
            planYears = plan.years,
            planCents = plan.priceCents,
            addOnCents = addOn.priceCents,
            tokenCount = plan.baseTokens + addOn.tokens,
        )

        fun freeTrial(account: SubscriptionAccountState) = CheckoutPrepareRequest(
            purchaseType = CheckoutPurchaseType.YEARLY_SUBSCRIPTION.wireValue,
            userId = account.userId,
            userEmail = account.userEmail,
            planYears = 1,
            planCents = 1_000,
            addOnCents = 0,
            tokenCount = 2,
            isFreeTrial = true,
        )

        fun tokenPack(
            account: SubscriptionAccountState,
            pack: TokenAddOn,
        ) = CheckoutPrepareRequest(
            purchaseType = CheckoutPurchaseType.TOKEN_PACK.wireValue,
            userId = account.userId,
            userEmail = account.userEmail,
            amountCents = pack.priceCents,
            tokenCount = pack.tokens,
        )

        fun businessSetup(
            account: SubscriptionAccountState,
            selectedPlanIndex: Int,
        ): CheckoutPrepareRequest {
            val plan = SubscriptionCatalog.plans.getOrNull(selectedPlanIndex)
                ?: SubscriptionCatalog.plans[0]
            return CheckoutPrepareRequest(
                purchaseType = CheckoutPurchaseType.YEARLY_SUBSCRIPTION.wireValue,
                userId = account.userId,
                userEmail = account.userEmail,
                planYears = plan.years,
                planCents = plan.priceCents,
                addOnCents = 0,
                tokenCount = plan.baseTokens,
            )
        }
    }
}

data class CheckoutConfirmRequest(
    val purchaseType: String = CheckoutPurchaseType.CONFIRM_SUCCESS.wireValue,
    val userId: String,
    val addTokens: Int,
    val isBusiness: Boolean,
    val subscriptionId: String? = null,
    val planCents: Int? = null,
    val planYears: Int? = null,
)

data class CheckoutSession(
    val clientSecret: String,
    val customerId: String,
    val ephemeralKeySecret: String,
    val publishableKey: String,
    val subscriptionId: String? = null,
) {
    val isSetupIntent: Boolean
        get() = clientSecret.startsWith("seti_")
}

sealed interface CheckoutPreparation {
    data class Ready(val session: CheckoutSession) : CheckoutPreparation
    data object TrialStarted : CheckoutPreparation
}

sealed class CheckoutError(message: String) : Exception(message) {
    data class Backend(val statusCode: Int, val responseBody: String) :
        CheckoutError("Checkout failed with HTTP $statusCode: $responseBody")

    data class Stripe(val detail: String) : CheckoutError("Stripe error: $detail")
    data object InvalidResponse : CheckoutError("The checkout service returned an invalid response.")
}

class CheckoutService internal constructor(
    private val httpClient: BusinessHttpClient,
    private val gson: Gson = Gson(),
) {
    constructor() : this(UrlConnectionBusinessHttpClient())

    suspend fun prepare(request: CheckoutPrepareRequest): CheckoutPreparation {
        val response = httpClient.execute(
            BusinessHttpRequest(
                method = "POST",
                url = "$LOOKSEE_API_BASE_URL/checkout",
                body = gson.toJson(request).toByteArray(Charsets.UTF_8),
                contentType = "application/json",
                timeoutMillis = 60_000,
            ),
        )
        validate(response)
        val body = decode(response.bodyText)
        body.error?.takeIf(String::isNotBlank)?.let { throw CheckoutError.Stripe(it) }
        if (body.setupIntent == "trial_started") return CheckoutPreparation.TrialStarted

        return CheckoutPreparation.Ready(
            CheckoutSession(
                clientSecret = body.setupIntent?.takeIf(String::isNotBlank)
                    ?: throw CheckoutError.InvalidResponse,
                customerId = body.customer?.takeIf(String::isNotBlank)
                    ?: throw CheckoutError.InvalidResponse,
                ephemeralKeySecret = body.ephemeralKey?.takeIf(String::isNotBlank)
                    ?: throw CheckoutError.InvalidResponse,
                publishableKey = body.publishableKey?.takeIf(String::isNotBlank)
                    ?: throw CheckoutError.InvalidResponse,
                subscriptionId = body.subscriptionId,
            ),
        )
    }

    suspend fun confirm(request: CheckoutConfirmRequest): Boolean {
        val response = httpClient.execute(
            BusinessHttpRequest(
                method = "POST",
                url = "$LOOKSEE_API_BASE_URL/checkout",
                body = gson.toJson(request).toByteArray(Charsets.UTF_8),
                contentType = "application/json",
                timeoutMillis = 60_000,
            ),
        )
        return response.statusCode == 200
    }

    private fun validate(response: BusinessHttpResponse) {
        if (response.statusCode !in 200..299) {
            throw CheckoutError.Backend(response.statusCode, response.bodyText)
        }
    }

    private fun decode(json: String): CheckoutResponse = try {
        gson.fromJson(json, CheckoutResponse::class.java) ?: throw CheckoutError.InvalidResponse
    } catch (error: CheckoutError) {
        throw error
    } catch (_: Exception) {
        throw CheckoutError.InvalidResponse
    }
}

private data class CheckoutResponse(
    val setupIntent: String? = null,
    val customer: String? = null,
    val ephemeralKey: String? = null,
    val publishableKey: String? = null,
    val subscriptionId: String? = null,
    val error: String? = null,
)
