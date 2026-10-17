// ScreenTime.swift
// The app-side implementation of the Screen Time part of the bridge contract
// (status, authorize, pickDistracting, pickAllowed, unlock, relock, startSession, endSession).
//
// How blocking works
// - The "distracting" ManagedSettingsStore shields the picked apps/categories/websites all the time.
// - unlock(minutes) lifts that shield and starts DeviceActivity monitoring named "unlock". The
//   UnrotMonitor extension puts the shield back when the distracting apps were used for <minutes>
//   (event threshold) or when the monitoring interval ends, even if unrot is closed.
// - startSession shields everything except the allowed apps in the "session" store, and
//   monitoring named "session" lets UnrotMonitor lift it when the interval ends.
// - DeviceActivity refuses intervals shorter than 15 minutes (MonitoringError.intervalTooShort),
//   so the monitored window is always at least 15 minutes long. Shorter purchases end through
//   the usage threshold, or when the app next asks for status.
//
// In the free build (Info.plist UnrotScreenTime = NO) none of the Screen Time frameworks are
// touched: every method just returns a STATUS with screenTime = "unavailable".

import Foundation
import UIKit
import FamilyControls
import ManagedSettings
import DeviceActivity
import UserNotifications

@MainActor
final class ScreenTimeManager: ObservableObject {

    /// Errors returned to JavaScript (the bridge Promise rejects with the message).
    enum BridgeError: LocalizedError {
        case pickerBusy
        var errorDescription: String? {
            switch self {
            case .pickerBusy: return "The app picker is already open."
            }
        }
    }

    /// True in the Screen Time build. Read once from Info.plist key UnrotScreenTime.
    let isEnabled: Bool

    /// Set when requestAuthorization failed for a reason other than the user saying no
    /// (most often: the app was signed without the Family Controls entitlement).
    private var authorizationUnavailable = false

    /// Set when the user dismissed the authorization prompt. While the system status still reads
    /// notDetermined, STATUS reports "denied" (the contract maps a failed authorize to
    /// "unavailable" or "denied", never back to "notDetermined"). Cleared by a later success.
    private var authorizationDeclined = false

    // MARK: Picker state (drives .familyActivityPicker in ContentView)

    @Published var isPickerPresented = false {
        didSet {
            // The picker sheet closed (Done, Cancel or swipe-down).
            if oldValue && !isPickerPresented { pickerDidClose() }
        }
    }
    // (An empty FamilyActivitySelection is a plain value; creating it calls no Screen Time API.)
    @Published var pickerSelection = FamilyActivitySelection()
    private var pickerContinuation: CheckedContinuation<FamilyActivitySelection, Never>?

    /// Created on first use, which only happens in the Screen Time build.
    private lazy var center = DeviceActivityCenter()
    private var activeObserver: NSObjectProtocol?

    init(bundle: Bundle = .main) {
        isEnabled = ScreenTimeManager.readFlag(bundle.object(forInfoDictionaryKey: "UnrotScreenTime"))
        if isEnabled {
            // Whenever unrot comes back to the foreground, end anything whose time is up
            // (the monitor extension only runs while the device is in use).
            activeObserver = NotificationCenter.default.addObserver(
                forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
            ) { [weak self] _ in
                Task { @MainActor [weak self] in self?.expireIfNeeded() }
            }
        }
    }

    /// The Info.plist value is the String "YES"/"NO" after build-setting expansion, but accept a
    /// real Bool (or NSNumber) too.
    nonisolated static func readFlag(_ value: Any?) -> Bool {
        if let bool = value as? Bool { return bool }
        if let string = value as? String {
            return ["yes", "true", "1"].contains(string.trimmingCharacters(in: .whitespaces).lowercased())
        }
        return false
    }

    var buildName: String { isEnabled ? "screentime" : "free" }

    // MARK: - Contract methods

    /// STATUS object of the bridge contract.
    func status() -> [String: Any] {
        guard isEnabled else {
            return [
                "build": buildName,
                "screenTime": "unavailable",
                "distracting": 0,
                "allowed": 0,
                "unlockedUntil": NSNull(),
                "sessionUntil": NSNull(),
            ]
        }
        expireIfNeeded()
        return [
            "build": buildName,
            "screenTime": screenTimeState(),
            "distracting": UnrotShared.count(UnrotShared.distractingSelection),
            "allowed": UnrotShared.count(UnrotShared.allowedSelection),
            "unlockedUntil": Self.jsTime(UnrotShared.unlockedUntil),
            "sessionUntil": Self.jsTime(UnrotShared.sessionUntil),
        ]
    }

