// BundleSchemeHandler.swift
// Serves the bundled web app to WKWebView under the custom scheme app://, for both the iPhone
// app (ios/App/WebView.swift) and the Mac app (mac/App/MacWebView.swift).
//
// Why a custom scheme instead of loadFileURL: file:// pages get an opaque origin, which breaks
// fetch() of local files and gives localStorage odd behaviour. A WKURLSchemeHandler gives the
// page a real origin (app://unrot) and lets us send proper Content-Type headers.
//
// Foundation + WebKit only (no UIKit / AppKit), so the same file compiles on iOS and macOS.

import Foundation
import WebKit

/// Serves files from the "unrot" folder reference inside the app bundle.
/// iPhone: app://unrot/index.html  ->  Unrot.app/web/unrot/index.html
/// Mac:    app://unrot/index.html  ->  Unrot.app/Contents/Resources/web/unrot/index.html
@MainActor
final class BundleSchemeHandler: NSObject, WKURLSchemeHandler {

    /// web/unrot in the bundle's resources. The folder is copied by the build (folder reference in
    /// ios/project.common.yml and mac/project.yml). It sits in "web/" because on iOS Unrot.app/unrot
    /// would collide with the executable Unrot.app/Unrot on a case-insensitive disk.
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
