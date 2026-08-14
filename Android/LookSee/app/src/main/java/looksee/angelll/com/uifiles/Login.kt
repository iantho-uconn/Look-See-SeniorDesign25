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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amplifyframework.kotlin.core.Amplify
import kotlinx.coroutines.launch

@Suppress("SpellCheckingInspection") // Fixed Typo Warning
@Composable
fun LoginScreen(
    vm: AuthViewModel,
    onSignedIn: () -> Unit,
    onGoToSignup: () -> Unit,
    onContinueAsGuest: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showForgotPassword by remember { mutableStateOf(false) }

    var newPasswordInput by remember { mutableStateOf("") }
    var showVerificationAlert by remember { mutableStateOf(false) }
    var verificationCode by remember { mutableStateOf("") }

    val sanitizedUsername = username.trim().lowercase()

    LaunchedEffect(vm.isSignedIn) {
        if (vm.isSignedIn) {
            onSignedIn()
        }
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
                .blur(60.dp)
                .background(Color(0xFF387DFF).copy(alpha = 0.12f), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp)
                .padding(bottom = 52.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(id = android.R.drawable.ic_dialog_info),
                    contentDescription = "LookSee Logo",
                    modifier = Modifier.size(width = 350.dp, height = 300.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Sign in to continue",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Email", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                    TextField(
                        value = username,
                        onValueChange = { username = it },
                        placeholder = { Text("you@example.com", color = Color.Gray) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF2E2E3D),
                            unfocusedContainerColor = Color(0xFF2E2E3D),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, Color(0xFF387DFF).copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Password", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = { Text("••••••••", color = Color.Gray) },
                        visualTransformation = PasswordVisualTransformation(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF2E2E3D),
                            unfocusedContainerColor = Color(0xFF2E2E3D),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, Color(0xFF387DFF).copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            text = "Forgot password?",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF387DFF),
                            modifier = Modifier.clickable {
                                vm.errorMessage = ""
                                showForgotPassword = true
                            }
                        )
                    }
                }

                if (vm.errorMessage.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = vm.errorMessage,
                            fontSize = 12.sp,
                            color = Color.Red
                        )
                        if (vm.errorMessage.lowercase().contains("verif")) {
                            Text(
                                text = "Account unverified? Tap here to enter code.",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF387DFF),
                                modifier = Modifier.clickable {
                                    focusManager.clearFocus()
                                    showVerificationAlert = true
                                }
                            )
                        }
                    }
                }

                if (vm.isSignedIn) {
                    Text(
                        text = "Signed in successfully!",
                        fontSize = 12.sp,
                        color = Color.Green
                    )
                }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        coroutineScope.launch {
                            Amplify.Auth.signOut()
                            vm.signIn(sanitizedUsername, password)
                        }
                    },
                    enabled = sanitizedUsername.isNotEmpty() && password.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF387DFF),
                        disabledContainerColor = Color(0xFF387DFF).copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Sign In", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.1f))
                    Text("or", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f))
                    HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.1f))
                }

                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        coroutineScope.launch {
                            Amplify.Auth.signOut()
                            onContinueAsGuest()
                        }
                    },
                    border = null,
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = 0.07f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Continue as Guest", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.6f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("Don't have an account? ", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                    Text(
                        text = "Sign up",
                        fontSize = 12.sp,
                        color = Color(0xFF387DFF),
                        modifier = Modifier.clickable {
                            focusManager.clearFocus()
                            onGoToSignup()
                        }
                    )
                }
            }
        }

        if (showForgotPassword) {
            // Fixed: Added onDismiss to clear the missing parameter warning
            ForgotPasswordView(
                initialUsername = username,
                onDismiss = { showForgotPassword = false }
            )
        }

        if (vm.requiresNewPassword) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Update Password") },
                text = {
                    Column {
                        Text("Your account has a temporary password. Please create a new permanent password.", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = newPasswordInput,
                            onValueChange = { newPasswordInput = it },
                            placeholder = { Text("New Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.confirmNewPassword(newPasswordInput) }) {
                        Text("Update & Sign In")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        newPasswordInput = ""
                        vm.errorMessage = ""
                    }) { Text("Cancel", color = Color.Gray) }
                }
            )
        }

        if (showVerificationAlert) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Verify Email") },
                text = {
                    Column {
                        Text("Enter the 6-digit code sent to $sanitizedUsername.", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = verificationCode,
                            onValueChange = { verificationCode = it },
                            placeholder = { Text("Verification Code") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            try {
                                val result = Amplify.Auth.confirmSignUp(sanitizedUsername, verificationCode)
                                if (result.isSignUpComplete) {
                                    showVerificationAlert = false
                                    vm.errorMessage = ""
                                    verificationCode = ""
                                    vm.signIn(sanitizedUsername, password)
                                } else {
                                    vm.errorMessage = "Verification incomplete. Please try again."
                                }
                            } catch (e: Exception) {
                                vm.errorMessage = "Verification failed: ${e.localizedMessage}"
                            }
                        }
                    }) { Text("Verify") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            coroutineScope.launch {
                                try {
                                    Amplify.Auth.resendSignUpCode(sanitizedUsername)
                                    vm.errorMessage = "A new code was sent! Tap 'Account unverified' to enter it."
                                } catch (e: Exception) {
                                    vm.errorMessage = "Failed to resend code: ${e.localizedMessage}"
                                }
                            }
                        }) { Text("Resend Code", color = Color(0xFF387DFF)) }

                        TextButton(onClick = { verificationCode = "" ; showVerificationAlert = false }) {
                            Text("Cancel", color = Color.Gray)
                        }
                    }
                }
            )
        }
    }
}