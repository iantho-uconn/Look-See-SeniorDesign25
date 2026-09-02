package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import looksee.angelll.com.ui.theme.LookSeeCard

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
                title = { Text("Forgot Password", color = Color.White) },
                navigationIcon = {
                    TextButton(onClick = onDismiss, enabled = !isWorking) {
                        Text("Cancel", color = Color(0xFF007AFF))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            if (resetCompleted) {
                item {
                    LookSeeCard {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34C759))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Your password has been reset successfully.", color = Color(0xFF34C759), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Text("Use your new password the next time you sign in.", color = Color.Gray, fontSize = 12.sp)
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Return to Sign In", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (awaitingCode) "Reset Password" else "Find Your Account",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                        LookSeeCard {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                placeholder = { Text("Email address", color = Color.Gray) },
                                enabled = !awaitingCode,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedContainerColor = Color(0xFF2D2D3D),
                                    unfocusedContainerColor = Color(0xFF2D2D3D),
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                )
                            )

                            if (awaitingCode) {
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = confirmationCode,
                                    onValueChange = { confirmationCode = it },
                                    placeholder = { Text("Verification code", color = Color.Gray) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color(0xFF2D2D3D),
                                        unfocusedContainerColor = Color(0xFF2D2D3D),
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent
                                    )
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = newPassword,
                                    onValueChange = { newPassword = it },
                                    placeholder = { Text("New password", color = Color.Gray) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color(0xFF2D2D3D),
                                        unfocusedContainerColor = Color(0xFF2D2D3D),
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent
                                    )
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = confirmedPassword,
                                    onValueChange = { confirmedPassword = it },
                                    placeholder = { Text("Confirm new password", color = Color.Gray) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color(0xFF2D2D3D),
                                        unfocusedContainerColor = Color(0xFF2D2D3D),
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent
                                    )
                                )
                            }
                        }
                        Text(
                            text = if (awaitingCode) "Enter the verification code Cognito sent to your email." else "We will send a password-reset code to the verified email on your account.",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(
                            onClick = { if (awaitingCode) confirmReset() else requestReset() },
                            enabled = canSubmit && !isWorking,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF), disabledContainerColor = Color(0xFF007AFF).copy(alpha = 0.5f))
                        ) {
                            if (isWorking) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Text(if (awaitingCode) "Reset Password" else "Send Reset Code", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        if (awaitingCode) {
                            TextButton(
                                onClick = requestReset,
                                enabled = !isWorking,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Send Another Code", color = Color(0xFF007AFF), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            if (statusMessage != null && !resetCompleted) {
                item {
                    LookSeeCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
