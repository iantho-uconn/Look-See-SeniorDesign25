package looksee.angelll.com.uifiles

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun MainScreen() {
    // Unresolved reference: ButtonsScreen()
    ButtonsScreen()
}

@Preview(showBackground = true)
@Composable
fun PreviewMainScreen() {
    MainScreen()
}