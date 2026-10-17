// ScreenTimeShared.swift
// Compiled into the app AND into all three extensions (UnrotMonitor, UnrotShield,
// UnrotShieldAction). It is the single place that knows where unrot keeps its Screen Time state
// and how the "distracting" and "session" shields are applied.
//
// It also compiles into the free build: FamilyControls, ManagedSettings and DeviceActivity link
// fine without the Family Controls entitlement. The free build simply never calls into them
// (ScreenTimeManager checks the UnrotScreenTime flag first).

import Foundation
import FamilyControls
import ManagedSettings
import DeviceActivity

// MARK: - Names shared by the app and the extensions

extension DeviceActivityName {
    /// Monitoring that starts when the user buys scroll time. Its interval end (or its event
    /// threshold) tells UnrotMonitor to put the distracting shield back.
    static let unlock = DeviceActivityName("unlock")
    /// Monitoring that covers an unrot session. Its interval end tells UnrotMonitor to lift the
    /// session shield.
    static let session = DeviceActivityName("session")
}

extension DeviceActivityEvent.Name {
    /// Fires once the distracting apps have been used for the number of minutes that were bought.
    static let unlockUsage = DeviceActivityEvent.Name("unlockUsage")
}

extension ManagedSettingsStore.Name {
    /// Always-on shield over the apps / categories / websites the user picked as distracting.
    static let distracting = ManagedSettingsStore.Name("distracting")
    /// Shield over everything except the allowed apps, only while an unrot session runs.
    static let session = ManagedSettingsStore.Name("session")
}

// MARK: - Shared storage

enum UnrotShared {

    /// Keys used in the shared UserDefaults.
    enum Key {
        static let distractingSelection = "unrot.selection.distracting"
        static let allowedSelection = "unrot.selection.allowed"
        static let unlockedUntil = "unrot.unlockedUntil"   // Double, seconds since 1970
        static let sessionUntil = "unrot.sessionUntil"     // Double, seconds since 1970
        static let unlockWindowEnd = "unrot.unlockWindowEnd"   // end of the monitored "unlock" interval
        static let sessionWindowEnd = "unrot.sessionWindowEnd" // end of the monitored "session" interval
        static let sessionTitle = "unrot.sessionTitle"
        static let state = "unrot.state"                   // JSON string from the web app
    }

