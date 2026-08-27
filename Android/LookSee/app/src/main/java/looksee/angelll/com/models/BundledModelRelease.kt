package looksee.angelll.com.models

import android.content.Context
import android.content.res.AssetManager
import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.logging.Logger

internal data class BundledModelReleaseMetadata(
    val schemaVersion: Int = 0,
    val status: String? = null,
    val platform: String? = null,
    val format: String? = null,
    val fileExtension: String? = null,
    val clusterId: String? = null,
    val modelVersion: String? = null,
    val modelFamily: String? = null,
    val task: String? = null,
    val modelFile: String? = null,
    val modelSha256: String? = null,
    val modelSizeBytes: Long = 0,
    val landmarkManifest: String? = null,
    val manifestSchemaVersion: Int = 0,
    val classCount: Int = 0,
    val dataYaml: String? = null,
    val imageSize: Int = 0,
    val batchSize: Int = 0,
    val precision: String? = null,
    val inputLayout: String? = null,
    val preprocessing: BundledModelPreprocessing? = null,
    val outputContract: BundledModelOutputContract? = null,
    val tensors: BundledModelTensors? = null,
)

internal data class BundledModelPreprocessing(
    val colorOrder: String? = null,
    val resize: String? = null,
    val targetWidth: Int = 0,
    val targetHeight: Int = 0,
    val pixelScale: String? = null,
)

internal data class BundledModelOutputContract(
    val endToEnd: Boolean = false,
    val shape: List<Int> = emptyList(),
    val detectionFields: List<String> = emptyList(),
    val boxFormat: String? = null,
    val nmsRequired: Boolean = true,
    val confidenceFilterRequired: Boolean = false,
)

internal data class BundledModelTensors(
    val inputLayout: String? = null,
    val inputs: List<BundledModelTensor> = emptyList(),
    val outputs: List<BundledModelTensor> = emptyList(),
    val smokeTest: BundledModelSmokeTest? = null,
)

internal data class BundledModelTensor(
    val name: String? = null,
    val index: Int = -1,
    val shape: List<Int> = emptyList(),
    val shapeSignature: List<Int> = emptyList(),
    val dataType: String? = null,
)

internal data class BundledModelSmokeTest(val status: String? = null)

internal data class ValidatedBundledModelRelease(
    val clusterId: String,
    val modelVersion: String,
    val modelFamily: String,
    val classCount: Int,
    val classLabels: List<String>,
)

internal object BundledModelReleaseValidator {
    private val expectedDetectionFields = listOf(
        "x1",
        "y1",
        "x2",
        "y2",
        "confidence",
        "classIndex",
    )

    fun validate(
        release: BundledModelReleaseMetadata,
        modelFile: File,
        manifest: ClusterLandmarkManifest,
        actualModelSha256: String? = null,
    ): ValidatedBundledModelRelease {
        manifest.validate()
        require(release.schemaVersion == 1) {
            "Bundled release schemaVersion must be 1; found ${release.schemaVersion}."
        }
        require(release.status == "ready") { "Bundled release is not ready." }
        require(release.platform == "android") { "Bundled release is not for Android." }
        require(release.format == "litert") { "Bundled release format must be litert." }
        require(release.fileExtension == ".tflite") {
            "Bundled release file extension must be .tflite."
        }
        require(release.task == "detect") { "Bundled release task must be detect." }
        require(release.imageSize == MODEL_INPUT_SIZE && release.batchSize == 1) {
            "Bundled release must use a 1x${MODEL_INPUT_SIZE}x$MODEL_INPUT_SIZE input."
        }
        require(release.precision == "fp32") { "Bundled release precision must be fp32." }
        require(release.inputLayout == "NCHW") { "Bundled release input must be NCHW." }

        val clusterId = release.clusterId?.trim().orEmpty()
        val numericClusterId = clusterId.toIntOrNull()
        require(numericClusterId != null) { "Bundled release clusterId must be numeric." }
        val modelVersion = release.modelVersion?.trim().orEmpty()
        require(modelVersion.isNotEmpty()) { "Bundled release modelVersion is empty." }
        val modelFamily = release.modelFamily?.trim().orEmpty()
        require(modelFamily.isNotEmpty()) { "Bundled release modelFamily is empty." }
        require(release.classCount > 0) { "Bundled release classCount must be positive." }
        require(release.manifestSchemaVersion == manifest.schemaVersion) {
            "Release and landmark manifest schema versions do not match."
        }
        require(numericClusterId == manifest.clusterId) {
            "Release cluster $numericClusterId does not match manifest cluster " +
                    "${manifest.clusterId}."
        }
        require(modelVersion == manifest.trainingRunId) {
            "Release version $modelVersion does not match manifest version " +
                    "${manifest.trainingRunId}."
        }
        require(release.classCount == manifest.classCount) {
            "Release classCount ${release.classCount} does not match manifest " +
                    "classCount ${manifest.classCount}."
        }

        validatePreprocessing(release.preprocessing)
        validateOutput(release.outputContract)
        validateTensors(release.tensors)
        validateModelFile(release, modelFile, actualModelSha256)

        return ValidatedBundledModelRelease(
            clusterId = clusterId,
            modelVersion = modelVersion,
            modelFamily = modelFamily,
            classCount = manifest.classCount,
            classLabels = (0 until manifest.classCount).map { classIndex ->
                requireNotNull(manifest.landmark(classIndex)).label
            },
        )
    }

