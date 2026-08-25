package looksee.angelll.com.uifiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amplifyframework.auth.AuthException
import kotlinx.coroutines.launch
import looksee.angelll.com.services.AuthService
import looksee.angelll.com.viewmodels.AuthViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestSignUpScreen(
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    // State Variables
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }

    var showVerification by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Constants matching iOS theme
    val primaryColor = Color(0xFF387DFF) // 0.22, 0.49, 1.00
    val darkBackground = Color(0xFF0F0F1A) // 0.06, 0.06, 0.10

    // Computed / Derived State
    val sanitizedEmail = email.trim().lowercase(Locale.ROOT)
    val sanitizedCode = verificationCode.trim()

    fun isValidPassword(pass: String): Boolean {
        val regex = "^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$".toRegex()
        return regex.matches(pass)
    }

    val isFormValid = username.isNotEmpty() &&
            sanitizedEmail.isNotEmpty() &&
            phoneNumber.trim().isNotEmpty() &&
            isValidPassword(password)

    val isVerificationValid = sanitizedCode.length >= 6

    // Custom TextField Style
    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.White.copy(alpha = 0.05f),
        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = primaryColor
    )

    // MARK: - AWS Logic Functions
    fun signUp() {
        isProcessing = true
        errorMessage = ""
        coroutineScope.launch {
            try {
                val result = AuthService.signUp(
                    usernameInput = sanitizedEmail,
                    passwordInput = password,
                    emailInput = sanitizedEmail,
                    groupInput = "business-users"
                )

                if (result.isSignUpComplete) {
                    AuthService.signOut()
                    val signInResult = AuthService.signIn(sanitizedEmail, password)
                    if (signInResult.isSignInComplete) {
                        vm.fetchUserDetails()
                        // 🚀 CLAIM USERNAME IMMEDIATELY AFTER LOGIN
                        vm.updateUserIdentity(newUsername = username, emailToSave = sanitizedEmail)
                        vm.fetchUserUsageStats()

                        vm.isSignedIn = true
                        onDismiss()
                    } else {
                        errorMessage = "Account created successfully! Please log in."
                    }
                } else {
                    showVerification = true
                }
            } catch (e: AuthException) {
                errorMessage = e.message ?: "An error occurred."
            } catch (e: Exception) {
                errorMessage = e.localizedMessage ?: "An error occurred."
            } finally {
                isProcessing = false
            }
        }
    }

    fun verifyCodeAndSignIn() {
        isProcessing = true
        errorMessage = ""
        coroutineScope.launch {
            try {
                val result = AuthService.confirm(sanitizedEmail, sanitizedCode)
                if (result.isSignUpComplete) {
                    AuthService.signOut()
                    val signInResult = AuthService.signIn(sanitizedEmail, password)
                    if (signInResult.isSignInComplete) {
                        vm.fetchUserDetails()
                        // 🚀 CLAIM USERNAME ON FINAL SUCCESS
                        vm.updateUserIdentity(newUsername = username, emailToSave = sanitizedEmail)
                        vm.fetchUserUsageStats()

                        vm.isSignedIn = true
                        onDismiss()
                    } else {
                        errorMessage = "Verified successfully, but please log in manually from the main menu."
                    }
                } else {
                    errorMessage = "Verification incomplete. Please check the code."
                }
            } catch (e: AuthException) {
                errorMessage = e.message ?: "An error occurred."
            } catch (e: Exception) {
                errorMessage = e.localizedMessage ?: "An error occurred."
            } finally {
                isProcessing = false
            }
        }
    }

    // MARK: - UI Layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBackground)
            .clickable { focusManager.clearFocus() } // Hide keyboard on tap
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Toolbar (Close Button)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, start = 8.dp)
            ) {
                IconButton(onClick = { onDismiss() }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.Gray.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Main Content ScrollView
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Header
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (showVerification) "Verify Email" else "Create Account",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (showVerification) "Enter the 6-digit code we sent to $sanitizedEmail."
                        else "Set up your LookSee identity to proceed to secure checkout.",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Forms
                if (!showVerification) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {

                        // Username Field
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Unique Username", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                            OutlinedTextField(
                                value = username,
                                onValueChange = { newValue ->
                                    val filtered = newValue.lowercase(Locale.ROOT).filter { char ->
                                        char.isLetterOrDigit() || char == '_'
                                    }
                                    username = filtered
                                },
                                placeholder = { Text("username", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = textFieldColors,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                                singleLine = true
                            )
                        }

                        // Email Field
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Email Address", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                placeholder = { Text("name@example.com", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = textFieldColors,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None),
                                singleLine = true
                            )
                        }

                        // Password Field
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Password", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                placeholder = { Text("Create a strong password", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = textFieldColors,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true
                            )
                            AnimatedVisibility(visible = password.isNotEmpty() && !isValidPassword(password)) {
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
                            OutlinedTextField(
                                value = phoneNumber,
                                onValueChange = { newValue ->
                                    val filtered = newValue.filter { it.isDigit() }
                                    if (filtered.length <= 10) {
                                        phoneNumber = filtered
                                    }
                                },
                                placeholder = { Text("123-456-7890", color = Color.Gray) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = textFieldColors,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true
                            )
                        }
                    }
                } else {
                    // MARK: - VERIFICATION FORM
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Enter 6-Digit Code", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        OutlinedTextField(
                            value = verificationCode,
                            onValueChange = { verificationCode = it.filter { char -> char.isDigit() }.take(6) },
                            placeholder = { Text("123456", color = Color.Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, primaryColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                }

                // Error Message
                if (errorMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Red,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Submit Button
                val isButtonEnabled = (if (showVerification) isVerificationValid else isFormValid) && !isProcessing

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (showVerification) verifyCodeAndSignIn() else signUp()
                    },
                    enabled = isButtonEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        disabledContainerColor = Color.Gray.copy(alpha = 0.3f),
                        disabledContentColor = Color.White.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text(
                            text = if (showVerification) "Verify & Continue" else "Create Account",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isButtonEnabled) Color.White else Color.White.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}