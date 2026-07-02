

//
//  MockManifest.swift
//  LookSeeTake2
//
//  Local test data that mirrors the real AWS manifest format.
//  Swap this out for the real loader once the backend is ready.
//  DELETE or flag with #if DEBUG before shipping to production.
//

import Foundation

#if DEBUG

extension ModelManifest {

    /// A fully populated manifest using real UConn landmark coordinates.
    /// Class indices match what your test model was trained on.
    static var mock: ModelManifest {
        ModelManifest(
            schemaVersion: 1,
            clusterId: 0,
            classCount: 2,
            landmarks: [
                "0": ObjectInfo(
                    classIndex: 0,
                    landmarkId: "landmark_D9E2C9EF",
                    label: "Dodd Center Stone Book",
                    shortDescription: "A representation of a book made of stone in front of the Dodd Center.",
                    latitude: 41.80792,   // ← real coordinates
                    longitude: -72.25183
                ),
                "1": ObjectInfo(
                    classIndex: 1,
                    landmarkId: "landmark_50BC3C5C",
                    label: "Dove Tower",
                    shortDescription: "Tower structure",
                    latitude: 41.80841,
                    longitude: -72.25401
                )
            ]
        )
    }
}

#endif

