package looksee.angelll.com.uifiles

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import looksee.angelll.com.ui.theme.AppleBlue

private val backgroundColor = Color(0xFF0F0F1A)
private val cardColor = Color(0xFF1F1F2E)
private val primaryColor = AppleBlue

enum class FAQCategory(val displayName: String) {
    ALL("All"),
    GENERAL("General"),
    SCANNING("Scanning"),
    RECORDING("Recording"),
    MEDIA("Media & Processing"),
    ACCOUNT("Account & Promos")
}

data class FAQItem(
    val category: FAQCategory,
    val question: String,
    val answer: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var searchText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(FAQCategory.ALL) }

    val faqs = remember {
        listOf(
            FAQItem(
                category = FAQCategory.SCANNING,
                question = "How do I scan a landmark?",
                answer = "Open LookSee and it will download the model for your current location and bring you to the scan screen. For the functionality to work, you will need to allow LookSee to obtain location data and get the camera feed from your phone. Simply point the camera at the landmark and if it is detected, you will see a green box around it. Click on the box to get a pop-up with more information on the landmark and potentially any promotional messages."
            ),
            FAQItem(
                category = FAQCategory.SCANNING,
                question = "What is the map function?",
                answer = "The map shows all landmarks in the LookSee model. You can pan and zoom on the map to see other landmarks in the area. The map also shows the title and description information for the landmark, but it does not show any active promotion associated with the landmark. You need to scan the landmark to see the promotions."
            ),
            FAQItem(
                category = FAQCategory.RECORDING,
                question = "How do I record a landmark?",
                answer = "To record a landmark, first create a user account or log in and ensure you have a token available for landmark uploads. The free 14-day trial includes two tokens to upload a landmark. You can also purchase tokens if you need more.\n\nOnce logged in, on the scan page, click on the record icon at the bottom center of the screen to bring you to the screen to record a landmark. Then follow the screen prompts to record video(s) of the object, negative detection video of the area around the landmark, and enter the title and description of the landmark. Then upload the videos and data to LookSee."
            ),
            FAQItem(
                category = FAQCategory.RECORDING,
                question = "What are some tips for taking video of landmarks?",
                answer = "You can take multiple shorter videos and LookSee will stitch them together for the submission. Take videos up close and farther away from the object from all directions where a user could scan the landmark. Try to keep the video as steady as possible. Avoid walking backwards while taking videos—you could trip!"
            ),
            FAQItem(
                category = FAQCategory.RECORDING,
                question = "Is there an easy way to enter a description for the landmark?",
                answer = "LookSee can grab description information from existing signage for the landmark by tapping the blue button inside the short description text box. Then the camera feed will be analyzed for text and brackets will be applied around the text. Click the bracket to add the text to the description box.\n\nWhen only one paragraph is put in brackets, you can adjust the distance from the sign to see if you can capture it all. Or you can follow the same process to capture each paragraph. The image to text function will just add the new paragraphs in the description and will not delete the existing data."
            ),
            FAQItem(
                category = FAQCategory.MEDIA,
                question = "What happens once I upload landmark data?",
                answer = "Data will be sent to our backend processing to extract images of the landmark and background details that will then be used to train our detection model. If you have no internet service, the data will be placed in queue to be uploaded once you have an internet connection.\n\nOnce we extract the images from the video, we will determine if we have enough unique views of the object to support effective training. If not, we will prompt you to load more video footage on our “Manage My Landmarks” page. If there is enough data, we will train our model to recognize the landmark and then activate the landmark and add it to our map of landmarks."
            ),
            FAQItem(
                category = FAQCategory.MEDIA,
                question = "What happens if a landmark does not have enough frames to train?",
                answer = "If needed, LookSee will prompt you for more video data on the “Manage My Landmarks” page about 5-10 minutes after you upload the landmark videos. Loading more video footage will not cost any additional tokens if you upload the video using the “details” link on the landmark on that page.\n\nWhen adding video, make sure to cover every possible perspective where a user could scan the object. Also make sure to take video up close to the target and as far away as feasible. We target 2000 unique frames for training, especially complex objects like large outdoor objects where scanning could occur from all four sides. For simple objects like a picture on a wall, the number of acceptable frames could be as low as 800 frames but the more you can get, the better the detections will work. We recommend uploading more videos, if prompted, at least once to see if the number of usable frames increases before forcing training with a less than optimal frame count."
            ),
            FAQItem(
                category = FAQCategory.ACCOUNT,
                question = "How do promotions work?",
                answer = "Any account with existing landmarks and a valid subscription can add promotions to the pop-up when the landmark detection is clicked. The promotions can be changed anytime and can include web pages. You can select multiple landmarks to add the same promotion. Use the promotion to advertise a sale, provide a discount coupon, or give the user additional timely information about the landmark."
            ),
            FAQItem(
                category = FAQCategory.ACCOUNT,
                question = "Can I delete a landmark?",
                answer = "Yes. Request deletion from the “Manage My Landmarks” page. Once the deletion request has been submitted, the landmark will be taken off the active list and no longer appear on the maps page and will not register during scans. The landmark will not be immediately removed from our model. It will only be deleted once additional training occurs for that model. If immediate deletion is required (inappropriate content, etc.), please submit a request by emailing support."
            ),
            FAQItem(
                category = FAQCategory.GENERAL,
                question = "Is my location data shared?",
                answer = "Your location is only used to place landmarks on the map and is never shared with third parties without your consent."
            )
        )
    }

    val filteredFAQs = remember(faqs, selectedCategory, searchText) {
        var results = faqs
        if (selectedCategory != FAQCategory.ALL) {
            results = results.filter { it.category == selectedCategory }
        }
        val query = searchText.trim().lowercase()
        if (query.isNotEmpty()) {
            results = results.filter {
                it.question.lowercase().contains(query) || it.answer.lowercase().contains(query)
            }
        }
        results
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help & Support", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = backgroundColor),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                }
            )
        },
        containerColor = backgroundColor,
        bottomBar = {
            Surface(
                color = cardColor,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SupportContactButton(
                            title = "Email Support",
                            icon = Icons.Default.Email,
                            color = primaryColor,
                            modifier = Modifier.weight(1f)
                        ) {
                            // 🚀 THE FIX: Uses valid Compose HapticFeedbackType
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:looksee.support@informationoutpost.com")
                            }
                            context.startActivity(Intent.createChooser(intent, "Send Email"))
                        }

                        SupportContactButton(
                            title = "Website",
                            icon = Icons.Default.Language,
                            color = primaryColor,
                            modifier = Modifier.weight(1f)
                        ) {
                            // 🚀 THE FIX: Uses valid Compose HapticFeedbackType
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.informationoutpost.com"))
                            context.startActivity(intent)
                        }
                    }

                    Text(
                        text = "Version 1.0.4 (Build 256) • © 2026 Information Outpost",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141424))
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("Search help topics...", color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) {
                            IconButton(onClick = { searchText = "" }) {
                                Icon(Icons.Default.Cancel, contentDescription = "Clear", tint = Color.Gray)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF1F1F2E),
                        unfocusedContainerColor = Color(0xFF1F1F2E),
                        focusedBorderColor = primaryColor,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(FAQCategory.entries) { category ->
                        val isSelected = selectedCategory == category
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                // 🚀 THE FIX: Uses valid Compose HapticFeedbackType
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedCategory = category
                            },
                            label = { Text(category.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = cardColor,
                                labelColor = Color.Gray,
                                selectedContainerColor = primaryColor,
                                selectedLabelColor = Color.White
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = Color.Transparent,
                                selectedBorderColor = Color.Transparent
                            )
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
            ) {
                if (filteredFAQs.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 60.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                "No matching answers found.",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                "Try adjusting your search or category.",
                                fontSize = 14.sp,
                                color = Color.Gray
                            )
                        }
                    }
                } else {
                    items(filteredFAQs) { faq ->
                        FAQCard(faq = faq, primaryColor = primaryColor)
                    }
                }
            }
        }
    }
}

@Composable
fun FAQCard(faq: FAQItem, primaryColor: Color) {
    var isExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Surface(
        color = cardColor,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // 🚀 THE FIX: Uses valid Compose HapticFeedbackType
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                isExpanded = !isExpanded
            }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = faq.question,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (isExpanded) primaryColor.copy(alpha = 0.15f) else Color(0xFF2C2C3E)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = if (isExpanded) primaryColor else Color.Gray,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(if (isExpanded) 90f else 0f)
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = faq.answer,
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp),
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
fun SupportContactButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    action: () -> Unit
) {
    Button(
        onClick = action,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}