package looksee.angelll.com.subscription

import looksee.angelll.com.models.BusinessHttpClient
import looksee.angelll.com.models.UrlConnectionBusinessHttpClient
import looksee.angelll.com.models.BusinessHttpRequest
import looksee.angelll.com.models.BusinessHttpResponse
import looksee.angelll.com.models.LOOKSEE_API_BASE_URL

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.kotlin.core.Amplify
import com.google.gson.Gson
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.delay
import java.util.UUID

/** Generic Stripe PaymentSheet screen translated from StripeCheckoutView.swift. */
@Composable
fun StripeCheckoutView(
    request: CheckoutPrepareRequest,
    onPaymentCompleted: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    checkoutService: CheckoutService? = null,
) {
    val context = LocalContext.current
    val defaultCheckoutService = remember { CheckoutService() }
    val service = checkoutService ?: defaultCheckoutService
    var session by remember { mutableStateOf<CheckoutSession?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var paymentProcessing by remember { mutableStateOf(false) }
    var paymentSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val paymentSheet = rememberLookSeePaymentSheet { result ->
        paymentProcessing = false
        when (result) {
            is PaymentSheetResult.Completed -> {
                paymentSuccess = true
                onPaymentCompleted()
            }
            is PaymentSheetResult.Canceled -> errorMessage = "Payment was canceled."
            is PaymentSheetResult.Failed -> {
                errorMessage = "Payment failed: ${result.error.message.orEmpty()}"
            }
        }
    }

    LaunchedEffect(request) {
        isLoading = true
        errorMessage = null
        try {
            when (val preparation = service.prepare(request)) {
                is CheckoutPreparation.Ready -> session = preparation.session
                CheckoutPreparation.TrialStarted -> {
                    paymentSuccess = true
                    onPaymentCompleted()
                }
            }
        } catch (error: Throwable) {
            errorMessage = error.message ?: "Could not load the secure checkout."
        } finally {
            isLoading = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                paymentSuccess -> {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(Color(0x2234C759), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✓", color = Color(0xFF34C759), style = MaterialTheme.typography.headlineLarge)
                    }
                    Text(
                        "Upgrade Successful!",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    Text(
                        "LookSee business features are now ready for the refreshed account state.",
                        color = Color.LightGray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                        Text("Get Started")
                    }
                }
                isLoading || paymentProcessing -> {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        if (paymentProcessing) "Confirming Subscription…" else "Loading secure connection…",
                        color = Color.White,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                else -> {
                    Text("Secure Stripe Checkout", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Pay with Google Pay or a card using Stripe's native PaymentSheet.",
                        color = Color.LightGray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                    session?.let { readySession ->
                        Button(
                            onClick = {
                                paymentProcessing = true
                                errorMessage = null
                                presentCheckoutSession(context, paymentSheet, readySession)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Pay with Google Pay / Card") }
                    }
                    errorMessage?.let {
                        Text(
                            it,
                            color = Color(0xFFFF6B6B),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                    Spacer(modifier = Modifier.padding(8.dp))
                    Button(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}


// --- CheckoutService.kt ---
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
    val checkoutRequestId: String = UUID.randomUUID().toString().lowercase(),
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
    val orderId: String? = null,
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
        CheckoutError(
            if (statusCode == 409 && responseBody.contains("pending", ignoreCase = true)) {
                "A previous checkout is still pending. Please try again in a moment."
            } else {
                "Checkout failed with HTTP $statusCode"
            }
        )

    data class Stripe(val detail: String) : CheckoutError("Stripe error: $detail")
    class InvalidResponse : CheckoutError("The checkout service returned an invalid response.")
}

class CheckoutService internal constructor(
    private val httpClient: BusinessHttpClient,
    private val gson: Gson = Gson(),
) {
    constructor() : this(UrlConnectionBusinessHttpClient())

    init {
        // 🚀 THE FIX: Forces Android Emulator networking to skip IPv6 lookups,
        // instantly eliminating the 5-second timeout delay.
        System.setProperty("java.net.preferIPv4Stack", "true")
    }

    private suspend fun fetchAuthHeaders(): Map<String, String> {
        return try {
            val session = com.amplifyframework.kotlin.core.Amplify.Auth.fetchAuthSession() as? AWSCognitoAuthSession
            val idToken = session?.userPoolTokensResult?.value?.idToken
            if (!idToken.isNullOrEmpty()) {
                mapOf("Authorization" to "Bearer $idToken")
            } else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun prepare(request: CheckoutPrepareRequest): CheckoutPreparation {
        val response = httpClient.execute(
            BusinessHttpRequest(
                method = "POST",
                url = "$LOOKSEE_API_BASE_URL/checkout",
                headers = fetchAuthHeaders(),
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
                    ?: throw CheckoutError.InvalidResponse(),
                customerId = body.customer?.takeIf(String::isNotBlank)
                    ?: throw CheckoutError.InvalidResponse(),
                ephemeralKeySecret = body.ephemeralKey?.takeIf(String::isNotBlank)
                    ?: throw CheckoutError.InvalidResponse(),
                publishableKey = body.publishableKey?.takeIf(String::isNotBlank)
                    ?: throw CheckoutError.InvalidResponse(),
                subscriptionId = body.subscriptionId,
            ),
        )
    }

    suspend fun confirm(request: CheckoutConfirmRequest): Boolean {
        var attempts = 0
        // 🚀 THE FIX: Retry loop to handle Stripe Webhook race conditions
        while (attempts < 4) {
            val response = httpClient.execute(
                BusinessHttpRequest(
                    method = "POST",
                    url = "$LOOKSEE_API_BASE_URL/checkout",
                    headers = fetchAuthHeaders(),
                    body = gson.toJson(request).toByteArray(Charsets.UTF_8),
                    contentType = "application/json",
                    timeoutMillis = 60_000,
                ),
            )

            if (response.statusCode in 200..299) {
                return true
            } else if (response.statusCode == 409) {
                // Backend is currently locked by the Stripe Webhook. Wait 1.5 seconds and retry.
                delay(1500)
                attempts++
            } else {
                return false
            }
        }
        return false
    }

    suspend fun cancelPendingCheckout(userId: String, orderId: String): Boolean {
        val requestBody = mapOf(
            "purchaseType" to "cancel_pending_checkout",
            "userId" to userId,
            "orderId" to orderId
        )
        return try {
            val response = httpClient.execute(
                BusinessHttpRequest(
                    method = "POST",
                    url = "$LOOKSEE_API_BASE_URL/checkout",
                    headers = fetchAuthHeaders(),
                    body = gson.toJson(requestBody).toByteArray(Charsets.UTF_8),
                    contentType = "application/json",
                    timeoutMillis = 15_000,
                )
            )
            response.statusCode == 200
        } catch (_: Exception) {
            false
        }
    }

    private fun validate(response: BusinessHttpResponse) {
        if (response.statusCode !in 200..299) {
            throw CheckoutError.Backend(response.statusCode, response.bodyText)
        }
    }

    private fun decode(json: String): CheckoutResponse = try {
        gson.fromJson(json, CheckoutResponse::class.java) ?: throw CheckoutError.InvalidResponse()
    } catch (error: CheckoutError) {
        throw error
    } catch (_: Exception) {
        throw CheckoutError.InvalidResponse()
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

// --- StripePaymentSupport.kt ---
@Composable
internal fun rememberLookSeePaymentSheet(
    onResult: (PaymentSheetResult) -> Unit,
): PaymentSheet {
    val currentResult = rememberUpdatedState(onResult)

    return remember {
        PaymentSheet.Builder { result ->
            currentResult.value(result)
        }
    }.build()
}

internal fun presentCheckoutSession(
    context: Context,
    paymentSheet: PaymentSheet,
    session: CheckoutSession,
) {
    PaymentConfiguration.init(context, session.publishableKey)

    val environment =
        if (session.publishableKey.startsWith("pk_live_")) {
            PaymentSheet.GooglePayConfiguration.Environment.Production
        } else {
            PaymentSheet.GooglePayConfiguration.Environment.Test
        }

    val configuration = PaymentSheet.Configuration.Builder("LookSee")
        .customer(
            PaymentSheet.CustomerConfiguration(
                id = session.customerId,
                ephemeralKeySecret = session.ephemeralKeySecret,
            ),
        )
        .googlePay(
            PaymentSheet.GooglePayConfiguration(
                environment = environment,
                countryCode = "US",
                currencyCode = "USD",
            ),
        )
        .allowsDelayedPaymentMethods(false)
        .build()

    if (session.isSetupIntent) {
        paymentSheet.presentWithSetupIntent(
            session.clientSecret,
            configuration,
        )
    } else {
        paymentSheet.presentWithPaymentIntent(
            session.clientSecret,
            configuration,
        )
    }
}