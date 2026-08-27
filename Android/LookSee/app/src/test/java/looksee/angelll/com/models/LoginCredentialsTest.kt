package looksee.angelll.com.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginCredentialsTest {
    @Test
    fun credentialsRoundTripWithOptionalEmail() {
        val original = LoginCredentials(
            id = 9,
            username = "ian",
            email = "ian@example.com",
            password = "not-a-real-password",
        )

        assertEquals(
            original,
            Gson().fromJson(Gson().toJson(original), LoginCredentials::class.java),
        )
    }

    @Test
    fun emailAndIdentityMayBeAbsent() {
        val decoded = Gson().fromJson(
            """{"username":"ian","password":"secret"}""",
            LoginCredentials::class.java,
        )

        assertNull(decoded.id)
        assertNull(decoded.email)
    }
}
