package looksee.angelll.com.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class ArchivedMediaTest {
    @Test
    fun createsAUniqueLocalIdentifierByDefault() {
        val first = archivedMedia()
        val second = archivedMedia()

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun queueFieldsRemainOptionalByDefault() {
        val media = archivedMedia()

        assertNull(media.landmarkId)
        assertNull(media.savedLabel)
        assertNull(media.savedDescription)
        assertNull(media.savedUserDescription)
        assertNull(media.negativeVideoFileName)
        assertNull(media.isTier2)
        assertEquals(false, media.isVideo)
    }

    private fun archivedMedia() = ArchivedMedia(
        title = "City Hall",
        fileName = "city-hall.jpg",
        thumbnailFileName = "city-hall-thumb.jpg",
        isVideo = false,
        latitude = 40.7128,
        longitude = -74.0060,
        dateSaved = Date(1_754_390_400_000),
    )
}
