// UnrotMacApp.swift
// Entry point of the native macOS shell around the unrot web app.
//
// Scenes
//   Window "unrot" (id "main")  the web app, phone-shaped by default (430 x 900)
//   MenuBarExtra               Brain Coins, lock state, Open unrot, Open at Login, Quit
//
// unrot keeps running when its window is closed: the blocker (MacBlocker) only works while the
// app runs, so it lives on in the menu bar. Clicking the Dock icon brings the window back.

import AppKit
import SwiftUI

@main
struct UnrotMacApp: App {
    /// The delegate owns the one AppModel (and through it the one MacBlocker). It is shared with
    /// the views through the environment; the delegate needs the same instance to reopen the
    /// window, which is why it lives there and not in an @StateObject of this struct.
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        Window("unrot", id: AppModel.mainWindowID) {
            ContentView()
                .environmentObject(appDelegate.model)
        }
        .defaultSize(width: 430, height: 900)
        .windowStyle(.hiddenTitleBar)
        // The window can be resized freely down to ContentView's minimum size (380 x 600).
        .windowResizability(.contentMinSize)

        MenuBarExtra("unrot", systemImage: "brain.head.profile") {
            MenuBarMenu()
                .environmentObject(appDelegate.model)
                .environmentObject(appDelegate.model.blocker)
        }
    }
}

// MARK: - App delegate

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {

    /// Created on first use (by the scene body or at launch), always on the main actor.
    private(set) lazy var model = AppModel()

    func applicationDidFinishLaunching(_ notification: Notification) {
        model.start()
    }

    // Closing the window must not quit: blocking only works while unrot runs.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    // Dock icon clicked (or the app opened again) while no window is visible: bring it back.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if flag { return true }
        // false = handled here. If AppModel has no way to open the window yet, let SwiftUI do it.
        return !model.showMainWindow()
    }

    // The user may have changed Login Items in System Settings meanwhile.
    func applicationDidBecomeActive(_ notification: Notification) {
        model.refreshLoginItem()
    }
}

// MARK: - Menu bar

/// The menu of the menu bar extra (the default .menu style turns these views into menu items).
@MainActor
struct MenuBarMenu: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var blocker: MacBlocker
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Text("Brain Coins: \(model.coins ?? 0)")
        Text(lockState)

        Divider()

        Button("Open unrot") {
            if !model.showMainWindow() {
                openWindow(id: AppModel.mainWindowID)
            }
        }
        Toggle("Open at Login", isOn: $model.opensAtLoginSetting)

        Divider()

        Button("Quit unrot") {
            NSApp.terminate(nil)
        }
        .keyboardShortcut("q")
    }

    private var lockState: String {
        let now = Date()
        if let until = blocker.sessionUntil, until > now {
            return "Unrot session until \(Self.time(until))"
        }
        if let until = blocker.unlockedUntil, until > now {
            return "Unlocked until \(Self.time(until))"
        }
        return blocker.distracting.isEmpty ? "No distracting apps picked yet" : "Apps locked"
    }

    /// Hours and minutes in the user's own style (24-hour or AM/PM).
    private static func time(_ date: Date) -> String {
        date.formatted(date: .omitted, time: .shortened)
    }
}
