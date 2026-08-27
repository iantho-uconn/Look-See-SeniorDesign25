package looksee.angelll.com.models

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.logging.Logger
import java.util.zip.ZipInputStream

data class ObjectLocation(
    val clusterId: String,
    val lat: Double,
    val lon: Double,
)

/** One complete, immutable Android model release. */
data class ModelInfo(
    val name: String,
    val downloadUrl: String,
    val manifestUrl: String,
    val reason: String,
    val clusterId: String,
    val modelVersion: String,
    val modelKey: String?,
    val manifestKey: String?,
    val manifestSchemaVersion: Int?,
    val classCount: Int,
    val modelFile: File,
    val manifestFile: File,
    val platform: String = "android",
    val format: String = "litert",
    val modelSha256: String? = null,
    val modelSizeBytes: Long? = null,
    val objects: List<ObjectLocation> = emptyList(),
) {
    val id: String
        get() = releaseIdentifier

    val releaseIdentifier: String
        get() = "$clusterId|$modelVersion"
}

sealed interface ModelPullReason {
    data object None : ModelPullReason
    data class Single(val reason: String) : ModelPullReason
    data class Multiple(val reasons: List<String>) : ModelPullReason
}

sealed interface ModelState {
    data object NotLoaded : ModelState
    data object Loading : ModelState
    data class Loaded(val models: List<ModelInfo>) : ModelState
    data class Failed(val message: String) : ModelState
}

data class ModelHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

/** Injectable transport keeps model installation deterministic in local tests. */
interface ModelTransport {
    suspend fun postJson(url: String, jsonBody: String): ModelHttpResponse
    suspend fun get(url: String): ModelHttpResponse
    suspend fun download(url: String, destination: File): Int
}

class HttpModelTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelTransport {
    override suspend fun postJson(url: String, jsonBody: String): ModelHttpResponse =
        withContext(ioDispatcher) {
            val connection = open(url).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            try {
                connection.outputStream.use { output ->
                    output.write(jsonBody.toByteArray(StandardCharsets.UTF_8))
                }
                connection.readResponse()
            } finally {
                connection.disconnect()
            }
        }

