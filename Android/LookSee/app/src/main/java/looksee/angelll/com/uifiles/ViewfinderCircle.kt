package looksee.angelll.com.uifiles

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ViewfinderCircle(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Icon(
        imageVector = Icons.Default.CenterFocusStrong,
        contentDescription = "Viewfinder",
        tint = tint,
        modifier = modifier.size(70.dp)
    )
}
