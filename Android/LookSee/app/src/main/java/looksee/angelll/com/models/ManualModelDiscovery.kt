package looksee.angelll.com.models

import android.content.Context
import java.io.File
import java.util.logging.Logger

/**
 * Discovers manually placed .tflite models in the app's files/MachineLearningModels directory.
 * This allows developers/power users to side-load models for testing specific clusters.
 */
internal object ManualModelDiscovery {
    private val logger = Logger.getLogger(ManualModelDiscovery::class.java.name)
    private const val DIRECTORY_NAME = "MachineLearningModels"
    private const val MANIFEST_FILENAME = "landmark-manifest.json"

    fun discover(context: Context): List<BundledTestModel> {
        val rootDir = File(context.filesDir, DIRECTORY_NAME)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            return emptyList()
        }

        // Look for subdirectories or files directly in root
        val subDirs = rootDir.listFiles { file -> file.isDirectory }.orEmpty()
        val manualModels = mutableListOf<BundledTestModel>()

        // 1. Process subdirectories (preferred: folder/Model.tflite + folder/landmark-manifest.json)
        for (dir in subDirs) {
            val tflite = dir.listFiles { f -> f.extension.equals("tflite", ignoreCase = true) }?.firstOrNull()
            if (tflite != null) {
                val manifest = File(dir, MANIFEST_FILENAME).takeIf { it.exists() && it.isFile }
                manualModels.add(createBundledModel(tflite, manifest, "Manual: ${dir.name}"))
            }
        }

        // 2. Process root files (.tflite files sitting directly in the folder)
        val rootTflites = rootDir.listFiles { f -> f.isFile && f.extension.equals("tflite", ignoreCase = true) }.orEmpty()
        for (tflite in rootTflites) {
            // Avoid duplicates if already picked up via subdirectory check (unlikely but safe)
            if (manualModels.none { it.modelFile.absolutePath == tflite.absolutePath }) {
                manualModels.add(createBundledModel(tflite, null, "Manual: ${tflite.nameWithoutExtension}"))
            }
        }

        return manualModels.sortedBy { it.displayName }
    }

    private fun createBundledModel(tflite: File, manifest: File?, name: String): BundledTestModel {
        // We attempt to extract clusterId/version from manifest if available, else use filename
        var clusterId = "manual-test"
        var modelVersion = "manual-${tflite.nameWithoutExtension}"
        var labels = emptyList<String>()
        var classCount = 0

        if (manifest != null) {
            runCatching {
                val store = LandmarkManifestStore()
                val loaded = store.load(manifest)
                clusterId = loaded.clusterId.toString()
                modelVersion = loaded.trainingRunId
                labels = (0 until loaded.classCount).mapNotNull { store.resolve(clusterId, modelVersion, it)?.label }
                classCount = loaded.classCount
            }.onFailure {
                logger.warning("Failed to parse manifest for manual model ${tflite.name}: ${it.message}")
            }
        }

        return BundledTestModel(
            modelFile = tflite,
            manifestFile = manifest,
            displayName = name,
            clusterId = clusterId,
            modelVersion = modelVersion,
            classLabels = labels,
            classCount = classCount,
            modelKey = tflite.absolutePath,
            manifestKey = manifest?.absolutePath
        )
    }
}