    /// The App Group identifier from the Info.plist key `UnrotAppGroup`
    /// (`group.$(BUNDLE_PREFIX).unrot`). The app and every extension carry the same key.
    static let appGroupID: String? = {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "UnrotAppGroup") as? String,
              !value.isEmpty, !value.contains("$(") else { return nil }
        return value
    }()

    /// True only when this process is really entitled to the App Group. The free build declares
    /// the key in Info.plist but has no application-groups entitlement; in that case
    /// `containerURL(forSecurityApplicationGroupIdentifier:)` returns nil.
    static let hasAppGroup: Bool = {
        guard let id = appGroupID else { return false }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) != nil
    }()

    /// UserDefaults visible to the app and all extensions, or `.standard` when there is no
    /// App Group (free build, or a signing problem).
    static let defaults: UserDefaults = {
        if hasAppGroup, let id = appGroupID, let suite = UserDefaults(suiteName: id) {
            return suite
        }
        return .standard
    }()

    // MARK: Selections (FamilyActivitySelection is Codable; its tokens are opaque)

    static func loadSelection(forKey key: String) -> FamilyActivitySelection {
        guard let data = defaults.data(forKey: key),
              let selection = try? JSONDecoder().decode(FamilyActivitySelection.self, from: data)
        else { return FamilyActivitySelection() }
        return selection
    }

    static func saveSelection(_ selection: FamilyActivitySelection, forKey key: String) {
        if let data = try? JSONEncoder().encode(selection) {
            defaults.set(data, forKey: key)
        }
    }

    static var distractingSelection: FamilyActivitySelection {
        get { loadSelection(forKey: Key.distractingSelection) }
        set { saveSelection(newValue, forKey: Key.distractingSelection) }
    }

    static var allowedSelection: FamilyActivitySelection {
        get { loadSelection(forKey: Key.allowedSelection) }
        set { saveSelection(newValue, forKey: Key.allowedSelection) }
    }

    /// Number of things picked (apps + categories + websites), as reported to the web app.
    static func count(_ selection: FamilyActivitySelection) -> Int {
        selection.applicationTokens.count + selection.categoryTokens.count + selection.webDomainTokens.count
    }

    // MARK: Times

    /// End of the scroll time the user bought, or nil when the distracting apps are locked.
    static var unlockedUntil: Date? {
        get { date(forKey: Key.unlockedUntil) }
        set { setDate(newValue, forKey: Key.unlockedUntil) }
    }

    /// End of the running unrot session, or nil when no session is running.
    static var sessionUntil: Date? {
        get { date(forKey: Key.sessionUntil) }
        set { setDate(newValue, forKey: Key.sessionUntil) }
    }

    /// End of the DeviceActivity interval monitored for the current unlock (at least 15 minutes
    /// after it started, so never confused with a callback from an earlier, replaced interval).
    static var unlockWindowEnd: Date? {
        get { date(forKey: Key.unlockWindowEnd) }
        set { setDate(newValue, forKey: Key.unlockWindowEnd) }
    }

    /// End of the DeviceActivity interval monitored for the current session.
    static var sessionWindowEnd: Date? {
        get { date(forKey: Key.sessionWindowEnd) }
        set { setDate(newValue, forKey: Key.sessionWindowEnd) }
    }

    /// True while a session that has not reached its end time is stored.
    static var isSessionActive: Bool {
        guard let end = sessionUntil else { return false }
        return end > Date()
    }

    /// True when `end` is missing or (almost) reached. The monitor extension passes the stored
    /// window end so that an interval-end callback from an older, replaced or stopped monitoring
    /// cannot cancel scroll time or a session started afterwards: a fresh window always ends at
    /// least 15 minutes after it began. The minute of slack covers callbacks that arrive a little
    /// before the stored end time.
    static func hasEnded(_ end: Date?, now: Date = Date()) -> Bool {
        guard let end else { return true }
        return end <= now.addingTimeInterval(60)
    }

    private static func date(forKey key: String) -> Date? {
        guard let seconds = defaults.object(forKey: key) as? Double else { return nil }
        return Date(timeIntervalSince1970: seconds)
    }

    private static func setDate(_ date: Date?, forKey key: String) {
        if let date {
            defaults.set(date.timeIntervalSince1970, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    // MARK: Shields
    //
    // A ManagedSettingsStore keeps its settings after the process exits, so the app and the
    // monitor extension can each re-apply or clear them. Setting a value to nil removes it.
    // Shields from several named stores add up: an app is covered if ANY store shields it.

    /// Covers the distracting selection (always on, except while bought scroll time runs).
    static func applyDistractingShield() {
        let selection = distractingSelection
        let store = ManagedSettingsStore(named: .distracting)
        store.shield.applications = selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
        store.shield.applicationCategories = selection.categoryTokens.isEmpty
            ? nil
            : .specific(selection.categoryTokens, except: [])
        store.shield.webDomains = selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
        // A picked category (e.g. "Social") should also cover websites of that category.
        store.shield.webDomainCategories = selection.categoryTokens.isEmpty
            ? nil
            : .specific(selection.categoryTokens, except: [])
    }

    /// Removes the distracting shield (scroll time was bought).
    static func clearDistractingShield() {
        ManagedSettingsStore(named: .distracting).clearAllSettings()
    }

    /// Covers every app category except the allowed apps, and every website category except the
    /// allowed websites. Used while an unrot session runs.
    static func applySessionShield() {
        let allowed = allowedSelection
        let store = ManagedSettingsStore(named: .session)
        store.shield.applicationCategories = .all(except: allowed.applicationTokens)
        store.shield.webDomainCategories = .all(except: allowed.webDomainTokens)
    }

    /// Lifts the session shield and forgets the session end time.
    static func clearSession() {
        ManagedSettingsStore(named: .session).clearAllSettings()
        sessionUntil = nil
        sessionWindowEnd = nil
        defaults.removeObject(forKey: Key.sessionTitle)
    }
}
