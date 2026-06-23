import Foundation

/// Temporary development-only check that proves the generated cluster manifest
/// can be loaded from the app bundle and resolved by model class index.
enum LandmarkManifestSmokeTest {
    static let resourceName = "cluster-0-landmark-manifest"
    static let resourceExtension = "json"

    private struct ExpectedLookup {
        let classIndex: Int
        let expectedLabel: String?
    }

    private static let expectedLookups: [ExpectedLookup] = [
        ExpectedLookup(classIndex: 0, expectedLabel: "Dodd Center Stone Book"),
        ExpectedLookup(classIndex: 8, expectedLabel: "Ian’s Tea Cup"),
        ExpectedLookup(classIndex: 17, expectedLabel: "Uconn planetarium"),
        ExpectedLookup(classIndex: 99, expectedLabel: nil)
    ]

    /// Loads the bundled JSON, validates it, registers it in the shared store,
    /// and verifies known class-index lookups.
    ///
    /// - Returns: `true` when every lookup matches the expected result.
    @discardableResult
    static func run(
        bundle: Bundle = .main,
        store: LandmarkManifestStore = .shared
    ) -> Bool {
        print("\n🧪 [Manifest Smoke Test] Starting")

        guard let manifestURL = bundle.url(
            forResource: resourceName,
            withExtension: resourceExtension
        ) else {
            print(
                "❌ [Manifest Smoke Test] Bundle resource not found: " +
                "\(resourceName).\(resourceExtension)"
            )
            print(
                "   Confirm the JSON file has LookSee target membership and " +
                "appears under Build Phases → Copy Bundle Resources."
            )
            return false
        }

        do {
            let manifest = try store.load(from: manifestURL)

            print("✅ [Manifest Smoke Test] Manifest decoded and validated")
            print("   clusterId: \(manifest.clusterId)")
            print("   trainingRunId: \(manifest.trainingRunId)")
            print("   classCount: \(manifest.classCount)")

            var allPassed = true

            for expectation in expectedLookups {
                let result = store.resolve(
                    clusterId: manifest.clusterId,
                    trainingRunId: manifest.trainingRunId,
                    classIndex: expectation.classIndex
                )

                switch (expectation.expectedLabel, result) {
                case let (.some(expectedLabel), .some(entry))
                    where entry.label == expectedLabel:
                    print(
                        "✅ classIndex \(expectation.classIndex) → " +
                        "\(entry.label) | \(entry.shortDescription)"
                    )

                case let (.some(expectedLabel), .some(entry)):
                    allPassed = false
                    print(
                        "❌ classIndex \(expectation.classIndex) expected " +
                        "'\(expectedLabel)' but resolved '\(entry.label)'"
                    )

                case let (.some(expectedLabel), .none):
                    allPassed = false
                    print(
                        "❌ classIndex \(expectation.classIndex) expected " +
                        "'\(expectedLabel)' but resolved nil"
                    )

                case (.none, .none):
                    print(
                        "✅ classIndex \(expectation.classIndex) → nil " +
                        "(expected invalid index)"
                    )

                case let (.none, .some(entry)):
                    allPassed = false
                    print(
                        "❌ classIndex \(expectation.classIndex) expected nil " +
                        "but resolved '\(entry.label)'"
                    )
                }
            }

            if allPassed {
                print("✅ [Manifest Smoke Test] PASS\n")
            } else {
                print("❌ [Manifest Smoke Test] FAIL\n")
            }

            return allPassed
        } catch {
            print("❌ [Manifest Smoke Test] Failed: \(error.localizedDescription)\n")
            return false
        }
    }
}
