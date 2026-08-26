package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amplifyframework.auth.AuthUserAttributeKey
import com.amplifyframework.kotlin.core.Amplify
import kotlinx.coroutines.launch
import looksee.angelll.com.viewmodels.AuthState
import looksee.angelll.com.viewmodels.AuthViewModel

enum class SecurityScreen { MAIN, EMAIL, PASSWORD }

@Composable
fun AccountSecurityView(
    vm: AuthViewModel,
    authState: AuthState,
    onDismiss: () -> Unit
) {
    var currentScreen by remember { mutableStateOf(SecurityScreen.MAIN) }
    var currentEmail by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showDeleteAlert by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val refreshAccount: () -> Unit = {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                Amplify.Auth.fetchAuthSession()
                val attributes = Amplify.Auth.fetchUserAttributes()
                currentEmail = attributes.find { it.key == AuthUserAttributeKey.email() }?.value ?: ""
                vm.fetchUserEmail()
            } catch (e: Exception) {
                errorMessage = e.localizedMessage
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshAccount()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF2F2F7))) {
        when (currentScreen) {
            SecurityScreen.MAIN -> {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Text("Account & Security", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                    }

                    // Email Section
                    item {
                        SettingsSection(header = "Email", footer = "Cognito will send a verification code to the new email address before completing the change.") {
                            SettingsRow(title = "Current email", value = if (currentEmail.isEmpty()) "Not set" else currentEmail)
                            HorizontalDivider()
                            SettingsRow(title = "Change Email", icon = Icons.Default.Email, onClick = { currentScreen = SecurityScreen.EMAIL })
                        }
                    }

                    // Password Section
                    item {
                        SettingsSection(header = "Password", footer = "Changing your password requires your current password.") {
                            SettingsRow(title = "Change Password", icon = Icons.Default.Lock, onClick = { currentScreen = SecurityScreen.PASSWORD })
                        }
                    }

                    if (isLoading) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    if (errorMessage != null) {
                        item {
                            SettingsSection {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(errorMessage ?: "", color = Color(0xFFFFA500))
                                }
                            }
                        }
                    }

                    // Delete Account Section
                    item {
                        SettingsSection(footer = "This action is permanent. All your data and active subscriptions will be lost.") {
                            Text(
                                "Delete Account",
                                color = Color.Red,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showDeleteAlert = true }
                                    .padding(16.dp)
                            )
                        }
                    }
                }
            }
            SecurityScreen.EMAIL -> {
                UpdateEmailView(
                    currentEmail = currentEmail,
                    onCompleted = {
                        refreshAccount()
                        currentScreen = SecurityScreen.MAIN
                    },
                    onBack = { currentScreen = SecurityScreen.MAIN }
                )
            }
            SecurityScreen.PASSWORD -> {
                ChangePasswordView(
                    onBack = { currentScreen = SecurityScreen.MAIN }
                )
            }
        }

        // Delete Alert Dialog
        if (showDeleteAlert) {
            AlertDialog(
                onDismissRequest = { showDeleteAlert = false },
                title = { Text("Delete Account?") },
                text = { Text("Are you sure you want to permanently delete your account? This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteAlert = false
                        isDeleting = true
                        coroutineScope.launch {
                            try {
                                Amplify.Auth.deleteUser()
                            } catch (e: Exception) {
                                println("Failed to delete user: ${e.message}")
                            } finally {
                                // 🚀 YOUR FIX: Removed 'authState' argument to match teammate's viewmodel
                                vm.signOut()
                                isDeleting = false
                                onDismiss()
                            }
                        }
                    }) { Text("Delete", color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAlert = false }) { Text("Cancel") }
                }
            )
        }

        // Loading Overlay
        if (isDeleting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0xFF2C2C2E), RoundedCornerShape(16.dp))
                        .padding(32.dp)
                ) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Deleting Account...", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun UpdateEmailView(currentEmail: String, onCompleted: () -> Unit, onBack: () -> Unit) {
    var newEmail by remember { mutableStateOf("") }
    var confirmationCode by remember { mutableStateOf("") }
    var awaitingConfirmation by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val beginEmailChange: () -> Unit = {
        val cleaned = newEmail.trim().lowercase()
        if (cleaned.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleaned).matches()) {
            isError = true; statusMessage = "Enter a valid email address."
        } else if (cleaned == currentEmail.lowercase()) {
            isError = true; statusMessage = "That is already the email address on this account."
        } else {
            isWorking = true; isError = false; statusMessage = null
            coroutineScope.launch {
                try {
                    val attribute = com.amplifyframework.auth.AuthUserAttribute(AuthUserAttributeKey.email(), cleaned)
                    val result = Amplify.Auth.updateUserAttribute(attribute)

                    if (!result.isUpdated) {
                        awaitingConfirmation = true
                        statusMessage = "A verification code was sent to the new email address."
                    } else {
                        onCompleted()
                    }
                } catch (e: Exception) {
                    isError = true; statusMessage = e.localizedMessage
                } finally {
                    isWorking = false
                }
            }
        }
    }

    val confirmEmailChange: () -> Unit = {
        isWorking = true; isError = false; statusMessage = null
        coroutineScope.launch {
            try {
                Amplify.Auth.confirmUserAttribute(AuthUserAttributeKey.email(), confirmationCode.trim())
                onCompleted()
            } catch (e: Exception) {
                isError = true; statusMessage = e.localizedMessage
            } finally {
                isWorking = false
            }
        }
    }

    val resendCode: () -> Unit = {
        isWorking = true; isError = false; statusMessage = null
        coroutineScope.launch {
            try {
                Amplify.Auth.resendUserAttributeConfirmationCode(AuthUserAttributeKey.email())
                statusMessage = "A new verification code was sent."
            } catch (e: Exception) {
                isError = true; statusMessage = e.localizedMessage
            } finally {
                isWorking = false
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Change Email", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
        }

        if (currentEmail.isNotEmpty()) {
            item {
                SettingsSection(header = "Current Email") {
                    Text(currentEmail, color = Color.Gray, modifier = Modifier.padding(16.dp))
                }
            }
        }

        item {
            SettingsSection(
                header = if (awaitingConfirmation) "Verify New Email" else "New Email",
                footer = if (awaitingConfirmation) "Enter the code sent to the new email address." else "Your current account remains signed in while the new address is verified."
            ) {
                if (awaitingConfirmation) {
                    OutlinedTextField(
                        value = confirmationCode,
                        onValueChange = { confirmationCode = it },
                        placeholder = { Text("Verification code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Button(onClick = confirmEmailChange, enabled = !isWorking && confirmationCode.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Verify Email")
                    }
                    TextButton(onClick = resendCode, enabled = !isWorking, modifier = Modifier.fillMaxWidth()) {
                        Text("Resend Code")
                    }
                } else {
                    OutlinedTextField(
                        value = newEmail,
                        onValueChange = { newEmail = it },
                        placeholder = { Text("New email address") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Button(onClick = beginEmailChange, enabled = !isWorking && newEmail.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Send Verification Code")
                    }
                }
            }
        }

        if (isWorking) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        if (statusMessage != null) {
            item {
                SettingsSection {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        Icon(if (isError) Icons.Default.Warning else Icons.Default.CheckCircle, contentDescription = null, tint = if (isError) Color(0xFFFFA500) else Color(0xFF34C759))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(statusMessage ?: "", color = if (isError) Color(0xFFFFA500) else Color(0xFF34C759))
                    }
                }
            }
        }

        item {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Back")
            }
        }
    }
}

@Composable
fun ChangePasswordView(onBack: () -> Unit) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmedPassword by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var passwordChanged by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val updatePassword: () -> Unit = {
        if (newPassword != confirmedPassword) {
            isError = true; statusMessage = "The new passwords do not match."
        } else if (currentPassword == newPassword) {
            isError = true; statusMessage = "Your new password must be different from your current password."
        } else {
            isWorking = true; isError = false; statusMessage = null
            coroutineScope.launch {
                try {
                    Amplify.Auth.updatePassword(currentPassword, newPassword)
                    currentPassword = ""; newPassword = ""; confirmedPassword = ""
                    passwordChanged = true
                } catch (e: Exception) {
                    isError = true; statusMessage = e.localizedMessage
                } finally {
                    isWorking = false
                }
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Change Password", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
        }

        if (passwordChanged) {
            item {
                SettingsSection {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34C759))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Password updated successfully.", color = Color(0xFF34C759))
                    }
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Done")
                    }
                }
            }
        } else {
            item {
                SettingsSection(header = "Password", footer = "The new password must satisfy the password policy configured in your Cognito user pool.") {
                    OutlinedTextField(
                        value = currentPassword, onValueChange = { currentPassword = it },
                        placeholder = { Text("Current password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    OutlinedTextField(
                        value = newPassword, onValueChange = { newPassword = it },
                        placeholder = { Text("New password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    OutlinedTextField(
                        value = confirmedPassword, onValueChange = { confirmedPassword = it },
                        placeholder = { Text("Confirm new password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Button(
                        onClick = updatePassword,
                        enabled = !isWorking && currentPassword.isNotBlank() && newPassword.isNotBlank() && confirmedPassword.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Text("Update Password")
                    }
                }
            }
        }

        if (isWorking) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        if (statusMessage != null && !passwordChanged) {
            item {
                SettingsSection {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA500))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(statusMessage ?: "", color = Color(0xFFFFA500))
                    }
                }
            }
        }

        if (!passwordChanged) {
            item {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text("Cancel")
                }
            }
        }
    }
}

// Custom modifiers for iOS Form layout style
@Composable
fun SettingsSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        if (header != null) {
            Text(header.uppercase(), fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        }
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White)) {
            content()
        }
        if (footer != null) {
            Text(footer, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
        }
    }
}

@Composable
fun SettingsRow(title: String, value: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }.padding(16.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color(0xFF007AFF), modifier = Modifier.padding(end = 12.dp))
        }
        Text(title, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, fontSize = 17.sp, color = Color.Gray)
        }
    }
}