    override suspend fun get(url: String): ModelHttpResponse = withContext(ioDispatcher) {
        val connection = open(url).apply { requestMethod = "GET" }
        try {
            connection.readResponse()
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(url: String, destination: File): Int =
        withContext(ioDispatcher) {
            val connection = open(url).apply { requestMethod = "GET" }
            try {
                val statusCode = connection.responseCode
                if (statusCode in 200..299) {
                    destination.parentFile?.mkdirs()
                    connection.inputStream.use { input ->
                        destination.outputStream().buffered().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    connection.errorStream?.close()
                    destination.delete()
                }
                statusCode
            } finally {
                connection.disconnect()
            }
        }

    private fun open(url: String): HttpURLConnection =
        URI(url).toURL().openConnection().let { connection ->
            connection as? HttpURLConnection
                ?: throw ModelReleaseException.InvalidUrl(url)
        }.apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            useCaches = false
        }

    private fun HttpURLConnection.readResponse(): ModelHttpResponse {
        val statusCode = responseCode
        val stream = if (statusCode in 200..299) inputStream else errorStream
        return ModelHttpResponse(
            statusCode = statusCode,
            body = stream?.use { it.readBytes() } ?: ByteArray(0),
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 120_000
    }
}

sealed class ModelReleaseException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause) {
    data class ServerError(val statusCode: Int) : ModelReleaseException(
        "The model discovery server returned HTTP $statusCode.",
    )

    data class MissingModelUrl(val clusterId: String) : ModelReleaseException(
        "Cluster $clusterId did not include a model download URL.",
    )

    data class MissingManifestUrl(val clusterId: String) : ModelReleaseException(
        "Cluster $clusterId did not include a landmark manifest URL.",
    )

    data class MissingModelVersion(val clusterId: String) : ModelReleaseException(
        "Cluster $clusterId did not include a modelVersion.",
    )

    data class MissingClusterId(val detail: String) : ModelReleaseException(
        "A model record included an invalid clusterId: $detail.",
    )

    data class InvalidUrl(val value: String) : ModelReleaseException(
        "The model service received an invalid HTTP URL: $value.",
    )

    data class ManifestDownloadFailed(val statusCode: Int) : ModelReleaseException(
        "The landmark manifest download returned HTTP $statusCode.",
    )

    data class ModelDownloadFailed(val statusCode: Int) : ModelReleaseException(
        "The model download returned HTTP $statusCode.",
    )

    data class NonNumericClusterId(val clusterId: String) : ModelReleaseException(
        "The landmark manifest requires a numeric cluster ID, but received $clusterId.",
    )

    data class ManifestClusterMismatch(val expected: Int, val actual: Int) :
        ModelReleaseException(
            "The manifest cluster ID $actual does not match release cluster ID $expected.",
        )

    data class ManifestVersionMismatch(val expected: String, val actual: String) :
        ModelReleaseException(
            "The manifest trainingRunId $actual does not match modelVersion $expected.",
        )

    data class ManifestSchemaMismatch(val expected: Int, val actual: Int) :
        ModelReleaseException(
            "The manifest schema version $actual does not match API value $expected.",
        )

    data class ManifestClassCountMismatch(val expected: Int, val actual: Int) :
        ModelReleaseException(
            "The manifest class count $actual does not match API value $expected.",
        )

    data class MissingTflite(val clusterId: String) : ModelReleaseException(
        "No .tflite model was found in the Android artifact for cluster $clusterId.",
    )

    data class UnsupportedModelArtifact(val clusterId: String, val artifact: String) :
        ModelReleaseException(
            "Cluster $clusterId returned an iOS or unsupported model artifact ($artifact). " +
                    "The discovery API must return the Android .tflite release.",
        )

    data class NoUsableModels(val details: String) : ModelReleaseException(
        "The discovery API returned model records, but none were usable on Android: $details",
    )

    data class ModelSizeMismatch(val expected: Long, val actual: Long) :
        ModelReleaseException(
            "The downloaded TFLite size was $actual bytes; expected $expected bytes.",
        )

    data class ModelChecksumMismatch(val expected: String, val actual: String) :
        ModelReleaseException(
            "The downloaded TFLite SHA-256 was $actual; expected $expected.",
        )

    data class InvalidTflite(val fileName: String) : ModelReleaseException(
        "$fileName is not a valid TensorFlow Lite flatbuffer.",
    )

    data class InvalidDiscoveryResponse(val detail: String, val source: Throwable? = null) :
        ModelReleaseException("Unable to decode model discovery response: $detail.", source)
}

/**
 * Discovers, downloads, validates, and atomically installs Android model releases.
 *
 * A release is visible in [state] only after its `Model.tflite` and matching
 * `landmark-manifest.json` are both present and validated.
 */
class ModelService(
    private val modelsDirectory: File,
    private val manifestStore: LandmarkManifestStore = LandmarkManifestStore.shared,
    private val transport: ModelTransport = HttpModelTransport(),
    private val apiUrl: String = DEFAULT_API_URL,
) {
    private val gson = Gson()
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val _pullReason = MutableStateFlow<ModelPullReason>(ModelPullReason.None)
    val pullReason: StateFlow<ModelPullReason> = _pullReason.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0.0)
    val downloadProgress: StateFlow<Double> = _downloadProgress.asStateFlow()

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    init {
        check(modelsDirectory.exists() || modelsDirectory.mkdirs()) {
            "Unable to create model cache at ${modelsDirectory.absolutePath}."
        }
    }

    suspend fun loadModels(latitude: Double, longitude: Double) {
        _state.value = ModelState.Loading
        _pullReason.value = ModelPullReason.None
        _downloadProgress.value = 0.0

        try {
            val models = fetchDownloadAndInstallModels(
                latitude = latitude,
                longitude = longitude,
                shouldUpdateProgress = true,
            )
            updatePullReason(models)
            _downloadProgress.value = 1.0
            _state.value = ModelState.Loaded(models)
        } catch (error: Exception) {
            logger.severe("Model load failed: ${error.message}")
            _state.value = ModelState.Failed(error.message ?: "Unknown model-service error.")
        }
    }

    /** Refreshes without replacing a working release with a loading/error state. */
    suspend fun refreshModelsSilentlyIfNeeded(latitude: Double, longitude: Double): Boolean {
        if (!refreshMutex.tryLock()) {
            logger.info("Model silent refresh skipped: refresh already in progress.")
            return false
        }

        try {
            val oldSignature = currentLoadedModelSignature()
            val newModels = fetchDownloadAndInstallModels(
                latitude = latitude,
                longitude = longitude,
                shouldUpdateProgress = false,
            )
            val newSignature = modelSignature(newModels)

            if (oldSignature == newSignature) return false

            updatePullReason(newModels)
            _state.value = ModelState.Loaded(newModels)
            return true
        } catch (error: Exception) {
            logger.warning(
                "Model silent refresh failed; keeping current releases: ${error.message}",
            )
            return false
        } finally {
            refreshMutex.unlock()
        }
    }

    suspend fun reloadModels(latitude: Double, longitude: Double) {
        clearCache()
        loadModels(latitude = latitude, longitude = longitude)
    }

    suspend fun checkForUpdates(latitude: Double, longitude: Double) {
        _updateAvailable.value = refreshModelsSilentlyIfNeeded(latitude, longitude)
    }

    fun deleteModels() {
        clearCache()
    }

    suspend fun checkIfShouldReload(latitude: Double, longitude: Double) {
        refreshModelsSilentlyIfNeeded(latitude, longitude)
    }

    private suspend fun fetchDownloadAndInstallModels(
        latitude: Double,
        longitude: Double,
        shouldUpdateProgress: Boolean,
    ): List<ModelInfo> {
        requireHttpUrl(apiUrl)
        val requestJson = gson.toJson(
            mapOf(
                "latitude" to latitude,
                "longitude" to longitude,
                "platform" to ANDROID_PLATFORM,
                "format" to ANDROID_FORMAT,
            ),
        )
        val response = transport.postJson(apiUrl, requestJson)
        if (response.statusCode !in 200..299) {
            throw ModelReleaseException.ServerError(response.statusCode)
        }

        val parsed = decodeDiscoveryResponse(response.body)
        if (shouldUpdateProgress) _downloadProgress.value = 0.15

        val allObjects = parsed.objects.orEmpty().map { payload ->
            ObjectLocation(
                clusterId = normalizeClusterId(resolveClusterId(payload.clusterId)),
                lat = payload.lat,
                lon = payload.lon,
            )
        }

        if (parsed.models.isEmpty()) {
            if (shouldUpdateProgress) _downloadProgress.value = 1.0
            return emptyList()
        }

        val models = mutableListOf<ModelInfo>()
        val preparationFailures = mutableListOf<String>()
        val progressPerModel = 0.85 / parsed.models.size.toDouble()

        parsed.models.forEachIndexed { index, payload ->
            val clusterId = runCatching {
                normalizeClusterId(resolveClusterId(payload.clusterId))
            }.getOrElse { "unknown" }

            try {
                models += prepareModelInfo(payload, parsed.reason, allObjects)
            } catch (error: Exception) {
                preparationFailures += "cluster $clusterId: ${error.message}"
                logger.warning(
                    "Skipping incomplete release for cluster $clusterId: ${error.message}",
                )
            }

            if (shouldUpdateProgress) {
                _downloadProgress.value = 0.15 + progressPerModel * (index + 1)
            }
        }

        if (models.isEmpty()) {
            throw ModelReleaseException.NoUsableModels(
                preparationFailures.joinToString(separator = "; ")
                    .ifBlank { "unknown release preparation failure" },
            )
        }

        return models
    }

    private suspend fun prepareModelInfo(
        payload: ModelPayload,
        reason: String,
        allObjects: List<ObjectLocation>,
    ): ModelInfo {
        val clusterId = normalizeClusterId(resolveClusterId(payload.clusterId))
        val modelVersion = payload.modelVersion?.trim().orEmpty()
        if (modelVersion.isEmpty()) {
            throw ModelReleaseException.MissingModelVersion(clusterId)
        }

        val modelUrl = payload.downloadUrl?.takeIf { it.isNotBlank() }
            ?: payload.modelUrl?.takeIf { it.isNotBlank() }
            ?: throw ModelReleaseException.MissingModelUrl(clusterId)
        val manifestUrl = payload.manifestUrl?.takeIf { it.isNotBlank() }
            ?: throw ModelReleaseException.MissingManifestUrl(clusterId)
        requireHttpUrl(modelUrl)
        requireHttpUrl(manifestUrl)
        validateAndroidArtifact(payload, clusterId, modelUrl)

        val prepared = prepareRelease(
            clusterId = clusterId,
            modelVersion = modelVersion,
            modelUrl = modelUrl,
            manifestUrl = manifestUrl,
            expectedManifestSchemaVersion = payload.manifestSchemaVersion,
            expectedClassCount = payload.classCount,
            expectedModelSha256 = payload.modelSha256,
            expectedModelSizeBytes = payload.modelSizeBytes,
        )

        return ModelInfo(
            name = clusterId,
            downloadUrl = modelUrl,
            manifestUrl = manifestUrl,
            reason = reason,
            clusterId = clusterId,
            modelVersion = modelVersion,
            modelKey = payload.modelKey,
            manifestKey = payload.manifestKey,
            manifestSchemaVersion = payload.manifestSchemaVersion,
            classCount = prepared.manifest.classCount,
            modelFile = prepared.modelFile,
            manifestFile = prepared.manifestFile,
            platform = payload.platform ?: ANDROID_PLATFORM,
            format = payload.format ?: ANDROID_FORMAT,
            modelSha256 = payload.modelSha256,
            modelSizeBytes = payload.modelSizeBytes,
            objects = allObjects.filter { it.clusterId == clusterId },
        )
    }

    private suspend fun prepareRelease(
        clusterId: String,
        modelVersion: String,
        modelUrl: String,
        manifestUrl: String,
        expectedManifestSchemaVersion: Int?,
        expectedClassCount: Int?,
        expectedModelSha256: String?,
        expectedModelSizeBytes: Long?,
    ): PreparedRelease {
        val releaseDirectory = releaseDirectory(clusterId, modelVersion)
        val modelFile = File(releaseDirectory, MODEL_FILE_NAME)
        val manifestFile = File(releaseDirectory, MANIFEST_FILE_NAME)

        if (modelFile.isFile && manifestFile.isFile) {
            try {
                validateTflite(modelFile)
                validateArtifactIntegrity(
                    modelFile,
                    expectedModelSha256,
                    expectedModelSizeBytes,
                )
                val manifest = decodeAndValidateManifest(
                    data = manifestFile.readBytes(),
                    clusterId = clusterId,
                    modelVersion = modelVersion,
                    expectedSchemaVersion = expectedManifestSchemaVersion,
                    expectedClassCount = expectedClassCount,
                )
                manifestStore.register(manifest)
                return PreparedRelease(modelFile, manifestFile, manifest)
            } catch (error: Exception) {
                logger.warning("Replacing invalid cached release: ${error.message}")
                releaseDirectory.deleteRecursively()
            }
        } else if (modelFile.exists() || manifestFile.exists() || releaseDirectory.exists()) {
            logger.warning("Removing partial cached release $clusterId|$modelVersion.")
            releaseDirectory.deleteRecursively()
        }

        return downloadAndInstallRelease(
            clusterId = clusterId,
            modelVersion = modelVersion,
            modelUrl = modelUrl,
            manifestUrl = manifestUrl,
            finalReleaseDirectory = releaseDirectory,
            expectedManifestSchemaVersion = expectedManifestSchemaVersion,
            expectedClassCount = expectedClassCount,
            expectedModelSha256 = expectedModelSha256,
            expectedModelSizeBytes = expectedModelSizeBytes,
        )
    }

    private suspend fun downloadAndInstallRelease(
        clusterId: String,
        modelVersion: String,
        modelUrl: String,
        manifestUrl: String,
        finalReleaseDirectory: File,
        expectedManifestSchemaVersion: Int?,
        expectedClassCount: Int?,
        expectedModelSha256: String?,
        expectedModelSizeBytes: Long?,
    ): PreparedRelease {
        val stagingDirectory = File(modelsDirectory, ".staging-${UUID.randomUUID()}")
        val workDirectory = File(stagingDirectory, "_work")
        val stagedManifest = File(stagingDirectory, MANIFEST_FILE_NAME)
        val stagedModel = File(stagingDirectory, MODEL_FILE_NAME)
        val downloadedModel = File(workDirectory, "model-download")

        check(workDirectory.mkdirs()) {
            "Unable to create staging directory ${workDirectory.absolutePath}."
        }

        try {
            val manifestResponse = transport.get(manifestUrl)
            if (manifestResponse.statusCode !in 200..299) {
                throw ModelReleaseException.ManifestDownloadFailed(
                    manifestResponse.statusCode,
                )
            }

            val manifest = decodeAndValidateManifest(
                data = manifestResponse.body,
                clusterId = clusterId,
                modelVersion = modelVersion,
                expectedSchemaVersion = expectedManifestSchemaVersion,
                expectedClassCount = expectedClassCount,
            )
            writeAtomically(stagedManifest, manifestResponse.body)

            val modelStatus = transport.download(modelUrl, downloadedModel)
            if (modelStatus !in 200..299) {
                throw ModelReleaseException.ModelDownloadFailed(modelStatus)
            }
            materializeTflite(downloadedModel, stagedModel, clusterId)
            validateTflite(stagedModel)
            validateArtifactIntegrity(
                stagedModel,
                expectedModelSha256,
                expectedModelSizeBytes,
            )

            workDirectory.deleteRecursively()
            finalReleaseDirectory.parentFile?.mkdirs()
            finalReleaseDirectory.deleteRecursively()
            moveDirectoryAtomically(stagingDirectory, finalReleaseDirectory)

            val finalModel = File(finalReleaseDirectory, MODEL_FILE_NAME)
            val finalManifest = File(finalReleaseDirectory, MANIFEST_FILE_NAME)
            try {
                manifestStore.register(manifest)
            } catch (error: Exception) {
                finalReleaseDirectory.deleteRecursively()
                throw error
            }

            return PreparedRelease(finalModel, finalManifest, manifest)
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    private fun decodeAndValidateManifest(
        data: ByteArray,
        clusterId: String,
        modelVersion: String,
        expectedSchemaVersion: Int?,
        expectedClassCount: Int?,
    ): ClusterLandmarkManifest {
        // Decode in an isolated store so an unpaired manifest never reaches shared state.
        val manifest = LandmarkManifestStore().load(data)
        val numericClusterId = normalizeClusterId(clusterId).toIntOrNull()
            ?: throw ModelReleaseException.NonNumericClusterId(clusterId)

        if (manifest.clusterId != numericClusterId) {
            throw ModelReleaseException.ManifestClusterMismatch(
                expected = numericClusterId,
                actual = manifest.clusterId,
            )
        }
        if (manifest.trainingRunId != modelVersion) {
            throw ModelReleaseException.ManifestVersionMismatch(
                expected = modelVersion,
                actual = manifest.trainingRunId,
            )
        }
        if (expectedSchemaVersion != null &&
            manifest.schemaVersion != expectedSchemaVersion
        ) {
            throw ModelReleaseException.ManifestSchemaMismatch(
                expected = expectedSchemaVersion,
                actual = manifest.schemaVersion,
            )
        }
        if (expectedClassCount != null && manifest.classCount != expectedClassCount) {
            throw ModelReleaseException.ManifestClassCountMismatch(
                expected = expectedClassCount,
                actual = manifest.classCount,
            )
        }
        return manifest
    }

    private fun materializeTflite(downloaded: File, target: File, clusterId: String) {
        if (downloaded.isZip()) {
            var found = false
            ZipInputStream(downloaded.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".tflite")) {
                        target.outputStream().buffered().use { output -> zip.copyTo(output) }
                        found = true
                        break
                    }
                    zip.closeEntry()
                }
            }
            if (!found) throw ModelReleaseException.MissingTflite(clusterId)
        } else {
            Files.copy(
                downloaded.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun validateTflite(file: File) {
        if (!file.isFile || file.length() < TFLITE_MINIMUM_HEADER_BYTES) {
            throw ModelReleaseException.InvalidTflite(file.name)
        }
        RandomAccessFile(file, "r").use { model ->
            model.seek(TFLITE_IDENTIFIER_OFFSET)
            val identifier = ByteArray(TFLITE_IDENTIFIER.size)
            model.readFully(identifier)
            if (!identifier.contentEquals(TFLITE_IDENTIFIER)) {
                throw ModelReleaseException.InvalidTflite(file.name)
            }
        }
    }

    private fun validateArtifactIntegrity(
        file: File,
        expectedSha256: String?,
        expectedSizeBytes: Long?,
    ) {
        if (expectedSizeBytes != null && file.length() != expectedSizeBytes) {
            throw ModelReleaseException.ModelSizeMismatch(
                expected = expectedSizeBytes,
                actual = file.length(),
            )
        }

        val normalizedExpectedSha = expectedSha256?.trim()?.lowercase().orEmpty()
        if (normalizedExpectedSha.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_DIGEST_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            if (actual != normalizedExpectedSha) {
                throw ModelReleaseException.ModelChecksumMismatch(
                    expected = normalizedExpectedSha,
                    actual = actual,
                )
            }
        }
    }

    private fun validateAndroidArtifact(
        payload: ModelPayload,
        clusterId: String,
        modelUrl: String,
    ) {
        val platform = payload.platform?.trim()?.lowercase()
        val format = payload.format?.trim()?.lowercase()
        val artifact = payload.modelKey?.takeIf { it.isNotBlank() }
            ?: runCatching { URI(modelUrl).path.substringAfterLast('/') }.getOrDefault(modelUrl)

        val platformIsAndroid = platform == null || platform == ANDROID_PLATFORM
        val formatIsLiteRt = format == null || format in ANDROID_FORMAT_ALIASES
        val artifactName = artifact.lowercase()
        val looksLikeTflite =
            artifactName.endsWith(".tflite") || artifactName.endsWith(".tflite.zip")

        if (!platformIsAndroid || !formatIsLiteRt || !looksLikeTflite) {
            throw ModelReleaseException.UnsupportedModelArtifact(clusterId, artifact)
        }
    }

    private fun File.isZip(): Boolean {
        if (!isFile || length() < ZIP_SIGNATURE.size) return false
        inputStream().use { input ->
            val signature = ByteArray(ZIP_SIGNATURE.size)
            return input.read(signature) == signature.size &&
                    signature.contentEquals(ZIP_SIGNATURE)
        }
    }

    private fun releaseDirectory(clusterId: String, modelVersion: String): File =
        File(
            File(
                modelsDirectory,
                "cluster-${sanitizePathComponent(normalizeClusterId(clusterId), "unknown-cluster")}",
            ),
            sanitizePathComponent(modelVersion, "unknown-version"),
        )

    private fun sanitizePathComponent(value: String, fallback: String): String {
        val sanitized = value
            .map { character ->
                if (character.isLetterOrDigit() || character in "._-") character else '-'
            }
            .joinToString("")
            .trim('-', '.', '_')
        return sanitized.ifEmpty { fallback }
    }

    private fun normalizeClusterId(rawValue: String): String {
        var value = rawValue.trim()
        value = when {
            value.startsWith("cluster-", ignoreCase = true) -> value.drop(8)
            value.startsWith("cluster_", ignoreCase = true) -> value.drop(8)
            else -> value
        }
        value.toLongOrNull()?.let { return it.toString() }
        value.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it % 1.0 == 0.0 }
            ?.let { return it.toLong().toString() }
        return value
    }

    private fun resolveClusterId(element: JsonElement?): String {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            throw ModelReleaseException.MissingClusterId("missing or non-primitive value")
        }
        val primitive = element.asJsonPrimitive
        if (!primitive.isString && !primitive.isNumber) {
            throw ModelReleaseException.MissingClusterId("expected string or number")
        }
        return primitive.asString
    }

    private fun requireHttpUrl(value: String) {
        try {
            val uri = URI(value)
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                throw ModelReleaseException.InvalidUrl(value)
            }
        } catch (error: ModelReleaseException.InvalidUrl) {
            throw error
        } catch (_: Exception) {
            throw ModelReleaseException.InvalidUrl(value)
        }
    }

