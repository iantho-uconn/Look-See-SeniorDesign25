import Foundation

/// Identifies one immutable cluster-model release.
///
/// `trainingRunId` is treated as the model version.
struct ClusterReleaseKey: Hashable, Codable {
    let clusterId: Int
    let trainingRunId: String
}

/// The cluster-local metadata generated beside `data.yaml` during dataset packaging.
///
/// Migration behavior:
/// - Schema 1 remains readable for already-cached releases.
/// - Schema 2 requires WGS84 latitude and longitude for every landmark.
struct ClusterLandmarkManifest: Codable, Equatable {
    let schemaVersion: Int
    let coordinateSystem: String?
    let clusterId: Int
    let trainingRunId: String
    let generatedAt: String?
    let classCount: Int
    let landmarks: [String: LandmarkManifestEntry]

    var releaseKey: ClusterReleaseKey {
        ClusterReleaseKey(
            clusterId: clusterId,
            trainingRunId: trainingRunId
        )
    }

    var containsRequiredCoordinates: Bool {
        schemaVersion >= 2 &&
        landmarks.values.allSatisfy { $0.hasCoordinates }
    }

    func landmark(for classIndex: Int) -> LandmarkManifestEntry? {
        landmarks[String(classIndex)]
    }

    func validate() throws {
        guard schemaVersion == 1 || schemaVersion == 2 else {
            throw LandmarkManifestValidationError
                .unsupportedSchemaVersion(schemaVersion)
        }

        if schemaVersion == 2 {
            guard coordinateSystem == "WGS84" else {
                throw LandmarkManifestValidationError
                    .invalidCoordinateSystem(
                        expected: "WGS84",
                        actual: coordinateSystem
                    )
            }
        }

        guard !trainingRunId
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .isEmpty
        else {
            throw LandmarkManifestValidationError.emptyTrainingRunId
        }

        guard classCount >= 0 else {
            throw LandmarkManifestValidationError
                .invalidClassCount(classCount)
        }

        guard landmarks.count == classCount else {
            throw LandmarkManifestValidationError
                .classCountMismatch(
                    declared: classCount,
                    actual: landmarks.count
                )
        }

        for classIndex in 0..<classCount {
            let key = String(classIndex)

            guard let entry = landmarks[key] else {
                throw LandmarkManifestValidationError
                    .missingClassIndex(classIndex)
            }

            guard entry.classIndex == classIndex else {
                throw LandmarkManifestValidationError
                    .classIndexMismatch(
                        dictionaryKey: classIndex,
                        entryValue: entry.classIndex
                    )
            }

            guard !entry.landmarkId
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .isEmpty
            else {
                throw LandmarkManifestValidationError
                    .emptyLandmarkId(classIndex: classIndex)
            }

            guard !entry.datasetClassName
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .isEmpty
            else {
                throw LandmarkManifestValidationError
                    .emptyDatasetClassName(classIndex: classIndex)
            }

            guard !entry.label
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .isEmpty
            else {
                throw LandmarkManifestValidationError
                    .emptyDisplayLabel(classIndex: classIndex)
            }

            try validateCoordinates(
                for: entry,
                classIndex: classIndex
            )
        }
    }

    private func validateCoordinates(
        for entry: LandmarkManifestEntry,
        classIndex: Int
    ) throws {
        let hasLatitude = entry.latitude != nil
        let hasLongitude = entry.longitude != nil

        guard hasLatitude == hasLongitude else {
            throw LandmarkManifestValidationError
                .incompleteCoordinates(classIndex: classIndex)
        }

        if schemaVersion == 2 && !hasLatitude {
            throw LandmarkManifestValidationError
                .missingCoordinates(classIndex: classIndex)
        }

        guard
            let latitude = entry.latitude,
            let longitude = entry.longitude
        else {
            return
        }

        guard latitude.isFinite,
              (-90.0...90.0).contains(latitude)
        else {
            throw LandmarkManifestValidationError
                .invalidLatitude(
                    classIndex: classIndex,
                    latitude: latitude
                )
        }

        guard longitude.isFinite,
              (-180.0...180.0).contains(longitude)
        else {
            throw LandmarkManifestValidationError
                .invalidLongitude(
                    classIndex: classIndex,
                    longitude: longitude
                )
        }
    }
}

/// Display and geographic information for one cluster-local class index.
struct LandmarkManifestEntry: Codable, Equatable, Identifiable {
    let classIndex: Int
    let landmarkId: String
    let datasetClassName: String
    let label: String
    let shortDescription: String

    /// Required by schema 2 and absent from legacy schema-1 manifests.
    let latitude: Double?
    let longitude: Double?

    let positiveImageCount: Int?

    var id: String { landmarkId }

    var hasCoordinates: Bool {
        latitude != nil && longitude != nil
    }
}

enum LandmarkManifestValidationError: LocalizedError, Equatable {
    case unsupportedSchemaVersion(Int)
    case invalidCoordinateSystem(expected: String, actual: String?)
    case emptyTrainingRunId
    case invalidClassCount(Int)
    case classCountMismatch(declared: Int, actual: Int)
    case missingClassIndex(Int)
    case classIndexMismatch(dictionaryKey: Int, entryValue: Int)
    case emptyLandmarkId(classIndex: Int)
    case emptyDatasetClassName(classIndex: Int)
    case emptyDisplayLabel(classIndex: Int)
    case incompleteCoordinates(classIndex: Int)
    case missingCoordinates(classIndex: Int)
    case invalidLatitude(classIndex: Int, latitude: Double)
    case invalidLongitude(classIndex: Int, longitude: Double)

    var errorDescription: String? {
        switch self {
        case .unsupportedSchemaVersion(let version):
            return "Unsupported landmark manifest schema version: \(version)."

        case .invalidCoordinateSystem(let expected, let actual):
            return "The landmark manifest requires coordinate system \(expected), but received \(actual) ?? \"no value\")."

        case .emptyTrainingRunId:
            return "The landmark manifest has an empty trainingRunId."

        case .invalidClassCount(let count):
            return "The landmark manifest has an invalid classCount: \(count)."

        case .classCountMismatch(let declared, let actual):
            return "The landmark manifest declares \(declared) classes but contains \(actual) landmark entries."

        case .missingClassIndex(let index):
            return "The landmark manifest is missing class index \(index)."

        case .classIndexMismatch(let dictionaryKey, let entryValue):
            return "The landmark manifest key \(dictionaryKey) contains an entry whose classIndex is \(entryValue)."

        case .emptyLandmarkId(let classIndex):
            return "The landmark at class index \(classIndex) has an empty landmarkId."

        case .emptyDatasetClassName(let classIndex):
            return "The landmark at class index \(classIndex) has an empty datasetClassName."

        case .emptyDisplayLabel(let classIndex):
            return "The landmark at class index \(classIndex) has an empty display label."

        case .incompleteCoordinates(let classIndex):
            return "The landmark at class index \(classIndex) must include both latitude and longitude when either coordinate is present."

        case .missingCoordinates(let classIndex):
            return "The schema-version-2 landmark at class index \(classIndex) is missing latitude and longitude."

        case .invalidLatitude(let classIndex, let latitude):
            return "The landmark at class index \(classIndex) has invalid latitude \(latitude)."

        case .invalidLongitude(let classIndex, let longitude):
            return "The landmark at class index \(classIndex) has invalid longitude \(longitude)."
        }
    }
}
