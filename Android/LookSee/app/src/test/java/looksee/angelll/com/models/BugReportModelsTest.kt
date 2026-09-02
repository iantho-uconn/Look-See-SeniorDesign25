package looksee.angelll.com.models

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BugReportModelsTest {
    @Test
    fun categoryMappingsMatchTheReportContracts() {
        assertEquals("ui_bug", ReportCategory.UI_BUG.wireValue)
        assertEquals("mobile-ui", ReportCategory.UI_BUG.jiraLabel)
        assertEquals("detection", ReportCategory.DETECTION_BUG.jiraLabel)
        assertEquals("upload-pipeline", ReportCategory.UPLOAD_BUG.jiraLabel)
        assertEquals("unclassified", ReportCategory.OTHER.jiraLabel)
    }

    @Test
    fun severityMappingsAndScreenshotBytesArePreserved() {
        assertEquals("Highest", ReportSeverity.CRITICAL.jiraPriority)
        assertEquals("high", ReportSeverity.HIGH.wireValue)

        val screenshot = byteArrayOf(1, 2, 3)
        val report = BugReport(
            category = ReportCategory.OTHER,
            severity = ReportSeverity.LOW,
            title = "Unexpected state",
            description = "Details",
            screenshotJpeg = screenshot,
        )

        assertArrayEquals(screenshot, report.screenshotJpeg)
    }
}
