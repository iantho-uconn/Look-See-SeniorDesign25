import Foundation

/// Identifies one immutable cluster-model release.
///
/// For the first implementation, `trainingRunId` is treated as the model version.
struct ClusterReleaseKey: Hashable, Codable {
    let clusterId: Int
    let trainingRunId: String
}

/// The cluster-local metadata generated beside `data.yaml` during dataset packaging.
struct ClusterLandmarkManifest: Codable, Equatable {
    let schemaVersion: Int
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

    /// Returns the landmark mapped to the model's zero-based class index.
    func landmark(for classIndex: Int) -> LandmarkManifestEntry? {
        landmarks[String(classIndex)]
    }

    /// Validates the mapping before the app registers or activates it.
    func validate() throws {
        guard schemaVersion == 1 else {
            throw LandmarkManifestValidationError.unsupportedSchemaVersion(schemaVersion)
        }

        guard !trainingRunId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw LandmarkManifestValidationError.emptyTrainingRunId
        }

        guard classCount >= 0 else {
            throw LandmarkManifestValidationError.invalidClassCount(classCount)
        }

        guard landmarks.count == classCount else {
            throw LandmarkManifestValidationError.classCountMismatch(
                declared: classCount,
                actual: landmarks.count
            )
        }

        for classIndex in 0..<classCount {
            let key = String(classIndex)

            guard let entry = landmarks[key] else {
                throw LandmarkManifestValidationError.missingClassIndex(classIndex)
            }

            guard entry.classIndex == classIndex else {
                throw LandmarkManifestValidationError.classIndexMismatch(
                    dictionaryKey: classIndex,
                    entryValue: entry.classIndex
                )
            }

            guard !entry.landmarkId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw LandmarkManifestValidationError.emptyLandmarkId(classIndex: classIndex)
            }

            guard !entry.datasetClassName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw LandmarkManifestValidationError.emptyDatasetClassName(classIndex: classIndex)
            }

            guard !entry.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw LandmarkManifestValidationError.emptyDisplayLabel(classIndex: classIndex)
            }
        }
    }
}

/// The display information associated with one cluster-local class index.
struct LandmarkManifestEntry: Codable, Equatable, Identifiable {
    let classIndex: Int
    let landmarkId: String
    let datasetClassName: String
    let label: String
    let shortDescription: String
    let positiveImageCount: Int?

    var id: String {
        landmarkId
    }
}

enum LandmarkManifestValidationError: LocalizedError, Equatable {
    case unsupportedSchemaVersion(Int)
    case emptyTrainingRunId
    case invalidClassCount(Int)
    case classCountMismatch(declared: Int, actual: Int)
    case missingClassIndex(Int)
    case classIndexMismatch(dictionaryKey: Int, entryValue: Int)
    case emptyLandmarkId(classIndex: Int)
    case emptyDatasetClassName(classIndex: Int)
    case emptyDisplayLabel(classIndex: Int)

    var errorDescription: String? {
        switch self {
        case .unsupportedSchemaVersion(let version):
            return "Unsupported landmark manifest schema version: \(version)."

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
        }
    }
}