    private fun decodeDiscoveryResponse(data: ByteArray): ModelsResponse {
        try {
            val decoded = gson.fromJson(
                data.toString(StandardCharsets.UTF_8),
                ModelsResponsePayload::class.java,
            ) ?: throw ModelReleaseException.InvalidDiscoveryResponse("empty response")
            val models = decoded.models
                ?: throw ModelReleaseException.InvalidDiscoveryResponse("models is missing")
            val reason = decoded.reason
                ?: throw ModelReleaseException.InvalidDiscoveryResponse("reason is missing")
            return ModelsResponse(models = models, reason = reason, objects = decoded.objects)
        } catch (error: ModelReleaseException.InvalidDiscoveryResponse) {
            throw error
        } catch (error: Exception) {
            throw ModelReleaseException.InvalidDiscoveryResponse(
                detail = error.message ?: "invalid JSON",
                source = error,
            )
        }
    }

    private fun updatePullReason(models: List<ModelInfo>) {
        _pullReason.value = when (models.size) {
            0 -> ModelPullReason.None
            1 -> ModelPullReason.Single(models.first().reason)
            else -> ModelPullReason.Multiple(models.map { it.reason })
        }
    }

    private fun currentLoadedModelSignature(): List<String> =
        (_state.value as? ModelState.Loaded)?.let { modelSignature(it.models) }.orEmpty()

