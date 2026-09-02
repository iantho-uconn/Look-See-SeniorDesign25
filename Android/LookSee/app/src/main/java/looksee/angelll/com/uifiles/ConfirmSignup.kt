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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amplifyframework.kotlin.core.Amplify
import kotlinx.coroutines.launch
import looksee.angelll.com.ui.theme.LookSeeCard

@Composable
fun ConfirmSignup(
    email: String,
    deliveryHint: String? = null,
    onConfirmed: () -> Unit
) {
    var username by remember { mutableStateOf(email) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val confirmAccount: () -> Unit = {
        isLoading = true
        message = ""

        coroutineScope.launch {
            try {
                val result = Amplify.Auth.confirmSignUp(
                    username.trim(),
                    code.trim()
                )

                if (!result.isSignUpComplete) {
                    confirmed = false
                    message = "Cognito requires another confirmation step."
                } else {
                    confirmed = true
                    message = "Account confirmed successfully!"
                    onConfirmed()
                }
            } catch (e: Exception) {
                confirmed = false
                message = e.localizedMessage ?: "An error occurred"
            } finally {
                isLoading = false
            }
        }
    }

    val resendCode: () -> Unit = {
        isLoading = true
        message = ""

        coroutineScope.launch {
            try {
                Amplify.Auth.resendSignUpCode(username.trim())
                confirmed = false
                message = "A new verification code was sent."
            } catch (e: Exception) {
                confirmed = false
                message = e.localizedMessage ?: "An error occurred"
            } finally {
                isLoading = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F1A))) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item { Spacer(modifier = Modifier.height(40.dp)) }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Confirm Account",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = deliveryHint ?: "Enter the verification code sent to the email address or phone number selected during sign-up.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        placeholder = { Text("Email, phone number, or username", color = Color.Gray) },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedBorderColor = Color(0xFF007AFF),
                            unfocusedBorderColor = Color.Transparent
                        )
                    )

                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        placeholder = { Text("Verification Code", color = Color.Gray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedBorderColor = Color(0xFF007AFF),
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = confirmAccount,
                        enabled = !isLoading && username.trim().isNotEmpty() && code.trim().isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF007AFF),
                            disabledContainerColor = Color(0xFF007AFF).copy(alpha = 0.3f)
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        } else {
                            Text("Confirm", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    TextButton(
                        onClick = resendCode,
                        enabled = !isLoading && username.trim().isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Resend Code", color = Color(0xFF007AFF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (message.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (confirmed) Color(0xFF34C759).copy(alpha = 0.1f) else Color.Red.copy(alpha = 0.1f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (confirmed) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (confirmed) Color(0xFF34C759) else Color(0xFFFFA500)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = message,
                                color = if (confirmed) Color(0xFF34C759) else Color(0xFFFFA500),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            if (confirmed) {
                                Text(
                                    text = "You can now sign in.",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
