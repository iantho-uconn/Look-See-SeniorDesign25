package looksee.angelll.com.uifiles

import android.graphics.Bitmap
import java.io.File
import java.util.UUID

data class CapturedNegativePhoto(
    val id: UUID = UUID.randomUUID(),
    val file: File,
    val thumbnail: Bitmap? = null
) {
    val filename: String
        get() = file.name

    fun deleteLocalFile() {
        if (!file.exists()) {
            return
        }

        try {
            file.delete()
        } catch (e: Exception) {
            println("⚠️ Failed to delete negative photo ${file.name}: ${e.localizedMessage}")
        }
    }
}