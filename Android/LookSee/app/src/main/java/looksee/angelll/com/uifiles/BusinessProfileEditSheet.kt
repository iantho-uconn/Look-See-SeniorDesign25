package looksee.angelll.com.uifiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import looksee.angelll.com.viewmodels.AuthViewModel
import looksee.angelll.com.ui.theme.AppleBlue
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessProfileEditSheet(
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var draftName by remember { mutableStateOf(vm.storeName) }
    var draftPhone by remember { mutableStateOf(vm.phoneNumber) }
    var draftBio by remember { mutableStateOf(vm.storeBio) }
    var draftLogoUrl by remember { mutableStateOf(vm.storeLogoUrl) }
    var draftWebsite by remember { mutableStateOf(vm.storeWebsite) }
    var draftAddress by remember { mutableStateOf(vm.storeAddress) }

    var isSaving by remember { mutableStateOf(false) }
    var logoBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            logoBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
            draftLogoUrl = ""
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            containerColor = Color(0xFF0F0F1A),
            topBar = {
                TopAppBar(
                    title = { Text("Edit Profile", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F1A)),
                    navigationIcon = {
                        TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.7f)) }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                isSaving = true
                                coroutineScope.launch {
                                    val base64 = logoBitmap?.let { resizeAndConvertToBase64(it) }
                                    val success = vm.updateBusinessProfile(
                                        storeNameInput = draftName,
                                        phoneNumberInput = draftPhone,
                                        storeWebsiteInput = draftWebsite,
                                        storeAddressInput = draftAddress,
                                        storeBioInput = draftBio,
                                        storeLogoUrlInput = draftLogoUrl,
                                        storeLogoBase64Input = base64
                                    )
                                    isSaving = false
                                    if (success) onDismiss()
                                }
                            },
                            enabled = !isSaving
                        ) {
                            if (isSaving) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                            else Text("Save", fontWeight = FontWeight.Bold, color = AppleBlue)
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Store Logo Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("STORE LOGO", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(AppleBlue.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (logoBitmap != null) {
                                Image(bitmap = logoBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else if (draftLogoUrl.isNotEmpty()) {
                                RemoteImage(url = draftLogoUrl, modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(Icons.Default.Storefront, contentDescription = null, tint = AppleBlue)
                            }
                        }
                        
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(if(draftLogoUrl.isEmpty() && logoBitmap == null) "Choose Photo" else "Change Logo", fontWeight = FontWeight.Bold)
                                }
                            }
                            if (draftLogoUrl.isNotEmpty() || logoBitmap != null) {
                                TextButton(onClick = { draftLogoUrl = ""; logoBitmap = null }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) {
                                    Text("Remove Logo", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Basic Info
                EditField(label = "STORE NAME", value = draftName, onValueChange = { draftName = it })
                EditField(label = "SHORT BIO", value = draftBio, onValueChange = { draftBio = it }, singleLine = false)

                // Contact Info
                EditField(label = "PHONE NUMBER", value = draftPhone, onValueChange = { draftPhone = it }, keyboardType = KeyboardType.Phone)
                EditField(label = "WEBSITE", value = draftWebsite, onValueChange = { draftWebsite = it }, keyboardType = KeyboardType.Uri)
                EditField(label = "ADDRESS", value = draftAddress, onValueChange = { draftAddress = it })
            }
        }
    }
}

@Composable
fun EditField(label: String, value: String, onValueChange: (String) -> Unit, singleLine: Boolean = true, keyboardType: KeyboardType = KeyboardType.Text) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )
    }
}
