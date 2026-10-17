// DeviceActivityMonitorExtension.swift  (UnrotMonitor target)
// The system launches this extension for the DeviceActivity monitoring that the app starts, even
// while unrot itself is not running. Callbacks only arrive while the device is in use, so the
// app also re-checks the end times whenever it is opened.
//
//   "unlock"  - started when scroll time is bought. When the distracting apps were used for the
//               bought minutes (event threshold) or the interval ends, put the shield back.
//   "session" - started with an unrot session. When the interval ends, lift the session shield.

import DeviceActivity
import ManagedSettings
import Foundation

final class DeviceActivityMonitorExtension: DeviceActivityMonitor {

    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        // Nothing to do: the app already changed the shields when it started monitoring.
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        // The interval can also "end" because the app stopped or replaced the monitoring, so
        // only act when the stored end of the current window has really been reached.
        switch activity {
        case .unlock:
            if UnrotShared.hasEnded(UnrotShared.unlockWindowEnd) { relock() }
        case .session:
            if UnrotShared.hasEnded(UnrotShared.sessionWindowEnd) { UnrotShared.clearSession() }
        default:
            break
        }
    }

    override func eventDidReachThreshold(_ event: DeviceActivityEvent.Name, activity: DeviceActivityName) {
        super.eventDidReachThreshold(event, activity: activity)
        guard activity == .unlock else { return }
        relock()
        // The bought minutes are used up; the rest of the (at least 15 minute) window is not needed.
        DeviceActivityCenter().stopMonitoring([.unlock])
    }

    private func relock() {
        UnrotShared.applyDistractingShield()
        UnrotShared.unlockedUntil = nil
        UnrotShared.unlockWindowEnd = nil
    }
}
