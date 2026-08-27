package looksee.angelll.com.uifiles

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*

@Composable
fun PopUp() {
    val infoView = remember { VariableContainer.shared }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var selectedPromotionImage by remember { mutableStateOf<String?>(null) }

    // Colors matching SwiftUI
    val purpleStart = Color(red = 0.25f, green = 0.10f, blue = 0.90f)
    val purpleEnd = Color(red = 0.50f, green = 0.15f, blue = 0.95f)
    val promotionOrange = Color(red = 1.00f, green = 0.58f, blue = 0.18f)

    // Derived Display Values
    val displayName = infoView.landmarkName.trim().ifEmpty { "Unknown Landmark" }
    val displayDescription = infoView.landmarkDescription.trim().ifEmpty { "No description is available for this landmark." }
    val cleanedPromoDescription = infoView.promoDescription.trim()
    val cleanedPromoName = infoView.promoName.trim()
    val shouldShowPromotion = cleanedPromoName.isNotEmpty() &&
            cleanedPromoName != "No active promotion" &&
            cleanedPromoName != "Checking promotions..."

    // URL Handling Helper
    fun normalizedURL(rawValue: String): String? {
        val cleaned = rawValue.trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.lowercase().startsWith("http://") || cleaned.lowercase().startsWith("https://")) {
            cleaned
        } else {
            "https://$cleaned"
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Calculate dynamic dimensions
        val maxWidthPx = maxWidth.value
        val maxHeightPx = maxHeight.value

        val popupWidth = minOf(maxOf(maxWidthPx - 56, 1f), 620f).dp
        val maximumPopupHeight = minOf(maxOf(maxHeightPx - 32, 1f), 780f).dp

        // Adaptive Shell
        Box(
            modifier = Modifier
                .width(popupWidth)
                .heightIn(max = maximumPopupHeight) // Acts like ViewThatFits (scrolls only if needed)
                .shadow(elevation = 30.dp, shape = RoundedCornerShape(30.dp), spotColor = Color.Black.copy(alpha = 0.30f), ambientColor = Color.Black.copy(alpha = 0.30f))
                .background(Color(0xFF1C1C1E).copy(alpha = 0.95f), RoundedCornerShape(30.dp)) // UltraThickMaterial equivalent
                .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(30.dp))
                .clip(RoundedCornerShape(30.dp))
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                // Content
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // MARK: - Landmark Text Section
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = displayName,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )

                        Text(
                            text = displayDescription,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White.copy(alpha = 0.7f),
                            lineHeight = 21.sp
                        )
                    }

                    // MARK: - Website Section
                    val websiteUrlStr = normalizedURL(infoView.landmarkWebsiteUrl)
                    if (websiteUrlStr != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Brush.horizontalGradient(listOf(purpleStart, purpleEnd)), RoundedCornerShape(16.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrlStr))
                                    context.startActivity(intent)
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Explore, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Visit Website", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                val host = remember(websiteUrlStr) {
                                    try { Uri.parse(websiteUrlStr).host } catch (e: Exception) { null }
                                }
                                if (host != null) {
                                    Text(host, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.NorthEast, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }

                    // MARK: - Promotion Section
                    if (shouldShowPromotion) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(promotionOrange.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                                .border(1.dp, promotionOrange.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(cleanedPromoName, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            if (cleanedPromoDescription.isNotEmpty()) {
                                Text(cleanedPromoDescription, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.7f))
                            }

                            // Promotion Image Section
                            val promoImageUrlStr = normalizedURL(infoView.promoImageUrl)
                            if (promoImageUrlStr != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectedPromotionImage = promoImageUrlStr
                                        },
                                    contentAlignment = Alignment.BottomEnd
                                ) {
                                    // Custom image loader for the promo box
                                    PromoAsyncImage(url = promoImageUrlStr)

                                    Row(
                                        modifier = Modifier
                                            .padding(10.dp)
                                            .background(Color.Black.copy(alpha = 0.60f), CircleShape)
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(Icons.Default.OpenInFull, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                        Text("View", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    // MARK: - Merchant Info
                    MerchantCardView()
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.18f))

                // MARK: - Close Button
                Box(modifier = Modifier.padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 18.dp)) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            infoView.dismissLandmark()
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Close", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // MARK: - Promotion Image Fullscreen Preview (NavigationStack equivalent)
    if (selectedPromotionImage != null) {
        Dialog(
            onDismissRequest = { selectedPromotionImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top Bar (ToolbarItem equivalent)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.width(48.dp)) // Balance the title
                        Text("Promotion Image", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        TextButton(onClick = { selectedPromotionImage = null }) {
                            Text("Done", fontSize = 16.sp, color = purpleStart)
                        }
                    }

                    // Image Content (scaledToFit)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        PromoPreviewAsyncImage(url = selectedPromotionImage!!)
                    }
                }
            }
        }
    }
}

// Helper Composable to handle the Image Loading states for the inner promotion card
@Composable
private fun PromoAsyncImage(url: String) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val stream = URL(url).openStream()
                bitmap = BitmapFactory.decodeStream(stream)
                isLoading = false
            } catch (e: Exception) {
                isError = true
                isLoading = false
            }
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    } else if (isError || bitmap == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Default.Image, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(26.dp))
                Text("Promotion image unavailable", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            }
        }
    } else {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit // Boss's fix: Show the complete promotion image
        )
    }
}

// Helper Composable for the Fullscreen Preview
@Composable
private fun PromoPreviewAsyncImage(url: String) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val stream = URL(url).openStream()
                bitmap = BitmapFactory.decodeStream(stream)
                isLoading = false
            } catch (e: Exception) {
                isError = true
                isLoading = false
            }
        }
    }

    if (isLoading) {
        CircularProgressIndicator(color = Color.White)
    } else if (isError || bitmap == null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(42.dp))
            Text("Could not load image", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.8f))
        }
    } else {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}
