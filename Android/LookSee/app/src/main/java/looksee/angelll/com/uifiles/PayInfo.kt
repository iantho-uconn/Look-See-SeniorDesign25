package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import looksee.angelll.com.viewmodels.AuthViewModel

data class Plan(val years: Int, val priceString: String, val baseTokens: Int, val label: String)
data class TokenAddOn(val tokens: Int, val label: String)

@Composable
fun PayInfo(
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Plan, 1: Tokens, 2: Free Trial
    var selectedPlanIndex by remember { mutableIntStateOf(0) }
    var selectedAddOnIndex by remember { mutableIntStateOf(0) }

    val plans = listOf(
        Plan(1, "$10", 10, "1 Year"),
        Plan(3, "$25", 25, "3 Years"),
        Plan(5, "$35", 35, "5 Years")
    )

    val addOns = listOf(
        TokenAddOn(0, "None"),
        TokenAddOn(1, "1 Token (+$3.00)"),
        TokenAddOn(5, "5 Tokens (+$10.00)"),
        TokenAddOn(10, "10 Tokens (+$15.00)"),
        TokenAddOn(25, "25 Tokens (+$35.00)"),
        TokenAddOn(50, "50 Tokens (+$60.00)"),
        TokenAddOn(100, "100 Tokens (+$100.00)")
    )

    val primaryColor = Color(0xFF387DFF)
    val backgroundColor = Color(0xFF0F0F1A)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Decorative Blurs
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset(x = (-100).dp, y = (-100).dp)
                .blur(80.dp)
                .background(primaryColor.copy(alpha = 0.15f), RoundedCornerShape(150.dp))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.4f))
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "LookSee",
                    color = primaryColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (selectedTab == 1) "Token Store" else "Upgrade to Business",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Segmented Picker
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                TabItem(
                    text = "Free Trial",
                    isSelected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.weight(1f)
                )
                TabItem(
                    text = "Plan",
                    isSelected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f)
                )
                TabItem(
                    text = "Tokens",
                    isSelected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            when (selectedTab) {
                0 -> PlanView(plans, selectedPlanIndex, { selectedPlanIndex = it }, addOns, selectedAddOnIndex, { selectedAddOnIndex = it }, primaryColor)
                1 -> TokenStoreView(vm, primaryColor)
                2 -> FreeTrialView(primaryColor)
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White.copy(alpha = 0.58f), modifier = Modifier.size(12.dp))
                Text(
                    text = "Secured by Stripe. Cancel at any time.",
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 11.sp
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun TabItem(text: String, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(36.dp)
            .background(
                if (isSelected) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun PlanView(
    plans: List<Plan>,
    selectedPlanIndex: Int,
    onPlanSelected: (Int) -> Unit,
    addOns: List<TokenAddOn>,
    selectedAddOnIndex: Int,
    onAddOnSelected: (Int) -> Unit,
    primaryColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(24.dp))
            .border(1.5.dp, primaryColor.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
            .padding(24.dp)
    ) {
        Text("Business Membership", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Select plan duration and included tokens.", color = Color.White.copy(alpha = 0.58f), fontSize = 13.sp)

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            plans.forEachIndexed { index, plan ->
                val isSelected = selectedPlanIndex == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (isSelected) primaryColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) primaryColor else Color.White.copy(alpha = 0.1f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onPlanSelected(index) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(plan.label, color = if (isSelected) Color.White else Color.White.copy(alpha = 0.58f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(plan.priceString, color = if (isSelected) primaryColor else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text("${plan.baseTokens} Tokens", color = Color.White.copy(alpha = 0.58f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        Spacer(modifier = Modifier.height(14.dp))

        VStack(spacing = 10.dp) {
            FeatureRow("Tokens included instantly", primaryColor)
            FeatureRow("Add or swap landmarks anytime", primaryColor)
            FeatureRow("Unlock promotion dashboard", primaryColor)
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        Spacer(modifier = Modifier.height(14.dp))

        Text("Optional Token Add-on", color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        
        // Simplified Spinner/Picker
        var expanded by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                .clickable { expanded = true }
                .padding(10.dp)
        ) {
            Text(addOns[selectedAddOnIndex].label, color = Color.White, fontSize = 14.sp)
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                addOns.forEachIndexed { index, addOn ->
                    DropdownMenuItem(
                        text = { Text(addOn.label) },
                        onClick = { 
                            onAddOnSelected(index)
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { /* Handle Subscribe */ },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
        ) {
            Text("Subscribe", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TokenStoreView(vm: AuthViewModel, primaryColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(48.dp), tint = primaryColor)
            Text(text = vm.tokenBalance.toString(), fontSize = 42.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text(text = "Tokens Available", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.58f))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What are tokens?", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "A token can be used to add another landmark to your account or swap an existing one out. Removing a landmark is free.",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Buy Token Packs", color = Color.White.copy(alpha = 0.58f), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(20.dp))
                    .padding(vertical = 8.dp)
            ) {
                TokenBundleRow(1, "$3.00", primaryColor)
                HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = Color.White.copy(alpha = 0.1f))
                TokenBundleRow(5, "$10.00", primaryColor)
                HorizontalDivider(modifier = Modifier.padding(start = 68.dp), color = Color.White.copy(alpha = 0.1f))
                TokenBundleRow(10, "$15.00", primaryColor)
            }
        }
    }
}

@Composable
fun TokenBundleRow(tokens: Int, price: String, primaryColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(primaryColor.copy(alpha = 0.15f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(tokens.toString(), color = primaryColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text("$tokens Tokens", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.weight(1f))
        Text(price, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun FreeTrialView(primaryColor: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(24.dp))
            .border(1.5.dp, Color.Yellow.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(24.dp)
    ) {
        Text("14-Day Free Trial", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
            Text("$0", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text("/14 days", color = Color.White.copy(alpha = 0.58f), fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }

        Text("Test out the platform risk-free with zero commitment.", color = Color.White.copy(alpha = 0.58f), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        Spacer(modifier = Modifier.height(14.dp))

        VStack(spacing = 10.dp) {
            FeatureRow("Includes exactly 2 Tokens", primaryColor)
            FeatureRow("Full access to business tools", primaryColor)
            FeatureRow("Auto-renews to 1-Year Plan ($10)", primaryColor)
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            "Payment information is required to start your trial. You will not be charged today. Cancel anytime.",
            color = Color.White.copy(alpha = 0.58f),
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Button(
            onClick = { /* Handle Trial */ },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow.copy(alpha = 0.8f))
        ) {
            Text("Start Free Trial", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
    }
}

@Composable
fun FeatureRow(text: String, primaryColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = primaryColor, modifier = Modifier.size(15.dp))
        Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
    }
}
