// UnrotApp.swift
// Entry point of the native iOS shell around the unrot web app.

import SwiftUI

@main
struct UnrotApp: App {
    /// Owns Screen Time state and the FamilyActivityPicker presentation for the whole app.
    @StateObject private var screenTime = ScreenTimeManager()

    var body: some Scene {
        WindowGroup {
            ContentView(screenTime: screenTime)
        }
    }
}
