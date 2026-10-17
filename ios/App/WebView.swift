// WebView.swift
// A full-screen WKWebView that shows the bundled web app from app://unrot/index.html.
//
// Why a custom scheme instead of loadFileURL: file:// pages get an opaque origin, which breaks
// fetch() of local files and gives localStorage odd behaviour. A WKURLSchemeHandler gives the
// page a real origin (app://unrot) and lets us send proper Content-Type headers.
// The handler (BundleSchemeHandler) lives in WebShared/ because the Mac app uses it too.

import SwiftUI
import UIKit
import WebKit

@MainActor
struct WebView: UIViewRepresentable {

    /// Custom URL scheme served by BundleSchemeHandler. Must not be a scheme WebKit handles itself.
    static let scheme = "app"
    static let startURL = URL(string: "app://unrot/index.html")!

    let screenTime: ScreenTimeManager

    func makeCoordinator() -> Coordinator {
        Coordinator(bridge: Bridge(screenTime: screenTime))
    }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.setURLSchemeHandler(context.coordinator.schemeHandler, forURLScheme: Self.scheme)
        configuration.allowsInlineMediaPlayback = true

        let contentController = configuration.userContentController
        contentController.addUserScript(context.coordinator.bridge.bootstrapScript())
        // Reply-capable handler in the page world: window.webkit.messageHandlers.unrot.postMessage
        // returns a Promise. (The controller keeps a strong reference to the handler.)
        contentController.addScriptMessageHandler(
            context.coordinator.bridge,
            contentWorld: .page,
            name: Bridge.handlerName
        )

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.isOpaque = false
        webView.backgroundColor = UIColor.unrotSky
        webView.scrollView.backgroundColor = UIColor.unrotSky
        // The page handles safe areas itself (env(safe-area-inset-*)).
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsBackForwardNavigationGestures = false
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        #if DEBUG
        if #available(iOS 16.4, *) {
            // Safari > Develop > [your iPhone] > unrot
            webView.isInspectable = true
        }
        #endif

        webView.load(URLRequest(url: Self.startURL))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    // MARK: - Coordinator

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let bridge: Bridge
        let schemeHandler = BundleSchemeHandler()

        init(bridge: Bridge) {
            self.bridge = bridge
            super.init()
        }

        // Keep the app on app://. Anything else (https links, mailto:, tel:) opens outside.
        // The async form avoids SDK differences in the decisionHandler closure annotations.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction
        ) async -> WKNavigationActionPolicy {
            guard let url = navigationAction.request.url,
                  let scheme = url.scheme?.lowercased() else { return .allow }
            if scheme == WebView.scheme || scheme == "about" || scheme == "blob" || scheme == "data" {
                return .allow
            }
            // Sub-resources in frames load normally; only top-level link taps leave the app.
            guard navigationAction.targetFrame?.isMainFrame ?? true else { return .allow }
            // In an async context Swift picks the async overload of open(_:options:).
            _ = await UIApplication.shared.open(url, options: [:])
            return .cancel
        }

        // window.open / target="_blank": open in Safari instead of creating a second web view.
        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            if let url = navigationAction.request.url, url.scheme?.lowercased() != WebView.scheme {
                UIApplication.shared.open(url)
            }
            return nil
        }

        // WKWebView ignores alert()/confirm() unless the UI delegate shows them.
        func webView(
            _ webView: WKWebView,
            runJavaScriptAlertPanelWithMessage message: String,
            initiatedByFrame frame: WKFrameInfo
        ) async {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in continuation.resume() })
                if !present(alert, from: webView) { continuation.resume() }
            }
        }

        func webView(
            _ webView: WKWebView,
            runJavaScriptConfirmPanelWithMessage message: String,
            initiatedByFrame frame: WKFrameInfo
        ) async -> Bool {
            await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
                let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in continuation.resume(returning: false) })
                alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in continuation.resume(returning: true) })
                if !present(alert, from: webView) { continuation.resume(returning: false) }
            }
        }

        /// Presents from the top-most view controller. Returns false if nothing could present.
        private func present(_ controller: UIViewController, from webView: WKWebView) -> Bool {
            guard var top = webView.window?.rootViewController else { return false }
            while let presented = top.presentedViewController { top = presented }
            top.present(controller, animated: true)
            return true
        }
    }
}

extension UIColor {
    /// unrot sky blue, #3ea7ee.
    static let unrotSky = UIColor(red: 0x3E / 255.0, green: 0xA7 / 255.0, blue: 0xEE / 255.0, alpha: 1)
}
