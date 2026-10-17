// AppModel.swift
// The one object the whole Mac app shares (created by AppDelegate, handed to the views through
// the environment):
// - owns the MacBlocker,
// - holds a weak reference to the main window's web view, so it can bring the window forward
//   and call window.__unrotBlocked in the page when the blocker hides an app,
// - runs the app picker sheet for the bridge (pickDistracting / pickAllowed),
// - feeds the menu bar extra: Brain Coins from the saved web state, Open at Login.

import AppKit
import Foundation
import ServiceManagement
import WebKit

@MainActor
final class AppModel: ObservableObject {

    /// Id of the main Window scene (UnrotMacApp), used with openWindow(id:).
    static let mainWindowID = "main"

    /// Errors returned to JavaScript (the bridge Promise rejects with the message).
    enum BridgeError: LocalizedError {
        case pickerBusy
        var errorDescription: String? {
            switch self {
            case .pickerBusy: return "The app picker is already open."
            }
        }
    }

    let blocker: MacBlocker

    /// Brain Coins from the last saved web state ("coins" in the JSON), shown in the menu bar.
    @Published private(set) var coins: Int?
    /// Mirrors SMAppService.mainApp.status == .enabled.
    @Published private(set) var opensAtLogin = false
    /// Drives the AppPicker sheet in ContentView. Non-nil while the sheet is open.
    @Published var picker: AppPickerRequest? = nil

    /// The main window's web view. Set by MacWebView (attach); nil while the window is closed.
    private(set) weak var webView: WKWebView?
    /// True once the current page has finished loading (and window.__unrotBlocked exists), until
    /// the next navigation starts. Set by MacWebView through the methods below.
    private var pageReady = false
    /// Opens (or fronts) the main Window scene. Captured from SwiftUI's openWindow by ContentView.
    var openMainWindowAction: (@MainActor () -> Void)?

    private var pickerContinuation: CheckedContinuation<Set<String>?, Never>?
    /// A block event that arrived while the page was not loaded; sent after the next load.
    private var pendingBlockEvent: MacBlocker.BlockEvent?

    init() {
        blocker = MacBlocker()
        _coins = Published(initialValue: AppModel.parseCoins(in: MacBridge.loadSavedState()))
        blocker.onBlock = { [weak self] event in
            self?.appDidBlock(event)
        }
    }

    /// Called once from applicationDidFinishLaunching.
    func start() {
        blocker.start()
        refreshLoginItem()
    }

    // MARK: - Main window

    /// Activates unrot and brings the main window forward, reopening it if it was closed.
    /// Returns false if there was no way to show it (SwiftUI then handles the reopen itself).
    @discardableResult
    func showMainWindow() -> Bool {
        NSApp.activate(ignoringOtherApps: true)
        if let window = webView?.window, window.isVisible || window.isMiniaturized {
            if window.isMiniaturized { window.deminiaturize(nil) }
            window.makeKeyAndOrderFront(nil)
            return true
        }
        guard let openMainWindowAction else { return false }
        openMainWindowAction()
        return true
    }

    // MARK: - Blocked apps -> web app

    private func appDidBlock(_ event: MacBlocker.BlockEvent) {
        showMainWindow()
        send(event)
    }

    /// Runs window.__unrotBlocked({ name, reason }) in the page, or keeps the event until the
    /// page has loaded (the window may just have been reopened).
    private func send(_ event: MacBlocker.BlockEvent) {
        guard let webView, pageReady else {
            pendingBlockEvent = event
            return
        }
        webView.evaluateJavaScript(Self.blockedScript(for: event), completionHandler: nil)
    }

    /// Called by MacWebView when it created the main window's web view (before the first load).
    func attach(_ webView: WKWebView) {
        self.webView = webView
        pageReady = false
    }

    /// Called by MacWebView when a (re)load of the page starts: wait for it before sending.
    func pageDidStartLoading(in webView: WKWebView) {
        if webView === self.webView { pageReady = false }
    }

    /// Called by MacWebView when a page load finished. Sends an event that waited for it.
    /// (A flag, not webView.isLoading: that may still read true inside didFinish.)
    func pageDidFinishLoading(in webView: WKWebView) {
        guard webView === self.webView else { return }
        pageReady = true
        guard let event = pendingBlockEvent else { return }
        pendingBlockEvent = nil
        send(event)
    }

