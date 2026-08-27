package looksee.angelll.com.models

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JiraReportServiceTest {
    @Test
    fun backendSubmissionIsDisabledByDefault(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient()
        val service = JiraReportService(http)

        assertReportError<JiraReportError.Disabled> {
            service.submit(report(), "token", null, deviceInfo())
        }

        assertTrue(http.requests.isEmpty())
        assertEquals(JiraReportStage.IDLE, service.stage.value)
        assertFalse(service.isSubmitting)
    }

    @Test
    fun enabledSubmissionPostsMultipartReportAndCompletes(): Unit = runBlocking {
        val http = RecordingBusinessHttpClient(
            jsonResponse(
                """{"issueKey":"LOOK-42","issueUrl":"https://jira.test/LOOK-42"}""",
            ),
        )
        val service = JiraReportService(
            httpClient = http,
            baseUrl = "https://api.test/",
            enabled = true,
        )

        val result = service.submit(
            report().copy(screenshotJpeg = "jpeg-data".toByteArray()),
            idToken = "id-token",
            userEmail = "owner@example.com",
            deviceInfo = deviceInfo(),
        )
        val request = http.requests.single()
        val multipart = request.body!!.toString(Charsets.UTF_8)

        assertEquals("LOOK-42", result.issueKey)
        assertEquals("POST", request.method)
        assertEquals("https://api.test/reports", request.url)
        assertEquals("Bearer id-token", request.authorization)
        assertTrue(request.contentType!!.startsWith("multipart/form-data; boundary=Boundary-"))
        assertTrue(multipart.contains("name=\"category\"\r\n\r\ndetection_bug"))
        assertTrue(multipart.contains("name=\"jiraPriority\"\r\n\r\nHigh"))
        assertTrue(multipart.contains("name=\"userEmail\"\r\n\r\nowner@example.com"))
        assertTrue(multipart.contains("filename=\"screenshot.jpg\""))
        assertTrue(multipart.contains("Content-Type: image/jpeg"))
        assertEquals(JiraReportStage.COMPLETE, service.stage.value)
        assertEquals("Reported as LOOK-42", service.status.value)
        assertEquals(1.0, service.progress.value, 0.0)

        service.reset()
        assertEquals(JiraReportStage.IDLE, service.stage.value)
        assertEquals(0.0, service.progress.value, 0.0)
    }

    @Test
    fun serverErrorUsesBackendMessageAndPublishesFailure(): Unit = runBlocking {
        val service = JiraReportService(
            httpClient = RecordingBusinessHttpClient(
                jsonResponse("""{"message":"Reports are unavailable"}""", 503),
            ),
            enabled = true,
        )

        val error = assertReportError<JiraReportError.Server> {
            service.submit(report(), "token", null, deviceInfo())
        }

        assertEquals(503, error.statusCode)
        assertEquals("Reports are unavailable", error.serverMessage)
        assertEquals(JiraReportStage.FAILED, service.stage.value)
        assertEquals("Failed", service.status.value)
    }

    @Test
    fun incompleteTicketResponseIsRejected(): Unit = runBlocking {
        val service = JiraReportService(
            httpClient = RecordingBusinessHttpClient(
                jsonResponse("""{"issueKey":"LOOK-42","issueUrl":""}"""),
            ),
            enabled = true,
        )

        assertReportError<JiraReportError.InvalidResponse> {
            service.submit(report(), "token", null, deviceInfo())
        }

        assertEquals(JiraReportStage.FAILED, service.stage.value)
        assertEquals("Failed to parse response", service.status.value)
    }

    @Test
    fun transportFailureIsWrappedAndPublishesNetworkStatus(): Unit = runBlocking {
        val http = object : BusinessHttpClient {
            override suspend fun execute(request: BusinessHttpRequest): BusinessHttpResponse {
                error("offline")
            }
        }
        val service = JiraReportService(httpClient = http, enabled = true)

        val error = assertReportError<JiraReportError.Network> {
            service.submit(report(), "token", null, deviceInfo())
        }

        assertEquals("offline", error.message)
        assertEquals(JiraReportStage.FAILED, service.stage.value)
        assertEquals("Network error", service.status.value)
    }

    private fun report() = BugReport(
        category = ReportCategory.DETECTION_BUG,
        severity = ReportSeverity.HIGH,
        title = "Boxes flicker",
        description = "Bounding boxes disappear.",
    )

    private fun deviceInfo() = ReportDeviceInfo(
        appVersion = "2.4.0",
        buildNumber = "240",
        osVersion = "16",
        deviceModel = "Google Pixel",
    )

    private suspend inline fun <reified T : Throwable> assertReportError(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            assertTrue(
                "Expected ${T::class.java.simpleName}, received ${error::class.java.simpleName}",
                error is T,
            )
            @Suppress("UNCHECKED_CAST")
            return error as T
        }
        error("Unreachable")
    }
}
