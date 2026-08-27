package looksee.angelll.com.uifiles

import androidx.compose.runtime.Composable
import looksee.angelll.com.viewmodels.AuthViewModel

@Composable
fun MainScreen(vm: AuthViewModel, onNavigate: (String) -> Unit) {
    ButtonsScreen(vm = vm, onNavigate = onNavigate)
}
