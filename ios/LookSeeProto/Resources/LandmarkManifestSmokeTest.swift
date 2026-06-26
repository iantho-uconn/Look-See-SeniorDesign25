import Foundation

/// Temporary development-only check that proves the generated cluster manifest
/// can be loaded from the app bundle, resolved by model class index, and passed
/// into the existing popup state.
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

    /// Class index displayed in the temporary popup smoke test.
    private static let popupClassIndex = 8

    /// Loads the bundled JSON, validates it, verifies known class-index lookups,
    /// then opens the existing popup using class index 8.
    @MainActor
    @discardableResult
    static func run(
        bundle: Bundle = .main,
        store: LandmarkManifestStore = .shared,
        popupState: VariableContainer = .shared,
        showPopup: Bool = true
    ) -> Bool {
        print("\n🧪 [Manifest + Popup Smoke Test] Starting")

        guard let manifestURL = bundle.url(
            forResource: resourceName,
            withExtension: resourceExtension
        ) else {
            print(
                "❌ [Manifest + Popup Smoke Test] Bundle resource not found: " +
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

            print("✅ Manifest decoded and validated")
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

            if showPopup {
                guard let popupEntry = store.resolve(
                    clusterId: manifest.clusterId,
                    trainingRunId: manifest.trainingRunId,
                    classIndex: popupClassIndex
                ) else {
                    print(
                        "❌ Unable to open popup because class index " +
                        "\(popupClassIndex) did not resolve."
                    )
                    return false
                }

                popupState.presentLandmark(
                    popupEntry,
                    clusterId: manifest.clusterId,
                    trainingRunId: manifest.trainingRunId,
                    detectionConfidence: 0.92
                )

                print(
                    "✅ Popup state populated with class index " +
                    "\(popupClassIndex): \(popupEntry.label)"
                )
            }

            if allPassed {
                print("✅ [Manifest + Popup Smoke Test] PASS\n")
            } else {
                print("❌ [Manifest + Popup Smoke Test] FAIL\n")
            }

            return allPassed
        } catch {
            print(
                "❌ [Manifest + Popup Smoke Test] Failed: " +
                "\(error.localizedDescription)\n"
            )
            return false
        }
    }
}
