// ShieldConfigurationExtension.swift  (UnrotShield target)
// Decides what the shield looks like when someone opens a blocked app or website.
// The system calls these methods quickly and in a sandbox (no network); keep them simple.
//
// Two looks:
// - distracting shield: "Brainrot blocked" with an "Unlock with Brain Coins" button
// - during an unrot session (sessionUntil in the future): "You're unrotting", no second button

import ManagedSettings
import ManagedSettingsUI
import UIKit

final class ShieldConfigurationExtension: ShieldConfigurationDataSource {

    override func configuration(shielding application: Application) -> ShieldConfiguration {
        makeConfiguration()
    }

    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration {
        makeConfiguration()
    }

    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration {
        makeConfiguration()
    }

    override func configuration(shielding webDomain: WebDomain, in category: ActivityCategory) -> ShieldConfiguration {
        makeConfiguration()
    }

    // MARK: -

    private static let sky = UIColor(red: 0x3E / 255.0, green: 0xA7 / 255.0, blue: 0xEE / 255.0, alpha: 1)
    private static let ink = UIColor(red: 0x0E / 255.0, green: 0x16 / 255.0, blue: 0x12 / 255.0, alpha: 1)

    private func makeConfiguration() -> ShieldConfiguration {
        // ShieldIcon lives in this extension's own Assets.xcassets.
        let icon = UIImage(named: "ShieldIcon")
        let close = ShieldConfiguration.Label(text: "Close", color: Self.sky)

        if UnrotShared.isSessionActive {
            return ShieldConfiguration(
                backgroundBlurStyle: nil,
                backgroundColor: Self.sky,
                icon: icon,
                title: ShieldConfiguration.Label(text: "You're unrotting", color: .white),
                subtitle: ShieldConfiguration.Label(text: "This app is paused until your session ends.", color: .white),
                primaryButtonLabel: close,
                primaryButtonBackgroundColor: .white,
                secondaryButtonLabel: nil
            )
        }

        return ShieldConfiguration(
            backgroundBlurStyle: nil,
            backgroundColor: Self.sky,
            icon: icon,
            title: ShieldConfiguration.Label(text: "Brainrot blocked", color: .white),
            subtitle: ShieldConfiguration.Label(text: "Spend Brain Coins in unrot to scroll.", color: .white),
            primaryButtonLabel: close,
            primaryButtonBackgroundColor: .white,
            secondaryButtonLabel: ShieldConfiguration.Label(text: "Unlock with Brain Coins", color: Self.ink)
        )
    }
}
