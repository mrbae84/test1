// ContentView.swift
// The main window: the web app on the unrot sky-blue background. It also hosts the AppPicker
// sheet: AppModel sets `picker` when a bridge call (pickDistracting / pickAllowed) needs it, and
// resumes that call when the sheet closes (the Mac version of the iPhone app's
// FamilyActivityPicker flow).

import SwiftUI

@MainActor
struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        MacWebView(model: model)
            // With the hidden title bar the content starts below the traffic lights; the strip
            // above it shows the sky-blue background.
            .background(Color.unrotSky.ignoresSafeArea())
            .frame(minWidth: 380, minHeight: 600)
            .sheet(item: $model.picker, onDismiss: { model.finishPicker(with: nil) }) { request in
                AppPicker(request: request) { selection in
                    model.finishPicker(with: selection)
                }
            }
            .onAppear {
                // Lets AppModel reopen this window later (Dock click, menu bar, a blocked app).
                // Copy the action out of the environment now; the closure outlives this view.
                let open = openWindow
                model.openMainWindowAction = { open(id: AppModel.mainWindowID) }
            }
            .onDisappear {
                // The window closed while the picker was open: resume the bridge call.
                model.finishPicker(with: nil)
            }
    }
}

extension Color {
    /// unrot sky blue, #3ea7ee.
    static let unrotSky = Color(red: 0x3E / 255.0, green: 0xA7 / 255.0, blue: 0xEE / 255.0)
}
