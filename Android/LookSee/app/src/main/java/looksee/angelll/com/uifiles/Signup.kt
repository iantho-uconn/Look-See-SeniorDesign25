package looksee.angelll.com.uifiles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amplifyframework.kotlin.core.Amplify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import looksee.angelll.com.services.AuthService
import looksee.angelll.com.ui.theme.LookSeeCard
import looksee.angelll.com.viewmodels.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Signup(
    vm: AuthViewModel,
    onSignupSuccess: () -> Unit,
    onGoToLogin: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var isBusinessAccount by remember { mutableStateOf(false) }

    var showVerification by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    val isValidPassword = { pass: String ->
        val passwordRegex = "^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$".toRegex()
        passwordRegex.matches(pass)
    }

    val signUp: () -> Unit = {
        isLoading = true
        message = ""
        val group = if (isBusinessAccount) "business-users" else "authenticated-users"
        coroutineScope.launch {
            try {
                val result = AuthService.signUp(email, password, email, group)
                if (result.isSignUpComplete) {
                    vm.pendingUsernameToSave = username
                    message = "Account created and verified! Routing to login..."
                    delay(1200)
                    onSignupSuccess()
                } else {
                    showVerification = true
                    message = "Code sent! Please check your email."
                }
            } catch (e: Exception) {
                message = e.localizedMessage ?: "Signup failed."
            } finally {
                isLoading = false
            }
        }
    }

    val verifyCode: () -> Unit = {
        isLoading = true
        message = ""
        coroutineScope.launch {
            try {
                val result = Amplify.Auth.confirmSignUp(email, verificationCode)
                if (result.isSignUpComplete) {
                    vm.pendingUsernameToSave = username
                    message = "Verification successful! Routing to login..."
                    delay(1500)
                    onSignupSuccess()
                } else {
                    message = "Verification incomplete. Please check the code."
                }
            } catch (e: Exception) {
                message = e.localizedMessage ?: "Verification failed."
            } finally {
                isLoading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A)),
        contentAlignment = Alignment.TopCenter
    ) {
        // Glow effect
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset(y = (-100).dp)
                .background(Brush.radialGradient(listOf(Color(0xFF387DFF).copy(alpha = 0.12f), Color.Transparent)))
                .blur(60.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Image(
                    painter = painterResource(id = looksee.angelll.com.R.drawable.looksee_logo),
                    contentDescription = "LookSee Logo",
                    modifier = Modifier.size(350.dp)
                )
                Text(
                    text = if (showVerification) "Check your email" else "Create your account",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (!showVerification) {
                // SIGNUP FORM
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    VStackLabel(label = "Unique Username") {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { newValue ->
                                username = newValue.lowercase().filter { it.isLetterOrDigit() || it == '_' }
                            },
                            placeholder = { Text("username", color = Color.Gray) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF2D2D3D),
                                unfocusedContainerColor = Color(0xFF2D2D3D),
                                focusedBorderColor = Color(0xFF387DFF).copy(alpha = 0.3f),
                                unfocusedBorderColor = Color(0xFF387DFF).copy(alpha = 0.3f)
                            )
                        )
                    }

                    VStackLabel(label = "Email") {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            placeholder = { Text("you@example.com", color = Color.Gray) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF2D2D3D),
                                unfocusedContainerColor = Color(0xFF2D2D3D),
                                focusedBorderColor = Color(0xFF387DFF).copy(alpha = 0.3f),
                                unfocusedBorderColor = Color(0xFF387DFF).copy(alpha = 0.3f)
                            )
                        )
                    }

                    VStackLabel(label = "Password") {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = { Text("••••••••", color = Color.Gray) },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF2D2D3D),
                                unfocusedContainerColor = Color(0xFF2D2D3D),
                                focusedBorderColor = Color(0xFF387DFF).copy(alpha = 0.3f),
                                unfocusedBorderColor = Color(0xFF387DFF).copy(alpha = 0.3f)
                            )
                        )
                        if (password.isNotEmpty() && !isValidPassword(password)) {
                            Text(
                                "Requires 8+ chars, 1 uppercase, 1 lowercase, 1 number, and 1 special char.",
                                color = Color.Red,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )
                        }
                    }

                    // Business Account Toggle
                    LookSeeCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Business Account", color = Color.White, fontSize = 15.sp)
                                Text("Enables promotion management and video uploads", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                            }
                            Switch(
                                checked = isBusinessAccount,
                                onCheckedChange = { isBusinessAccount = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF007AFF))
                            )
                        }
                    }
                }
            } else {
                // VERIFICATION FORM
                VStackLabel(label = "Enter 6-Digit Code") {
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { if (it.length <= 6) verificationCode = it },
                        placeholder = { Text("123456", color = Color.Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF2D2D3D),
                            unfocusedContainerColor = Color(0xFF2D2D3D),
                            focusedBorderColor = Color(0xFF387DFF).copy(alpha = 0.3f),
                            unfocusedBorderColor = Color(0xFF387DFF).copy(alpha = 0.3f)
                        )
                    )
                }
            }

            if (message.isNotEmpty()) {
                Text(
                    message,
                    color = if (message.contains("successful") || message.contains("verified")) Color.Green else Color.Red,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            val canSubmit = if (showVerification) verificationCode.length >= 6 else (email.isNotEmpty() && username.isNotEmpty() && isValidPassword(password))

            Button(
                onClick = { if (showVerification) verifyCode() else signUp() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF), disabledContainerColor = Color(0xFF007AFF).copy(alpha = 0.5f)),
                enabled = canSubmit && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (showVerification) "Verify Account" else "Create Account", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (!showVerification) {
                TextButton(onClick = onGoToLogin, modifier = Modifier.padding(top = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Already have an account?", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                        Text("Sign in", color = Color(0xFF007AFF), fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(52.dp))
        }
    }
}

@Composable
fun VStackLabel(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        content()
    }
}
