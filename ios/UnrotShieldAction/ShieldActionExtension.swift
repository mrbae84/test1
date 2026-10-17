// ShieldActionExtension.swift  (UnrotShieldAction target)
// Handles taps on the shield's buttons.
//
// Apple does not let a shield open the parent app: ShieldActionResponse only offers .none,
// .close and .defer (plus .openParentalControlsApp, which is not unrot), and extensions cannot
// call UIApplication.open. So "Unlock with Brain Coins" posts a local notification instead;
// tapping it opens unrot, where the user can buy scroll time.
// The notification only shows if the user allowed notifications for unrot (the app asks during
// Screen Time setup).

import ManagedSettings
import UserNotifications

final class ShieldActionExtension: ShieldActionDelegate {

    override func handle(action: ShieldAction, for application: ApplicationToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        respond(to: action, completionHandler: completionHandler)
    }

    override func handle(action: ShieldAction, for webDomain: WebDomainToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        respond(to: action, completionHandler: completionHandler)
    }

    override func handle(action: ShieldAction, for category: ActivityCategoryToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        respond(to: action, completionHandler: completionHandler)
    }

    private func respond(to action: ShieldAction, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        switch action {
        case .primaryButtonPressed:
            completionHandler(.close)
        case .secondaryButtonPressed:
            postOpenUnrotNotification {
                completionHandler(.close)
            }
        default:
            // Submenu items (newer SDKs) and future actions: nothing special.
            completionHandler(.close)
        }
    }

    private func postOpenUnrotNotification(then done: @escaping () -> Void) {
        let content = UNMutableNotificationContent()
        content.title = "Open unrot"
        content.body = "Tap here to spend Brain Coins and unlock."
        content.sound = .default
        // A nil trigger delivers right away. A fixed identifier replaces an older, unread one.
        let request = UNNotificationRequest(identifier: "unrot.shield.unlock", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                print("[unrot] could not post shield notification: \(error)")
            }
            done()
        }
    }
}
