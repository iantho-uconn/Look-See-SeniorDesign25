package looksee.angelll.com

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.core.Amplify
import looksee.angelll.com.ui.theme.LookSeeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🚀 Boot up AWS when the app launches
        configureAmplify()

        setContent {
            LookSeeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LookSeeWelcomeScreen()
                }
            }
        }
    }

    private fun configureAmplify() {
        try {
            // Add the Auth plugin (Cognito)
            Amplify.addPlugin(AWSCognitoAuthPlugin())

            // Tell Amplify to configure itself using that JSON file we pasted
            Amplify.configure(applicationContext)
            Log.i("AmplifyEngine", "Initialized Amplify successfully")
        } catch (error: Exception) {
            Log.e("AmplifyEngine", "Could not initialize Amplify", error)
        }
    }
}

@Composable
fun LookSeeWelcomeScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Welcome to LookSee Android!")

        Button(onClick = {
            Log.i("UserAction", "Start Scanning Tapped!")
        }) {
            Text("Start Scanning")
        }
    }
}