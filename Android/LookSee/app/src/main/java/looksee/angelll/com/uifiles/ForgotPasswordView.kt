package looksee.angelll.com.uifiles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape // 🚀 FIXED: Added missing import
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amplifyframework.kotlin.core.Amplify
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordView(
    initialUsername: String = "",
    onDismiss: () -> Unit
) {
    var username by remember { mutableStateOf(initialUsername) }
    var confirmationCode by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmedPassword by remember { mutableStateOf("") }

    var awaitingCode by remember { mutableStateOf(false) }
    var resetCompleted by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val cleanedUsername = username.trim()

    val canSubmit = if (awaitingCode) {
        cleanedUsername.isNotEmpty() && confirmationCode.trim().isNotEmpty() && newPassword.isNotEmpty() && confirmedPassword.isNotEmpty()
    } else {
        cleanedUsername.isNotEmpty()
    }

    fun friendlyMessage(e: Exception): String {
        val fullDescription = e.toString()
        return when {
            fullDescription.contains("CodeMismatchException") -> "That verification code is incorrect. Please try again."
            fullDescription.contains("ExpiredCodeException") -> "That verification code has expired. Send a new code and try again."
            fullDescription.contains("LimitExceededException") || fullDescription.contains("TooManyRequestsException") -> "Too many attempts. Please wait a moment and try again."
            fullDescription.contains("InvalidPasswordException") -> "The new password does not meet the Cognito password requirements."
            else -> e.localizedMessage ?: "An unknown error occurred."
        }
    }

    val requestReset: () -> Unit = {
        if (cleanedUsername.isNotEmpty()) {
            isWorking = true
            isError = false
            statusMessage = null

            coroutineScope.launch {
                try {
                    val result = Amplify.Auth.resetPassword(cleanedUsername)

                    // 🚀 FIXED: Bypassed the strict Enum import by checking the name directly
                    if (result.nextStep.resetPasswordStep.name == "DONE") {
                        resetCompleted = true
                    } else {
                        awaitingCode = true
                        statusMessage = "A password-reset code was sent to your verified email."
                    }
                } catch (e: Exception) {
                    isError = true
                    statusMessage = friendlyMessage(e)
                } finally {
                    isWorking = false
                }
            }
        }
    }

    val confirmReset: () -> Unit = {
        if (newPassword != confirmedPassword) {
            isError = true
            statusMessage = "The new passwords do not match."
        } else {
            isWorking = true
            isError = false
            statusMessage = null

            coroutineScope.launch {
                try {
                    Amplify.Auth.confirmResetPassword(cleanedUsername, newPassword, confirmationCode.trim())
                    resetCompleted = true
                } catch (e: Exception) {
                    isError = true
                    statusMessage = friendlyMessage(e)
                } finally {
                    isWorking = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Forgot Password") },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !isWorking) {
                        Text("Cancel", color = Color(0xFF007AFF), modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            )
        },
        containerColor = Color(0xFFF2F2F7)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (resetCompleted) {
                item {
                    SettingsSection(footer = "Use your new password the next time you sign in.") {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34C759))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Your password has been reset successfully.", color = Color(0xFF34C759), fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Return to Sign In", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                item {
                    SettingsSection(
                        header = if (awaitingCode) "Reset Password" else "Find Your Account",
                        footer = if (awaitingCode) "Enter the verification code Cognito sent to your email." else "We will send a password-reset code to the verified email on your account."
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            placeholder = { Text("Email address") },
                            enabled = !awaitingCode,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        if (awaitingCode) {
                            OutlinedTextField(
                                value = confirmationCode,
                                onValueChange = { confirmationCode = it },
                                placeholder = { Text("Verification code") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            )

                            OutlinedTextField(
                                value = newPassword,
                                onValueChange = { newPassword = it },
                                placeholder = { Text("New password") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            )

                            OutlinedTextField(
                                value = confirmedPassword,
                                onValueChange = { confirmedPassword = it },
                                placeholder = { Text("Confirm new password") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { if (awaitingCode) confirmReset() else requestReset() },
                            enabled = canSubmit && !isWorking,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (awaitingCode) "Reset Password" else "Send Reset Code", fontWeight = FontWeight.Bold)
                        }

                        if (awaitingCode) {
                            Button(
                                onClick = requestReset,
                                enabled = !isWorking,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Send Another Code", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (isWorking) {
                item {
                    SettingsSection {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            if (statusMessage != null && !resetCompleted) {
                item {
                    SettingsSection {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (isError) Color(0xFFFFA500) else Color(0xFF34C759)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(statusMessage!!, color = if (isError) Color(0xFFFFA500) else Color(0xFF34C759), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}