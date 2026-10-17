// WebView.swift
// A full-screen WKWebView that shows the bundled web app from app://unrot/index.html.
//
// Why a custom scheme instead of loadFileURL: file:// pages get an opaque origin, which breaks
// fetch() of local files and gives localStorage odd behaviour. A WKURLSchemeHandler gives the
// page a real origin (app://unrot) and lets us send proper Content-Type headers.

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

// MARK: - app:// scheme handler

/// Serves files from the "unrot" folder reference inside the app bundle.
/// app://unrot/index.html  ->  Unrot.app/web/unrot/index.html
@MainActor
final class BundleSchemeHandler: NSObject, WKURLSchemeHandler {

    /// Unrot.app/web/unrot. The folder is copied by the build (folder reference in
    /// project.common.yml). It sits in "web/" because Unrot.app/unrot would collide with the
    /// executable Unrot.app/Unrot on a case-insensitive disk.
    private let root: URL = {
        guard let url = Bundle.main.url(forResource: "unrot", withExtension: nil, subdirectory: "web") else {
            fatalError("unrot: the web app folder web/unrot is missing from the app bundle")
        }
        return url.standardizedFileURL.resolvingSymlinksInPath()
    }()

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url else {
            urlSchemeTask.didFailWithError(URLError(.badURL))
            return
        }
        guard let fileURL = fileURL(for: url),
              let data = try? Data(contentsOf: fileURL) else {
            respond(urlSchemeTask, url: url, status: 404, mimeType: "text/plain", data: Data("Not found".utf8))
            return
        }
        respond(urlSchemeTask, url: url, status: 200, mimeType: Self.mimeType(for: fileURL), data: data)
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
        // Every request is answered synchronously in start(), so there is nothing to cancel.
    }

    /// Maps the URL path to a file inside `root`, refusing anything that escapes it.
    private func fileURL(for url: URL) -> URL? {
        var path = url.path
        if path.isEmpty || path == "/" { path = "/index.html" }
        let candidate = root.appendingPathComponent(String(path.dropFirst()))
            .standardizedFileURL.resolvingSymlinksInPath()
        guard candidate.path.hasPrefix(root.path + "/") else { return nil }
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: candidate.path, isDirectory: &isDirectory) else { return nil }
        if isDirectory.boolValue {
            return fileURL(for: url.appendingPathComponent("index.html"))
        }
        return candidate
    }

    private func respond(_ task: WKURLSchemeTask, url: URL, status: Int, mimeType: String, data: Data) {
        let isText = mimeType.hasPrefix("text/")
            || ["application/json", "application/manifest+json", "image/svg+xml"].contains(mimeType)
        let contentType = isText ? "\(mimeType); charset=utf-8" : mimeType
        let headers = [
            "Content-Type": contentType,
            "Content-Length": String(data.count),
            "Cache-Control": "no-cache",
        ]
        let response: URLResponse = HTTPURLResponse(
            url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers
        ) ?? URLResponse(url: url, mimeType: mimeType, expectedContentLength: data.count,
                         textEncodingName: isText ? "utf-8" : nil)
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }

    static func mimeType(for fileURL: URL) -> String {
        switch fileURL.pathExtension.lowercased() {
        case "html", "htm": return "text/html"
        case "js", "mjs": return "text/javascript"
        case "css": return "text/css"
        case "json": return "application/json"
        case "webmanifest": return "application/manifest+json"
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "webp": return "image/webp"
        case "svg": return "image/svg+xml"
        case "ico": return "image/x-icon"
        case "woff2": return "font/woff2"
        case "woff": return "font/woff"
        case "ttf": return "font/ttf"
        case "txt", "md": return "text/plain"
        default: return "application/octet-stream"
        }
    }
}

extension UIColor {
    /// unrot sky blue, #3ea7ee.
    static let unrotSky = UIColor(red: 0x3E / 255.0, green: 0xA7 / 255.0, blue: 0xEE / 255.0, alpha: 1)
}
