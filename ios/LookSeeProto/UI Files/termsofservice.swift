//
//  termsofservice.swift
//  LookSeeProto
//
//  Created by Looksee#3 on 8/11/26.
//


import SwiftUI
import WebKit

struct TermsOfServiceView: View {
    @Environment(\.openURL) private var openURL
    @State private var isLoading = true
    @State private var loadFailed = false
    @State private var reloadID = UUID()

    private let termsURL = URL(
        string: "https://www.informationoutpost.com/terms-and-conditions.html"
    )!

    var body: some View {
        ZStack {
            Color(uiColor: .systemBackground)

            LookSeeTermsWebView(
                url: termsURL,
                isLoading: $isLoading,
                loadFailed: $loadFailed
            )
            .id(reloadID)
            .opacity(loadFailed ? 0 : 1)

            if loadFailed {
                VStack(spacing: 16) {
                    Image(systemName: "wifi.exclamationmark")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    Text("Terms of Service Unavailable")
                        .font(.headline)
                    Text("Check your internet connection and try again.")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button("Try Again") {
                        loadFailed = false
                        isLoading = true
                        reloadID = UUID()
                    }
                    .buttonStyle(.borderedProminent)
                    Button("Open in Browser") { openURL(termsURL) }
                }
                .padding(24)
            } else if isLoading {
                ProgressView("Loading terms of service…")
                    .padding(20)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .navigationTitle("Terms of Service")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { openURL(termsURL) } label: {
                    Image(systemName: "safari")
                }
                .accessibilityLabel("Open terms of service in browser")
            }
        }
    }
}

private struct LookSeeTermsWebView: UIViewRepresentable {
    let url: URL
    @Binding var isLoading: Bool
    @Binding var loadFailed: Bool

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.load(URLRequest(url: url, timeoutInterval: 30))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.parent = self
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.navigationDelegate = nil
        webView.stopLoading()
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var parent: LookSeeTermsWebView
        init(_ parent: LookSeeTermsWebView) { self.parent = parent }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            parent.isLoading = false
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            handle(error)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            handle(error)
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationResponse: WKNavigationResponse,
            decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void
        ) {
            if navigationResponse.isForMainFrame,
               let response = navigationResponse.response as? HTTPURLResponse,
               response.statusCode >= 400 {
                parent.isLoading = false
                parent.loadFailed = true
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
            }
        }

        private func handle(_ error: Error) {
            guard (error as NSError).code != NSURLErrorCancelled else { return }
            parent.isLoading = false
            parent.loadFailed = true
            print("[TermsOfService] Page load failed: \(error.localizedDescription)")
        }
    }
}

