package looksee.angelll.com.models

object AppAttestService {
    fun generateAssertionHeader(payload: ByteArray): Pair<String, String>? {
        return null // Android uses Play Integrity natively, so we pass null here to skip the iOS-specific headers
    }
}