    /// Requests Family Controls authorization for this device's own user (.individual, which
    /// asks for Face ID / Touch ID / passcode) and notification permission. Never throws.
    func authorize() async -> [String: Any] {
        guard isEnabled else { return status() }
        await requestScreenTimeAuthorization()
        // Notifications are used by the shield's "Unlock with Brain Coins" button.
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        // Shields set before authorization did nothing; apply them now if appropriate.
        if isApproved && !isUnlocked { UnrotShared.applyDistractingShield() }
        return status()
    }

    func pickDistracting() async throws -> [String: Any] {
        guard isEnabled else { return status() }
        guard try await ensureAuthorizedForPicking() else { return status() }
        let selection = try await presentPicker(initial: UnrotShared.distractingSelection)
        UnrotShared.distractingSelection = selection
        if !isUnlocked {
            UnrotShared.applyDistractingShield()
        }
        // While scroll time runs, the new selection takes effect at the next relock.
        return status()
    }

    func pickAllowed() async throws -> [String: Any] {
        guard isEnabled else { return status() }
        guard try await ensureAuthorizedForPicking() else { return status() }
        // The session shield can only except APPLICATION tokens (ActivityCategoryPolicy.all(except:)),
        // so a category picked here would allow nothing. includeEntireCategory makes the picker put
        // every app of a picked category into applicationTokens as well.
        let stored = UnrotShared.allowedSelection
        var initial = FamilyActivitySelection(includeEntireCategory: true)
        initial.applicationTokens = stored.applicationTokens
        initial.categoryTokens = stored.categoryTokens
        initial.webDomainTokens = stored.webDomainTokens
        let selection = try await presentPicker(initial: initial)
        UnrotShared.allowedSelection = selection
        // Update a running session so the new allowed apps open right away.
        if UnrotShared.isSessionActive {
            UnrotShared.applySessionShield()
        }
        return status()
    }

    func unlock(minutes rawMinutes: Int) -> [String: Any] {
        guard isEnabled, isApproved else { return status() }
        let minutes = Self.clampMinutes(rawMinutes)
        let now = Date()

        // Write the end time before touching monitoring: UnrotMonitor compares against it and
        // ignores an interval end that belongs to an earlier unlock.
        UnrotShared.unlockedUntil = now.addingTimeInterval(TimeInterval(minutes * 60))
        UnrotShared.clearDistractingShield()

        // The monitored window must be at least 15 minutes long.
        let windowEnd = now.addingTimeInterval(TimeInterval(max(minutes, 15) * 60))
        UnrotShared.unlockWindowEnd = windowEnd
        let selection = UnrotShared.distractingSelection
        var events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
        // An event with no apps, categories or web domains would count ALL device use, so only
        // add the usage threshold when something is picked.
        if UnrotShared.count(selection) > 0 {
            events[.unlockUsage] = DeviceActivityEvent(
                applications: selection.applicationTokens,
                categories: selection.categoryTokens,
                webDomains: selection.webDomainTokens,
                threshold: DateComponents(minute: minutes)
            )
        }
        do {
            // Starting an activity with the same name replaces its previous schedule and events.
            try center.startMonitoring(.unlock, during: Self.schedule(from: now, to: windowEnd), events: events)
        } catch {
            // The user already paid for this time, so keep the apps unlocked. Without monitoring
            // the shield comes back the next time unrot is opened after unlockedUntil.
            print("[unrot] startMonitoring(unlock) failed: \(error)")
        }
        return status()
    }

    func relock() -> [String: Any] {
        guard isEnabled else { return status() }
        relockNow()
        return status()
    }

    func startSession(minutes rawMinutes: Int, title: String) -> [String: Any] {
        guard isEnabled, isApproved else { return status() }
        let minutes = Self.clampMinutes(rawMinutes)
        let now = Date()

        // End time first (see unlock), then the shield.
        UnrotShared.sessionUntil = now.addingTimeInterval(TimeInterval(minutes * 60))
        UnrotShared.defaults.set(title, forKey: UnrotShared.Key.sessionTitle)
        UnrotShared.applySessionShield()

        let windowEnd = now.addingTimeInterval(TimeInterval(max(minutes, 15) * 60))
        UnrotShared.sessionWindowEnd = windowEnd
        do {
            try center.startMonitoring(.session, during: Self.schedule(from: now, to: windowEnd))
        } catch {
            // The session still ends when the web app calls endSession, or at the next status
            // call after sessionUntil.
            print("[unrot] startMonitoring(session) failed: \(error)")
        }
        return status()
    }

