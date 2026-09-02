# Global Refactor: Fixing Translation Errors and Android Configurations

This plan addresses over 100 compilation errors across 30+ files resulting from a Swift-to-Kotlin translation. The goal is to stabilize the codebase while maintaining parity with the original Swift app's logic.

## User Review Required

> [!IMPORTANT]
> This refactor involves modifying almost every UI and ViewModel file in the project. While I will prioritize logic parity, some Swift-specific patterns (like certain delegated properties) will be replaced with Android-idiomatic alternatives.

## Proposed Changes

### Build Configuration
- **[MODIFY] [app/build.gradle.kts](file:///Users/angelslookseemac/Documents/Look-See-SeniorDesign25/Android/LookSee/app/build.gradle.kts)**: Enable Core Library Desugaring to support AWS Amplify's Java 8 dependencies.

---

### Core Data & State
- **[MODIFY] [AuthViewModel.kt](file:///Users/angelslookseemac/Documents/Look-See-SeniorDesign25/Android/LookSee/app/src/main/java/looksee/angelll/com/viewmodels/AuthViewModel.kt)**: Fix unresolved Amplify result properties.
- **[MODIFY] [AuthState.kt](file:///Users/angelslookseemac/Documents/Look-See-SeniorDesign25/Android/LookSee/app/src/main/java/looksee/angelll/com/viewmodels/AuthState.kt)**: Ensure consistent state management.

---

### UI Layer (Pattern Fixes)
Most UI files share these common issues which I will fix in bulk:
1.  **Swift Logic**: Convert `.size()` (function) to `.size` (property).
2.  **Missing Imports**: Add `dp`, `sp`, `CircleShape`, `Modifier` extensions.
3.  **Type Mismatches**: Fix `WideNavigationRailValue` being used as a boolean.
4.  **Missing Classes**: Provide stubs or link to correct Android equivalents for `VideoMerger`, `BusinessLandmark`, `AuthService`, etc.

**Files to be modified:**
- `ArchiveView.kt`, `BusinessLandmarksView.kt`, `LandmarkRecord.kt`, `Login.kt`, `Signup.kt`, `Settings.kt`, `LandmarkScan.kt`, and 20 others.

---

### Services
- **[MODIFY] [NegativeVideoCameraService.kt](file:///Users/angelslookseemac/Documents/Look-See-SeniorDesign25/Android/LookSee/app/src/main/java/looksee/angelll/com/services/NegativeVideoCameraService.kt)**: Resolve `VideoMerger` and camera lifecycle issues.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` after each batch of changes to ensure the error count decreases.
- Final verification: A successful build of the `:app` module.

### Manual Verification
- Deploy the app to a device to verify that the "Checking Session" and "Loading Model" flows (modified in `MainActivity`) still work as expected.
