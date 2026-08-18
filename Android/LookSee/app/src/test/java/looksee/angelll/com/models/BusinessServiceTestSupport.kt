package looksee.angelll.com.models

internal class RecordingBusinessHttpClient(
    vararg responses: BusinessHttpResponse,
) : BusinessHttpClient {
    private val queued = ArrayDeque(responses.toList())
    val requests = mutableListOf<BusinessHttpRequest>()

    override suspend fun execute(request: BusinessHttpRequest): BusinessHttpResponse {
        requests += request
        return queued.removeFirstOrNull()
            ?: error("No HTTP response was queued for ${request.method} ${request.url}")
    }
}

internal fun jsonResponse(json: String, statusCode: Int = 200) = BusinessHttpResponse(
    statusCode = statusCode,
    body = json.toByteArray(Charsets.UTF_8),
)

internal fun fixedToken(value: String = "id-token") = IdTokenProvider { value }
