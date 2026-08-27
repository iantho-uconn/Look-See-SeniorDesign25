package looksee.angelll.com.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class ScannedLandmarkTest {
    @Test
    fun scanHistoryModelRoundTripsWithoutDroppingOptionalFields() {
        val original = ScannedLandmark(
            id = 7,
            name = "Clock Tower",
            description = "Historic tower",
            url = "https://example.test/tower",
            category = "Architecture",
            confidence = "94%",
            detectionTime = 1_786_000_000.5,
        )

        val decoded = Gson().fromJson(Gson().toJson(original), ScannedLandmark::class.java)

        assertEquals(original, decoded)
    }
}