    /// The argument is JSON-encoded, never string-concatenated, so any app name is safe.
    static func blockedScript(for event: MacBlocker.BlockEvent) -> String {
        let payload = ["name": event.name, "reason": event.reason.rawValue]
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let json = (try? encoder.encode(payload)).flatMap { String(data: $0, encoding: .utf8) }
            ?? "{\"name\":\"\",\"reason\":\"\(event.reason.rawValue)\"}"
        return "window.__unrotBlocked && window.__unrotBlocked(\(json))"
    }

    // MARK: - App picker (bridge: pickDistracting / pickAllowed)

    /// Shows the AppPicker sheet on the main window and waits until it closes.
    /// Returns the new selection, or nil if the user cancelled.
    func pickApps(_ kind: AppPickerKind) async throws -> Set<String>? {
        if pickerContinuation != nil { throw BridgeError.pickerBusy }
        showMainWindow()
        let current = kind == .distracting ? blocker.distracting : blocker.allowed
        return await withCheckedContinuation { continuation in
            pickerContinuation = continuation
            picker = AppPickerRequest(kind: kind, selection: current)
        }
    }

    /// Closes the picker sheet and resumes the waiting bridge call. Safe to call more than once
    /// (Done/Cancel and the sheet's onDismiss both end up here).
    func finishPicker(with selection: Set<String>?) {
        if picker != nil { picker = nil }
        guard let continuation = pickerContinuation else { return }
        pickerContinuation = nil
        continuation.resume(returning: selection)
    }

    // MARK: - Saved web state

    /// Called by MacBridge after saveState stored a new JSON string.
    func stateDidSave(_ json: String) {
        let newCoins = Self.parseCoins(in: json)
        if newCoins != coins { coins = newCoins }
    }

    private static func parseCoins(in json: String?) -> Int? {
        guard let data = json?.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let number = object["coins"] as? NSNumber else { return nil }
        let value = number.doubleValue
        guard value.isFinite else { return nil }
        // Clamp before converting: Int(Double) traps on out-of-range values.
        return Int(min(max(value, -1_000_000_000), 1_000_000_000).rounded())
    }

    // MARK: - Open at Login

    /// For a Toggle: reading gives the current state, writing registers or unregisters.
    /// The work runs in a later main-actor job, after the menu that holds the Toggle has closed,
    /// because it may show an alert.
    var opensAtLoginSetting: Bool {
        get { opensAtLogin }
        set {
            Task { @MainActor [weak self] in self?.setOpensAtLogin(newValue) }
        }
    }

    func refreshLoginItem() {
        let enabled = SMAppService.mainApp.status == .enabled
        if enabled != opensAtLogin { opensAtLogin = enabled }
    }

    func setOpensAtLogin(_ enabled: Bool) {
        let service = SMAppService.mainApp
        do {
            if enabled {
                try service.register()
            } else {
                try service.unregister()
            }
        } catch {
            refreshLoginItem()
            showAlert(
                title: enabled ? "Couldn't turn on Open at Login" : "Couldn't turn off Open at Login",
                message: """
                \(error.localizedDescription)

                Make sure unrot is in your Applications folder and was opened from there, then \
                try again. You can also add it in System Settings > General > Login Items.
                """
            )
            return
        }
        refreshLoginItem()
        if enabled && service.status == .requiresApproval {
            let open = showAlert(
                title: "Allow unrot in Login Items",
                message: "macOS wants you to confirm this. Turn on unrot under General > Login Items in System Settings.",
                actionTitle: "Open System Settings"
            )
            if open { SMAppService.openSystemSettingsLoginItems() }
        }
    }

    // MARK: - Alerts

    /// Shows an app-modal alert. Returns true if `actionTitle` was chosen.
    @discardableResult
    func showAlert(title: String, message: String, actionTitle: String? = nil) -> Bool {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = title
        alert.informativeText = message
        if let actionTitle {
            alert.addButton(withTitle: actionTitle)
            alert.addButton(withTitle: "Not Now")
        } else {
            alert.addButton(withTitle: "OK")
        }
        let response = alert.runModal()
        return actionTitle != nil && response == .alertFirstButtonReturn
    }
}
