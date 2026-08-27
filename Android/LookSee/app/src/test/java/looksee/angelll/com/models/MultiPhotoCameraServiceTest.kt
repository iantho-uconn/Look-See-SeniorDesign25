package looksee.angelll.com.models

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiPhotoCameraServiceTest {
    @Test
    fun captureAvailabilityRequiresAConfiguredIdleCameraAndCapacity() {
        val collection = MultiPhotoCaptureCollection(emptyList(), maximumPhotoCount = 1)

        assertFalse(collection.canAdd(isConfigured = false, isCapturing = false))
        assertFalse(collection.canAdd(isConfigured = true, isCapturing = true))
        assertTrue(collection.canAdd(isConfigured = true, isCapturing = false))

        collection.add(photo("new"))

        assertFalse(collection.canAdd(isConfigured = true, isCapturing = false))
    }

    @Test
    fun addStopsAtTheConfiguredMaximum() {
        val collection = MultiPhotoCaptureCollection(emptyList(), maximumPhotoCount = 1)
        val accepted = photo("accepted")

        assertTrue(collection.add(accepted))
        assertFalse(collection.add(photo("rejected")))
        assertEquals(listOf(accepted), collection.photos)
    }

    @Test
    fun removingUnknownIdentityLeavesTheCollectionUntouched() {
        val initial = photo("initial")
        val collection = MultiPhotoCaptureCollection(listOf(initial), maximumPhotoCount = 2)

        assertNull(collection.remove(UUID.randomUUID()))
        assertEquals(listOf(initial), collection.photos)
    }

    @Test
    fun removingKnownIdentityReturnsAndRemovesThePhoto() {
        val initial = photo("initial")
        val collection = MultiPhotoCaptureCollection(listOf(initial), maximumPhotoCount = 2)

        assertEquals(initial, collection.remove(initial.id))
        assertTrue(collection.photos.isEmpty())
    }

    @Test
    fun discardNewRetainsPhotosThatPredatedTheCameraSession() {
        val original = photo("original")
        val addedOne = photo("added-one")
        val addedTwo = photo("added-two")
        val collection = MultiPhotoCaptureCollection(listOf(original), maximumPhotoCount = 3)
        collection.add(addedOne)
        collection.add(addedTwo)

        val discarded = collection.discardNew()

        assertEquals(listOf(addedOne, addedTwo), discarded)
        assertEquals(listOf(original), collection.photos)
    }

    @Test
    fun preservesInitialPhotosBeyondTheMaximumButDoesNotAddMore() {
        val initial = listOf(photo("one"), photo("two"))
        val collection = MultiPhotoCaptureCollection(
            initialPhotos = initial,
            maximumPhotoCount = 1,
        )

        assertEquals(initial, collection.photos)
        assertFalse(collection.canAdd(isConfigured = true, isCapturing = false))
        assertFalse(collection.add(photo("three")))
    }

    private fun photo(name: String) = CapturedNegativePhoto(
        file = File("$name.jpg"),
        id = UUID.nameUUIDFromBytes(name.toByteArray()),
    )
}
