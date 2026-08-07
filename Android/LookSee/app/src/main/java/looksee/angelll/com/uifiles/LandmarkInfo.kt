package looksee.angelll.com.uifiles

import androidx.compose.runtime.Composable

@Deprecated(
    message = "Use PopUp() instead of LandmarkInfoScreen().",
    replaceWith = ReplaceWith("PopUp()")
)
@Composable
fun LandmarkInfoScreen() {
    PopUp()
}