package looksee.angelll.com.models

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Thread-safe in-memory storage for validated cluster landmark manifests.
 *
 * The key includes both clusterId and trainingRunId so detections cannot resolve
 * against metadata from a different model release.
 */
class LandmarkManifestStore(
    private val decodeManifest: (String) -> ClusterLandmarkManifest =
        LandmarkManifestJsonDecoder::decode,
) {
    private val manifests = ConcurrentHashMap<ClusterReleaseKey, ClusterLandmarkManifest>()

    /** Decodes, validates, and registers a manifest from UTF-8 JSON bytes. */
    @Throws(LandmarkManifestDecodingException::class, LandmarkManifestValidationException::class)
    fun load(data: ByteArray): ClusterLandmarkManifest =
        load(data.toString(StandardCharsets.UTF_8))

    /** Decodes, validates, and registers a manifest from a JSON string. */
    @Throws(LandmarkManifestDecodingException::class, LandmarkManifestValidationException::class)
    fun load(json: String): ClusterLandmarkManifest {
        val manifest = decodeManifest(json)
        register(manifest)
        return manifest
    }

    /** Decodes, validates, and registers a manifest stored in a local file. */
    @Throws(LandmarkManifestDecodingException::class, LandmarkManifestValidationException::class)
    fun load(file: File): ClusterLandmarkManifest = load(file.readBytes())

    /** Registers a decoded manifest after validating its schema and class map. */
    @Throws(LandmarkManifestValidationException::class)
    fun register(manifest: ClusterLandmarkManifest) {
        manifest.validate()
        manifests[manifest.releaseKey] = manifest

        logger.info(
            "Registered landmark manifest " +
                "clusterId=${manifest.clusterId}, " +
                "trainingRunId=${manifest.trainingRunId}, " +
                "classCount=${manifest.classCount}",
        )
    }

    /** Returns the complete manifest for one cluster-model release. */
    fun manifest(clusterId: Int, trainingRunId: String): ClusterLandmarkManifest? =
        manifests[ClusterReleaseKey(clusterId, trainingRunId)]

    /** Resolves a model class index into local landmark display information. */
    fun resolve(
        clusterId: Int,
        trainingRunId: String,
        classIndex: Int,
    ): LandmarkManifestEntry? {
        if (classIndex < 0) return null

        return manifests[ClusterReleaseKey(clusterId, trainingRunId)]
            ?.landmark(classIndex)
    }

    /** Convenience overload for code paths that store cluster IDs as strings. */
    fun resolve(
        clusterId: String,
        trainingRunId: String,
        classIndex: Int,
    ): LandmarkManifestEntry? {
        val numericClusterId = clusterId.toIntOrNull()
        if (numericClusterId == null) {
            logger.warning(
                "Unable to resolve landmark because clusterId is not numeric: $clusterId",
            )
            return null
        }

        return resolve(
            clusterId = numericClusterId,
            trainingRunId = trainingRunId,
            classIndex = classIndex,
        )
    }

    /** Removes one cached release from memory. */
    fun remove(clusterId: Int, trainingRunId: String) {
        manifests.remove(ClusterReleaseKey(clusterId, trainingRunId))
    }

    /** Clears every registered manifest from memory. */
    fun removeAll() {
        manifests.clear()
    }

    /** Useful for diagnostics and cache-management tests. */
    val registeredReleaseCount: Int
        get() = manifests.size

    companion object {
        @JvmField
        val shared = LandmarkManifestStore()

        private val logger = Logger.getLogger(LandmarkManifestStore::class.java.name)
    }
}

class LandmarkManifestDecodingException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Strict decoder for the backend landmark-manifest.json contract.
 *
 * Parsing explicitly checks required values before constructing Kotlin's
 * non-null model, avoiding reflection-created nulls from malformed JSON.
 */
