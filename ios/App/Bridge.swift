// Bridge.swift
// The native end of the web <-> iOS bridge.
//
// JavaScript calls
//     window.webkit.messageHandlers.unrot.postMessage({ method: "unlock", args: { minutes: 10 } })
// which returns a Promise. This handler (registered with
// addScriptMessageHandler(_:contentWorld:name:) in the .page world) answers with a dictionary
// (the Promise resolves with the equivalent JS object) or with an error string (it rejects).
//
// Before any page script runs, a user script defines
//     window.__unrotNative = { build: "free" | "screentime", savedState: <string or null> };

import Foundation
import UIKit
import WebKit

@MainActor
final class Bridge: NSObject, WKScriptMessageHandlerWithReply {

    static let handlerName = "unrot"

    private static let screenTimeMethods: Set<String> = [
        "status", "authorize", "pickDistracting", "pickAllowed",
        "unlock", "relock", "startSession", "endSession",
    ]

    enum Failure: LocalizedError {
        case badMessage
        case untrustedOrigin
        case unknownMethod(String)
        case badArgument(String)

        var errorDescription: String? {
            switch self {
            case .badMessage: return "unrot bridge: expected { method: string, args: object }"
            case .untrustedOrigin: return "unrot bridge: messages are only accepted from the bundled app"
            case .unknownMethod(let name): return "unrot bridge: unknown method \"\(name)\""
            case .badArgument(let name): return "unrot bridge: missing or invalid argument \"\(name)\""
            }
        }
    }

    private let screenTime: ScreenTimeManager

    init(screenTime: ScreenTimeManager) {
        self.screenTime = screenTime
        super.init()
    }

    // MARK: - window.__unrotNative

    /// The user script that defines window.__unrotNative. Injected at document start, main frame
    /// only, in the page content world (the default for this initializer).
    func bootstrapScript() -> WKUserScript {
        let savedState = Self.loadSavedState().flatMap(Self.javaScriptStringLiteral) ?? "null"
        let source = "window.__unrotNative = { build: \"\(screenTime.buildName)\", savedState: \(savedState) };"
        return WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true)
    }

    /// The last JSON string received through saveState, or nil.
    static func loadSavedState() -> String? {
        if let state = UnrotShared.defaults.string(forKey: UnrotShared.Key.state) {
            return state
        }
        // Someone who ran the free build first saved into the standard defaults; the Screen
        // Time build uses the App Group. Pick the old state up once.
        if UnrotShared.defaults !== UserDefaults.standard,
           let state = UserDefaults.standard.string(forKey: UnrotShared.Key.state) {
            return state
        }
        return nil
    }

    /// Encodes a Swift string as a JavaScript string literal (JSON string syntax is valid JS).
    private static func javaScriptStringLiteral(_ string: String) -> String? {
        guard let data = try? JSONEncoder().encode(string) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    // MARK: - WKScriptMessageHandlerWithReply

    // The async form of userContentController(_:didReceive:replyHandler:). WebKit calls it on the
    // main thread and sends the returned (reply, errorMessage) pair back to the Promise. Using the
    // async form keeps the signature identical across SDKs (newer SDKs annotate the replyHandler
    // closure with @MainActor @Sendable). A non-nil error string makes the Promise reject.
    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) async -> (Any?, String?) {
        // Only the bundled page (app://unrot/...) may drive Screen Time.
        guard message.frameInfo.isMainFrame,
              message.frameInfo.securityOrigin.protocol == WebView.scheme else {
            return (nil, Failure.untrustedOrigin.localizedDescription)
        }
        guard let body = message.body as? [String: Any],
              let method = body["method"] as? String else {
            return (nil, Failure.badMessage.localizedDescription)
        }
        let args = body["args"] as? [String: Any] ?? [:]
        do {
            let reply = try await handle(method: method, args: args, controller: userContentController)
            return (reply, nil)
        } catch {
            return (nil, error.localizedDescription)
        }
    }

    // MARK: - Dispatch

    private func handle(
        method: String,
        args: [String: Any],
        controller: WKUserContentController
    ) async throws -> [String: Any] {
        // Free build: every Screen Time method is a no-op that reports "unavailable",
        // whatever its arguments.
        if Self.screenTimeMethods.contains(method) && !screenTime.isEnabled {
            return screenTime.status()
        }
        switch method {
        case "status":
            return screenTime.status()
        case "authorize":
            return await screenTime.authorize()
        case "pickDistracting":
            return try await screenTime.pickDistracting()
        case "pickAllowed":
            return try await screenTime.pickAllowed()
        case "unlock":
            let minutes = try Self.int(args, "minutes")
            return screenTime.unlock(minutes: minutes)
        case "relock":
            return screenTime.relock()
        case "startSession":
            let minutes = try Self.int(args, "minutes")
            let title = args["title"] as? String ?? ""
            return screenTime.startSession(minutes: minutes, title: title)
        case "endSession":
            return screenTime.endSession()
        case "saveState":
            guard let json = args["json"] as? String else { throw Failure.badArgument("json") }
            UnrotShared.defaults.set(json, forKey: UnrotShared.Key.state)
            // Refresh the injected script so a page reload sees the latest state.
            controller.removeAllUserScripts()
            controller.addUserScript(bootstrapScript())
            return ["ok": true]
        case "openSettings":
            if let url = URL(string: UIApplication.openSettingsURLString) {
                // In an async context Swift picks the async overload of open(_:options:).
                _ = await UIApplication.shared.open(url, options: [:])
            }
            return ["ok": true]
        default:
            throw Failure.unknownMethod(method)
        }
    }

    /// Reads a whole number from a JS number (NSNumber) or numeric string.
    private static func int(_ args: [String: Any], _ key: String) throws -> Int {
        let value: Double?
        if let number = args[key] as? NSNumber {
            value = number.doubleValue
        } else if let string = args[key] as? String {
            value = Double(string)
        } else {
            value = nil
        }
        guard let value, value.isFinite else { throw Failure.badArgument(key) }
        // Clamp before converting: Int(Double) traps on out-of-range values.
        return Int(min(max(value, -1_000_000), 1_000_000).rounded())
    }
}
