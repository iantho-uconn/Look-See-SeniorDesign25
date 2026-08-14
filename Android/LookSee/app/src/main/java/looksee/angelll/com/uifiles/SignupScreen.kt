package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
import kotlin.time.Duration.Companion.seconds

private val BackgroundDark = Color(0xFF0F0F1A)
private val InputBackground = Color(0xFF2E2E3D)
private val LookSeeBlue = Color(0xFF387DFF)

@Composable
fun SignupScreen(
    onSignupSuccess: (String) -> Unit,
    onGoToLogin: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }

    var message by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showVerification by remember { mutableStateOf(false) }
    var isBusinessAccount by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    fun isValidPassword(pass: String): Boolean {
        val passwordRegex = """^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*[^A-Za-z0-9]).{8,}$"""
        return pass.matches(passwordRegex.toRegex())
    }

    val isSignupDisabled = isLoading || (if (showVerification) verificationCode.length < 6 else (email.isEmpty() || !isValidPassword(password)))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
    ) {
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset(y = (-100).dp)
                .align(Alignment.TopCenter)
                .background(LookSeeBlue.copy(alpha = 0.12f), CircleShape)
                .blur(radius = 60.dp)
        )

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(20.dp))
                Box(modifier = Modifier.size(350.dp, 300.dp), contentAlignment = Alignment.Center) { Text("Logo Placeholder", color = Color.White) }
                Text(text = if (showVerification) "Check your email" else "Create your account", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
            }

            Column(
                modifier = Modifier.padding(horizontal = 28.dp).padding(bottom = 52.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!showVerification) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Email", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        CustomTextField(value = email, onValueChange = { email = it }, placeholder = "you@example.com", keyboardType = KeyboardType.Email)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Password", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        CustomTextField(value = password, onValueChange = { password = it }, placeholder = "••••••••", isPassword = true)

                        if (password.isNotEmpty() && !isValidPassword(password)) {
                            Text("Requires 8+ chars, 1 uppercase, 1 lowercase, 1 number, and 1 special char.", fontSize = 11.sp, color = Color.Red, modifier = Modifier.padding(start = 4.dp))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().background(InputBackground, RoundedCornerShape(12.dp)).border(0.5.dp, LookSeeBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Business Account", fontSize = 14.sp, color = Color.White)
                            Text("Enables promotion management and video uploads", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                        }
                        Switch(checked = isBusinessAccount, onCheckedChange = { isBusinessAccount = it }, colors = SwitchDefaults.colors(checkedThumbColor = LookSeeBlue, checkedTrackColor = LookSeeBlue.copy(alpha = 0.5f)))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Enter 6-Digit Code", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                        CustomTextField(value = verificationCode, onValueChange = { verificationCode = it }, placeholder = "123456", keyboardType = KeyboardType.Number, textStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center))
                    }
                }

                if (message.isNotEmpty()) {
                    val isSuccess = message.contains("successful", ignoreCase = true) || message.contains("verified", ignoreCase = true)
                    Text(text = message, fontSize = 12.sp, color = if (isSuccess) Color.Green else Color.Red, modifier = Modifier.fillMaxWidth())
                }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (showVerification) {
                            isLoading = true
                            message = ""
                            coroutineScope.launch {
                                try {
                                    val result = Amplify.Auth.confirmSignUp(email, verificationCode)
                                    if (result.isSignUpComplete) {
                                        message = "Verification successful! Routing to login..."
                                        delay(1.5.seconds)
                                        onSignupSuccess(email)
                                    } else {
                                        message = "Verification incomplete. Please check the code."
                                    }
                                } catch (e: Exception) {
                                    message = e.localizedMessage ?: "Unknown Error"
                                }
                                isLoading = false
                            }
                        } else {
                            isLoading = true
                            message = ""
                            val group = if (isBusinessAccount) "business-users" else "authenticated-users"
                            coroutineScope.launch {
                                try {
                                    val result = AuthService.signUp(username = email, password = password, email = email, group = group)
                                    if (result.isSignUpComplete) {
                                        message = "Account created and verified! Routing to login..."
                                        delay(1.2.seconds)
                                        onSignupSuccess(email)
                                    } else {
                                        showVerification = true
                                        message = "Code sent! Please check your email."
                                    }
                                } catch (e: Exception) {
                                    message = e.localizedMessage ?: "Unknown Error"
                                }
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LookSeeBlue, disabledContainerColor = LookSeeBlue.copy(alpha = 0.5f)),
                    enabled = !isSignupDisabled, shape = RoundedCornerShape(14.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text(if (showVerification) "Verify Account" else "Create Account", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("→", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (!showVerification) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onGoToLogin() }, horizontalArrangement = Arrangement.Center) {
                        Text("Already have an account? ", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                        Text("Sign in", color = LookSeeBlue, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun CustomTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    textStyle: TextStyle = TextStyle(fontSize = 16.sp)
) {
    TextField(
        value = value, onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Color.White.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth(), textAlign = textStyle.textAlign) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        singleLine = true, textStyle = textStyle.copy(color = Color.White),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = InputBackground, unfocusedContainerColor = InputBackground,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = LookSeeBlue
        ),
        modifier = Modifier.fillMaxWidth().border(0.5.dp, LookSeeBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp)
    )
}