    private fun validatePreprocessing(value: BundledModelPreprocessing?) {
        requireNotNull(value) { "Bundled release preprocessing is missing." }
        require(value.colorOrder == "RGB") { "Bundled model color order must be RGB." }
        require(value.resize == "letterbox") { "Bundled model resize must be letterbox." }
        require(value.targetWidth == MODEL_INPUT_SIZE && value.targetHeight == MODEL_INPUT_SIZE) {
            "Bundled model preprocessing target must be 640x640."
        }
        require(value.pixelScale == "0_to_1") {
            "Bundled model pixel scale must be 0_to_1."
        }
    }

    private fun validateOutput(value: BundledModelOutputContract?) {
        requireNotNull(value) { "Bundled release outputContract is missing." }
        require(value.endToEnd) { "Bundled model output must be end-to-end." }
        require(value.shape == listOf(1, 300, 6)) {
            "Bundled model output shape must be [1, 300, 6]."
        }
        require(value.detectionFields == expectedDetectionFields) {
            "Bundled model detection field order is unsupported."
        }
        require(value.boxFormat == "xyxy") { "Bundled model boxes must use xyxy." }
        require(!value.nmsRequired) { "Bundled model must include NMS." }
        require(value.confidenceFilterRequired) {
            "Bundled model must expose confidence values for app-side filtering."
        }
    }

    private fun validateTensors(value: BundledModelTensors?) {
        requireNotNull(value) { "Bundled release tensor metadata is missing." }
        require(value.inputLayout == "NCHW") { "Tensor input layout must be NCHW." }
        require(value.inputs.size == 1 && value.outputs.size == 1) {
            "Bundled model must have exactly one input and one output."
        }
        val input = value.inputs.single()
        require(input.shape == listOf(1, 3, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)) {
            "Bundled model input tensor must be [1, 3, 640, 640]."
        }
        require(input.dataType == "float32") { "Bundled model input must be float32." }
        val output = value.outputs.single()
        require(output.shape == listOf(1, 300, 6)) {
            "Bundled model output tensor must be [1, 300, 6]."
        }
        require(output.dataType == "float32") { "Bundled model output must be float32." }
        require(value.smokeTest?.status == "passed") {
            "Bundled model conversion smoke test did not pass."
        }
    }

    private fun validateModelFile(
        release: BundledModelReleaseMetadata,
        modelFile: File,
        actualModelSha256: String?,
    ) {
        require(modelFile.isFile) { "Bundled model file is missing." }
        require(modelFile.name == release.modelFile) {
            "Bundled model filename does not match release metadata."
        }
        require(modelFile.extension.equals("tflite", ignoreCase = true)) {
            "Bundled model file must end in .tflite."
        }
        require(modelFile.length() == release.modelSizeBytes) {
            "Bundled model size does not match release metadata."
        }
        require(hasTfliteIdentifier(modelFile)) {
            "Bundled model does not contain the TFL3 identifier."
        }
        val expectedHash = release.modelSha256?.trim()?.lowercase().orEmpty()
        require(expectedHash.matches(Regex("[0-9a-f]{64}"))) {
            "Bundled model SHA-256 is invalid."
        }
        require((actualModelSha256 ?: sha256(modelFile)) == expectedHash) {
            "Bundled model SHA-256 does not match release metadata."
        }
    }

    internal fun hasTfliteIdentifier(file: File): Boolean =
        file.inputStream().buffered().use { input ->
            val header = ByteArray(8)
            input.read(header) == header.size &&
                    header.copyOfRange(4, 8).contentEquals("TFL3".toByteArray())
        }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private const val MODEL_INPUT_SIZE = 640
}

internal object BundledModelAssetInstaller {
    private val gson = Gson()
    private val logger = Logger.getLogger(BundledModelAssetInstaller::class.java.name)