    private fun modelSignature(models: List<ModelInfo>): List<String> =
        models.map { model ->
            listOf(
                model.clusterId,
                model.modelVersion,
                model.modelKey ?: "no-model-key",
                model.manifestKey ?: "no-manifest-key",
                model.modelSha256 ?: "no-model-sha",
                model.objects.size.toString(),
            ).joinToString("|")
        }.sorted()

    private fun clearCache() {
        modelsDirectory.deleteRecursively()
        check(modelsDirectory.mkdirs()) {
            "Unable to recreate model cache at ${modelsDirectory.absolutePath}."
        }
        manifestStore.removeAll()
        _state.value = ModelState.NotLoaded
        _pullReason.value = ModelPullReason.None
        _downloadProgress.value = 0.0
        _updateAvailable.value = false
    }

    private fun writeAtomically(destination: File, data: ByteArray) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(data)
            moveFileAtomically(temporary, destination)
        } finally {
            temporary.delete()
        }
    }

    private fun moveFileAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun moveDirectoryAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private data class PreparedRelease(
        val modelFile: File,
        val manifestFile: File,
        val manifest: ClusterLandmarkManifest,
    )

    private data class ModelsResponsePayload(
        val models: List<ModelPayload>?,
        val reason: String?,
        val objects: List<ObjectPayload>?,
    )

    private data class ModelsResponse(
        val models: List<ModelPayload>,
        val reason: String,
        val objects: List<ObjectPayload>?,
    )

    private data class ModelPayload(
        val clusterId: JsonElement?,
        val modelVersion: String?,
        val downloadUrl: String?,
        val modelUrl: String?,
        val manifestUrl: String?,
        val modelKey: String?,
        val manifestKey: String?,
        val manifestSchemaVersion: Int?,
        val classCount: Int?,
        val platform: String?,
        val format: String?,
        val modelSha256: String?,
        val modelSizeBytes: Long?,
    )

    private data class ObjectPayload(
        val clusterId: JsonElement?,
        val lat: Double,
        val lon: Double,
    )

    companion object {
        const val DEFAULT_API_URL =
            "https://o1ul6zexoj.execute-api.us-east-1.amazonaws.com/prod/discover"

        private const val MODEL_FILE_NAME = "Model.tflite"
        private const val MANIFEST_FILE_NAME = "landmark-manifest.json"
        private const val ANDROID_PLATFORM = "android"
        private const val ANDROID_FORMAT = "litert"
        private val ANDROID_FORMAT_ALIASES = setOf("litert", "tflite", "tensorflow-lite")
        private const val DEFAULT_DIGEST_BUFFER_SIZE = 64 * 1024
        private const val TFLITE_IDENTIFIER_OFFSET = 4L
        private const val TFLITE_MINIMUM_HEADER_BYTES = 8L
        private val TFLITE_IDENTIFIER = byteArrayOf('T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())
        private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val logger = Logger.getLogger(ModelService::class.java.name)

        @Volatile
        private var sharedInstance: ModelService? = null

        /** Android's equivalent of Swift's `shared`, initialized with app context. */
        fun shared(context: Context): ModelService =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: ModelService(
                    modelsDirectory = File(context.applicationContext.filesDir, "LookSeeModels"),
                ).also { sharedInstance = it }
            }
    }
}
