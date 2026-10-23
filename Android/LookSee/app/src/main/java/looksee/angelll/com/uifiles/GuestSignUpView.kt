package looksee.angelll.com.uifiles

import androidx.compose.runtime.Composable
import looksee.angelll.com.viewmodels.AuthViewModel

@Composable
fun GuestSignUpView(
    vm: AuthViewModel,
    initialBusinessAccount: Boolean = false,
    onSignupSuccess: (String, Boolean) -> Unit,
    onGoToLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    SignupScreen(
        vm = vm,
        initialBusinessAccount = initialBusinessAccount,
        onSignupSuccess = { email: String, wantsBusiness: Boolean ->
            onDismiss()
            onSignupSuccess(email, wantsBusiness)
        },
        onNavigate = { route: String ->
            if (route == "login") {
                onDismiss()
                onGoToLogin()
            } else {
                onDismiss()
            }
        }
    )
}
