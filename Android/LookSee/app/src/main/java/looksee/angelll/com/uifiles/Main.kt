package looksee.angelll.com.uifiles

import androidx.compose.runtime.Composable
import looksee.angelll.com.viewmodels.AuthViewModel

import looksee.angelll.com.viewmodels.AuthState

@Composable
fun MainScreen(vm: AuthViewModel, authState: AuthState, onNavigate: (String) -> Unit) {
    ButtonsScreen(vm = vm, authState = authState, onNavigate = onNavigate)
}
