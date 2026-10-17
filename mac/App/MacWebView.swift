// MacWebView.swift
// The WKWebView in the main window that shows the bundled web app from app://unrot/index.html.
// The Mac twin of ios/App/WebView.swift: same app:// scheme (served by the shared
// BundleSchemeHandler in ios/WebShared), same bootstrap user script and "unrot" reply handler.
//
// The web view registers itself with AppModel (a weak reference), so AppModel can bring the
// window forward and run window.__unrotBlocked in the page when MacBlocker hides an app.

import AppKit
import SwiftUI
import UniformTypeIdentifiers
import WebKit

@MainActor
struct MacWebView: NSViewRepresentable {

    /// Custom URL scheme served by BundleSchemeHandler. Must not be a scheme WebKit handles itself.
    static let scheme = "app"
    static let startURL = URL(string: "app://unrot/index.html")!

    let model: AppModel

    func makeCoordinator() -> Coordinator {
        Coordinator(model: model, bridge: MacBridge(model: model))
    }

    func makeNSView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.setURLSchemeHandler(context.coordinator.schemeHandler, forURLScheme: Self.scheme)

        let contentController = configuration.userContentController
        contentController.addUserScript(context.coordinator.bridge.bootstrapScript())
        // Reply-capable handler in the page world: window.webkit.messageHandlers.unrot.postMessage
        // returns a Promise. (The controller keeps a strong reference to the handler.)
        contentController.addScriptMessageHandler(
            context.coordinator.bridge,
            contentWorld: .page,
            name: MacBridge.handlerName
        )

        let webView = WKWebView(frame: .zero, configuration: configuration)
        // Shown when the page is pulled past its edges (rubber-banding).
        webView.underPageBackgroundColor = NSColor.unrotSky
        webView.allowsBackForwardNavigationGestures = false
        webView.allowsMagnification = false
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        #if DEBUG
        if #available(macOS 13.3, *) {
            // Safari > Develop > [this Mac] > unrot
            webView.isInspectable = true
        }
        #endif

        model.attach(webView)
        webView.load(URLRequest(url: Self.startURL))
        return webView
    }

    func updateNSView(_ webView: WKWebView, context: Context) {}

    static func dismantleNSView(_ webView: WKWebView, coordinator: Coordinator) {
        // The window was closed. The content controller holds the bridge strongly; let it go.
        webView.configuration.userContentController.removeAllScriptMessageHandlers()
    }

    // MARK: - Coordinator

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let bridge: MacBridge
        let schemeHandler = BundleSchemeHandler()
        private let model: AppModel

        init(model: AppModel, bridge: MacBridge) {
            self.model = model
            self.bridge = bridge
            super.init()
        }

        // Keep the app on app://. Anything else (https links, mailto:) opens in the default app.
        // The async form avoids SDK differences in the decisionHandler closure annotations.
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction
        ) async -> WKNavigationActionPolicy {
            guard let url = navigationAction.request.url,
                  let scheme = url.scheme?.lowercased() else { return .allow }
            if scheme == MacWebView.scheme || scheme == "about" || scheme == "blob" || scheme == "data" {
                return .allow
            }
            // Sub-resources in frames load normally; only top-level link clicks leave the app.
            guard navigationAction.targetFrame?.isMainFrame ?? true else { return .allow }
            NSWorkspace.shared.open(url)
            return .cancel
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            model.pageDidStartLoading(in: webView)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            model.pageDidFinishLoading(in: webView)
        }

        // If the web content process crashes, the view goes blank; load the app again.
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            webView.load(URLRequest(url: MacWebView.startURL))
        }

        // window.open / target="_blank": open in the default browser instead of a second web view.
        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            if let url = navigationAction.request.url, url.scheme?.lowercased() != MacWebView.scheme {
                NSWorkspace.shared.open(url)
            }
            return nil
        }

        // WKWebView ignores alert()/confirm() unless the UI delegate shows them.
        func webView(
            _ webView: WKWebView,
            runJavaScriptAlertPanelWithMessage message: String,
            initiatedByFrame frame: WKFrameInfo
        ) async {
            let alert = NSAlert()
            alert.messageText = message
            alert.addButton(withTitle: "OK")
            _ = await present(alert, in: webView.window)
        }

        func webView(
            _ webView: WKWebView,
            runJavaScriptConfirmPanelWithMessage message: String,
            initiatedByFrame frame: WKFrameInfo
        ) async -> Bool {
            let alert = NSAlert()
            alert.messageText = message
            alert.addButton(withTitle: "OK")
            alert.addButton(withTitle: "Cancel")
            return await present(alert, in: webView.window) == .alertFirstButtonReturn
        }

        // <input type="file">: without this, clicking a file input does nothing on macOS.
        // The page's only file input takes a photo for a Side Quest (accept="image/*"), and
        // WKOpenPanelParameters does not expose the accept list, so the panel offers images.
        func webView(
            _ webView: WKWebView,
            runOpenPanelWith parameters: WKOpenPanelParameters,
            initiatedByFrame frame: WKFrameInfo
        ) async -> [URL]? {
            let panel = NSOpenPanel()
            panel.canChooseFiles = true
            panel.canChooseDirectories = false
            panel.allowsMultipleSelection = parameters.allowsMultipleSelection
            panel.allowedContentTypes = [.image]
            panel.message = "Choose a photo"
            let response: NSApplication.ModalResponse
            if let window = webView.window {
                response = await withCheckedContinuation { continuation in
                    panel.beginSheetModal(for: window) { continuation.resume(returning: $0) }
                }
            } else {
                response = panel.runModal()
            }
            return response == .OK ? panel.urls : nil
        }

        /// Shows the alert as a sheet on the window (or app-modal without one) and waits for it.
        private func present(_ alert: NSAlert, in window: NSWindow?) async -> NSApplication.ModalResponse {
            guard let window else { return alert.runModal() }
            return await withCheckedContinuation { continuation in
                alert.beginSheetModal(for: window) { continuation.resume(returning: $0) }
            }
        }
    }
}

extension NSColor {
    /// unrot sky blue, #3ea7ee.
    static let unrotSky = NSColor(srgbRed: 0x3E / 255.0, green: 0xA7 / 255.0, blue: 0xEE / 255.0, alpha: 1)
}
