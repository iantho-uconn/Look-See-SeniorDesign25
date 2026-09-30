package looksee.angelll.com.services

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AdConsentManager(private val context: Context) {
    private val consentInformation: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    var adsReady by mutableStateOf(false)
        private set

    var isPrivacyOptionsRequired by mutableStateOf(false)
        private set

    fun start(activity: Activity) {
        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { loadAndShowError ->
                    if (loadAndShowError != null) {
                        println("AdConsentManager Error: ${loadAndShowError.message}")
                    }
                    if (consentInformation.canRequestAds()) {
                        adsReady = true
                    }
                    
                    isPrivacyOptionsRequired = consentInformation.privacyOptionsRequirementStatus == 
                            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
                }
            },
            { requestConsentError ->
                println("AdConsentManager InfoUpdateError: ${requestConsentError.message}")
            }
        )

        // Check if you can request ads on start
        if (consentInformation.canRequestAds()) {
            adsReady = true
        }
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                println("AdConsentManager PrivacyOptionsError: ${formError.message}")
            }
        }
    }
}
