package looksee.angelll.com.subscription

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.launch
import looksee.angelll.com.models.CheckoutConfirmRequest
import looksee.angelll.com.models.CheckoutPreparation
import looksee.angelll.com.models.CheckoutPrepareRequest
import looksee.angelll.com.models.CheckoutService
import looksee.angelll.com.models.PendingCheckout
import looksee.angelll.com.models.SubscriptionAccountState
import looksee.angelll.com.models.SubscriptionCatalog
import looksee.angelll.com.models.SubscriptionPurchaseUpdate
import looksee.angelll.com.models.SubscriptionTab
import looksee.angelll.com.models.TokenAddOn
import looksee.angelll.com.models.asUsd

private val LookSeeBlue = Color(0xFF387DFF)
private val LookSeeBackground = Color(0xFF0F0F1A)

/**
 * Android translation of SubscriptionPlans.swift.
 *
 * Authentication remains owned by the caller: pass the latest [account], handle sign-up through
 * [onRequireSignUp], and apply successful local state through [onAccountUpdated].
 */
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
        val pending = PendingCheckout(
            tab = SubscriptionTab.PLAN,
            planIndex = planIndex,
            addOnIndex = addOnIndex,
        )
        if (!account.isFullyLoggedIn) {
            onRequireSignUp(pending)
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
            update = SubscriptionPurchaseUpdate(
                addedTokens = addedTokens,
                subscriptionActivated = true,
                planCents = plan.priceCents,
                planYears = plan.years,
            ),
        )
    }

    fun beginTrialCheckout() {
        val pending = PendingCheckout(tab = SubscriptionTab.FREE_TRIAL)
        if (!account.isFullyLoggedIn) {
            onRequireSignUp(pending)
            return
        }
        prepareCheckout(
            request = CheckoutPrepareRequest.freeTrial(account),
            confirmation = CheckoutConfirmRequest(
                userId = account.userId,
                addTokens = 2,
                isBusiness = true,
                planCents = 1_000,
                planYears = 1,
            ),
            update = SubscriptionPurchaseUpdate(
                addedTokens = 2,
                subscriptionActivated = true,
                planCents = 1_000,
                planYears = 1,
                isFreeTrial = true,
            ),
        )
    }

    fun beginTokenCheckout(pack: TokenAddOn) {
        val pending = PendingCheckout(
            tab = SubscriptionTab.TOKENS,
            tokenCount = pack.tokens,
            tokenPriceCents = pack.priceCents,
        )
        if (!account.isFullyLoggedIn) {
            onRequireSignUp(pending)
            return
        }
        prepareCheckout(
            request = CheckoutPrepareRequest.tokenPack(account, pack),
            confirmation = CheckoutConfirmRequest(
                userId = account.userId,
                addTokens = pack.tokens,
                isBusiness = false,
            ),
            update = SubscriptionPurchaseUpdate(
                addedTokens = pack.tokens,
                subscriptionActivated = false,
            ),
        )
    }

    LaunchedEffect(account.hasActiveSubscription, selectedTab) {
        if (!account.hasActiveSubscription && selectedTab == SubscriptionTab.TOKENS) {
            selectedTab = SubscriptionTab.PLAN
        }
    }

    LaunchedEffect(resumeCheckout) {
        val pending = resumeCheckout ?: return@LaunchedEffect
        if (consumedResume) return@LaunchedEffect
        consumedResume = true
        val resumedPlanIndex = pending.planIndex.coerceIn(SubscriptionCatalog.plans.indices)
        val resumedAddOnIndex = pending.addOnIndex.coerceIn(SubscriptionCatalog.addOns.indices)
        selectedTab = pending.tab
        selectedPlanIndex = resumedPlanIndex
        selectedAddOnIndex = resumedAddOnIndex
        when (pending.tab) {
            SubscriptionTab.PLAN -> beginPlanCheckout(resumedPlanIndex, resumedAddOnIndex)
            SubscriptionTab.FREE_TRIAL -> beginTrialCheckout()
            SubscriptionTab.TOKENS -> {
                val pack = SubscriptionCatalog.tokenPacks.firstOrNull {
                    it.tokens == pending.tokenCount && it.priceCents == pending.tokenPriceCents
                }
                if (pack != null) beginTokenCheckout(pack)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LookSeeBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SubscriptionHeader(
                account = account,
                selectedTab = selectedTab,
                isProcessing = isProcessing,
                onClose = onClose,
            )

            if (!(startingTab == SubscriptionTab.TOKENS && account.hasActiveSubscription)) {
                SubscriptionTabs(
                    selectedTab = selectedTab,
                    account = account,
                    onSelect = { selectedTab = it },
                )
            }

            when (selectedTab) {
                SubscriptionTab.PLAN -> PlanContent(
                    account = account,
                    selectedPlanIndex = selectedPlanIndex,
                    selectedAddOnIndex = selectedAddOnIndex,
                    isProcessing = isProcessing,
                    onPlanSelected = { selectedPlanIndex = it },
                    onAddOnSelected = { selectedAddOnIndex = it },
                    onSubscribe = { beginPlanCheckout(selectedPlanIndex, selectedAddOnIndex) },
                    modifier = Modifier.weight(1f),
                )
                SubscriptionTab.FREE_TRIAL -> TrialContent(
                    account = account,
                    isProcessing = isProcessing,
                    onStartTrial = { beginTrialCheckout() },
                    modifier = Modifier.weight(1f),
                )
                SubscriptionTab.TOKENS -> TokenContent(
                    account = account,
                    isProcessing = isProcessing,
                    onBuy = { beginTokenCheckout(it) },
                    modifier = Modifier.weight(1f),
                )
            }

            paymentStatusMessage?.let {
                Text(
                    text = it,
                    color = if (it.contains("successful", ignoreCase = true) ||
                        it.contains("activated", ignoreCase = true)
                    ) Color(0xFF34C759) else Color(0xFFFF6B6B),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            Text(
                "Secured by Stripe. Cancel at any time.",
                color = Color.White.copy(alpha = 0.58f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
        }

        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Connecting to Stripe…", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SubscriptionHeader(
    account: SubscriptionAccountState,
    selectedTab: SubscriptionTab,
    isProcessing: Boolean,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("LookSee", color = LookSeeBlue, fontWeight = FontWeight.Bold)
            Text(
                when {
                    selectedTab == SubscriptionTab.TOKENS -> "Token Store"
                    account.hasActiveSubscription -> "Manage Membership"
                    else -> "Upgrade to Business"
                },
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        OutlinedButton(onClick = onClose, enabled = !isProcessing) { Text("Close") }
    }
}

@Composable
private fun SubscriptionTabs(
    selectedTab: SubscriptionTab,
    account: SubscriptionAccountState,
    onSelect: (SubscriptionTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(SubscriptionTab.FREE_TRIAL, SubscriptionTab.PLAN).forEach { tab ->
            Button(
                onClick = { onSelect(tab) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTab == tab) LookSeeBlue else Color.DarkGray,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (tab == SubscriptionTab.PLAN) "Plan" else "Free Trial")
            }
        }
        if (account.hasActiveSubscription) {
            Button(
                onClick = { onSelect(SubscriptionTab.TOKENS) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedTab == SubscriptionTab.TOKENS) {
                        LookSeeBlue
                    } else {
                        Color.DarkGray
                    },
                ),
                modifier = Modifier.weight(1f),
            ) { Text("Tokens") }
        }
    }
}

@Composable
private fun PlanContent(
    account: SubscriptionAccountState,
    selectedPlanIndex: Int,
    selectedAddOnIndex: Int,
    isProcessing: Boolean,
    onPlanSelected: (Int) -> Unit,
    onAddOnSelected: (Int) -> Unit,
    onSubscribe: () -> Unit,
    modifier: Modifier,
) {
    val selectedPlan = SubscriptionCatalog.plans[selectedPlanIndex]
    val selectedAddOn = SubscriptionCatalog.addOns[selectedAddOnIndex]
    val unavailable = account.hasActiveSubscription && !account.isFreeTrial &&
        selectedPlan.priceCents <= account.normalizedActivePlanCents

    LazyColumn(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Business Membership",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text("Select plan duration and included tokens.", color = Color.LightGray)
        }
        items(SubscriptionCatalog.plans.indices.toList()) { index ->
            val plan = SubscriptionCatalog.plans[index]
            SelectionRow(
                title = "${plan.label} · ${plan.priceLabel}",
                subtitle = "${plan.baseTokens} tokens included",
                selected = selectedPlanIndex == index,
                onClick = { onPlanSelected(index) },
            )
        }
        item { Text("Optional Token Add-on", color = Color.LightGray, fontWeight = FontWeight.Bold) }
        items(SubscriptionCatalog.addOns.indices.toList()) { index ->
            val addOn = SubscriptionCatalog.addOns[index]
            SelectionRow(
                title = addOn.label,
                subtitle = if (addOn.tokens == 0) "No extra tokens" else "${addOn.tokens} extra tokens",
                selected = selectedAddOnIndex == index,
                onClick = { onAddOnSelected(index) },
            )
        }
        item {
            Button(
                onClick = onSubscribe,
                enabled = !unavailable && !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            ) {
                val total = selectedPlan.priceCents + selectedAddOn.priceCents
                Text(
                    when {
                        unavailable && selectedPlan.priceCents == account.normalizedActivePlanCents ->
                            "Current Plan"
                        unavailable -> "Included in Active Plan"
                        account.hasActiveSubscription -> "Upgrade · ${total.asUsd()}"
                        else -> "Subscribe · ${total.asUsd()}"
                    },
                )
            }
        }
    }
}

@Composable
private fun TrialContent(
    account: SubscriptionAccountState,
    isProcessing: Boolean,
    onStartTrial: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(22.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("14-Day Free Trial", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text("$0 / 14 days", color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Text("Includes exactly 2 tokens and full access to business tools.", color = Color.LightGray)
        Text(
            "Payment information is required. You will not be charged today. Unless canceled, " +
                "the trial renews to the $10 one-year Business Plan.",
            color = Color.LightGray,
        )
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onStartTrial,
            enabled = account.isEligibleForTrial && !isProcessing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (account.isEligibleForTrial) "Start Free Trial" else "Not Eligible for Free Trial")
        }
    }
}

@Composable
private fun TokenContent(
    account: SubscriptionAccountState,
    isProcessing: Boolean,
    onBuy: (TokenAddOn) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
            ) {
                Text("${account.tokenBalance}", color = Color.White, style = MaterialTheme.typography.displaySmall)
                Text("TOKENS AVAILABLE", color = Color.LightGray, fontWeight = FontWeight.Bold)
                Text(
                    "Use a token to add a landmark or swap an existing landmark. Removing one is free.",
                    color = Color.LightGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        if (!account.isFullyLoggedIn || !account.hasActiveSubscription || account.isFreeTrial) {
            item {
                Text(
                    if (account.isFreeTrial) {
                        "Token add-ons become available after upgrading from the free trial."
                    } else {
                        "Subscribe to a paid Business plan to purchase tokens."
                    },
                    color = Color.LightGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                )
            }
        } else {
            items(SubscriptionCatalog.tokenPacks) { pack ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isProcessing) { onBuy(pack) }
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(LookSeeBlue.copy(alpha = 0.20f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${pack.tokens}", color = LookSeeBlue, fontWeight = FontWeight.Bold) }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text("${pack.tokens} Tokens", color = Color.White, modifier = Modifier.weight(1f))
                    Text(pack.priceCents.asUsd(), color = Color.White, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            }
        }
    }
}

@Composable
private fun SelectionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) LookSeeBlue.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(14.dp),
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(if (selected) LookSeeBlue else Color.DarkGray, CircleShape),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
}
