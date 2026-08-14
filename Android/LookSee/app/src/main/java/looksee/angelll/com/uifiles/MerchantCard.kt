package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage

// Modern state-driven card for the AR popup view
@Composable
fun MerchantCardView() {
    // Unresolved reference: VariableContainer
    val vm = VariableContainer.shared

    val cardBackground = Color.White.copy(alpha = 0.08f)
    val cardBorder = Color.White.copy(alpha = 0.10f)
    val titleText = Color.White
    val secondaryText = Color.White.copy(alpha = 0.78f)
    val tertiaryText = Color.White.copy(alpha = 0.62f)

    if (vm.merchantName.isNotEmpty()) {
        Column(
            modifier = Modifier
                .background(cardBackground, RoundedCornerShape(14.dp))
                .border(0.5.dp, cardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header & Verified Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(10.dp))
                    Text(
                        text = "LANDMARK SPONSORED BY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = tertiaryText,
                        letterSpacing = 1.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .background(Color.Blue.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Blue, modifier = Modifier.size(10.dp))
                    Text(
                        text = "Verified",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Blue
                    )
                }
            }

            // Logo & Business Info
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SubcomposeAsyncImage(
                    model = vm.merchantLogoUrl,
                    contentDescription = "Merchant Logo",
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                    contentScale = ContentScale.Crop,
                    loading = {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        }
                    },
                    error = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.10f), CircleShape)
                        ) {
                            Icon(Icons.Default.Storefront, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = vm.merchantName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = titleText
                    )
                    if (vm.merchantBio.isNotEmpty()) {
                        Text(
                            text = vm.merchantBio,
                            fontSize = 14.sp,
                            color = secondaryText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Phone Number Row (Optional)
            if (vm.merchantPhone.isNotEmpty()) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Phone, contentDescription = null, tint = Color.Green, modifier = Modifier.size(14.dp))
                    Text(
                        text = vm.merchantPhone,
                        fontSize = 14.sp,
                        color = secondaryText
                    )
                }
            }
        }
    }
}

// Legacy Parameterized Merchant Card (Required by Settings)
@Composable
fun MerchantCard(
    storeName: String,
    logoUrl: String,
    bio: String,
    phone: String
) {
    Column(
        modifier = Modifier
            .background(Color(0xFF1C1C1E), RoundedCornerShape(14.dp)) // secondarySystemGroupedBackground equivalent
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SubcomposeAsyncImage(
                model = logoUrl,
                contentDescription = "Merchant Logo",
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                loading = {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    }
                },
                error = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Gray.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(Icons.Default.Storefront, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = storeName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                if (bio.isNotEmpty()) {
                    Text(
                        text = bio,
                        fontSize = 14.sp,
                        color = Color.Gray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (phone.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Phone, contentDescription = null, tint = Color.Green, modifier = Modifier.size(14.dp))
                Text(
                    text = phone,
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        }
    }
}