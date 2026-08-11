package looksee.angelll.com.uifiles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            SettingsSection(
                header = "Confirm Account",
                footer = deliveryHint ?: "Enter the verification code sent to the email address or phone number selected during sign-up."
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = { Text("Email, phone number, or username") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    placeholder = { Text("Verification Code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = confirmAccount,
                    enabled = !isLoading && username.trim().isNotEmpty() && code.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                    } else {
                        Text("Confirm", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = resendCode,
                    enabled = !isLoading && username.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("Resend Code", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (message.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (confirmed) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (confirmed) Color(0xFF34C759) else Color(0xFFFFA500)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(message, color = if (confirmed) Color(0xFF34C759) else Color(0xFFFFA500), fontWeight = FontWeight.Bold)
                }

                if (confirmed) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("You can now sign in.", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}