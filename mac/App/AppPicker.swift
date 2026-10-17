// AppPicker.swift
// The Mac's stand-in for Apple's FamilyActivityPicker: a sheet on the main window that lists the
// installed apps with a checkbox each. Used for both "Distracting apps" (locked until scroll time
// is bought) and "Allowed apps" (still usable during unrot sessions).
//
// Flow: the bridge call (pickDistracting / pickAllowed) asks AppModel.pickApps, which sets
// AppModel.picker; ContentView shows this sheet; Done (or Cancel / Esc) calls back, and the
// bridge call resumes with the new selection (or nil for no change).
//
// Apps are found by a shallow scan of the usual Applications folders, off the main thread.

import AppKit
import SwiftUI

enum AppPickerKind {
    case distracting
    case allowed

    var title: String {
        switch self {
        case .distracting: return "Distracting apps"
        case .allowed: return "Allowed apps"
        }
    }

    var subtitle: String {
        switch self {
        case .distracting:
            return "These apps stay locked until you buy scroll time with Brain Coins."
        case .allowed:
            return "These apps keep working during unrot sessions. Finder, System Settings and Activity Monitor always do."
        }
    }
}

/// One open picker sheet (ContentView presents it with .sheet(item:)).
struct AppPickerRequest: Identifiable {
    let id = UUID()
    let kind: AppPickerKind
    /// Bundle IDs that start out ticked.
    let selection: Set<String>
}

/// An app found on disk (or a picked app that is no longer installed, with `path` nil).
struct InstalledApp: Identifiable, Hashable, Sendable {
    /// The bundle identifier, which is what MacBlocker stores.
    let id: String
    let name: String
    let path: String?

    /// Shallow scan (no subfolders) of the folders apps are normally installed in.
    static func scan(excluding excluded: Set<String>) -> [InstalledApp] {
        let fileManager = FileManager.default
        let folders = [
            "/Applications",
            "/Applications/Utilities",
            "/System/Applications",
            "/System/Applications/Utilities",
        ].map { URL(fileURLWithPath: $0, isDirectory: true) }
            + [fileManager.homeDirectoryForCurrentUser.appendingPathComponent("Applications", isDirectory: true)]

        var seen = Set<String>()
        var apps: [InstalledApp] = []
        for folder in folders {
            guard let urls = try? fileManager.contentsOfDirectory(
                at: folder, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]
            ) else { continue }
            for url in urls where url.pathExtension.lowercased() == "app" {
                guard let bundle = Bundle(url: url),
                      let bundleID = bundle.bundleIdentifier,
                      !excluded.contains(bundleID),
                      seen.insert(bundleID).inserted else { continue }
                apps.append(InstalledApp(id: bundleID, name: displayName(of: bundle, at: url), path: url.path))
            }
        }
        return apps
    }

    private static func displayName(of bundle: Bundle, at url: URL) -> String {
        for key in ["CFBundleDisplayName", "CFBundleName"] {
            let value = bundle.localizedInfoDictionary?[key] as? String ?? bundle.infoDictionary?[key] as? String
            if let value, !value.trimmingCharacters(in: .whitespaces).isEmpty { return value }
        }
        return url.deletingPathExtension().lastPathComponent
    }
}

@MainActor
struct AppPicker: View {
    let request: AppPickerRequest
    /// Called with the new selection (Done) or nil (Cancel).
    let onFinish: (Set<String>?) -> Void

    @State private var selection: Set<String>
    @State private var apps: [InstalledApp] = []
    @State private var isLoading = true
    @State private var query = ""

    init(request: AppPickerRequest, onFinish: @escaping (Set<String>?) -> Void) {
        self.request = request
        self.onFinish = onFinish
        _selection = State(initialValue: request.selection)
    }

    private var visibleApps: [InstalledApp] {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return apps }
        return apps.filter { $0.name.localizedCaseInsensitiveContains(text) || $0.id.localizedCaseInsensitiveContains(text) }
    }

    private var selectionSummary: String {
        selection.isEmpty ? "None selected" : "\(selection.count) selected"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(request.kind.title)
                    .font(.title2.bold())
                Text(request.kind.subtitle)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            TextField("Search apps", text: $query)
                .textFieldStyle(.roundedBorder)

            Group {
                if isLoading {
                    ProgressView("Looking for apps…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if visibleApps.isEmpty {
                    Text(verbatim: apps.isEmpty ? "No apps found." : "No apps match \u{201C}\(query)\u{201D}.")
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(visibleApps) { app in
                        Toggle(isOn: isSelected(app.id)) {
                            HStack(spacing: 8) {
                                AppIcon(path: app.path)
                                Text(app.name)
                                    .lineLimit(1)
                            }
                        }
                        .toggleStyle(.checkbox)
                    }
                    .listStyle(.inset)
                }
            }
            .frame(maxHeight: .infinity)

            HStack {
                Text(selectionSummary)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Cancel") { onFinish(nil) }
                    .keyboardShortcut(.cancelAction)
                Button("Done") { onFinish(selection) }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 420, height: 560)
        .task { await loadApps() }
    }

    private func isSelected(_ bundleID: String) -> Binding<Bool> {
        Binding(
            get: { selection.contains(bundleID) },
            set: { isOn in
                if isOn {
                    selection.insert(bundleID)
                } else {
                    selection.remove(bundleID)
                }
            }
        )
    }

    private func loadApps() async {
        let excluded = MacBlocker.alwaysAllowed.union([Bundle.main.bundleIdentifier ?? ""])
        let found = await Task.detached(priority: .userInitiated) {
            InstalledApp.scan(excluding: excluded)
        }.value
        // Keep picked apps that were not found (deleted, or installed somewhere else) so they can
        // still be unticked.
        let foundIDs = Set(found.map(\.id))
        let missing = request.selection
            .filter { !foundIDs.contains($0) && !excluded.contains($0) }
            .map { InstalledApp(id: $0, name: $0, path: nil) }
        apps = (found + missing).sorted {
            $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
        isLoading = false
    }
}

/// The app's Finder icon at 24 pt (a placeholder for apps that are not installed any more).
@MainActor
private struct AppIcon: View {
    let path: String?

    var body: some View {
        Group {
            if let path {
                Image(nsImage: NSWorkspace.shared.icon(forFile: path))
                    .resizable()
            } else {
                Image(systemName: "app.dashed")
                    .resizable()
                    .foregroundStyle(.secondary)
            }
        }
        .aspectRatio(contentMode: .fit)
        .frame(width: 24, height: 24)
    }
}
