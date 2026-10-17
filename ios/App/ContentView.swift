// ContentView.swift
// Full-screen web view on the unrot sky-blue background. In the Screen Time build it also hosts
// Apple's FamilyActivityPicker: ScreenTimeManager flips isPickerPresented when a bridge call
// (pickDistracting / pickAllowed) needs it, and resumes that call when the sheet closes.

import SwiftUI
import FamilyControls

struct ContentView: View {
    @ObservedObject var screenTime: ScreenTimeManager

    var body: some View {
        WebView(screenTime: screenTime)
            .ignoresSafeArea()
            .background(Color.unrotSky.ignoresSafeArea())
            .modifier(ActivityPickerHost(screenTime: screenTime))
    }
}

/// Attaches .familyActivityPicker only in the Screen Time build, so the free build never touches
/// FamilyControls UI.
private struct ActivityPickerHost: ViewModifier {
    @ObservedObject var screenTime: ScreenTimeManager

    @ViewBuilder
    func body(content: Content) -> some View {
        if screenTime.isEnabled {
            content.familyActivityPicker(
                isPresented: $screenTime.isPickerPresented,
                selection: $screenTime.pickerSelection
            )
        } else {
            content
        }
    }
}

extension Color {
    /// unrot sky blue, #3ea7ee.
    static let unrotSky = Color(red: 0x3E / 255.0, green: 0xA7 / 255.0, blue: 0xEE / 255.0)
}
