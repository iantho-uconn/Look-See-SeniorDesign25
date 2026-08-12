package looksee.angelll.com.uifiles

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import java.util.UUID

// Data class to hold the selected preview item
private data class PromotionImagePreviewItem(
    val id: UUID = UUID.randomUUID(),
    val url: String
)

@Composable
fun PopUp() {
    val infoView = VariableContainer.shared
    var selectedPromotionImage by remember { mutableStateOf<PromotionImagePreviewItem?>(null) }

    // Colors
    val purpleStart = Color(red = 0.25f, green = 0.10f, blue = 0.90f)
    val purpleEnd = Color(red = 0.50f, green = 0.15f, blue = 0.95f)
    val promotionOrange = Color(red = 1.00f, green = 0.58f, blue = 0.18f)

    // Side Effects
    LaunchedEffect(infoView.landmarkWebsiteUrl) {
        Log.d("PopUp", "🔗 PopUp observed website URL: ${infoView.landmarkWebsiteUrl}")
    }
    LaunchedEffect(infoView.promoImageUrl) {
        Log.d("PopUp", "🖼️ PopUp observed promotion image URL: ${infoView.promoImageUrl}")
    }

    // Adaptive Layout Logic (Mimicking `ViewThatFits`)
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val availableHeight = maxOf(maxHeight - 32.dp, 280.dp)
        val maximumPopupHeight = minOf(availableHeight, 780.dp)

        // If the screen has enough room, show Intrinsic. Otherwise, show Scrolling.
        val useScrolling = maxHeight < 780.dp

        Box(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .widthIn(max = 620.dp)
                .shadow(elevation = 30.dp, shape = RoundedCornerShape(30.dp), spotColor = Color.Black.copy(alpha = 0.30f))
                .background(Color.DarkGray.copy(alpha = 0.9f), RoundedCornerShape(30.dp))
                .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(30.dp))
        ) {
            Column {
                if (useScrolling) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = maximumPopupHeight)
                            .verticalScroll(rememberScrollState())
                    ) {
                        PopupContent(infoView, purpleStart, purpleEnd, promotionOrange) { url ->
                            selectedPromotionImage = PromotionImagePreviewItem(url = url)
                        }
                    }
                } else {
                    PopupContent(infoView, purpleStart, purpleEnd, promotionOrange) { url ->
                        selectedPromotionImage = PromotionImagePreviewItem(url = url)
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.18f))

                // Close Button Area
                Button(
                    onClick = { infoView.dismissLandmark() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text("Close", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Image Preview Sheet (Dialog)
    selectedPromotionImage?.let { item ->
        PromotionImagePreview(url = item.url, onDismiss = { selectedPromotionImage = null })
    }
}

@Composable
private fun PopupContent(
    infoView: VariableContainer,
    purpleStart: Color,
    purpleEnd: Color,
    promotionOrange: Color,
    onImageTap: (String) -> Unit
) {
    val displayName = infoView.landmarkName.trim().ifEmpty { "Unknown Landmark" }
    val displayDescription = infoView.landmarkDescription.trim().ifEmpty { "No description is available for this landmark." }
    val cleanedPromoDescription = infoView.promoDescription.trim()

    val cleanedName = infoView.promoName.trim()
    val shouldShowPromotion = cleanedName.isNotEmpty() &&
            cleanedName != "No active promotion" &&
            cleanedName != "Checking promotions..."

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // MARK: - Landmark Text Section
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = displayName,
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                lineHeight = 38.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = displayDescription,
                fontSize = 16.sp,
                color = Color.LightGray,
                lineHeight = 21.sp
            )
        }

        // MARK: - Website Section
        val websiteUrl = normalizedURL(infoView.landmarkWebsiteUrl)
        if (websiteUrl != null) {
            val host = Uri.parse(websiteUrl).host ?: ""

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(purpleStart, purpleEnd)))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                    .clickable { /* Launch Intent to Browser */ }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Language, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Visit Website", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (host.isNotEmpty()) {
                            Text(host, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(imageVector = Icons.Default.OpenInNew, contentDescription = null, tint = Color.White)
                }
            }
        }

        // MARK: - Promotion Section
        if (shouldShowPromotion) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(promotionOrange.copy(alpha = 0.12f))
                    .border(1.dp, promotionOrange.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = infoView.promoName,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (cleanedPromoDescription.isNotEmpty()) {
                    Text(
                        text = cleanedPromoDescription,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.LightGray
                    )
                }

                // Promotion Image
                val imageURL = normalizedURL(infoView.promoImageUrl)
                if (imageURL != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                            .clickable { onImageTap(imageURL) }
                    ) {
                        SubcomposeAsyncImage(
                            model = imageURL,
                            contentDescription = "Promotion Image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            loading = {
                                Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                            },
                            error = {
                                Column(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(imageVector = Icons.Default.Photo, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(26.dp))
                                    Spacer(modifier = Modifier.height(7.dp))
                                    Text("Promotion image unavailable", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        )

                        // "View" Badge
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                                .background(Color.Black.copy(alpha = 0.60f), CircleShape)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text("View", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // MerchantCardView (Custom View)
        MerchantCardView()
    }
}

// MARK: - Helper Functions

private fun normalizedURL(rawValue: String): String? {
    val cleaned = rawValue.trim()
    if (cleaned.isEmpty()) return null

    val scheme = Uri.parse(cleaned).scheme?.lowercase()
    if (scheme == "http" || scheme == "https") {
        return cleaned
    }
    return "https://$cleaned"
}

// MARK: - Image Preview Dialog (Sheet Equivalent)
@Composable
private fun PromotionImagePreview(url: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Promotion Image", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                SubcomposeAsyncImage(
                    model = url,
                    contentDescription = "Full Promotion Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    loading = {
                        Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                    },
                    error = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(imageVector = Icons.Default.Photo, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(42.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Could not load image", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        }
    }
}