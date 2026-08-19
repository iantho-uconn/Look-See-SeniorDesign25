package looksee.angelll.com.models

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

enum class ReportCategory(
    val wireValue: String,
    val displayName: String,
    val jiraLabel: String,
) {
    UI_BUG("ui_bug", "UI Bug", "mobile-ui"),
    DETECTION_BUG("detection_bug", "Detection Bug", "detection"),
    UPLOAD_BUG("upload_bug", "Upload Bug", "upload-pipeline"),
    OTHER("other", "Other", "unclassified"),
}

enum class ReportSeverity(
    val wireValue: String,
    val displayName: String,
    val jiraPriority: String,
) {
    LOW("low", "Low", "Low"),
    MEDIUM("medium", "Medium", "Medium"),
    HIGH("high", "High", "High"),
    CRITICAL("critical", "Critical", "Highest"),
}

data class BugReport(
    val category: ReportCategory,
    val severity: ReportSeverity,
    val title: String,
    val description: String,
    val screenshotJpeg: ByteArray? = null,
)

data class ReportDeviceInfo(
    val appVersion: String,
    val buildNumber: String,
    val osVersion: String,
    val deviceModel: String,
) {
    companion object {
        fun current(context: Context): ReportDeviceInfo {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val buildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
            return ReportDeviceInfo(
                appVersion = packageInfo.versionName ?: "unknown",
                buildNumber = buildNumber,
                osVersion = Build.VERSION.RELEASE.ifBlank { "unknown" },
                deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
                    .map { it.trim() }
                    .filter(String::isNotEmpty)
                    .joinToString(" ")
                    .ifBlank { "unknown" },
            )
        }
    }
}
