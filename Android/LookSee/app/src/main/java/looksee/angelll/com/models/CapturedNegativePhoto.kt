package looksee.angelll.com.models

import java.io.File
import java.nio.file.Files
import java.util.UUID

/** A negative-reference JPEG captured locally before submission or upload. */
data class CapturedNegativePhoto(
    val file: File,
    val id: UUID = UUID.randomUUID(),
) {
    val filename: String
        get() = file.name

    /** Deletes the local JPEG when it still exists, matching the iOS value type. */
    fun deleteLocalFile() {
        try {
            Files.deleteIfExists(file.toPath())
        } catch (error: Exception) {
            System.err.println(
                "Failed to delete negative photo ${file.name}: ${error.message}",
            )
        }
    }
}
