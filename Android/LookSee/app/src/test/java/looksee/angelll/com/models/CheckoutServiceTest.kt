package looksee.angelll.com.models

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutServiceTest {
    private val account = SubscriptionAccountState(
        isSignedIn = true,
        userId = "user-1",
        userEmail = "ian@example.com",
        stripeSubscriptionId = "sub_existing",
    )

    @Test
    fun yearlyFactoryCombinesPlanAndAddOnTokens() {
        val request = CheckoutPrepareRequest.yearly(
            account,
            SubscriptionCatalog.plans[1],
            SubscriptionCatalog.addOns[2],
        )

        assertEquals("yearly_subscription", request.purchaseType)
        assertEquals(3, request.planYears)
        assertEquals(2_500, request.planCents)
        assertEquals(1_000, request.addOnCents)
        assertEquals(30, request.tokenCount)
    }

    @Test
    fun trialFactoryUsesTwoTokensAndOneYearRenewal() {
        val request = CheckoutPrepareRequest.freeTrial(account)

        assertEquals(2, request.tokenCount)
        assertEquals(1_000, request.planCents)
        assertEquals(1, request.planYears)
        assertEquals(true, request.isFreeTrial)
    }

    @Test
    fun tokenFactoryUsesSelectedPackAmount() {
        val request = CheckoutPrepareRequest.tokenPack(account, SubscriptionCatalog.tokenPacks[3])

        assertEquals("token_pack", request.purchaseType)
        assertEquals(3_500, request.amountCents)
        assertEquals(25, request.tokenCount)
    }

    @Test
    fun preparePostsJsonAndParsesPaymentIntentSession(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(readyResponse("pi_secret_123"))
        val service = CheckoutService(http)

        val preparation = service.prepare(CheckoutPrepareRequest.freeTrial(account))

        val ready = preparation as CheckoutPreparation.Ready
        assertFalse(ready.session.isSetupIntent)
        assertEquals("sub_123", ready.session.subscriptionId)
        assertEquals("POST", http.requests.single().method)
        assertTrue(http.requests.single().url.endsWith("/checkout"))
        assertEquals("application/json", http.requests.single().contentType)
        val body = http.requests.single().body!!.toString(Charsets.UTF_8)
        assertTrue(body.contains("\"isFreeTrial\":true"))
        assertTrue(body.contains("\"tokenCount\":2"))
    }

    @Test
    fun prepareRecognizesSetupIntent(): Unit = runBlocking {
        val service = CheckoutService(RecordingBusinessHttpClient(readyResponse("seti_secret_123")))

        val ready = service.prepare(CheckoutPrepareRequest.freeTrial(account))
            as CheckoutPreparation.Ready

        assertTrue(ready.session.isSetupIntent)
    }

    @Test
    fun prepareRecognizesImmediateTrialActivation(): Unit = runBlocking {
        val service = CheckoutService(
            RecordingBusinessHttpClient(jsonResponse("""{"setupIntent":"trial_started"}""")),
        )

        assertEquals(
            CheckoutPreparation.TrialStarted,
            service.prepare(CheckoutPrepareRequest.businessSetup(account, 0)),
        )
    }

    @Test(expected = CheckoutError.Stripe::class)
    fun stripeErrorIsSurfaced(): Unit = runBlocking {
        val service = CheckoutService(
            RecordingBusinessHttpClient(jsonResponse("""{"error":"customer missing"}""")),
        )

        service.prepare(CheckoutPrepareRequest.freeTrial(account))
    }

    @Test(expected = CheckoutError.Backend::class)
    fun nonSuccessStatusIsRejected(): Unit = runBlocking {
        val service = CheckoutService(
            RecordingBusinessHttpClient(jsonResponse("backend down", statusCode = 503)),
        )

        service.prepare(CheckoutPrepareRequest.freeTrial(account))
    }

    @Test(expected = CheckoutError.InvalidResponse::class)
    fun missingStripeKeysAreRejected(): Unit = runBlocking {
        val service = CheckoutService(
            RecordingBusinessHttpClient(jsonResponse("""{"setupIntent":"pi_secret"}""")),
        )

        service.prepare(CheckoutPrepareRequest.freeTrial(account))
    }

    @Test
    fun confirmReturnsTrueOnlyForHttp200AndSendsExactWireType(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(BusinessHttpResponse(200), BusinessHttpResponse(500))
        val service = CheckoutService(http)
        val request = CheckoutConfirmRequest(
            userId = "user-1",
            addTokens = 10,
            isBusiness = true,
        )

        assertTrue(service.confirm(request))
        assertFalse(service.confirm(request))
        assertTrue(http.requests.first().body!!.toString(Charsets.UTF_8).contains("confirm_success"))
    }

    @Test
    fun businessSetupFactoryPreservesSelectedPlanAndExistingSubscription() {
        val request = CheckoutPrepareRequest.businessSetup(account, selectedPlanIndex = 2)
        val json = Gson().toJson(request)

        assertTrue(json.contains("\"purchaseType\":\"subscription\""))
        assertTrue(json.contains("\"selectedPlanIndex\":2"))
        assertTrue(json.contains("\"stripeSubscriptionId\":\"sub_existing\""))
    }

    private fun readyResponse(secret: String) = jsonResponse(
        """{"setupIntent":"$secret","customer":"cus_123","ephemeralKey":"eph_123","publishableKey":"pk_test_123","subscriptionId":"sub_123"}""",
    )
}
