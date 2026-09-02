package looksee.angelll.com.models

/** Identifies one immutable cluster-model release. */
data class ClusterReleaseKey(
    val clusterId: Int,
    val trainingRunId: String,
)

/**
 * Cluster-local landmark metadata generated beside the model's data.yaml.
 *
 * The JSON field names intentionally match the iOS/backend contract. A model
 * release and its manifest are always paired by [releaseKey].
 */
data class ClusterLandmarkManifest(
    val schemaVersion: Int,
    val clusterId: Int,
    val trainingRunId: String,
    val generatedAt: String?,
    val classCount: Int,
    val coordinateSystem: String?,
    val landmarks: Map<String, LandmarkManifestEntry>,
) {
    val releaseKey: ClusterReleaseKey
        get() = ClusterReleaseKey(
            clusterId = clusterId,
            trainingRunId = trainingRunId,
        )

    /** Returns the landmark mapped to the model's zero-based class index. */
    fun landmark(classIndex: Int): LandmarkManifestEntry? =
        landmarks[classIndex.toString()]

    /** Validates the mapping before the app registers or activates it. */
    @Throws(LandmarkManifestValidationException::class)
    fun validate() {
        if (schemaVersion != 1 && schemaVersion != 2) {
            throw LandmarkManifestValidationException.UnsupportedSchemaVersion(schemaVersion)
        }

        if (trainingRunId.isBlank()) {
            throw LandmarkManifestValidationException.EmptyTrainingRunId
        }

        if (classCount < 0) {
            throw LandmarkManifestValidationException.InvalidClassCount(classCount)
        }

        if (landmarks.size != classCount) {
            throw LandmarkManifestValidationException.ClassCountMismatch(
                declared = classCount,
                actual = landmarks.size,
            )
        }

        if (schemaVersion == 2 && coordinateSystem != WGS84) {
            throw LandmarkManifestValidationException.InvalidCoordinateSystem(coordinateSystem)
        }

        repeat(classCount) { classIndex ->
            val entry = landmarks[classIndex.toString()]
                ?: throw LandmarkManifestValidationException.MissingClassIndex(classIndex)

            if (entry.classIndex != classIndex) {
                throw LandmarkManifestValidationException.ClassIndexMismatch(
                    dictionaryKey = classIndex,
                    entryValue = entry.classIndex,
                )
            }

            if (entry.landmarkId.isBlank()) {
                throw LandmarkManifestValidationException.EmptyLandmarkId(classIndex)
            }

            if (entry.datasetClassName.isBlank()) {
                throw LandmarkManifestValidationException.EmptyDatasetClassName(classIndex)
            }

            if (entry.label.isBlank()) {
                throw LandmarkManifestValidationException.EmptyDisplayLabel(classIndex)
            }

            if (!entry.latitude.isFinite() || entry.latitude !in -90.0..90.0) {
                throw LandmarkManifestValidationException.InvalidCoordinate(
                    classIndex = classIndex,
                    field = "latitude",
                    value = entry.latitude,
                )
            }

            if (!entry.longitude.isFinite() || entry.longitude !in -180.0..180.0) {
                throw LandmarkManifestValidationException.InvalidCoordinate(
                    classIndex = classIndex,
                    field = "longitude",
                    value = entry.longitude,
                )
            }
        }
    }

    private companion object {
        const val WGS84 = "WGS84"
    }
}

/** Display information associated with one cluster-local class index. */
data class LandmarkManifestEntry(
    val classIndex: Int,
    val landmarkId: String,
    val datasetClassName: String,
    val label: String,
    val shortDescription: String,
    val latitude: Double,
    val longitude: Double,
    val positiveImageCount: Int?,
) {
    val id: String
        get() = landmarkId
}

sealed class LandmarkManifestValidationException(message: String) :
    IllegalArgumentException(message) {

    class UnsupportedSchemaVersion(val version: Int) :
        LandmarkManifestValidationException(
            "Unsupported landmark manifest schema version: $version.",
        )

    data object EmptyTrainingRunId :
        LandmarkManifestValidationException(
            "The landmark manifest has an empty trainingRunId.",
        )

    class InvalidClassCount(val count: Int) :
        LandmarkManifestValidationException(
            "The landmark manifest has an invalid classCount: $count.",
        )

    class ClassCountMismatch(val declared: Int, val actual: Int) :
        LandmarkManifestValidationException(
            "The landmark manifest declares $declared classes but contains " +
                "$actual landmark entries.",
        )

    class MissingClassIndex(val index: Int) :
        LandmarkManifestValidationException(
            "The landmark manifest is missing class index $index.",
        )

    class ClassIndexMismatch(val dictionaryKey: Int, val entryValue: Int) :
        LandmarkManifestValidationException(
            "The landmark manifest key $dictionaryKey contains an entry whose " +
                "classIndex is $entryValue.",
        )

    class EmptyLandmarkId(val classIndex: Int) :
        LandmarkManifestValidationException(
            "The landmark at class index $classIndex has an empty landmarkId.",
        )

    class EmptyDatasetClassName(val classIndex: Int) :
        LandmarkManifestValidationException(
            "The landmark at class index $classIndex has an empty datasetClassName.",
        )

    class EmptyDisplayLabel(val classIndex: Int) :
        LandmarkManifestValidationException(
            "The landmark at class index $classIndex has an empty display label.",
        )

    class InvalidCoordinateSystem(val coordinateSystem: String?) :
        LandmarkManifestValidationException(
            "Schema 2 requires coordinateSystem WGS84; found " +
                "${coordinateSystem ?: "null"}.",
        )

    class InvalidCoordinate(
        val classIndex: Int,
        val field: String,
        val value: Double,
    ) : LandmarkManifestValidationException(
        "The landmark at class index $classIndex has an invalid $field: $value.",
    )
}
