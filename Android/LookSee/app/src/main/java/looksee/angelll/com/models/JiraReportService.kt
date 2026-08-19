package looksee.angelll.com.models

import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class JiraReportStage {
    IDLE,
    SUBMITTING,
    COMPLETE,
    FAILED,
}

data class JiraTicketResult(
    val issueKey: String = "",
    val issueUrl: String = "",
)

sealed class JiraReportError(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    data object Disabled : JiraReportError(
        "Backend Jira reporting is disabled; use the email report flow.",
    )
    data object InvalidResponse : JiraReportError(
        "Received an unexpected response from the report server.",
    )
    class Server(val statusCode: Int, val serverMessage: String?) : JiraReportError(
        serverMessage?.takeIf(String::isNotBlank)
            ?: "The report server returned an error ($statusCode).",
    )
    class Network(error: Throwable) : JiraReportError(
        error.message ?: "The report could not be submitted.",
        error,
    )
}

/**
 * Optional backend Jira adapter. The supplied Swift source is fully commented
 * out, so Android keeps this disabled unless a caller explicitly enables it.
 * Jira credentials never live in the app; the backend owns ticket creation.
 */
class JiraReportService internal constructor(
    private val httpClient: BusinessHttpClient,
    private val gson: Gson = Gson(),
    private val baseUrl: String = LOOKSEE_API_BASE_URL,
    private val enabled: Boolean = false,
) {
    constructor() : this(UrlConnectionBusinessHttpClient())

    private val _stage = MutableStateFlow(JiraReportStage.IDLE)
    val stage: StateFlow<JiraReportStage> = _stage.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress.asStateFlow()

    val isSubmitting: Boolean
        get() = _stage.value == JiraReportStage.SUBMITTING

    fun reset() {
        _stage.value = JiraReportStage.IDLE
        _status.value = "Idle"
        _progress.value = 0.0
    }

    suspend fun submit(
        report: BugReport,
        idToken: String,
        userEmail: String?,
        deviceInfo: ReportDeviceInfo,
    ): JiraTicketResult {
        if (!enabled) throw JiraReportError.Disabled
        _stage.value = JiraReportStage.SUBMITTING
        _status.value = "Submitting report…"
        _progress.value = 0.1

        val boundary = "Boundary-${UUID.randomUUID()}"
        val body = MultipartReportBodyBuilder(boundary).build(
            report = report,
            userEmail = userEmail,
            deviceInfo = deviceInfo,
        )
        _progress.value = 0.4

        val response = try {
            httpClient.execute(
                BusinessHttpRequest(
                    method = "POST",
                    url = "${baseUrl.trimEnd('/')}/reports",
                    authorization = "Bearer $idToken",
                    body = body,
                    contentType = "multipart/form-data; boundary=$boundary",
                ),
            )
        } catch (error: Throwable) {
            _stage.value = JiraReportStage.FAILED
            _status.value = "Network error"
            throw JiraReportError.Network(error)
        }

        _progress.value = 0.85
        if (response.statusCode !in 200..299) {
            _stage.value = JiraReportStage.FAILED
            _status.value = "Failed"
            val serverMessage = runCatching {
                gson.fromJson(response.bodyText, ServerErrorPayload::class.java)?.message
            }.getOrNull()
            throw JiraReportError.Server(response.statusCode, serverMessage)
        }

        val result = runCatching {
            gson.fromJson(response.bodyText, JiraTicketResult::class.java)
        }.getOrNull()?.takeIf {
            it.issueKey.isNotBlank() && it.issueUrl.isNotBlank()
        }
            ?: run {
                _stage.value = JiraReportStage.FAILED
                _status.value = "Failed to parse response"
                throw JiraReportError.InvalidResponse
            }

        _stage.value = JiraReportStage.COMPLETE
        _status.value = "Reported as ${result.issueKey}"
        _progress.value = 1.0
        return result
    }
}

internal class MultipartReportBodyBuilder(private val boundary: String) {
    fun build(
        report: BugReport,
        userEmail: String?,
        deviceInfo: ReportDeviceInfo,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        fun append(value: String) {
            body.write(value.toByteArray(Charsets.UTF_8))
        }
        fun field(name: String, value: String) {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            append("$value\r\n")
        }

        field("category", report.category.wireValue)
        field("jiraLabel", report.category.jiraLabel)
        field("severity", report.severity.wireValue)
        field("jiraPriority", report.severity.jiraPriority)
        field("title", report.title)
        field("description", report.description)
        field("userEmail", userEmail ?: "unknown")
        field("appVersion", deviceInfo.appVersion)
        field("buildNumber", deviceInfo.buildNumber)
        field("osVersion", deviceInfo.osVersion)
        field("deviceModel", deviceInfo.deviceModel)

        report.screenshotJpeg?.let { screenshot ->
            append("--$boundary\r\n")
            append(
                "Content-Disposition: form-data; name=\"screenshot\"; " +
                    "filename=\"screenshot.jpg\"\r\n",
            )
            append("Content-Type: image/jpeg\r\n\r\n")
            body.write(screenshot)
            append("\r\n")
        }
        append("--$boundary--\r\n")
        return body.toByteArray()
    }
}

private data class ServerErrorPayload(val message: String? = null)
