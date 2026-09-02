package looksee.angelll.com.models

import java.io.File
import java.nio.file.Files

/** A negative-reference video captured and stored locally before upload. */
data class CapturedNegativeVideo(
    val file: File,
) {
    val filename: String
        get() = file.name

    /** Deletes the local video when it still exists, matching the iOS behavior. */
    fun deleteLocalFile() {
        try {
            Files.deleteIfExists(file.toPath())
        } catch (error: Exception) {
            System.err.println(
                "Failed to delete negative video ${file.name}: ${error.message}",
            )
        }
    }
}
