package looksee.angelll.com.models

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailReportServiceTest {
    private val deviceInfo = ReportDeviceInfo(
        appVersion = "2.4.0",
        buildNumber = "240",
        osVersion = "16",
        deviceModel = "Google Pixel",
    )

    @Test
    fun subjectIncludesCategorySeverityAndTitle() {
        assertEquals(
            "[Detection Bug · High] Boxes flicker",
            MailReportService.subject(report()),
        )
    }

    @Test
    fun bodyIncludesDescriptionReporterAndAndroidDeviceInfo() {
        val body = MailReportService.body(
            report = report(),
            userEmail = "owner@example.com",
            deviceInfo = deviceInfo,
        )

        assertTrue(body.startsWith("Bounding boxes disappear."))
        assertTrue(body.contains("Category: Detection Bug"))
        assertTrue(body.contains("Severity: High"))
        assertTrue(body.contains("Reported by: owner@example.com"))
        assertTrue(body.contains("App version: 2.4.0 (240)"))
        assertTrue(body.contains("OS: Android 16"))
        assertTrue(body.contains("Device: Google Pixel"))
    }

    @Test
    fun draftTargetsSupportInboxAndAttachesScreenshot() {
        val screenshot = byteArrayOf(4, 5, 6)
        val draft = MailReportService.buildDraft(
            report().copy(screenshotJpeg = screenshot),
            userEmail = null,
            deviceInfo = deviceInfo,
        )

        assertEquals(
            listOf("Looksee.support@informationoutpost.com"),
            draft.recipients,
        )
        assertArrayEquals(screenshot, draft.attachmentJpeg)
        assertEquals("screenshot.jpg", draft.attachmentFilename)
    }

    @Test
    fun draftWithoutScreenshotHasNoAttachmentFilename() {
        val draft = MailReportService.buildDraft(
            report(),
            userEmail = null,
            deviceInfo = deviceInfo,
        )

        assertNull(draft.attachmentJpeg)
        assertNull(draft.attachmentFilename)
    }

    private fun report() = BugReport(
        category = ReportCategory.DETECTION_BUG,
        severity = ReportSeverity.HIGH,
        title = "Boxes flicker",
        description = "Bounding boxes disappear.",
    )
}