    func endSession() -> [String: Any] {
        guard isEnabled else { return status() }
        endSessionNow()
        return status()
    }

    // MARK: - Helpers

    private var isApproved: Bool { screenTimeState() == "approved" }

    private var isUnlocked: Bool {
        guard let until = UnrotShared.unlockedUntil else { return false }
        return until > Date()
    }

    /// "unavailable" | "notDetermined" | "denied" | "approved"
    private func screenTimeState() -> String {
        guard isEnabled else { return "unavailable" }
        let status = AuthorizationCenter.shared.authorizationStatus
        switch status {
        case .approved:
            return "approved"
        case .denied:
            return "denied"
        case .notDetermined:
            if authorizationUnavailable { return "unavailable" }
            return authorizationDeclined ? "denied" : "notDetermined"
        default:
            // Newer SDKs add cases such as .approvedWithDataAccess (still an approval).
            return String(describing: status).lowercased().hasPrefix("approved") ? "approved" : "denied"
        }
    }

    private func requestScreenTimeAuthorization() async {
        do {
            try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
            authorizationUnavailable = false
            authorizationDeclined = false
        } catch let error as FamilyControlsError {
            switch error {
            case .authorizationCanceled:
                // The user said no. The system status may still read notDetermined.
                authorizationUnavailable = false
                authorizationDeclined = true
            default:
                // restricted, unavailable, invalidAccountType, authenticationMethodUnavailable, ...
                print("[unrot] Family Controls authorization failed: \(error)")
                authorizationUnavailable = true
            }
        } catch {
            // Typically thrown when the build has no Family Controls entitlement.
            print("[unrot] Family Controls authorization failed: \(error)")
            authorizationUnavailable = true
        }
    }

    /// The picker only works once the app is authorized. Ask first if needed.
    private func ensureAuthorizedForPicking() async throws -> Bool {
        if pickerContinuation != nil { throw BridgeError.pickerBusy }
        if !isApproved { await requestScreenTimeAuthorization() }
        return isApproved
    }

    /// Shows the FamilyActivityPicker (hosted by ContentView) and waits until it closes.
    private func presentPicker(initial: FamilyActivitySelection) async throws -> FamilyActivitySelection {
        if pickerContinuation != nil { throw BridgeError.pickerBusy }
        pickerSelection = initial
        return await withCheckedContinuation { continuation in
            pickerContinuation = continuation
            isPickerPresented = true
        }
    }

    private func pickerDidClose() {
        // Read the selection in a later main-actor job so a binding write that lands in the
        // same update as the dismissal is included.
        Task { @MainActor [weak self] in
            guard let self, let continuation = self.pickerContinuation else { return }
            self.pickerContinuation = nil
            continuation.resume(returning: self.pickerSelection)
        }
    }

    /// Ends bought scroll time or a session whose end time has passed.
    func expireIfNeeded() {
        guard isEnabled else { return }
        let now = Date()
        if let until = UnrotShared.unlockedUntil, until <= now {
            relockNow()
        }
        if let until = UnrotShared.sessionUntil, until <= now {
            endSessionNow()
        }
    }

    private func relockNow() {
        center.stopMonitoring([.unlock])
        UnrotShared.unlockedUntil = nil
        UnrotShared.unlockWindowEnd = nil
        UnrotShared.applyDistractingShield()
    }

    private func endSessionNow() {
        center.stopMonitoring([.session])
        UnrotShared.clearSession()
    }

    /// 1 minute ... 24 hours.
    private static func clampMinutes(_ minutes: Int) -> Int {
        min(max(minutes, 1), 24 * 60)
    }

    /// Non-repeating schedule from `start` to `end`, using full date components so it can cross
    /// midnight and never repeats.
    private static func schedule(from start: Date, to end: Date) -> DeviceActivitySchedule {
        let calendar = Calendar.current
        let parts: Set<Calendar.Component> = [.year, .month, .day, .hour, .minute, .second]
        return DeviceActivitySchedule(
            intervalStart: calendar.dateComponents(parts, from: start),
            intervalEnd: calendar.dateComponents(parts, from: end),
            repeats: false,
            warningTime: nil
        )
    }

    /// Milliseconds since 1970 for JavaScript, or NSNull (JS null).
    private static func jsTime(_ date: Date?) -> Any {
        guard let date else { return NSNull() }
        return NSNumber(value: (date.timeIntervalSince1970 * 1000).rounded())
    }
}
