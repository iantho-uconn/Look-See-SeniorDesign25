//
//  ReportCategory 2.swift
//  LookSeeProto
//
//  Created by Looksee#3 on 8/11/26.
//


//
//  MailReportService.swift
//  LookSeeProto
//
//  Builds the content for an in-app bug report and hands it to the
//  system Mail composer. No backend or credentials involved — the
//  report is sent straight from the user's own device Mail account
//  to the team inbox below.
//

import SwiftUI
import UIKit
import MessageUI

// MARK: - Report Models

enum ReportCategory: String, CaseIterable, Identifiable {
    case uiBug = "ui_bug"
    case detectionBug = "detection_bug"
    case uploadBug = "upload_bug"
    case other = "other"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .uiBug: return String(localized: "UI Bug")
        case .detectionBug: return String(localized: "Detection Bug")
        case .uploadBug: return String(localized: "Upload Bug")
        case .other: return String(localized: "Other")
        }
    }

    var icon: String {
        switch self {
        case .uiBug: return "rectangle.on.rectangle.slash"
        case .detectionBug: return "viewfinder.circle"
        case .uploadBug: return "arrow.up.circle"
        case .other: return "questionmark.circle"
        }
    }
}

enum ReportSeverity: String, CaseIterable, Identifiable {
    case low, medium, high, critical

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .low: return String(localized: "Low")
        case .medium: return String(localized: "Medium")
        case .high: return String(localized: "High")
        case .critical: return String(localized: "Critical")
        }
    }

    var color: Color {
        switch self {
        case .low: return .green
        case .medium: return .yellow
        case .high: return .orange
        case .critical: return .red
        }
    }
}

struct BugReport {
    var category: ReportCategory
    var severity: ReportSeverity
    var title: String
    var description: String
    var screenshot: UIImage?
}

// MARK: - Service

/// Builds ready-to-send email content from a BugReport. This is a plain
/// value builder, not a network service — the actual send happens through
/// MFMailComposeViewController in the UI layer (see MailComposerView).
enum MailReportService {

    /// Update this to your team's real inbox (or a distribution list).
    static let recipients = ["Looksee.support@informationoutpost.com"]

    static func canSendMail() -> Bool {
        MFMailComposeViewController.canSendMail()
    }

    static func subject(for report: BugReport) -> String {
        "[\(report.category.displayName) · \(report.severity.displayName)] \(report.title)"
    }

    static func body(for report: BugReport, userEmail: String?, deviceInfo: DeviceInfo = .current) -> String {
        """
        \(report.description)

        ---
        Category: \(report.category.displayName)
        Severity: \(report.severity.displayName)
        Reported by: \(userEmail ?? "")
        App version: \(deviceInfo.appVersion) (\(deviceInfo.buildNumber))
        OS: iOS \(deviceInfo.osVersion)
        Device: \(deviceInfo.deviceModel)
        """
    }

    static func attachmentData(for report: BugReport) -> Data? {
        report.screenshot?.jpegData(compressionQuality: 0.8)
    }
}

// MARK: - Device Info

struct DeviceInfo {
    let appVersion: String
    let buildNumber: String
    let osVersion: String
    let deviceModel: String

    static var current: DeviceInfo {
        let bundle = Bundle.main
        return DeviceInfo(
            appVersion: bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown",
            buildNumber: bundle.infoDictionary?["CFBundleVersion"] as? String ?? "unknown",
            osVersion: UIDevice.current.systemVersion,
            deviceModel: UIDevice.current.model
        )
    }
}
