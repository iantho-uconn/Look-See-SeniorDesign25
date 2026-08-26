package looksee.angelll.com.uifiles

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

// MARK: - Modern state-driven card for the AR popup view
@Composable
fun MerchantCardView() {
    val vm = remember { VariableContainer.shared } // Connects to your actual singleton state
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var showWebsiteAlert by remember { mutableStateOf(false) }

    // Colors matching iOS Theme (Scoped inside to prevent package conflicts)
    val SecondaryGrouped = Color(0xFF1C1C1E)
    val cardBackground = Color.White.copy(alpha = 0.08f)
    val cardBorder = Color.White.copy(alpha = 0.10f)
    val titleText = Color.White
    val secondaryText = Color.White.copy(alpha = 0.78f)
    val tertiaryText = Color.White.copy(alpha = 0.62f)

    if (vm.merchantName.isNotEmpty()) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth()
                .background(cardBackground, RoundedCornerShape(14.dp))
                .border(0.5.dp, cardBorder, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // MARK: - Header & Verified Badge
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(12.dp))
                    Text(
                        "LANDMARK OWNER",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = tertiaryText
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color.Blue.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = Color.Blue, modifier = Modifier.size(12.dp))
                    Text("Verified", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.Blue)
                }
            }

            // MARK: - Logo & Business Info
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (vm.merchantLogoUrl.trim().isEmpty()) {
                    Box(modifier = Modifier.size(52.dp).background(Color.White.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Storefront, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                } else {
                    Box(modifier = Modifier.size(52.dp).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)) {
                        MerchantCardRemoteImage(url = vm.merchantLogoUrl, modifier = Modifier.fillMaxSize())
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(vm.merchantName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = titleText)
                    if (vm.merchantBio.isNotEmpty()) {
                        Text(vm.merchantBio, fontSize = 14.sp, color = secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // MARK: - Contact Info Hyperlinks
            if (vm.merchantPhone.isNotEmpty() || vm.merchantWebsite.isNotEmpty() || vm.merchantAddress.isNotEmpty()) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (vm.merchantPhone.isNotEmpty()) {
                        Row(modifier = Modifier.clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val digits = vm.merchantPhone.filter { it.isDigit() }
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
                            context.startActivity(intent)
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = Color.Green, modifier = Modifier.size(16.dp))
                            Text(vm.merchantPhone, fontSize = 14.sp, color = Color.Blue, textDecoration = TextDecoration.Underline)
                        }
                    }

                    if (vm.merchantWebsite.isNotEmpty()) {
                        Row(modifier = Modifier.clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showWebsiteAlert = true
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Public, contentDescription = null, tint = Color.Blue, modifier = Modifier.size(16.dp))
                            Text(vm.merchantWebsite, fontSize = 14.sp, color = Color.Blue, textDecoration = TextDecoration.Underline)
                        }
                    }

                    if (vm.merchantAddress.isNotEmpty()) {
                        Row(modifier = Modifier.clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val encodedAddress = Uri.encode(vm.merchantAddress)
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encodedAddress"))
                            context.startActivity(intent)
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                            Text(vm.merchantAddress, fontSize = 14.sp, color = Color.Blue, textDecoration = TextDecoration.Underline)
                        }
                    }
                }
            }
        }

        if (showWebsiteAlert) {
            AlertDialog(
                onDismissRequest = { showWebsiteAlert = false },
                title = { Text("Leave LookSee?") },
                text = { Text("You are about to open your browser to visit this merchant's website.") },
                confirmButton = {
                    TextButton(onClick = {
                        showWebsiteAlert = false
                        var urlStr = vm.merchantWebsite
                        if (!urlStr.lowercase().startsWith("http")) urlStr = "https://$urlStr"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr))
                        context.startActivity(intent)
                    }) { Text("Visit Website", color = Color.Blue) }
                },
                dismissButton = {
                    TextButton(onClick = { showWebsiteAlert = false }) { Text("Cancel", color = Color.Gray) }
                },
                containerColor = SecondaryGrouped,
                titleContentColor = Color.White,
                textContentColor = Color.LightGray
            )
        }
    }
}

// MARK: - Legacy Parameterized Merchant Card (Required by Settings.kt)
@Composable
fun MerchantCard(
    storeName: String,
    logoUrl: String,
    bio: String,
    phone: String,
    website: String,
    address: String
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showWebsiteAlert by remember { mutableStateOf(false) }

    // Scoped to prevent package conflicts
    val SecondaryGrouped = Color(0xFF1C1C1E)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SecondaryGrouped, RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (logoUrl.trim().isEmpty()) {
                Box(modifier = Modifier.size(50.dp).background(Color.Gray.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Storefront, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            } else {
                Box(modifier = Modifier.size(50.dp).clip(CircleShape)) {
                    MerchantCardRemoteImage(url = logoUrl, modifier = Modifier.fillMaxSize())
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(storeName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                if (bio.isNotEmpty()) {
                    Text(bio, fontSize = 14.sp, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (phone.isNotEmpty() || website.isNotEmpty() || address.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (phone.isNotEmpty()) {
                    Row(modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val digits = phone.filter { it.isDigit() }
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
                        context.startActivity(intent)
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Phone, contentDescription = null, tint = Color.Green, modifier = Modifier.size(16.dp))
                        Text(phone, fontSize = 14.sp, color = Color.Blue, textDecoration = TextDecoration.Underline)
                    }
                }

                if (website.isNotEmpty()) {
                    Row(modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showWebsiteAlert = true
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = Color.Blue, modifier = Modifier.size(16.dp))
                        Text(website, fontSize = 14.sp, color = Color.Blue, textDecoration = TextDecoration.Underline)
                    }
                }

                if (address.isNotEmpty()) {
                    Row(modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val encodedAddress = Uri.encode(address)
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encodedAddress"))
                        context.startActivity(intent)
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                        Text(address, fontSize = 14.sp, color = Color.Blue, textDecoration = TextDecoration.Underline)
                    }
                }
            }
        }
    }

    if (showWebsiteAlert) {
        AlertDialog(
            onDismissRequest = { showWebsiteAlert = false },
            title = { Text("Leave LookSee?") },
            text = { Text("You are about to open your browser to visit this merchant's website.") },
            confirmButton = {
                TextButton(onClick = {
                    showWebsiteAlert = false
                    var urlStr = website
                    if (!urlStr.lowercase().startsWith("http")) urlStr = "https://$urlStr"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr))
                    context.startActivity(intent)
                }) { Text("Visit Website", color = Color.Blue) }
            },
            dismissButton = {
                TextButton(onClick = { showWebsiteAlert = false }) { Text("Cancel", color = Color.Gray) }
            },
            containerColor = SecondaryGrouped,
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}

// Native Generic Image Loader (Renamed to private to avoid overloads in other files)
@Composable
private fun MerchantCardRemoteImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val stream = URL(url).openStream()
                bitmap = BitmapFactory.decodeStream(stream)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    if (bitmap != null) {
        androidx.compose.foundation.Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier = modifier.background(Color.DarkGray), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        }
    }
}