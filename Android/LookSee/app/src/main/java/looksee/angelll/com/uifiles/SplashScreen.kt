package looksee.angelll.com.uifiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SplashScreen(onGetStarted: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF387DFF), // RGB: 0.22, 0.49, 1.00
                        Color(0xFFF2359E)  // RGB: 0.95, 0.21, 0.62
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 🚀 The real Material Visibility (Eye) Icon!
                Icon(
                    imageVector = Icons.Filled.Visibility,
                    contentDescription = "LookSee Icon",
                    tint = Color.White,
                    modifier = Modifier
                        .size(150.dp)
                        .padding(16.dp)
                )
                Text(
                    text = "LookSee",
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    color = Color.White
                )
                Text(
                    text = "Explore landmarks and buildings around you",
                    color = Color.White,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Button(
                onClick = onGetStarted,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 20.dp)
                    .height(60.dp)
            ) {
                Text(
                    text = "Get Started",
                    color = Color(0xFF990FFA), // RGB: 0.60, 0.06, 0.98
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}