private object LandmarkManifestJsonDecoder {
    fun decode(json: String): ClusterLandmarkManifest {
        try {
            val rootElement = JsonParser.parseString(json)
            if (!rootElement.isJsonObject) {
                decodingFailure("The landmark manifest root must be a JSON object.")
            }
            val root = rootElement.asJsonObject
            val landmarkObjects = root.requiredObject("landmarks")
            val landmarks = LinkedHashMap<String, LandmarkManifestEntry>()

            landmarkObjects.entrySet().forEach { (key, value) ->
                if (!value.isJsonObject) {
                    decodingFailure("landmarks.$key must be a JSON object.")
                }
                val entry = value.asJsonObject
                landmarks[key] = LandmarkManifestEntry(
                    classIndex = entry.requiredInt("classIndex", "landmarks.$key"),
                    landmarkId = entry.requiredString("landmarkId", "landmarks.$key"),
                    datasetClassName = entry.requiredString(
                        "datasetClassName",
                        "landmarks.$key",
                    ),
                    label = entry.requiredString("label", "landmarks.$key"),
                    shortDescription = entry.requiredString(
                        "shortDescription",
                        "landmarks.$key",
                    ),
                    latitude = entry.requiredDouble("latitude", "landmarks.$key"),
                    longitude = entry.requiredDouble("longitude", "landmarks.$key"),
                    positiveImageCount = entry.optionalInt(
                        "positiveImageCount",
                        "landmarks.$key",
                    ),
                )
            }

            return ClusterLandmarkManifest(
                schemaVersion = root.requiredInt("schemaVersion"),
                clusterId = root.requiredInt("clusterId"),
                trainingRunId = root.requiredString("trainingRunId"),
                generatedAt = root.optionalString("generatedAt"),
                classCount = root.requiredInt("classCount"),
                coordinateSystem = root.optionalString("coordinateSystem"),
                landmarks = landmarks,
            )
        } catch (error: LandmarkManifestDecodingException) {
            throw error
        } catch (error: RuntimeException) {
            throw LandmarkManifestDecodingException(
                message = "Unable to decode landmark manifest JSON: ${error.message}",
                cause = error,
            )
        }
    }

    private fun JsonObject.requiredObject(name: String): JsonObject {
        val value = get(name)
        if (value == null || !value.isJsonObject) {
            decodingFailure("$name must be a JSON object.")
        }
        return value.asJsonObject
    }

    private fun JsonObject.requiredString(name: String, path: String = "root"): String {
        val value = get(name)
        if (value == null || value.isJsonNull || !value.isJsonPrimitive ||
            !value.asJsonPrimitive.isString
        ) {
            decodingFailure("$path.$name must be a string.")
        }
        return value.asString
    }

    private fun JsonObject.optionalString(name: String, path: String = "root"): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            decodingFailure("$path.$name must be a string or null.")
        }
        return value.asString
    }

    private fun JsonObject.requiredInt(name: String, path: String = "root"): Int {
        val value = get(name)
        if (!value.isJsonNumber()) {
            decodingFailure("$path.$name must be an integer.")
        }
        return value.asString.toIntOrNull()
            ?: decodingFailure("$path.$name must be an integer.")
    }

    private fun JsonObject.optionalInt(name: String, path: String = "root"): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonNumber()) {
            decodingFailure("$path.$name must be an integer or null.")
        }
        return value.asString.toIntOrNull()
            ?: decodingFailure("$path.$name must be an integer or null.")
    }

    private fun JsonObject.requiredDouble(name: String, path: String = "root"): Double {
        val value = get(name)
        if (!value.isJsonNumber()) {
            decodingFailure("$path.$name must be a number.")
        }
        return value.asString.toDoubleOrNull()
            ?: decodingFailure("$path.$name must be a number.")
    }

    private fun JsonElement?.isJsonNumber(): Boolean =
        this != null &&
            !isJsonNull &&
            isJsonPrimitive &&
            asJsonPrimitive.isNumber

    private fun decodingFailure(message: String): Nothing =
        throw LandmarkManifestDecodingException(message)
}
