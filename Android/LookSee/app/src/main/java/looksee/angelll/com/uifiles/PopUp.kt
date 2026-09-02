package looksee.angelll.com.uifiles

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
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
import looksee.angelll.com.ui.theme.AppleBlue

@Composable
fun PopUp() {
    val infoView = remember { VariableContainer.shared }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var selectedPromotionImage by remember { mutableStateOf<String?>(null) }

    // Colors matching SwiftUI PopUp.swift
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
        val maxWidthPx = maxWidth.value
        val maxHeightPx = maxHeight.value

        val popupWidth = minOf(maxOf(maxWidthPx - 56, 1f), 620f).dp
        val maximumPopupHeight = minOf(maxOf(maxHeightPx - 32, 1f), 780f).dp

        // Shell: Equivalent to popupShell in PopUp.swift
        Surface(
            modifier = Modifier
                .width(popupWidth)
                .heightIn(max = maximumPopupHeight)
                .shadow(
                    elevation = 30.dp,
                    shape = RoundedCornerShape(30.dp),
                    spotColor = Color.Black.copy(alpha = 0.30f)
                ),
            color = Color(0xFF1C1C1E).copy(alpha = 0.95f), // UltraThickMaterial-ish
            shape = RoundedCornerShape(30.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Scrolling Content
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Landmark Text
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = displayName,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            lineHeight = 40.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = displayDescription,
                            fontSize = 16.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            lineHeight = 24.sp
                        )
                    }

                    // Website Button
                    val websiteUrl = normalizedURL(infoView.landmarkWebsiteUrl)
                    if (websiteUrl != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Brush.horizontalGradient(listOf(purpleStart, purpleEnd)), RoundedCornerShape(16.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl)))
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Public, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Visit Website", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                val host = remember(websiteUrl) {
                                    runCatching { Uri.parse(websiteUrl).host }.getOrNull()
                                }
                                if (host != null) {
                                    Text(host, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Default.NorthEast, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }

                    // Promotion Section
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
                                Text(cleanedPromoDescription, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                            }
                            
                            val promoImg = normalizedURL(infoView.promoImageUrl)
                            if (promoImg != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectedPromotionImage = promoImg
                                        },
                                    contentAlignment = Alignment.BottomEnd
                                ) {
                                    PromoImageLoader(url = promoImg)
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

                    // Merchant Card
                    MerchantCardView()
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.18f))

                // Footer: Close Button
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

    // Fullscreen Image Preview
    if (selectedPromotionImage != null) {
        Dialog(
            onDismissRequest = { selectedPromotionImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.width(48.dp))
                        Text("Promotion Image", color = Color.White, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { selectedPromotionImage = null }) {
                            Text("Done", color = purpleStart, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PromoImageLoader(url = selectedPromotionImage!!, contentScale = ContentScale.Fit)
                    }
                }
            }
        }
    }
}

@Composable
private fun PromoImageLoader(url: String, contentScale: ContentScale = ContentScale.Fit) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val stream = URL(url).openStream()
                bitmap = BitmapFactory.decodeStream(stream)
                isLoading = false
            } catch (e: Exception) {
                isLoading = false
            }
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
        }
    } else if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Image, contentDescription = null, tint = Color.Gray)
        }
    }
}
