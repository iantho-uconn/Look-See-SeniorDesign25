//
//  LandmarkManifestStore.swift
//  LookSeeProto
//
//  Created by Ian Thompson on 6/23/26.
//

import Foundation

/// Thread-safe in-memory storage for validated cluster landmark manifests.
///
/// The store is keyed by both `clusterId` and `trainingRunId` so a detection
/// can never accidentally resolve against metadata from a different model release.
final class LandmarkManifestStore {
    static let shared = LandmarkManifestStore()

    private var manifests: [ClusterReleaseKey: ClusterLandmarkManifest] = [:]
    private let accessQueue = DispatchQueue(
        label: "com.looksee.landmark-manifest-store",
        attributes: .concurrent
    )

    private let decoder: JSONDecoder

    init(decoder: JSONDecoder = JSONDecoder()) {
        self.decoder = decoder
    }

    /// Decodes, validates, and registers a manifest from raw JSON data.
    @discardableResult
    func load(from data: Data) throws -> ClusterLandmarkManifest {
        let manifest = try decoder.decode(ClusterLandmarkManifest.self, from: data)
        try register(manifest)
        return manifest
    }

    /// Decodes, validates, and registers a manifest stored at a local file URL.
    @discardableResult
    func load(from fileURL: URL) throws -> ClusterLandmarkManifest {
        let data = try Data(contentsOf: fileURL)
        return try load(from: data)
    }

    /// Registers a decoded manifest after validating its schema and class map.
    func register(_ manifest: ClusterLandmarkManifest) throws {
        try manifest.validate()

        accessQueue.sync(flags: .barrier) {
            manifests[manifest.releaseKey] = manifest
        }

        print(
            "✅ Registered landmark manifest " +
            "clusterId=\(manifest.clusterId), " +
            "trainingRunId=\(manifest.trainingRunId), " +
            "classCount=\(manifest.classCount)"
        )
    }

    /// Returns the complete manifest for one cluster-model release.
    func manifest(
        clusterId: Int,
        trainingRunId: String
    ) -> ClusterLandmarkManifest? {
        let key = ClusterReleaseKey(
            clusterId: clusterId,
            trainingRunId: trainingRunId
        )

        return accessQueue.sync {
            manifests[key]
        }
    }

    /// Resolves a model class index into its local landmark display information.
    func resolve(
        clusterId: Int,
        trainingRunId: String,
        classIndex: Int
    ) -> LandmarkManifestEntry? {
        guard classIndex >= 0 else {
            return nil
        }

        let key = ClusterReleaseKey(
            clusterId: clusterId,
            trainingRunId: trainingRunId
        )

        return accessQueue.sync {
            manifests[key]?.landmark(for: classIndex)
        }
    }

    /// Convenience overload for code paths that currently store cluster IDs as strings.
    func resolve(
        clusterId: String,
        trainingRunId: String,
        classIndex: Int
    ) -> LandmarkManifestEntry? {
        guard let numericClusterId = Int(clusterId) else {
            print("⚠️ Unable to resolve landmark because clusterId is not numeric: \(clusterId)")
            return nil
        }

        return resolve(
            clusterId: numericClusterId,
            trainingRunId: trainingRunId,
            classIndex: classIndex
        )
    }

    /// Removes one cached release from memory.
    func remove(
        clusterId: Int,
        trainingRunId: String
    ) {
        let key = ClusterReleaseKey(
            clusterId: clusterId,
            trainingRunId: trainingRunId
        )

        _ = accessQueue.sync(flags: .barrier) {
            manifests.removeValue(forKey: key)
        }
    }

    /// Clears every registered manifest from memory.
    func removeAll() {
        accessQueue.sync(flags: .barrier) {
            manifests.removeAll()
        }
    }

    /// Useful for diagnostics and cache-management tests.
    var registeredReleaseCount: Int {
        accessQueue.sync {
            manifests.count
        }
    }
}
