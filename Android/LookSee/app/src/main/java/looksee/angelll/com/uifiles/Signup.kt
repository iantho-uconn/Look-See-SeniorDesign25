package looksee.angelll.com.uifiles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

// Expected to be unresolved until Amplify is added!
import com.amplifyframework.kotlin.core.Amplify

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Signup(
    vm: AuthViewModel,
    onSignupSuccess: (String) -> Unit,
    onGoToLogin: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }

    var message by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showVerification by remember { mutableStateOf(false) }
    var isBusinessAccount by remember { mutableStateOf(false) }

    // Colors exactly mapped from your Swift RGB values
    val bgDark = Color(0xFF0F0F1A) // 0.06, 0.06, 0.10
    val brandBlue = Color(0xFF387DFF) // 0.22, 0.49, 1.00
    val fieldBg = Color(0xFF2E2E3D) // 0.18, 0.18, 0.24

    fun isValidPassword(pass: String): Boolean {
        val passwordRegex = """^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$""".toRegex()
        return passwordRegex.matches(pass)
    }

    val isFormInvalid = if (showVerification) {
        verificationCode.length < 6
    } else {
        email.isEmpty() || username.isEmpty() || !isValidPassword(password)
    }

    // MARK: - AWS Logic
    fun signUp() {
        isLoading = true
        message = ""
        val group = if (isBusinessAccount) "business-users" else "authenticated-users"

        coroutineScope.launch {
            try {
                val result = AuthService.shared.signUp(
                    username = email,
                    password = password,
                    email = email,
                    group = group
                )

                if (result.isSignUpComplete) {
                    // 🚀 FIXED: Memorize username to be processed AFTER login!
                    vm.pendingUsernameToSave = username
                    message = "Account created and verified! Routing to login..."
                    delay(1200.milliseconds)
                    onSignupSuccess(email)
                } else {
                    showVerification = true
                    message = "Code sent! Please check your email."
                }
            } catch (e: Exception) {
                message = e.localizedMessage ?: "An error occurred."
            } finally {
                isLoading = false
            }
        }
    }

    fun verifyCode() {
        isLoading = true
        message = ""
        coroutineScope.launch {
            try {
                val result = Amplify.Auth.confirmSignUp(email, verificationCode)
                if (result.isSignUpComplete) {
                    // 🚀 FIXED: Memorize username to be processed AFTER login!
                    vm.pendingUsernameToSave = username
                    message = "Verification successful! Routing to login..."
                    delay(1500.milliseconds)
                    onSignupSuccess(email)
                } else {
                    message = "Verification incomplete. Please check the code."
                }
            } catch (e: Exception) {
                message = e.localizedMessage ?: "An error occurred."
            } finally {
                isLoading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgDark)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                focusManager.clearFocus()
            }
    ) {
        // Blurred Background Circle
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset(y = (-100).dp)
                .align(Alignment.TopCenter)
                .blur(radius = 60.dp)
                .background(brandBlue.copy(alpha = 0.12f), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Fully qualified R package to prevent compiler panic before image is added
                Image(
                    painter = painterResource(id = looksee.angelll.com.R.drawable.looksee_logo),
                    contentDescription = "LookSee Logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 350.dp, height = 300.dp)
                )
                Text(
                    text = if (showVerification) "Check your email" else "Create your account",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }

            // Forms
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .padding(bottom = 52.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!showVerification) {
                    // MARK: - SIGNUP FORM

                    // Username
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Unique Username", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        TextField(
                            value = username,
                            onValueChange = { newValue ->
                                username = newValue.lowercase(Locale.getDefault()).filter { "abcdefghijklmnopqrstuvwxyz0123456789_".contains(it) }
                            },
                            placeholder = { Text("username", color = Color.Gray) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = fieldBg,
                                unfocusedContainerColor = fieldBg,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, brandBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        )
                    }

                    // Email
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Email", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        TextField(
                            value = email,
                            onValueChange = { email = it },
                            placeholder = { Text("you@example.com", color = Color.Gray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = fieldBg,
                                unfocusedContainerColor = fieldBg,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, brandBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        )
                    }

                    // Password
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Password", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        TextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = { Text("••••••••", color = Color.Gray) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, capitalization = KeyboardCapitalization.None),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = fieldBg,
                                unfocusedContainerColor = fieldBg,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, brandBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        )
                        if (password.isNotEmpty() && !isValidPassword(password)) {
                            Text(
                                "Requires 8+ chars, 1 uppercase, 1 lowercase, 1 number, and 1 special char.",
                                fontSize = 11.sp, color = Color.Red, modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }

                    // Business Account Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(fieldBg, RoundedCornerShape(12.dp))
                            .border(0.5.dp, brandBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Business Account", fontSize = 14.sp, color = Color.White)
                            Text("Enables promotion management and video uploads", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                        }
                        Switch(
                            checked = isBusinessAccount,
                            onCheckedChange = { isBusinessAccount = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = brandBlue)
                        )
                    }
                } else {
                    // MARK: - VERIFICATION FORM
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Enter 6-Digit Code", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        TextField(
                            value = verificationCode,
                            onValueChange = { verificationCode = it },
                            placeholder = { Text("123456", color = Color.Gray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                color = Color.White
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = fieldBg,
                                unfocusedContainerColor = fieldBg,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, brandBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        )
                    }
                }

                // Message Text
                if (message.isNotEmpty()) {
                    val isSuccess = message.contains("successful", ignoreCase = true) || message.contains("verified", ignoreCase = true)
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = if (isSuccess) Color.Green else Color.Red,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Action Button
                Button(
                    onClick = { if (showVerification) verifyCode() else signUp() },
                    enabled = !(isLoading || isFormInvalid),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brandBlue,
                        disabledContainerColor = brandBlue.copy(alpha = 0.5f)
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (showVerification) "Verify Account" else "Create Account",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Sign in Link
                if (!showVerification) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                onGoToLogin()
                            },
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("Already have an account? ", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                        Text("Sign in", fontSize = 12.sp, color = brandBlue)
                    }
                }
            }
        }
    }
}