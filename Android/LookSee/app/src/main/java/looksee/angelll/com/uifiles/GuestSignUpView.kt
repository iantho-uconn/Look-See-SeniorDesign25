package looksee.angelll.com.uifiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amplifyframework.kotlin.core.Amplify
import kotlinx.coroutines.launch

@Composable
fun GuestSignUpScreen(
    viewModel: AuthViewModel, // Pass your translated ViewModel here
    onDismiss: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }

    var showVerification by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Computed/Sanitized Properties
    val sanitizedEmail = email.trim().lowercase()
    val sanitizedCode = verificationCode.trim()

    // Fixed Regex warning using triple quotes
    val passwordRegex = Regex("""^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$""")
    val isValidPassword = passwordRegex.matches(password)

    val isFormValid = sanitizedEmail.isNotEmpty() &&
            phoneNumber.trim().isNotEmpty() &&
            isValidPassword

    val isVerificationValid = sanitizedCode.length >= 6

    // Dark Background Color matching iOS: Color(red: 0.06, green: 0.06, blue: 0.10)
    val backgroundColor = Color(0xFF0F0F1A)
    val buttonActiveColor = Color(0xFF387DFF) // ~ Color(red: 0.22, green: 0.49, blue: 1.00)
    val buttonDisabledColor = Color.Gray.copy(alpha = 0.3f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                focusManager.clearFocus()
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar (Toolbar leading icon)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color.Gray.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (showVerification) "Verify Email" else "Create Account",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (showVerification) "Enter the 6-digit code we sent to $sanitizedEmail."
                        else "Set up your LookSee identity to proceed to secure checkout.",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }

                // Form Fields
                AnimatedVisibility(
                    visible = !showVerification,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {

                        // Email Field
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Email Address", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                            LookSeeTextField(
                                value = email,
                                onValueChange = { email = it },
                                placeholder = "name@example.com",
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false // Fixed deprecation warning
                                )
                            )
                        }

                        // Password Field
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Password", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                            LookSeeTextField(
                                value = password,
                                onValueChange = { password = it },
                                placeholder = "Create a strong password",
                                isPassword = true
                            )
                            if (password.isNotEmpty() && !isValidPassword) {
                                Text(
                                    text = "Requires 8+ chars, 1 uppercase, 1 lowercase, 1 number, and 1 special char.",
                                    fontSize = 11.sp,
                                    color = Color.Red,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }

                        // Phone Number Field
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Phone Number", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                            LookSeeTextField(
                                value = phoneNumber,
                                onValueChange = { newValue ->
                                    val filtered = newValue.filter { it.isDigit() }
                                    if (filtered.length <= 10) {
                                        phoneNumber = filtered
                                    }
                                },
                                placeholder = "123-456-7890",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                            )
                        }
                    }
                }

                // Verification Code Field
                AnimatedVisibility(
                    visible = showVerification,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Verification Code", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                        LookSeeTextField(
                            value = verificationCode,
                            onValueChange = { verificationCode = it },
                            placeholder = "123456",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isMonospaced = true,
                            centerText = true
                        )
                    }
                }

                // Error Message
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Red,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(8.dp)) // Extra padding before button

                // Submit Button
                val isButtonEnabled = (if (showVerification) isVerificationValid else isFormValid) && !isProcessing

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (showVerification) {
                            // verifyCodeAndSignIn()
                            isProcessing = true
                            errorMessage = ""
                            coroutineScope.launch {
                                try {
                                    val result = Amplify.Auth.confirmSignUp(sanitizedEmail, sanitizedCode)
                                    if (result.isSignUpComplete) {
                                        Amplify.Auth.signOut()
                                        val signInResult = AuthService.shared.signIn(sanitizedEmail, password)
                                        if (signInResult.isSignedIn) {
                                            viewModel.fetchUserDetails()
                                            viewModel.fetchUserUsageStats()
                                            viewModel.isSignedIn = true
                                            onDismiss()
                                        } else {
                                            errorMessage = "Verified successfully, but please log in manually from the main menu."
                                        }
                                    } else {
                                        errorMessage = "Verification incomplete. Please check the code."
                                    }
                                } catch (e: Exception) {
                                    errorMessage = e.localizedMessage ?: "An error occurred"
                                } finally {
                                    isProcessing = false
                                }
                            }
                        } else {
                            // signUp()
                            isProcessing = true
                            errorMessage = ""
                            coroutineScope.launch {
                                try {
                                    // NOTE: Ensure AuthService in Kotlin matches this parameter signature
                                    val result = AuthService.shared.signUp(sanitizedEmail, password, sanitizedEmail, "business-users")
                                    if (result.isSignUpComplete) {
                                        Amplify.Auth.signOut()
                                        val signInResult = AuthService.shared.signIn(sanitizedEmail, password)
                                        if (signInResult.isSignedIn) {
                                            viewModel.fetchUserDetails()
                                            viewModel.fetchUserUsageStats()
                                            viewModel.isSignedIn = true
                                            onDismiss()
                                        } else {
                                            errorMessage = "Account created successfully! Please log in."
                                        }
                                    } else {
                                        showVerification = true
                                    }
                                } catch (e: Exception) {
                                    errorMessage = e.localizedMessage ?: "An error occurred"
                                } finally {
                                    isProcessing = false
                                }
                            }
                        }
                    },
                    enabled = isButtonEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonActiveColor,
                        disabledContainerColor = buttonDisabledColor,
                        contentColor = Color.White,
                        disabledContentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp) // matches SwiftUI vertical padding size
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = if (showVerification) "Verify & Continue" else "Create Account",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// Reusable TextField matching the custom LookSee Swift Modifier
@Composable
fun LookSeeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isPassword: Boolean = false,
    isMonospaced: Boolean = false,
    centerText: Boolean = false
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        keyboardOptions = keyboardOptions,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        textStyle = TextStyle(
            color = Color.White,
            fontSize = if (isMonospaced) 24.sp else 16.sp,
            fontWeight = if (isMonospaced) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (isMonospaced) FontFamily.Monospace else FontFamily.Default,
            textAlign = if (centerText) TextAlign.Center else TextAlign.Start
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = if (centerText) Alignment.Center else Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = Color.Gray,
                        fontSize = if (isMonospaced) 24.sp else 16.sp,
                        fontWeight = if (isMonospaced) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = if (isMonospaced) FontFamily.Monospace else FontFamily.Default,
                        textAlign = if (centerText) TextAlign.Center else TextAlign.Start
                    )
                }
                innerTextField()
            }
        }
    )
}