// MacBlocker.swift
// Blocks apps on the Mac, the way desktop focus apps do.
//
// Apple's Screen Time shielding APIs (FamilyControls / ManagedSettings) do not exist on macOS, so
// the Mac app watches which app is in front instead:
// - NSWorkspace reports every app that is activated or launched, and a 2-second safety timer
//   checks NSWorkspace.frontmostApplication in case a notification was missed.
// - A blocked app is hidden (NSRunningApplication.hide(), not quit, so nothing is lost), unrot
//   comes to the front and the web app is told via window.__unrotBlocked({ name, reason })
//   (AppModel does that part through onBlock).
// - reason "locked":  a picked distracting app while no scroll time is bought.
//   reason "session": during an unrot session, every regular app except the allowed apps, unrot
//   itself and a few system apps (alwaysAllowed).
// - Bought time and sessions end on their own at their end time (one-shot timers), whether or
//   not the web page is open. The next status then reports null for that field, and the web
//   app's reconcile logic catches up.
//
// No permission prompt is involved: hiding other apps works for any app that is not sandboxed.
// The limits (unrot must be running, browsers' websites are not blocked) are in mac/README.md.
//
// Everything persists in UserDefaults.standard, so a restart keeps the picks and the end times.

import AppKit
import Foundation

@MainActor
final class MacBlocker: ObservableObject {

    enum Reason: String {
        case locked
        case session
    }

    /// What the web app is told when an app was hidden.
    struct BlockEvent {
        let name: String
        let reason: Reason
    }

    /// Never blocked, not even during a session, so the Mac stays usable: Finder, System Settings
    /// (still com.apple.systempreferences on macOS 13 and later) and Activity Monitor.
    static let alwaysAllowed: Set<String> = [
        "com.apple.finder",
        "com.apple.systempreferences",
        "com.apple.ActivityMonitor",
    ]

    private enum Key {
        static let distracting = "unrot.mac.distracting"      // [String] bundle IDs
        static let allowed = "unrot.mac.allowed"              // [String] bundle IDs
        static let unlockedUntil = "unrot.mac.unlockedUntil"  // Double, seconds since 1970
        static let sessionUntil = "unrot.mac.sessionUntil"    // Double, seconds since 1970
        static let sessionTitle = "unrot.mac.sessionTitle"    // String
    }

    /// Bundle IDs of the apps that stay locked until scroll time is bought.
    @Published private(set) var distracting: Set<String>
    /// Bundle IDs of the apps that keep working during an unrot session.
    @Published private(set) var allowed: Set<String>
    /// End of bought scroll time, or nil while the distracting apps are locked.
    @Published private(set) var unlockedUntil: Date?
    /// End of the running unrot session, or nil.
    @Published private(set) var sessionUntil: Date?
    /// Title of the running session (stored like the iPhone app does; not shown anywhere yet).
    private(set) var sessionTitle: String

    /// Called after an app in front was hidden (at most once per app every `notifyInterval`).
    var onBlock: (@MainActor (BlockEvent) -> Void)?

    private let defaults: UserDefaults
    private let ownBundleID = Bundle.main.bundleIdentifier
    private let ownPID = ProcessInfo.processInfo.processIdentifier

    private var observers: [NSObjectProtocol] = []
    private var safetyTimer: Timer?
    private var unlockTimer: Timer?
    private var sessionTimer: Timer?
    /// Last time each bundle ID triggered onBlock (debounce).
    private var lastNotified: [String: Date] = [:]

