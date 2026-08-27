package looksee.angelll.com.uifiles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import looksee.angelll.com.R

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    // Matches the iOS Splash Screen in Pics.pdf
    val splashGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A1931), Color(0xFF000000))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(splashGradient)
    ) {
        // City Silhouette at the bottom (if you have the resource, otherwise a placeholder)
        // Image(
        //    painter = painterResource(id = R.drawable.city_silhouette),
        //    contentDescription = null,
        //    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        //    contentScale = ContentScale.FillWidth
        // )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.looksee_logo),
                contentDescription = "LookSee Logo",
                modifier = Modifier.size(140.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "LookSee",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    LaunchedEffect(Unit) {
        delay(2000)
        onTimeout()
    }
}
