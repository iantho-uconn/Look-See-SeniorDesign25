package looksee.angelll.com.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class CapturedNegativeVideoTest {
    @Test
    fun exposesTheLocalFilename() {
        val directory = Files.createTempDirectory("looksee-negative-test")
        val file = directory.resolve("negative-reference.mp4").toFile()

        assertEquals(
            "negative-reference.mp4",
            CapturedNegativeVideo(file).filename,
        )
    }

    @Test
    fun deletesAnExistingLocalFile() {
        val file = Files.createTempFile("looksee-negative-test", ".mp4").toFile()

        CapturedNegativeVideo(file).deleteLocalFile()

        assertFalse(file.exists())
    }
}
