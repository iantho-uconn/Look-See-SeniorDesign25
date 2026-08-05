package looksee.angelll.com.detection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CameraPreviewTest {
    @Test
    fun rgbaConversionUsesCameraXArgbByteOrder() {
        val buffer = ByteBuffer.wrap(
            byteArrayOf(
                0x7F, 0x11, 0x22, 0x33,
                0xFF.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
            ),
        )

        val pixels = rgbaPlaneToArgb(
            buffer = buffer,
            bufferWidth = 2,
            bufferHeight = 1,
            rowStride = 8,
            pixelStride = 4,
        )

        assertArrayEquals(intArrayOf(0x7F112233, 0xFFAABBCC.toInt()), pixels)
    }

    @Test
    fun rgbaConversionRespectsRowPadding() {
        val bytes = ByteArray(24)
        putArgb(bytes, 0, 0xFF, 0x10, 0x20, 0x30)
        putArgb(bytes, 4, 0xFF, 0x40, 0x50, 0x60)
        putArgb(bytes, 12, 0xFF, 0x70, 0x80, 0x90)
        putArgb(bytes, 16, 0xFF, 0xA0, 0xB0, 0xC0)

        val pixels = rgbaPlaneToArgb(
            buffer = ByteBuffer.wrap(bytes),
            bufferWidth = 2,
            bufferHeight = 2,
            rowStride = 12,
            pixelStride = 4,
        )

        assertArrayEquals(
            intArrayOf(
                0xFF102030.toInt(),
                0xFF405060.toInt(),
                0xFF708090.toInt(),
                0xFFA0B0C0.toInt(),
            ),
            pixels,
        )
    }

    @Test
    fun rgbaConversionRespectsImageCropRectangle() {
        val bytes = ByteArray(3 * 2 * 4)
        repeat(6) { index ->
            putArgb(bytes, index * 4, 0xFF, index + 1, index + 11, index + 21)
        }

        val pixels = rgbaPlaneToArgb(
            buffer = ByteBuffer.wrap(bytes),
            bufferWidth = 3,
            bufferHeight = 2,
            rowStride = 12,
            pixelStride = 4,
            cropLeft = 1,
            cropTop = 0,
            cropWidth = 2,
            cropHeight = 2,
        )

        assertArrayEquals(
            intArrayOf(
                argb(0xFF, 2, 12, 22),
                argb(0xFF, 3, 13, 23),
                argb(0xFF, 5, 15, 25),
                argb(0xFF, 6, 16, 26),
            ),
            pixels,
        )
    }

    @Test
    fun previewTransformMapsIdentityBox() {
        val transform = PreviewTransform(
            floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            ),
        )

        assertEquals(
            DetectionBox(10f, 20f, 30f, 40f),
            transform.map(DetectionBox(10f, 20f, 30f, 40f)),
        )
    }

    @Test
    fun previewTransformMapsScaleAndTranslation() {
        val transform = PreviewTransform(
            floatArrayOf(
                2f, 0f, 5f,
                0f, 3f, 7f,
                0f, 0f, 1f,
            ),
        )

        assertEquals(
            DetectionBox(25f, 67f, 65f, 127f),
            transform.map(DetectionBox(10f, 20f, 30f, 40f)),
        )
    }

    @Test
    fun intersectionClipsRealDetectionToSafeZone() {
        val detection = DetectionBox(10f, 20f, 90f, 100f)
        val safeZone = DetectionBox(40f, 50f, 80f, 85f)

        assertEquals(
            DetectionBox(40f, 50f, 80f, 85f),
            detection.intersectionOrNull(safeZone),
        )
        assertNull(detection.intersectionOrNull(DetectionBox(100f, 100f, 120f, 120f)))
    }

    @Test
    fun expandedHitTargetAddsFortyPixelsOnEverySide() {
        val expanded = DetectionBox(100f, 100f, 150f, 150f).expandedBy(40f)

        assertEquals(DetectionBox(60f, 60f, 190f, 190f), expanded)
        assertTrue(expanded.contains(65f, 65f))
        assertFalse(expanded.contains(59f, 65f))
    }

    private fun putArgb(
        destination: ByteArray,
        offset: Int,
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int,
    ) {
        destination[offset] = alpha.toByte()
        destination[offset + 1] = red.toByte()
        destination[offset + 2] = green.toByte()
        destination[offset + 3] = blue.toByte()
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
