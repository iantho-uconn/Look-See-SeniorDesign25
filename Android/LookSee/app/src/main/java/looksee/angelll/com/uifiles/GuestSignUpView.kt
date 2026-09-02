package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import looksee.angelll.com.models.*
import looksee.angelll.com.viewmodels.*
import looksee.angelll.com.services.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestSignUpView(
    vm: AuthViewModel,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var isSigningUp by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack, // Or X icon
                    contentDescription = "Close",
                    tint = Color.White.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Create Account",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Set up your LookSee identity to proceed to secure checkout.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            VStack(spacing = 18.dp) {
                InputField(
                    label = "Unique Username",
                    value = username,
                    onValueChange = { 
                        username = it.lowercase().filter { char -> "abcdefghijklmnopqrstuvwxyz0123456789_".contains(char) }
                    },
                    placeholder = "username"
                )

                InputField(
                    label = "Email Address",
                    value = email,
                    onValueChange = { email = it },
                    placeholder = "name@example.com",
                    keyboardType = KeyboardType.Email
                )

                InputField(
                    label = "Password",
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Create a strong password",
                    keyboardType = KeyboardType.Password,
                    isPassword = true
                )

                InputField(
                    label = "Phone Number",
                    value = phoneNumber,
                    onValueChange = { 
                        val filtered = it.filter { char -> char.isDigit() }
                        phoneNumber = if (filtered.length > 10) filtered.take(10) else filtered
                    },
                    placeholder = "123-456-7890",
                    keyboardType = KeyboardType.Phone
                )
            }
            
            if (errorMessage.isNotEmpty()) {
                Text(
                    text = errorMessage,
                    color = Color.Red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    focusManager.clearFocus()
                    isSigningUp = true
                    errorMessage = ""
                    coroutineScope.launch {
                        try {
                            AuthService.signUp(username, password, email, "business-users")
                            vm.pendingUsernameToSave = username
                            onNavigate("confirm_signup/$email")
                        } catch (e: Exception) {
                            errorMessage = e.localizedMessage ?: "Signup failed."
                        } finally {
                            isSigningUp = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF007AFF),
                    disabledContainerColor = Color(0xFF007AFF).copy(alpha = 0.3f)
                ),
                enabled = email.isNotEmpty() && password.isNotEmpty() && username.isNotEmpty() && phoneNumber.isNotEmpty() && !isSigningUp
            ) {
                if (isSigningUp) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text("Create Account", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            TextButton(
                onClick = { onNavigate("login") },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Already have an account?", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                    Text("Log in", color = Color(0xFF007AFF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun VStack(spacing: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        content()
    }
}

@Composable
fun InputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                focusedBorderColor = Color(0xFF007AFF).copy(alpha = 0.3f),
                unfocusedBorderColor = Color.Transparent
            )
        )
    }
}
