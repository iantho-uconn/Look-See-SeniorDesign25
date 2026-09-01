package looksee.angelll.com.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.launch
import looksee.angelll.com.models.BusinessProfileInput
import looksee.angelll.com.models.CheckoutConfirmRequest
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
    var pendingConfirm by remember { mutableStateOf<CheckoutConfirmRequest?>(null) }
    val profile = BusinessProfileInput(businessName, industryType, contactPhone)
    val safePlanIndex = selectedPlanIndex.coerceIn(SubscriptionCatalog.plans.indices)

    val lookSeeBlue = Color(0xFF387DFF)

    fun activateBusiness() {
        val confirmation = pendingConfirm
        if (confirmation == null) {
            isProcessing = false
            paymentStatusMessage = "The checkout state was lost. Please retry."
            return
        }
        scope.launch {
            val confirmed = runCatching { service.confirm(confirmation) }.getOrDefault(false)
            isProcessing = false
            if (confirmed) {
                paymentStatusMessage = "Payment successful! Updating account…"
                onBusinessActivated(profile)
                onClose()
            } else {
                paymentStatusMessage = "Payment completed, but activation failed. Refresh your account."
            }
        }
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
        val plan = SubscriptionCatalog.plans[safePlanIndex]
        pendingConfirm = CheckoutConfirmRequest(
            userId = account.userId,
            addTokens = plan.baseTokens,
            isBusiness = true,
            planCents = plan.priceCents,
            planYears = plan.years,
        )
        scope.launch {
            try {
                when (
                    val preparation = service.prepare(
                        CheckoutPrepareRequest.businessSetup(account, safePlanIndex),
                    )
                ) {
                    is CheckoutPreparation.Ready -> {
                        pendingConfirm = pendingConfirm?.copy(
                            subscriptionId = preparation.session.subscriptionId,
                        )
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Decorative Glow Blobs
        Box(
            modifier = Modifier
                .size(350.dp)
                .offset(x = (-120).dp, y = (-120).dp)
                .blur(80.dp)
                .background(lookSeeBlue.copy(alpha = 0.15f), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 100.dp, y = 100.dp)
                .blur(80.dp)
                .background(lookSeeBlue.copy(alpha = 0.10f), CircleShape),
        )

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, enabled = !isProcessing) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    "Business Profile",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                "Tell us about your venue before moving to secure checkout.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(lookSeeBlue.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "${SubscriptionCatalog.plans[safePlanIndex].label} Membership selected" +
                        if (isAnnualPlan) " · annual billing" else "",
                    color = lookSeeBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormTextField(
                    value = businessName,
                    onValueChange = { businessName = it },
                    label = "Business Name",
                    placeholder = "e.g., Mystic Seaport Museum",
                    isProcessing = isProcessing
                )
                FormTextField(
                    value = industryType,
                    onValueChange = { industryType = it },
                    label = "Venue / Industry Type",
                    placeholder = "e.g., Museum, Retail, University",
                    isProcessing = isProcessing
                )
                FormTextField(
                    value = contactPhone,
                    onValueChange = { contactPhone = BusinessProfileInput.formatPhone(it) },
                    label = "Contact Phone Number",
                    placeholder = "(XXX) XXX-XXXX",
                    isProcessing = isProcessing,
                    keyboardType = KeyboardType.Phone,
                    isError = contactPhone.isNotEmpty() && profile.cleanPhoneDigits.length != 10,
                    supportingText = if (contactPhone.isNotEmpty() && profile.cleanPhoneDigits.length != 10) {
                        "Please enter a valid 10-digit phone number."
                    } else null
                )
            }

            paymentStatusMessage?.let {
                Text(
                    it,
                    color = if (it.contains("successful", ignoreCase = true) ||
                        it.contains("activated", ignoreCase = true)
                    ) Color(0xFF34C759) else Color(0xFFFF6B6B),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { preparePayment() },
                enabled = profile.isValid && !isProcessing,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = lookSeeBlue),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Text("Proceed to Secure Payment", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            
            Text(
                "Secured by Stripe. Payment info encrypted.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isProcessing: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    supportingText: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label.uppercase(),
            color = Color.Gray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Color.Gray) },
            enabled = !isProcessing,
            singleLine = true,
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF387DFF),
                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            supportingText = supportingText?.let { { Text(it) } },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
