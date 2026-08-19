package looksee.angelll.com.models

data class MailReportDraft(
    val recipients: List<String>,
    val subject: String,
    val body: String,
    val attachmentJpeg: ByteArray?,
    val attachmentFilename: String? = attachmentJpeg?.let { "screenshot.jpg" },
)

/**
 * Platform-neutral report-email builder. The UI layer owns launching Android's
 * email intent and granting a FileProvider URI when a screenshot is attached.
 */
object MailReportService {
    val recipients = listOf("Looksee.support@informationoutpost.com")

    fun subject(report: BugReport): String =
        "[${report.category.displayName} · ${report.severity.displayName}] ${report.title}"

    fun body(
        report: BugReport,
        userEmail: String?,
        deviceInfo: ReportDeviceInfo,
    ): String = """
        ${report.description}

        ---
        Category: ${report.category.displayName}
        Severity: ${report.severity.displayName}
        Reported by: ${userEmail.orEmpty()}
        App version: ${deviceInfo.appVersion} (${deviceInfo.buildNumber})
        OS: Android ${deviceInfo.osVersion}
        Device: ${deviceInfo.deviceModel}
    """.trimIndent()

    fun buildDraft(
        report: BugReport,
        userEmail: String?,
        deviceInfo: ReportDeviceInfo,
    ): MailReportDraft = MailReportDraft(
        recipients = recipients,
        subject = subject(report),
        body = body(report, userEmail, deviceInfo),
        attachmentJpeg = report.screenshotJpeg,
    )
}
