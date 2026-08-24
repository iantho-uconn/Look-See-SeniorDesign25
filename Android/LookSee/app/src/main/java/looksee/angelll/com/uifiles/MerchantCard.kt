package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable
fun MerchantCardView(vm: VariableContainer) {
    // FIX: Extracting values from StateFlow using collectAsState()
    val merchantName by vm.merchantName.collectAsState()
    val merchantLogoUrl by vm.merchantLogoUrl.collectAsState()
    val merchantBio by vm.merchantBio.collectAsState()
    val merchantPhone by vm.merchantPhone.collectAsState()

    val cardBackground = Color.White.copy(alpha = 0.08f)
    val cardBorder = Color.White.copy(alpha = 0.10f)
    val titleText = Color.White
    val secondaryText = Color.White.copy(alpha = 0.78f)
    val tertiaryText = Color.White.copy(alpha = 0.62f)

    if (merchantName.isNotEmpty()) {
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
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(12.dp))
                    Text(
                        text = "LANDMARK OWNER",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tertiaryText
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .background(Color.Blue.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = Color.Blue, modifier = Modifier.size(12.dp))
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
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        .background(Color.White.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    SubcomposeAsyncImage(
                        model = merchantLogoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        loading = { CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(14.dp)) },
                        error = { Icon(Icons.Default.Storefront, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        text = merchantName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = titleText
                    )

                    if (merchantBio.isNotEmpty()) {
                        Text(
                            text = merchantBio,
                            fontSize = 14.sp,
                            color = secondaryText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Phone Number Row
            if (merchantPhone.isNotEmpty()) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Phone, contentDescription = null, tint = Color.Green, modifier = Modifier.size(14.dp))
                    Text(
                        text = merchantPhone,
                        fontSize = 14.sp,
                        color = secondaryText,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun MerchantCard(
    storeName: String,
    logoUrl: String,
    bio: String,
    phone: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF2F2F7), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(Color.Gray.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                SubcomposeAsyncImage(
                    model = logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { CircularProgressIndicator(modifier = Modifier.padding(12.dp)) },
                    error = { Icon(Icons.Default.Storefront, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = storeName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
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
                    color = Color.Gray,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}