    private static let safetyInterval: TimeInterval = 2
    private static let notifyInterval: TimeInterval = 3

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        _distracting = Published(initialValue: Set(defaults.stringArray(forKey: Key.distracting) ?? []))
        _allowed = Published(initialValue: Set(defaults.stringArray(forKey: Key.allowed) ?? []))
        _unlockedUntil = Published(initialValue: MacBlocker.readDate(defaults, Key.unlockedUntil))
        _sessionUntil = Published(initialValue: MacBlocker.readDate(defaults, Key.sessionUntil))
        sessionTitle = defaults.string(forKey: Key.sessionTitle) ?? ""
    }

    // The blocker lives as long as the app, so observers and timers are never torn down.

    /// Starts watching apps and enforces the rules once. Called at launch; later calls do nothing.
    func start() {
        guard observers.isEmpty else { return }
        let center = NSWorkspace.shared.notificationCenter

        // The observer closures run on the main queue, but they are not main-actor isolated in
        // the type system, so they hop with Task { @MainActor }. Only the process ID (a plain
        // Int32) crosses over; the app object is looked up again on the main actor.
        observers.append(center.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main
        ) { [weak self] note in
            guard let pid = MacBlocker.processIdentifier(in: note) else { return }
            Task { @MainActor [weak self] in self?.appDidActivate(pid: pid) }
        })
        observers.append(center.addObserver(
            forName: NSWorkspace.didLaunchApplicationNotification, object: nil, queue: .main
        ) { [weak self] note in
            guard let pid = MacBlocker.processIdentifier(in: note) else { return }
            Task { @MainActor [weak self] in self?.appDidLaunch(pid: pid) }
        })
        // Timers do not run while the Mac sleeps; catch up on wake.
        observers.append(center.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.enforce(hideAll: true) }
        })

        // Safety net for missed notifications, for apps that ignored the first hide(), and for
        // blocked apps made visible without being activated (app menu > Show All).
        // .common mode keeps it firing while a menu (such as the menu bar extra) is open.
        let timer = Timer(timeInterval: Self.safetyInterval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.enforce(hideAll: true) }
        }
        timer.tolerance = 0.5
        RunLoop.main.add(timer, forMode: .common)
        safetyTimer = timer

        // End times saved by an earlier run: expire them or wait for them again.
        scheduleEndTimers()
        enforce(hideAll: true)
    }

    // MARK: - Contract methods (called by MacBridge)

    /// STATUS object of the bridge contract. The Mac blocker needs no permission, so screenTime
    /// is always "approved".
    func status() -> [String: Any] {
        if expireIfNeeded() { enforce(hideAll: true) }
        return [
            "build": MacBridge.buildName,
            "screenTime": "approved",
            "distracting": distracting.count,
            "allowed": allowed.count,
            "unlockedUntil": Self.jsTime(unlockedUntil),
            "sessionUntil": Self.jsTime(sessionUntil),
        ]
    }

    /// Distracting apps may be used for `minutes` from now.
    func unlock(minutes rawMinutes: Int) -> [String: Any] {
        let minutes = Self.clampMinutes(rawMinutes)
        setUnlockedUntil(Date().addingTimeInterval(TimeInterval(minutes * 60)))
        return status()
    }

    func relock() -> [String: Any] {
        setUnlockedUntil(nil)
        enforce(hideAll: true)
        return status()
    }

    /// Blocks every regular app except the allowed ones for `minutes` from now.
    func startSession(minutes rawMinutes: Int, title: String) -> [String: Any] {
        let minutes = Self.clampMinutes(rawMinutes)
        setSession(until: Date().addingTimeInterval(TimeInterval(minutes * 60)), title: title)
        enforce(hideAll: true)
        return status()
    }

    func endSession() -> [String: Any] {
        setSession(until: nil, title: "")
        // An app that is both allowed and distracting was usable during the session; lock it again.
        enforce(hideAll: true)
        return status()
    }

    /// New distracting apps from the picker. Takes effect right away (unless time is bought).
    func setDistracting(_ bundleIDs: Set<String>) {
        distracting = cleaned(bundleIDs)
        defaults.set(distracting.sorted(), forKey: Key.distracting)
        enforce(hideAll: true)
    }

    /// New allowed apps from the picker. A running session uses them right away.
    func setAllowed(_ bundleIDs: Set<String>) {
        allowed = cleaned(bundleIDs)
        defaults.set(allowed.sorted(), forKey: Key.allowed)
        enforce(hideAll: true)
    }

    // MARK: - Rules

    /// Why `app` must not be used right now, or nil if it may.
    func blockReason(for app: NSRunningApplication, now: Date = Date()) -> Reason? {
        // Only apps with a Dock icon and menu bar. Agents, menu bar extras, background helpers
        // and system UI (Dock, Spotlight, loginwindow, ...) are never touched.
        guard app.activationPolicy == .regular,
              !app.isTerminated,
              app.processIdentifier != ownPID,
              let bundleID = app.bundleIdentifier,
              bundleID != ownBundleID,
              !Self.alwaysAllowed.contains(bundleID) else { return nil }

        if let until = sessionUntil, until > now {
            return allowed.contains(bundleID) ? nil : .session
        }
        if distracting.contains(bundleID) {
            let unlocked = unlockedUntil.map { $0 > now } ?? false
            return unlocked ? nil : .locked
        }
        return nil
    }

    /// Applies the rules: hides the frontmost app if it is blocked (and tells the web app).
    /// `hideAll` also hides blocked apps in the background, after the rules got stricter.
    func enforce(hideAll: Bool = false) {
        let ended = expireIfNeeded()
        let workspace = NSWorkspace.shared
        let front = workspace.frontmostApplication
        if hideAll || ended {
            for app in workspace.runningApplications
            where app != front && !app.isHidden && blockReason(for: app) != nil {
                app.hide()
            }
        }
        if let front {
            check(front, notify: true)
        }
    }

    private func appDidActivate(pid: pid_t) {
        if expireIfNeeded() { enforce(hideAll: true) }
        guard let app = NSRunningApplication(processIdentifier: pid) else { return }
        check(app, notify: true)
    }

    private func appDidLaunch(pid: pid_t) {
        guard let app = NSRunningApplication(processIdentifier: pid) else { return }
        // An app launched in the background (for example a login item) is hidden quietly; unrot
        // only jumps to the front when the blocked app is the one in front.
        check(app, notify: app.isActive)
    }

    /// Hides `app` if it is blocked. With `notify`, also brings unrot forward and tells the web
    /// app, at most once per app every `notifyInterval` seconds.
    private func check(_ app: NSRunningApplication, notify: Bool) {
        guard let reason = blockReason(for: app) else { return }
        // hide() returns false while an app is still launching; the safety timer tries again.
        _ = app.hide()
        guard notify, let bundleID = app.bundleIdentifier else { return }
        let now = Date()
        if let last = lastNotified[bundleID], now.timeIntervalSince(last) < Self.notifyInterval {
            return
        }
        lastNotified[bundleID] = now
        let name = app.localizedName.flatMap { $0.isEmpty ? nil : $0 } ?? bundleID
        onBlock?(BlockEvent(name: name, reason: reason))
    }

    // MARK: - End times

    /// Ends bought time and sessions whose end time has passed. Returns true if anything ended.
    @discardableResult
    private func expireIfNeeded(now: Date = Date()) -> Bool {
        var ended = false
        if let until = unlockedUntil, until <= now {
            setUnlockedUntil(nil)
            ended = true
        }
        if let until = sessionUntil, until <= now {
            setSession(until: nil, title: "")
            ended = true
        }
        return ended
    }

    private func setUnlockedUntil(_ date: Date?) {
        unlockedUntil = date
        Self.writeDate(date, defaults, Key.unlockedUntil)
        unlockTimer?.invalidate()
        unlockTimer = date.map { makeEndTimer(at: $0) }
    }

    private func setSession(until date: Date?, title: String) {
        sessionUntil = date
        sessionTitle = title
        Self.writeDate(date, defaults, Key.sessionUntil)
        if date == nil {
            defaults.removeObject(forKey: Key.sessionTitle)
        } else {
            defaults.set(title, forKey: Key.sessionTitle)
        }
        sessionTimer?.invalidate()
        sessionTimer = date.map { makeEndTimer(at: $0) }
    }

    private func scheduleEndTimers() {
        unlockTimer?.invalidate()
        unlockTimer = unlockedUntil.map { makeEndTimer(at: $0) }
        sessionTimer?.invalidate()
        sessionTimer = sessionUntil.map { makeEndTimer(at: $0) }
    }

    /// A one-shot timer that fires at `date` and then re-applies the rules (which ends the
    /// unlock or session and immediately re-checks the frontmost app).
    private func makeEndTimer(at date: Date) -> Timer {
        let timer = Timer(fire: date, interval: 0, repeats: false) { [weak self] _ in
            Task { @MainActor [weak self] in self?.enforce(hideAll: true) }
        }
        RunLoop.main.add(timer, forMode: .common)
        return timer
    }

    // MARK: - Helpers

    /// Drops unrot itself and the always-allowed apps from a picked set.
    private func cleaned(_ bundleIDs: Set<String>) -> Set<String> {
        bundleIDs.filter { !$0.isEmpty && $0 != ownBundleID && !Self.alwaysAllowed.contains($0) }
    }

    nonisolated private static func processIdentifier(in note: Notification) -> pid_t? {
        (note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication)?.processIdentifier
    }

    /// 1 minute ... 24 hours (same range as the iPhone app).
    private static func clampMinutes(_ minutes: Int) -> Int {
        min(max(minutes, 1), 24 * 60)
    }

    private static func readDate(_ defaults: UserDefaults, _ key: String) -> Date? {
        guard let seconds = defaults.object(forKey: key) as? Double, seconds.isFinite else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }

    private static func writeDate(_ date: Date?, _ defaults: UserDefaults, _ key: String) {
        if let date {
            defaults.set(date.timeIntervalSince1970, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    /// Milliseconds since 1970 for JavaScript, or NSNull (JS null).
    private static func jsTime(_ date: Date?) -> Any {
        guard let date else { return NSNull() }
        return NSNumber(value: (date.timeIntervalSince1970 * 1000).rounded())
    }
}
