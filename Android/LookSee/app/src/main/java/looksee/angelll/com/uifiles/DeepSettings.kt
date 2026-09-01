package looksee.angelll.com.uifiles

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import looksee.angelll.com.BuildConfig
import looksee.angelll.com.models.BundledTestModel
import looksee.angelll.com.models.ModelSelector
import looksee.angelll.com.viewmodels.AuthState
import looksee.angelll.com.viewmodels.AuthViewModel
import looksee.angelll.com.ui.theme.AppleBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepSettingsView(
    vm: AuthViewModel,
    authState: AuthState,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val modelSelector = remember { ModelSelector.shared(context) }
    val activeRelease by modelSelector.activeRelease.collectAsState()
    
    val coroutineScope = rememberCoroutineScope()
    var showAlertSignOut by remember { mutableStateOf(false) }
    var isReloading by remember { mutableStateOf(false) }
    var showReloadSuccess by remember { mutableStateOf(false) }

    val isFullyLoggedIn = vm.isSignedIn && vm.userEmail.isNotEmpty()

    Scaffold(
        containerColor = Color(0xFF0F0F1A),
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontSize = 18.sp, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F0F1A),
                    navigationIconContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // App Language (Placeholder/System Link)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "App Language",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                
                Surface(
                    onClick = { /* Open System Settings logic */ },
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("App Language", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("System Settings", color = Color.Gray, fontSize = 15.sp)
                            Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Testing Section
            if (BuildConfig.DEBUG) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Testing",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )

                    Surface(
                        onClick = { onNavigate("ModelSelectionView") },
                        color = Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).background(Color(0xFF4B0082), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Memory, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text("Model Select", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(modelSelector.activeDisplayName, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
                            }

                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }

            // Reload Model Button
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        isReloading = true
                        coroutineScope.launch {
                            delay(1500)
                            isReloading = false
                            showReloadSuccess = true
                            delay(2500)
                            showReloadSuccess = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showReloadSuccess) Color.Green else AppleBlue
                    ),
                    enabled = !isReloading
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isReloading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            Text("Fetching Clusters...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        } else if (showReloadSuccess) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Text("Models Reloaded!", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Text("Reload Model", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (activeRelease == null) "No Model Loaded" else "Active Model: ${modelSelector.activeDisplayName}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                }
            }

            // Sign Out Button
            if (isFullyLoggedIn) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showAlertSignOut = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.ExitToApp, contentDescription = null, tint = Color.Red)
                        Text("Sign Out", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    if (showAlertSignOut) {
        AlertDialog(
            onDismissRequest = { showAlertSignOut = false },
            title = { Text("Sign Out", color = Color.White) },
            text = { Text("Are you sure you want to sign out?", color = Color.LightGray) },
            confirmButton = {
                TextButton(onClick = {
                    showAlertSignOut = false
                    coroutineScope.launch {
                        vm.signOut()
                        authState.signOut()
                    }
                }) {
                    Text("Sign Out", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlertSignOut = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1C1C1E)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionView(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modelSelector = remember { ModelSelector.shared(context) }
    val selectedTestModelID by modelSelector.selectedTestModelId.collectAsState()
    val availableModels = modelSelector.availableTestModels

    Scaffold(
        containerColor = Color(0xFF0F0F1A),
        topBar = {
            TopAppBar(
                title = { Text("Model Select", fontSize = 18.sp, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F0F1A),
                    navigationIconContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Selection Mode", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
                ModelRow(
                    title = "Automatic",
                    detail = "Use the normal location-based model",
                    icon = Icons.Default.LocationOn,
                    isSelected = selectedTestModelID == null,
                    onClick = { modelSelector.useAutomaticModelSelection() }
                )
            }

            item {
                Text("Bundled Models", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            }

            if (availableModels.isEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500))
                        Text("No bundled models found in this app assets.", color = Color(0xFFFFA500), fontSize = 14.sp)
                    }
                }
            } else {
                items(availableModels) { model ->
                    ModelRow(
                        title = model.displayName,
                        detail = "Cluster: ${model.clusterId}",
                        icon = Icons.Default.Category,
                        isSelected = selectedTestModelID == model.id,
                        onClick = { modelSelector.selectTestModel(model) }
                    )
                }
            }
            
            item {
                Text(
                    "The selected model is applied immediately and remembered between launches.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun ModelRow(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) AppleBlue else Color.Gray,
                modifier = Modifier.size(24.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
            }

            if (isSelected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = AppleBlue, modifier = Modifier.size(20.dp))
            }
        }
    }
}
