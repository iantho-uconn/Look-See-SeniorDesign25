//
//
////
////  JiraReportService.swift
////  LookSeeProto
////
////  Submits in-app bug reports to our backend, which creates the
////  corresponding Jira ticket server-side (and attaches the screenshot).
////  The app never talks to Jira directly or holds a Jira credential —
////  that stays server-side so it isn't extractable from the app bundle.
////
//
//import SwiftUI
//import UIKit
//
//// MARK: - Report Models
//
//enum ReportCategory: String, CaseIterable, Identifiable {
//    case uiBug = "ui_bug"
//    case detectionBug = "detection_bug"
//    case uploadBug = "upload_bug"
//    case other = "other"
//
//    var id: String { rawValue }
//
//    var displayName: String {
//        switch self {
//        case .uiBug: return String(localized: "UI Bug")
//        case .detectionBug: return String(localized: "Detection Bug")
//        case .uploadBug: return String(localized: "Upload Bug")
//        case .other: return String(localized: "Other")
//        }
//    }
//
//    var icon: String {
//        switch self {
//        case .uiBug: return "rectangle.on.rectangle.slash"
//        case .detectionBug: return "viewfinder.circle"
//        case .uploadBug: return "arrow.up.circle"
//        case .other: return "questionmark.circle"
//        }
//    }
//
//    /// Maps to whatever label/component scheme your Jira project uses.
//    /// Adjust these to match your actual Jira project configuration.
//    var jiraLabel: String {
//        switch self {
//        case .uiBug: return "mobile-ui"
//        case .detectionBug: return "detection"
//        case .uploadBug: return "upload-pipeline"
//        case .other: return "unclassified"
//        }
//    }
//}
//
//enum ReportSeverity: String, CaseIterable, Identifiable {
//    case low, medium, high, critical
//
//    var id: String { rawValue }
//
//    var displayName: String {
//        switch self {
//        case .low: return String(localized: "Low")
//        case .medium: return String(localized: "Medium")
//        case .high: return String(localized: "High")
//        case .critical: return String(localized: "Critical")
//        }
//    }
//
//    var color: Color {
//        switch self {
//        case .low: return .green
//        case .medium: return .yellow
//        case .high: return .orange
//        case .critical: return .red
//        }
//    }
//
//    /// Maps to your Jira project's priority scheme.
//    var jiraPriority: String {
//        switch self {
//        case .low: return "Low"
//        case .medium: return "Medium"
//        case .high: return "High"
//        case .critical: return "Highest"
//        }
//    }
//}
//
//struct BugReport {
//    var category: ReportCategory
//    var severity: ReportSeverity
//    var title: String
//    var description: String
//    var screenshot: UIImage?
//}
//
//struct JiraTicketResult: Decodable {
//    let issueKey: String
//    let issueUrl: String
//}
//
//// MARK: - Errors
//
//enum JiraReportError: LocalizedError {
//    case invalidResponse
//    case server(status: Int, message: String?)
//    case decoding
//    case network(Error)
//
//    var errorDescription: String? {
//        switch self {
//        case .invalidResponse:
//            return String(localized: "Received an unexpected response from the server.")
//        case .server(let status, let message):
//            return message ?? String(localized: "The report server returned an error (\(status)).")
//        case .decoding:
//            return String(localized: "Could not read the server's response.")
//        case .network(let error):
//            return error.localizedDescription
//        }
//    }
//}
//
//// MARK: - Service
//
//@MainActor
//final class JiraReportService: ObservableObject {
//
//    enum Stage {
//        case idle
//        case submitting
//        case complete
//        case failed
//    }
//
//    @Published private(set) var stage: Stage = .idle
//    @Published private(set) var status: String = String(localized: "Idle")
//    @Published private(set) var progress: Double = 0
//
//    var isSubmitting: Bool { stage == .submitting }
//
//    /// Base URL for your backend, e.g. "https://api.looksee.app".
//    /// Point this at wherever your report-intake endpoint lives.
//    private let baseURL: URL
//    private let session: URLSession
//
//    init(
//        baseURL: URL = AppConfig.apiBaseURL,
//        session: URLSession = .shared
//    ) {
//        self.baseURL = baseURL
//        self.session = session
//    }
//
//    func reset() {
//        stage = .idle
//        status = String(localized: "Idle")
//        progress = 0
//    }
//
//    /// Submits a bug report. The backend is responsible for creating the
//    /// Jira issue and attaching the screenshot, then returning the created
//    /// issue's key/URL so we can show the user a confirmation + link.
//    @discardableResult
//    func submit(
//        report: BugReport,
//        idToken: String,
//        userEmail: String?
//    ) async throws -> JiraTicketResult {
//        stage = .submitting
//        status = String(localized: "Submitting report…")
//        progress = 0.1
//
//        let url = baseURL.appendingPathComponent("reports")
//        var request = URLRequest(url: url)
//        request.httpMethod = "POST"
//        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
//
//        let boundary = "Boundary-\(UUID().uuidString)"
//        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
//
//        let body = buildMultipartBody(
//            report: report,
//            userEmail: userEmail,
//            deviceInfo: DeviceInfo.current,
//            boundary: boundary
//        )
//        request.httpBody = body
//
//        progress = 0.4
//
//        let data: Data
//        let response: URLResponse
//        do {
//            (data, response) = try await session.data(for: request)
//        } catch {
//            stage = .failed
//            status = String(localized: "Network error")
//            throw JiraReportError.network(error)
//        }
//
//        progress = 0.85
//
//        guard let httpResponse = response as? HTTPURLResponse else {
//            stage = .failed
//            status = String(localized: "Failed")
//            throw JiraReportError.invalidResponse
//        }
//
//        guard (200...299).contains(httpResponse.statusCode) else {
//            let serverMessage = try? JSONDecoder().decode(ServerErrorPayload.self, from: data).message
//            stage = .failed
//            status = String(localized: "Failed")
//            throw JiraReportError.server(status: httpResponse.statusCode, message: serverMessage)
//        }
//
//        do {
//            let result = try JSONDecoder().decode(JiraTicketResult.self, from: data)
//            stage = .complete
//            status = String(localized: "Reported as \(result.issueKey)")
//            progress = 1
//            return result
//        } catch {
//            stage = .failed
//            status = String(localized: "Failed to parse response")
//            throw JiraReportError.decoding
//        }
//    }
//
//    // MARK: - Multipart body
//
//    private func buildMultipartBody(
//        report: BugReport,
//        userEmail: String?,
//        deviceInfo: DeviceInfo,
//        boundary: String
//    ) -> Data {
//        var body = Data()
//
//        func appendField(name: String, value: String) {
//            body.append("--\(boundary)\r\n".data(using: .utf8)!)
//            body.append("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n".data(using: .utf8)!)
//            body.append("\(value)\r\n".data(using: .utf8)!)
//        }
//
//        appendField(name: "category", value: report.category.rawValue)
//        appendField(name: "jiraLabel", value: report.category.jiraLabel)
//        appendField(name: "severity", value: report.severity.rawValue)
//        appendField(name: "jiraPriority", value: report.severity.jiraPriority)
//        appendField(name: "title", value: report.title)
//        appendField(name: "description", value: report.description)
//        appendField(name: "userEmail", value: userEmail ?? "unknown")
//        appendField(name: "appVersion", value: deviceInfo.appVersion)
//        appendField(name: "buildNumber", value: deviceInfo.buildNumber)
//        appendField(name: "osVersion", value: deviceInfo.osVersion)
//        appendField(name: "deviceModel", value: deviceInfo.deviceModel)
//
//        if let screenshot = report.screenshot,
//           let jpegData = screenshot.jpegData(compressionQuality: 0.8) {
//            body.append("--\(boundary)\r\n".data(using: .utf8)!)
//            body.append(
//                "Content-Disposition: form-data; name=\"screenshot\"; filename=\"screenshot.jpg\"\r\n"
//                    .data(using: .utf8)!
//            )
//            body.append("Content-Type: image/jpeg\r\n\r\n".data(using: .utf8)!)
//            body.append(jpegData)
//            body.append("\r\n".data(using: .utf8)!)
//        }
//
//        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
//        return body
//    }
//}
//
//private struct ServerErrorPayload: Decodable {
//    let message: String?
//}
//
//// MARK: - Device Info
//
//private struct DeviceInfo {
//    let appVersion: String
//    let buildNumber: String
//    let osVersion: String
//    let deviceModel: String
//
//    static var current: DeviceInfo {
//        let bundle = Bundle.main
//        return DeviceInfo(
//            appVersion: bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown",
//            buildNumber: bundle.infoDictionary?["CFBundleVersion"] as? String ?? "unknown",
//            osVersion: UIDevice.current.systemVersion,
//            deviceModel: UIDevice.current.model
//        )
//    }
//}
//
//// MARK: - Config
//
///// Replace with wherever your existing app-wide config/base-URL lives.
///// Stubbed here so this file compiles standalone — point it at your
///// real config source (e.g. an existing AppConfig/Environment type).
//enum AppConfig {
//    static let apiBaseURL = URL(string: "https://api.looksee.app")!
//}
