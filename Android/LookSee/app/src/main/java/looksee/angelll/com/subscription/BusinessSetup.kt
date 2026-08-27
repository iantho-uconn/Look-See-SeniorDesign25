package looksee.angelll.com.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.launch
import looksee.angelll.com.models.BusinessProfileInput
import looksee.angelll.com.models.CheckoutPreparation
import looksee.angelll.com.models.CheckoutPrepareRequest
import looksee.angelll.com.models.CheckoutService
import looksee.angelll.com.models.SubscriptionAccountState
import looksee.angelll.com.models.SubscriptionCatalog

/** Business-profile form and secure checkout handoff translated from BusinessSetup.swift. */
@Composable
fun BusinessSetup(
    selectedPlanIndex: Int,
    isAnnualPlan: Boolean,
    account: SubscriptionAccountState,
    onBusinessActivated: (BusinessProfileInput) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    checkoutService: CheckoutService? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultCheckoutService = remember { CheckoutService() }
    val service = checkoutService ?: defaultCheckoutService
    var businessName by rememberSaveable { mutableStateOf("") }
    var industryType by rememberSaveable { mutableStateOf("") }
    var contactPhone by rememberSaveable { mutableStateOf("") }
    var isProcessing by rememberSaveable { mutableStateOf(false) }
    var paymentStatusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val profile = BusinessProfileInput(businessName, industryType, contactPhone)
    val safePlanIndex = selectedPlanIndex.coerceIn(SubscriptionCatalog.plans.indices)

    fun activateBusiness() {
        isProcessing = false
        paymentStatusMessage = "Payment successful! Updating account…"
        onBusinessActivated(profile)
        onClose()
    }

    val paymentSheet = rememberLookSeePaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> activateBusiness()
            is PaymentSheetResult.Canceled -> {
                isProcessing = false
                paymentStatusMessage = "Payment was canceled."
            }
            is PaymentSheetResult.Failed -> {
                isProcessing = false
                paymentStatusMessage = "Payment failed: ${result.error.message.orEmpty()}"
            }
        }
    }

    fun preparePayment() {
        if (!profile.isValid || isProcessing) return
        isProcessing = true
        paymentStatusMessage = null
        scope.launch {
            try {
                when (
                    val preparation = service.prepare(
                        CheckoutPrepareRequest.businessSetup(account, safePlanIndex),
                    )
                ) {
                    is CheckoutPreparation.Ready -> {
                        presentCheckoutSession(context, paymentSheet, preparation.session)
                    }
                    CheckoutPreparation.TrialStarted -> {
                        paymentStatusMessage = "Trial activated successfully! Updating account…"
                        onBusinessActivated(profile)
                        isProcessing = false
                        onClose()
                    }
                }
            } catch (error: Throwable) {
                isProcessing = false
                paymentStatusMessage = error.message ?: "Could not prepare checkout."
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            "Business Profile",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Tell us about your venue before moving to secure checkout.",
            color = Color.LightGray,
        )
        Text(
            "${SubscriptionCatalog.plans[safePlanIndex].label} selected" +
                if (isAnnualPlan) " · annual billing" else "",
            color = Color(0xFF387DFF),
            fontWeight = FontWeight.Bold,
        )
        OutlinedTextField(
            value = businessName,
            onValueChange = { businessName = it },
            label = { Text("Business Name") },
            placeholder = { Text("e.g., Mystic Seaport Museum") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = industryType,
            onValueChange = { industryType = it },
            label = { Text("Venue / Industry Type") },
            placeholder = { Text("e.g., Museum, Retail, University") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = contactPhone,
            onValueChange = { contactPhone = BusinessProfileInput.formatPhone(it) },
            label = { Text("Contact Phone Number") },
            placeholder = { Text("10-digit phone number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            supportingText = {
                if (contactPhone.isNotEmpty() && profile.cleanPhoneDigits.length != 10) {
                    Text("Please enter a valid 10-digit phone number.")
                }
            },
            isError = contactPhone.isNotEmpty() && profile.cleanPhoneDigits.length != 10,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        paymentStatusMessage?.let {
            Text(
                it,
                color = if (it.contains("successful", ignoreCase = true) ||
                    it.contains("activated", ignoreCase = true)
                ) Color(0xFF34C759) else Color(0xFFFF6B6B),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { preparePayment() },
            enabled = profile.isValid && !isProcessing,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(24.dp),
                )
            } else {
                Text("Proceed to Secure Payment", fontWeight = FontWeight.Bold)
            }
        }
        Button(onClick = onClose, enabled = !isProcessing, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}
