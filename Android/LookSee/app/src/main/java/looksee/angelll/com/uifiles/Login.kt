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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amplifyframework.core.Amplify
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import looksee.angelll.com.R
import looksee.angelll.com.services.AuthService
import looksee.angelll.com.viewmodels.AuthViewModel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    vm: AuthViewModel,
    onSignedIn: () -> Unit,
    onGoToSignup: () -> Unit,
    onContinueAsGuest: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showForgotPassword by remember { mutableStateOf(false) }

    var newPasswordInput by remember { mutableStateOf("") }
    var showVerificationAlert by remember { mutableStateOf(false) }
    var verificationCode by remember { mutableStateOf("") }

    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val isSignedIn by vm.isSignedIn.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val requiresNewPassword by vm.requiresNewPassword.collectAsState()

    val sanitizedUsername = username.trim().lowercase()

    LaunchedEffect(isSignedIn) {
        if (isSignedIn) onSignedIn()
    }

    if (requiresNewPassword) {
        AlertDialog(
            onDismissRequest = { newPasswordInput = "" },
            title = { Text("Update Password") },
            text = {
                Column {
                    Text("Your account has a temporary password. Please create a new permanent password.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPasswordInput,
                        onValueChange = { newPasswordInput = it },
                        label = { Text("New Password") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmNewPassword(newPasswordInput) }) {
                    Text("Update & Sign In")
                }
            },
            dismissButton = {
                TextButton(onClick = { newPasswordInput = "" }) { Text("Cancel") }
            }
        )
    }

    if (showVerificationAlert) {
        AlertDialog(
            onDismissRequest = { verificationCode = "" },
            title = { Text("Verify Email") },
            text = {
                Column {
                    Text("Enter the 6-digit code sent to $sanitizedUsername.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it },
                        label = { Text("Verification Code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                val result = AuthService.shared.confirm(sanitizedUsername, verificationCode)
                                if (result.isSignUpComplete) {
                                    showVerificationAlert = false
                                    verificationCode = ""
                                    vm.signIn(sanitizedUsername, password)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                ) { Text("Verify") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                suspendCancellableCoroutine<com.amplifyframework.auth.result.AuthCodeDeliveryDetails> { cont ->
                                    Amplify.Auth.resendSignUpCode(sanitizedUsername, { cont.resume(it) }, { cont.resumeWithException(it) })
                                }
                            } catch (_: Exception) {}
                        }
                    }
                ) { Text("Resend Code") }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() }
    ) {
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset(y = (-100).dp)
                .align(Alignment.TopCenter)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x33387DFF), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Image(
                painter = painterResource(id = R.drawable.looksee_logo),
                contentDescription = "LookSee Logo",
                modifier = Modifier.size(250.dp)
            )

            Text(
                text = "Sign in to continue",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Email",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = { Text("you@example.com", color = Color.Gray) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF2E2E3D),
                        unfocusedContainerColor = Color(0xFF2E2E3D),
                        unfocusedBorderColor = Color(0xFF387DFF).copy(alpha = 0.3f),
                        focusedBorderColor = Color(0xFF387DFF),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Password",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text("••••••••", color = Color.Gray) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF2E2E3D),
                        unfocusedContainerColor = Color(0xFF2E2E3D),
                        unfocusedBorderColor = Color(0xFF387DFF).copy(alpha = 0.3f),
                        focusedBorderColor = Color(0xFF387DFF),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showForgotPassword = true }) {
                    Text(
                        text = "Forgot password?",
                        color = Color(0xFF387DFF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (errorMessage.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = errorMessage,
                        color = Color.Red,
                        fontSize = 12.sp
                    )
                    if (errorMessage.lowercase().contains("verif")) {
                        TextButton(onClick = {
                            focusManager.clearFocus()
                            showVerificationAlert = true
                        }) {
                            Text(
                                text = "Account unverified? Tap here to enter code.",
                                color = Color(0xFF387DFF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (isSignedIn) {
                Text(
                    text = "Signed in successfully!",
                    color = Color.Green,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    coroutineScope.launch {
                        Amplify.Auth.signOut { }
                        vm.signIn(sanitizedUsername, password)
                    }
                },
                enabled = sanitizedUsername.isNotEmpty() && password.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF387DFF)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sign In", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))

                    // 🚀 The real Arrow Icon!
                    Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
                Text("or", color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
                Box(modifier = Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    coroutineScope.launch {
                        Amplify.Auth.signOut { }
                        onContinueAsGuest()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.07f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 🚀 The real Person Icon!
                    Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Continue as Guest", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.6f))
                }
            }

            TextButton(
                onClick = {
                    focusManager.clearFocus()
                    onGoToSignup()
                },
                modifier = Modifier.padding(top = 8.dp, bottom = 40.dp)
            ) {
                Row {
                    Text("Don't have an account? ", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                    Text("Sign up", color = Color(0xFF387DFF), fontSize = 12.sp)
                }
            }
        }
    }
}