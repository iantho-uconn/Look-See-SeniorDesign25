package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*
import looksee.angelll.com.detection.*

@Composable
fun ModelLoadingScreen(
    onModelsLoaded: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val modelService = remember { ModelService.shared(context) }
    val locationManager = remember { LocationManager(context) }
    
    val modelState by modelService.state.collectAsState()
    val locationState by locationManager.state.collectAsState()
    val downloadProgress by modelService.downloadProgress.collectAsState()
    
    var showSkipButton by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(8000) // Show skip button after 8 seconds of waiting
        showSkipButton = true
    }
    
    LaunchedEffect(Unit) {
        if (locationManager.hasLocationPermission()) {
            locationManager.start()
        }
    }
    
    LaunchedEffect(locationState) {
        val state = locationState
        if (state is LookSeeLocationState.Ready) {
            modelService.loadModels(state.fix.latitude, state.fix.longitude)
        }
    }
    
    LaunchedEffect(modelState) {
        if (modelState is ModelState.Loaded) {
            onModelsLoaded()
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1C1C24)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.CloudDownload,
                contentDescription = null,
                tint = Color(0xFF007AFF),
                modifier = Modifier.size(64.dp)
            )
            
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = when (modelState) {
                        is ModelState.Loading -> "Loading Models"
                        is ModelState.Failed -> "Error Loading Models"
                        else -> "Initializing"
                    },
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = when (val state = modelState) {
                        is ModelState.Failed -> state.message
                        is ModelState.Loading -> "Fetching latest landmark data for your area..."
                        else -> "Setting up your LookSee experience."
                    },
                    color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            
            if (modelState is ModelState.Loading) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Color(0xFF007AFF),
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                }
            }
            
            if (modelState is ModelState.Failed) {
                Button(
                    onClick = {
                        val state = locationState
                        if (state is LookSeeLocationState.Ready) {
                            coroutineScope.launch {
                                modelService.loadModels(state.fix.latitude, state.fix.longitude)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Retry", color = Color.White)
                }
            }
            
            if (showSkipButton && modelState !is ModelState.Loaded) {
                TextButton(
                    onClick = { onModelsLoaded() },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Skip for now", color = Color.Gray)
                }
            }
        }
    }
}
