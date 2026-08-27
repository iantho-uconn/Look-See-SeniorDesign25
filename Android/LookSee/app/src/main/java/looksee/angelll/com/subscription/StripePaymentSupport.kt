package looksee.angelll.com.subscription

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import looksee.angelll.com.models.CheckoutSession

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