    fun discover(context: Context): List<BundledTestModel> {
        val assets = context.assets
        val releasePaths = findReleaseFiles(assets, MODEL_ASSET_DIRECTORY)
        val releases = releasePaths.mapNotNull { releasePath ->
            runCatching { installRelease(context, releasePath) }
                .onFailure { error ->
                    logger.warning(
                        "Could not prepare bundled release $releasePath: ${error.message}",
                    )
                }
                .getOrNull()
        }

        val releaseDirectories = releasePaths.mapTo(mutableSetOf()) {
            it.substringBeforeLast('/', missingDelimiterValue = MODEL_ASSET_DIRECTORY)
        }
        val legacyModels = assets.list(MODEL_ASSET_DIRECTORY)
            .orEmpty()
            .filter { it.endsWith(".tflite", ignoreCase = true) }
            .mapNotNull { assetName ->
                val assetPath = "$MODEL_ASSET_DIRECTORY/$assetName"
                if (releaseDirectories.contains(MODEL_ASSET_DIRECTORY)) return@mapNotNull null
                runCatching {
                    val output = File(context.codeCacheDir, assetPath)
                    copyAssetAtomically(assets, assetPath, output)
                    BundledTestModel(output)
                }.getOrNull()
            }
        return (releases + legacyModels).sortedBy(BundledTestModel::displayName)
    }

    private fun installRelease(context: Context, releaseAssetPath: String): BundledTestModel {
        val assets = context.assets
        val releaseJson = assets.open(releaseAssetPath).bufferedReader().use { it.readText() }
        val release = gson.fromJson(releaseJson, BundledModelReleaseMetadata::class.java)
            ?: error("Bundled release JSON was empty.")
        val assetDirectory = releaseAssetPath.substringBeforeLast('/')
        val modelName = safeRelativeFilename(release.modelFile, "modelFile")
        val manifestName = safeRelativeFilename(
            release.landmarkManifest,
            "landmarkManifest",
        )
        val releaseDirectory = File(
            context.codeCacheDir,
            "$MODEL_ASSET_DIRECTORY/${safeDirectoryName(release.clusterId)}/" +
                    safeDirectoryName(release.modelVersion),
        )
        val modelFile = File(releaseDirectory, modelName)
        val manifestFile = File(releaseDirectory, manifestName)
        val expectedHash = release.modelSha256?.trim()?.lowercase().orEmpty()
        var actualHash = modelFile
            .takeIf {
                it.isFile &&
                        it.length() == release.modelSizeBytes &&
                        BundledModelReleaseValidator.hasTfliteIdentifier(it)
            }
            ?.let(BundledModelReleaseValidator::sha256)
        if (actualHash != expectedHash) {
            copyAssetAtomically(assets, "$assetDirectory/$modelName", modelFile)
            actualHash = BundledModelReleaseValidator.sha256(modelFile)
            require(actualHash == expectedHash) {
                "Bundled asset $modelName failed SHA-256 validation."
            }
        }
        copyAssetAtomically(assets, "$assetDirectory/$manifestName", manifestFile)

        val manifest = LandmarkManifestStore().load(manifestFile)
        val validated = BundledModelReleaseValidator.validate(
            release,
            modelFile,
            manifest,
            actualModelSha256 = actualHash,
        )
        return BundledTestModel(
            modelFile = modelFile,
            displayName = "Cluster ${validated.clusterId} · ${validated.modelFamily}",
            classLabels = validated.classLabels,
            clusterId = validated.clusterId,
            modelVersion = validated.modelVersion,
            manifestFile = manifestFile,
            classCount = validated.classCount,
            modelKey = "$assetDirectory/$modelName",
            manifestKey = "$assetDirectory/$manifestName",
        )
    }

    private fun findReleaseFiles(assets: AssetManager, directory: String): List<String> {
        val children = assets.list(directory).orEmpty()
        if (children.isEmpty()) return emptyList()
        return children.flatMap { child ->
            val path = "$directory/$child"
            when {
                child == RELEASE_FILENAME -> listOf(path)
                assets.list(path).orEmpty().isNotEmpty() -> findReleaseFiles(assets, path)
                else -> emptyList()
            }
        }
    }

    private fun copyAssetAtomically(
        assets: AssetManager,
        assetPath: String,
        destination: File,
    ) {
        val destinationDirectory = checkNotNull(destination.parentFile) {
            "Bundled asset destination has no parent directory: $destination."
        }
        check(destinationDirectory.isDirectory || destinationDirectory.mkdirs()) {
            "Unable to create $destinationDirectory."
        }
        val temporary = File(
            destinationDirectory,
            ".${destination.name}.${UUID.randomUUID()}",
        )
        try {
            assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { input ->
                temporary.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun safeRelativeFilename(value: String?, field: String): String {
        val name = value?.trim().orEmpty()
        require(name.isNotEmpty() && name == File(name).name && name != "." && name != "..") {
            "Bundled release $field must be a simple filename."
        }
        return name
    }

    private fun safeDirectoryName(value: String?): String {
        val name = value?.trim().orEmpty()
        require(name.isNotEmpty() && name.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Bundled release identity contains unsupported path characters."
        }
        return name
    }

    private const val MODEL_ASSET_DIRECTORY = "models"
    private const val RELEASE_FILENAME = "release.json"
}
