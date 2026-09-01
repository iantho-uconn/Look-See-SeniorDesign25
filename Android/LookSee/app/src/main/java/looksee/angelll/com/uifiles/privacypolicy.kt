package looksee.angelll.com.uifiles

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Expected ghost error until the txt file is added to res/raw
import looksee.angelll.com.R

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("DiscouragedApi")
@Composable
fun PrivacyPolicy(onDismiss: () -> Unit) {
    val context = LocalContext.current

    val termsText = remember {
        try {
            context.resources.openRawResource(R.raw.privacypolicy)
                .bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "Privacy Policy could not be loaded."
        }
    }

    Scaffold(
        containerColor = Color(0xFF0F0F1A),
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy", fontSize = 18.sp, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F0F1A),
                    navigationIconContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Text(
            text = termsText,
            fontSize = 15.sp,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        )
    }
}