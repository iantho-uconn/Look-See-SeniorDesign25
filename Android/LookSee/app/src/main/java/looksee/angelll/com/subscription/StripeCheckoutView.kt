package looksee.angelll.com.subscription

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stripe.android.paymentsheet.PaymentSheetResult
import looksee.angelll.com.models.CheckoutPreparation
import looksee.angelll.com.models.CheckoutPrepareRequest
import looksee.angelll.com.models.CheckoutService
import looksee.angelll.com.models.CheckoutSession

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
