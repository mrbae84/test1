// MacBridge.swift
// The native end of the web <-> macOS bridge. Same contract as the iPhone app
// (ios/App/Bridge.swift), with build "mac".
//
// JavaScript calls
//     window.webkit.messageHandlers.unrot.postMessage({ method: "unlock", args: { minutes: 10 } })
// which returns a Promise. This handler (registered with
// addScriptMessageHandler(_:contentWorld:name:) in the .page world) answers with a dictionary
// (the Promise resolves with the equivalent JS object) or with an error string (it rejects).
//
// Before any page script runs, a user script defines
//     window.__unrotNative = { build: "mac", savedState: <string or null> };
//
// STATUS on the Mac: { build: "mac", screenTime: "approved" (the Mac blocker needs no permission),
// distracting: <number of picked apps>, allowed: <number>, unlockedUntil: <ms or null>,
// sessionUntil: <ms or null> }. The other direction (native -> page) is
// window.__unrotBlocked({ name, reason }), sent by AppModel when MacBlocker hides an app.

import Foundation
import WebKit

@MainActor
final class MacBridge: NSObject, WKScriptMessageHandlerWithReply {

    static let handlerName = "unrot"
    static let buildName = "mac"
    /// UserDefaults.standard key of the web app's saved state (a JSON string).
    static let stateKey = "unrot.state"

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

    private let model: AppModel
    private var blocker: MacBlocker { model.blocker }

    init(model: AppModel) {
        self.model = model
        super.init()
    }

    // MARK: - window.__unrotNative

    /// The user script that defines window.__unrotNative. Injected at document start, main frame
    /// only, in the page content world (the default for this initializer).
    func bootstrapScript() -> WKUserScript {
        let savedState = Self.loadSavedState().flatMap(Self.javaScriptStringLiteral) ?? "null"
        let source = "window.__unrotNative = { build: \"\(Self.buildName)\", savedState: \(savedState) };"
        return WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true)
    }

    /// The last JSON string received through saveState, or nil.
    static func loadSavedState() -> String? {
        UserDefaults.standard.string(forKey: stateKey)
    }

    /// Encodes a Swift string as a JavaScript string literal (JSON string syntax is valid JS).
    private static func javaScriptStringLiteral(_ string: String) -> String? {
        guard let data = try? JSONEncoder().encode(string) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    // MARK: - WKScriptMessageHandlerWithReply

    // The async form of userContentController(_:didReceive:replyHandler:). WebKit calls it on the
    // main thread and sends the returned (reply, errorMessage) pair back to the Promise. The class
    // is @MainActor, so the reply is always produced on the main actor. A non-nil error string
    // makes the Promise reject.
    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) async -> (Any?, String?) {
        // Only the bundled page (app://unrot/...) may drive the blocker.
        guard message.frameInfo.isMainFrame,
              message.frameInfo.securityOrigin.protocol == MacWebView.scheme else {
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
        switch method {
        case "status":
            return blocker.status()
        case "authorize":
            // Nothing to ask for on the Mac: hiding other apps needs no permission.
            return blocker.status()
        case "pickDistracting":
            if let selection = try await model.pickApps(.distracting) {
                blocker.setDistracting(selection)
            }
            return blocker.status()
        case "pickAllowed":
            if let selection = try await model.pickApps(.allowed) {
                blocker.setAllowed(selection)
            }
            return blocker.status()
        case "unlock":
            let minutes = try Self.int(args, "minutes")
            return blocker.unlock(minutes: minutes)
        case "relock":
            return blocker.relock()
        case "startSession":
            let minutes = try Self.int(args, "minutes")
            let title = args["title"] as? String ?? ""
            return blocker.startSession(minutes: minutes, title: title)
        case "endSession":
            return blocker.endSession()
        case "saveState":
            guard let json = args["json"] as? String else { throw Failure.badArgument("json") }
            UserDefaults.standard.set(json, forKey: Self.stateKey)
            // Refresh the injected script so a page reload sees the latest state.
            controller.removeAllUserScripts()
            controller.addUserScript(bootstrapScript())
            model.stateDidSave(json)
            return ["ok": true]
        case "openSettings":
            // The Mac has no permission to send the user to; kept so the contract is the same.
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
