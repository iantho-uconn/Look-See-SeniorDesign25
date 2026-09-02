package looksee.angelll.com.models

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CapturedNegativePhotoTest {
    @Test
    fun exposesTheLocalFilenameAndStableIdentity() {
        val id = UUID.fromString("7b977731-3e1a-4ce3-843c-1d51b499fb99")
        val directory = Files.createTempDirectory("looksee-negative-photo-test")
        val photo = CapturedNegativePhoto(
            file = directory.resolve("negative-reference.jpg").toFile(),
            id = id,
        )

        assertEquals("negative-reference.jpg", photo.filename)
        assertEquals(id, photo.id)
    }

    @Test
    fun deletesAnExistingLocalFile() {
        val file = Files.createTempFile("looksee-negative-photo-test", ".jpg").toFile()

        CapturedNegativePhoto(file).deleteLocalFile()

        assertFalse(file.exists())
    }
}
