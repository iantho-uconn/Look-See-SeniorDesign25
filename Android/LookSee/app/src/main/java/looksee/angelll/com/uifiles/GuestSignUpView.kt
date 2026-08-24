package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.launch
import java.util.Locale

// Notice we use the Kotlin core for suspend/await support instead of Java callbacks!
import com.amplifyframework.kotlin.core.Amplify

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestSignUpView(
    authState: AuthState,
    vm: AuthViewModel,
    onDismiss: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }

    var showVerification by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val sanitizedEmail = email.trim().lowercase(Locale.getDefault())
    val sanitizedCode = verificationCode.trim()

    fun isValidPassword(pass: String): Boolean {
        // FIXED: Using Kotlin Raw String (""") to prevent escape character warnings
        val passwordRegex = """^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$""".toRegex()
        return passwordRegex.matches(pass)
    }

    val isFormValid = username.isNotEmpty() &&
            sanitizedEmail.isNotEmpty() &&
            phoneNumber.trim().isNotEmpty() &&
            isValidPassword(password)

    val isVerificationValid = sanitizedCode.length >= 6

    // MARK: - AWS Logic
    fun signUp() {
        isProcessing = true
        errorMessage = ""
        coroutineScope.launch {
            try {
                val result = AuthService.shared.signUp(
                    username = sanitizedEmail,
                    password = password,
                    email = sanitizedEmail,
                    group = "business-users"
                )

                if (result.isSignUpComplete) {
                    Amplify.Auth.signOut()
                    val signInResult = AuthService.shared.signIn(username = sanitizedEmail, password = password)
                    if (signInResult.isSignedIn) {
                        vm.fetchUserDetails()
                        // 🚀 CLAIM USERNAME IMMEDIATELY AFTER LOGIN
                        vm.updateUserIdentity(newUsername = username)
                        vm.fetchUserUsageStats()
                        vm.isSignedIn = true
                        onDismiss()
                    } else {
                        errorMessage = "Account created successfully! Please log in."
                    }
                } else {
                    showVerification = true
                }
            } catch (e: Exception) {
                // In iOS this maps AuthError, mapped natively to Exception message here
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
                val result = Amplify.Auth.confirmSignUp(sanitizedEmail, sanitizedCode)
                if (result.isSignUpComplete) {
                    Amplify.Auth.signOut()
                    val signInResult = AuthService.shared.signIn(username = sanitizedEmail, password = password)
                    if (signInResult.isSignedIn) {
                        vm.fetchUserDetails()
                        // 🚀 CLAIM USERNAME ON FINAL SUCCESS
                        vm.updateUserIdentity(newUsername = username)
                        vm.fetchUserUsageStats()
                        vm.isSignedIn = true
                        onDismiss()
                    } else {
                        errorMessage = "Verified successfully, but please log in manually from the main menu."
                    }
                } else {
                    errorMessage = "Verification incomplete. Please check the code."
                }
            } catch (e: Exception) {
                errorMessage = e.localizedMessage ?: "An error occurred."
            } finally {
                isProcessing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close, // FIXED: Using standard Close icon
                            contentDescription = "Close",
                            tint = Color.Gray.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color(0xFF0F0F1A),
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                focusManager.clearFocus()
            }
    ) { paddingValues ->
        ScrollViewContent(
            paddingValues = paddingValues,
            showVerification = showVerification,
            isFormValid = isFormValid,
            isVerificationValid = isVerificationValid,
            isProcessing = isProcessing,
            errorMessage = errorMessage,
            username = username,
            onUsernameChange = { newValue ->
                username = newValue.lowercase(Locale.getDefault()).filter { "abcdefghijklmnopqrstuvwxyz0123456789_".contains(it) }
            },
            email = email,
            onEmailChange = { email = it },
            password = password,
            onPasswordChange = { password = it },
            isValidPassword = { isValidPassword(it) },
            phoneNumber = phoneNumber,
            onPhoneNumberChange = { newValue ->
                val filtered = newValue.filter { it.isDigit() }
                phoneNumber = if (filtered.length > 10) filtered.take(10) else filtered
            },
            verificationCode = verificationCode,
            onVerificationCodeChange = { verificationCode = it },
            onPrimaryAction = {
                if (showVerification) verifyCodeAndSignIn() else signUp()
            }
        )
    }
}

@Composable
private fun ScrollViewContent(
    paddingValues: PaddingValues,
    showVerification: Boolean,
    isFormValid: Boolean,
    isVerificationValid: Boolean,
    isProcessing: Boolean,
    errorMessage: String,
    username: String,
    onUsernameChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    isValidPassword: (String) -> Boolean,
    phoneNumber: String,
    onPhoneNumberChange: (String) -> Unit,
    verificationCode: String,
    onVerificationCodeChange: (String) -> Unit,
    onPrimaryAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Header
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
            Text(
                text = if (showVerification) "Verify Email" else "Create Account",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = if (showVerification) "Enter the 6-digit code we sent to $email." else "Set up your LookSee identity to proceed to secure checkout.",
                fontSize = 14.sp,
                color = Color.Gray
            )
        }

        if (!showVerification) {
            // MARK: - SIGN UP FORM
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {

                // Username Field
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Unique Username", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                    TextField(
                        value = username,
                        onValueChange = onUsernameChange,
                        placeholder = { Text("username", color = Color.Gray) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Email Field
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Email Address", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                    TextField(
                        value = email,
                        onValueChange = onEmailChange,
                        placeholder = { Text("name@example.com", color = Color.Gray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Password Field
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Password", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                    TextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        placeholder = { Text("Create a strong password", color = Color.Gray) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, capitalization = KeyboardCapitalization.None),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (password.isNotEmpty() && !isValidPassword(password)) {
                        Text(
                            "Requires 8+ chars, 1 uppercase, 1 lowercase, 1 number, and 1 special char.",
                            fontSize = 11.sp, color = Color.Red, modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }

                // Phone Field
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Phone Number", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
                    TextField(
                        value = phoneNumber,
                        onValueChange = onPhoneNumberChange,
                        placeholder = { Text("123-456-7890", color = Color.Gray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White.copy(alpha = 0.05f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            // MARK: - VERIFICATION FORM
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Enter 6-Digit Code", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                TextField(
                    value = verificationCode,
                    onValueChange = onVerificationCodeChange,
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
                        focusedContainerColor = Color.White.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.5.dp, Color(0xFF387DFF).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
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

        Spacer(modifier = Modifier.height(32.dp))

        // Action Button
        val primaryBlue = Color(0xFF387DFF)
        val buttonEnabled = if (showVerification) isVerificationValid else isFormValid

        Button(
            onClick = onPrimaryAction,
            enabled = buttonEnabled && !isProcessing,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryBlue,
                disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
            )
        ) {
            if (isProcessing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(
                    text = if (showVerification) "Verify & Continue" else "Create Account",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}