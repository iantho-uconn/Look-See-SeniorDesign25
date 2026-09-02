package looksee.angelll.com.subscription

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.ui.theme.LookSeeBlue
import java.util.*

private val LookSeeBackground = Color(0xFF0F0F1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionPlans(
    account: SubscriptionAccountState,
    onClose: () -> Unit,
    onRequireSignUp: (PendingCheckout) -> Unit,
    onAccountUpdated: (SubscriptionPurchaseUpdate) -> Unit,
    modifier: Modifier = Modifier,
    startingTab: SubscriptionTab = SubscriptionTab.PLAN,
    resumeCheckout: PendingCheckout? = null,
    checkoutService: CheckoutService? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultCheckoutService = remember { CheckoutService() }
    val service = checkoutService ?: defaultCheckoutService
    
    var selectedTab by rememberSaveable { mutableStateOf(startingTab) }
    var selectedPlanIndex by rememberSaveable { mutableIntStateOf(0) }
    var selectedAddOnIndex by rememberSaveable { mutableIntStateOf(0) }
    var isProcessing by rememberSaveable { mutableStateOf(false) }
    var paymentStatusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingConfirm by remember { mutableStateOf<CheckoutConfirmRequest?>(null) }
    var pendingUpdate by remember { mutableStateOf<SubscriptionPurchaseUpdate?>(null) }
    var consumedResume by rememberSaveable { mutableStateOf(false) }

    val isTokenOnlyMode = startingTab == SubscriptionTab.TOKENS && account.hasActiveSubscription

    fun finishPayment(result: PaymentSheetResult) {
        when (result) {
            is PaymentSheetResult.Completed -> {
                val confirmation = pendingConfirm
                val update = pendingUpdate
                if (confirmation == null || update == null) {
                    isProcessing = false
                    paymentStatusMessage = "The checkout state was lost. Please refresh your account."
                    return
                }
                scope.launch {
                    val confirmed = runCatching { service.confirm(confirmation) }
                        .getOrDefault(false)
                    isProcessing = false
                    if (confirmed) {
                        paymentStatusMessage = "Purchase successful."
                        onAccountUpdated(update)
                        onClose()
                    } else {
                        paymentStatusMessage = "Payment completed, but account confirmation failed. Refresh before retrying."
                    }
                }
            }
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

    val paymentSheet = rememberLookSeePaymentSheet { finishPayment(it) }

    fun prepareCheckout(
        request: CheckoutPrepareRequest,
        confirmation: CheckoutConfirmRequest,
        update: SubscriptionPurchaseUpdate,
    ) {
        isProcessing = true
        paymentStatusMessage = null
        pendingConfirm = confirmation
        pendingUpdate = update
        scope.launch {
            try {
                when (val preparation = service.prepare(request)) {
                    is CheckoutPreparation.Ready -> {
                        pendingUpdate = update.copy(
                            subscriptionId = preparation.session.subscriptionId,
                        )
                        pendingConfirm = confirmation.copy(
                            subscriptionId = preparation.session.subscriptionId,
                        )
                        presentCheckoutSession(context, paymentSheet, preparation.session)
                    }
                    CheckoutPreparation.TrialStarted -> {
                        isProcessing = false
                        paymentStatusMessage = "Trial activated successfully."
                        onAccountUpdated(update)
                        onClose()
                    }
                }
            } catch (error: Throwable) {
                isProcessing = false
                paymentStatusMessage = error.message ?: "Could not prepare checkout."
            }
        }
    }

    fun beginPlanCheckout(planIndex: Int, addOnIndex: Int) {
        val plan = SubscriptionCatalog.plans[planIndex]
        val addOn = SubscriptionCatalog.addOns[addOnIndex]
        if (!account.isFullyLoggedIn) {
            onRequireSignUp(PendingCheckout(SubscriptionTab.PLAN, planIndex, addOnIndex))
            return
        }
        val addedTokens = plan.baseTokens + addOn.tokens
        prepareCheckout(
            request = CheckoutPrepareRequest.yearly(account, plan, addOn),
            confirmation = CheckoutConfirmRequest(
                userId = account.userId,
                addTokens = addedTokens,
                isBusiness = true,
                planCents = plan.priceCents,
                planYears = plan.years,
            ),
            update = SubscriptionPurchaseUpdate(addedTokens, true, plan.priceCents, plan.years)
        )
    }

    fun beginTrialCheckout() {
        if (!account.isFullyLoggedIn) {
            onRequireSignUp(PendingCheckout(SubscriptionTab.FREE_TRIAL))
            return
        }
        prepareCheckout(
            request = CheckoutPrepareRequest.freeTrial(account),
            confirmation = CheckoutConfirmRequest(
                userId = account.userId,
                addTokens = 2,
                isBusiness = true,
                planCents = 1000,
                planYears = 1,
            ),
            update = SubscriptionPurchaseUpdate(2, true, 1000, 1, true)
        )
    }

    fun beginTokenCheckout(pack: TokenAddOn) {
        if (!account.isFullyLoggedIn) {
            onRequireSignUp(PendingCheckout(SubscriptionTab.TOKENS, tokenCount = pack.tokens, tokenPriceCents = pack.priceCents))
            return
        }
        prepareCheckout(
            request = CheckoutPrepareRequest.tokenPack(account, pack),
            confirmation = CheckoutConfirmRequest(
                userId = account.userId,
                addTokens = pack.tokens,
                isBusiness = false,
            ),
            update = SubscriptionPurchaseUpdate(pack.tokens, false)
        )
    }

    LaunchedEffect(account.hasActiveSubscription, selectedTab) {
        if (!account.hasActiveSubscription && selectedTab == SubscriptionTab.TOKENS) {
            selectedTab = SubscriptionTab.PLAN
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = modifier.fillMaxSize().background(LookSeeBackground)) {
            Column(modifier = Modifier.fillMaxSize()) {
                SubscriptionHeader(account, selectedTab, isProcessing, onClose)

                if (!isTokenOnlyMode) {
                    SubscriptionTabs(selectedTab, onSelect = { selectedTab = it })
                }

                when (selectedTab) {
                    SubscriptionTab.PLAN -> PlanContent(
                        account, selectedPlanIndex, selectedAddOnIndex, isProcessing,
                        onPlanSelected = { selectedPlanIndex = it },
                        onAddOnSelected = { selectedAddOnIndex = it },
                        onSubscribe = { beginPlanCheckout(selectedPlanIndex, selectedAddOnIndex) },
                        modifier = Modifier.weight(1f)
                    )
                    SubscriptionTab.FREE_TRIAL -> TrialContent(
                        account, isProcessing,
                        onStartTrial = { beginTrialCheckout() },
                        modifier = Modifier.weight(1f)
                    )
                    else -> {}
                }

                paymentStatusMessage?.let {
                    Text(it, color = if (it.contains("success", true) || it.contains("activated", true)) Color(0xFF34C759) else Color(0xFFFF6B6B), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
                }
                Text("Secured by Stripe. Cancel at any time.", color = Color.White.copy(0.58f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            }

            if (isProcessing) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.55f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(if (selectedTab == SubscriptionTab.FREE_TRIAL) "Preparing Trial..." else "Connecting to Stripe…", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionHeader(account: SubscriptionAccountState, selectedTab: SubscriptionTab, isProcessing: Boolean, onClose: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("LookSee", color = LookSeeBlue, fontWeight = FontWeight.Bold)
            Text(if (account.hasActiveSubscription) "Manage Membership" else "Upgrade to Business", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onClose, enabled = !isProcessing) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(0.4f)) }
    }
}

@Composable
private fun SubscriptionTabs(selectedTab: SubscriptionTab, onSelect: (SubscriptionTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .background(Color.White.copy(0.05f), RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SubscriptionTabButton(
            title = "Free Trial",
            selected = selectedTab == SubscriptionTab.FREE_TRIAL,
            onClick = { onSelect(SubscriptionTab.FREE_TRIAL) },
            modifier = Modifier.weight(1f)
        )
        SubscriptionTabButton(
            title = "Plan",
            selected = selectedTab == SubscriptionTab.PLAN,
            onClick = { onSelect(SubscriptionTab.PLAN) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SubscriptionTabButton(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) Color.White.copy(0.15f) else Color.Transparent).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(title, color = if (selected) Color.White else Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PlanContent(account: SubscriptionAccountState, selectedPlanIndex: Int, selectedAddOnIndex: Int, isProcessing: Boolean, onPlanSelected: (Int) -> Unit, onAddOnSelected: (Int) -> Unit, onSubscribe: () -> Unit, modifier: Modifier) {
    val selectedPlan = SubscriptionCatalog.plans[selectedPlanIndex]
    val selectedAddOn = SubscriptionCatalog.addOns[selectedAddOnIndex]
    val unavailable = account.hasActiveSubscription && !account.isFreeTrial && selectedPlan.priceCents <= account.normalizedActivePlanCents

    LazyColumn(modifier = modifier.padding(horizontal = 24.dp)) {
        item {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text("Business Membership", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Select plan duration and included tokens.", color = Color.Gray, fontSize = 13.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubscriptionCatalog.plans.forEachIndexed { index, plan ->
                    PlanTierCard(plan, selectedPlanIndex == index, { onPlanSelected(index) }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FeatureRow("${selectedPlan.baseTokens} Tokens included instantly")
                FeatureRow("Add or swap landmarks anytime")
                FeatureRow("Unlock promotion dashboard")
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 20.dp))
        }
        item { Text("Optional Token Add-on", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)) }
        item {
            var expanded by remember { mutableStateOf(false) }
            Box {
                Surface(onClick = { expanded = true }, color = Color.White.copy(0.05f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(SubscriptionCatalog.addOns[selectedAddOnIndex].label, color = Color.White, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null, tint = Color.White)
                    }
                }
                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.background(Color(0xFF1C1C1E))) {
                    SubscriptionCatalog.addOns.forEachIndexed { index, addOn ->
                        DropdownMenuItem(text = { Text(addOn.label, color = Color.White) }, onClick = { onAddOnSelected(index); expanded = false })
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
        item {
            Button(onClick = onSubscribe, enabled = !unavailable && !isProcessing, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = LookSeeBlue)) {
                val total = selectedPlan.priceCents + selectedAddOn.priceCents
                val totalStr = (total / 100.0).let { String.format(Locale.US, "%.2f", it) }
                Text(if (unavailable && selectedPlan.priceCents == account.normalizedActivePlanCents) "Current Plan" else if (unavailable) "Included in Active Plan" else if (account.hasActiveSubscription) "Upgrade to ${selectedPlan.label} - \$$totalStr" else "Subscribe - \$$totalStr", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PlanTierCard(plan: SubscriptionPlan, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, color = if (isSelected) LookSeeBlue.copy(0.2f) else Color.White.copy(0.05f), shape = RoundedCornerShape(12.dp), border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) LookSeeBlue else Color.White.copy(0.1f)), modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 12.dp)) {
            Text(plan.label, color = if (isSelected) Color.White else Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(plan.priceLabel.replace(".00", ""), color = if (isSelected) LookSeeBlue else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text("${plan.baseTokens} Tokens", color = Color.Gray, fontSize = 10.sp)
        }
    }
}

@Composable
private fun FeatureRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.CheckCircle, null, tint = LookSeeBlue, modifier = Modifier.size(15.dp))
        Text(text, color = Color.White.copy(0.8f), fontSize = 13.sp)
    }
}

@Composable
private fun TrialContent(account: SubscriptionAccountState, isProcessing: Boolean, onStartTrial: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.padding(24.dp).background(Color.White.copy(0.04f), RoundedCornerShape(24.dp)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(24.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text("14-Day Free Trial", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
            Text("$0", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text("/14 days", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text("Test out the platform risk-free with zero commitment.", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        HorizontalDivider(color = Color.White.copy(0.12f), modifier = Modifier.padding(vertical = 20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { FeatureRow("Includes exactly 2 Tokens"); FeatureRow("Full access to business tools"); FeatureRow("Auto-renews to 1-Year Plan ($10)") }
        Spacer(Modifier.weight(1f))
        Text("Payment information is required to start your trial. You will not be charged today. If you do not cancel before your 14 days are up, you will be billed $10 for the 1-Year Business Plan. Cancel anytime.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(bottom = 16.dp))
        Button(onClick = onStartTrial, enabled = account.isEligibleForTrial && !isProcessing, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500))) {
            Text(if (account.isEligibleForTrial) "Start Free Trial" else if (account.isFreeTrial) "Active (Free Trial)" else "Unavailable", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TokenContent(account: SubscriptionAccountState, isProcessing: Boolean, onBuy: (TokenAddOn) -> Unit, modifier: Modifier) {
    LazyColumn(modifier = modifier.padding(top = 4.dp, bottom = 16.dp, start = 24.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.GeneratingTokens, null, tint = LookSeeBlue, modifier = Modifier.size(48.dp))
                Text("${account.tokenBalance}", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Black)
                Text("TOKENS AVAILABLE", color = Color.Gray, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Use a token to add a landmark or swap an existing landmark. Removing one is free.", color = Color.Gray, textAlign = TextAlign.Center, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
            }
        }
        if (!account.isFullyLoggedIn || !account.hasActiveSubscription || account.isFreeTrial) {
            item { Text(if (account.isFreeTrial) "Token add-ons become available after upgrading from the free trial." else "Subscribe to a paid Business plan to purchase tokens.", color = Color.Gray, textAlign = TextAlign.Center, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
        } else {
            item { Text("BUY TOKEN PACKS", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
            items(SubscriptionCatalog.tokenPacks) { pack ->
                Row(modifier = Modifier.fillMaxWidth().background(Color.White.copy(0.04f), RoundedCornerShape(20.dp)).clickable(enabled = !isProcessing) { onBuy(pack) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(36.dp).background(LookSeeBlue.copy(0.15f), CircleShape), contentAlignment = Alignment.Center) { Text("${pack.tokens}", color = LookSeeBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    Spacer(modifier = Modifier.width(14.dp)); Text("${pack.tokens} Tokens", color = Color.White, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold); Text(pack.priceCents.asUsd(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}
