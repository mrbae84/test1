# unrot for iPhone

This folder holds the iPhone app. It is a small native SwiftUI shell that shows the web app from
[`../unrot`](../unrot) in a full-screen web view. In its Screen Time build it also really blocks
distracting apps with Apple's Screen Time API (FamilyControls, ManagedSettings, DeviceActivity).

There are **two builds from the same code**:

| | Free build | Screen Time build |
| --- | --- | --- |
| Spec file | `project.yml` | `project.screentime.yml` |
| Xcode project | `Unrot.xcodeproj` | `UnrotScreenTime.xcodeproj` |
| Apple account | Any Apple ID (free "Personal Team") | Paid Apple Developer Program ($99/year) |
| Blocks other apps | No, scroll time works on the honour system | Yes |
| Targets | App only | App + 3 extensions |

Apple only grants the Family Controls capability that app blocking needs to paid developer
accounts, which is why the free build exists. Start with the free build. Switch later without
losing anything: both builds install as the same app.

The `.xcodeproj` files are **generated** by [XcodeGen](https://github.com/yonaskolb/XcodeGen) from
the `.yml` specs and are not stored in git. Regenerate them whenever you pull new code.

---

## Part 1: Run the free build on your iPhone

### 1. Install the tools (once)

1. **Xcode**: install it from the Mac App Store, open it once and let it install its components.
   When it asks which platforms to install, include **iOS**.
2. **Homebrew** (a package manager for the Mac). Open the **Terminal** app and paste the command
   from [brew.sh](https://brew.sh):
   ```sh
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
   ```
   At the end it prints "Next steps" with two or three commands. Run them, then close and reopen
   Terminal.
3. **XcodeGen**:
   ```sh
   brew install xcodegen
   ```

### 2. Get the code and go to this folder

```sh
cd path/to/this/repository/ios
```

Tip: type `cd ` (with a space), then drag the `ios` folder from Finder into the Terminal window
and press Return.

### 3. Edit `Config.xcconfig`

Open `ios/Config.xcconfig` in any text editor (or in Xcode) and change:

- `BUNDLE_PREFIX = com.yourname` to something unique to you, for example `com.janedoe` or
  `io.github.janedoe`. Only letters, digits, hyphens and dots. Apple rejects IDs that someone else
  already uses, so do not keep `com.yourname`.
- `DEVELOPMENT_TEAM =` can stay empty for now (you pick the team in Xcode in step 6). If you
  later know your 10-character Team ID, put it here (for example `DEVELOPMENT_TEAM = AB12CD34EF`)
  so you never have to pick the team again after regenerating.

### 4. Generate the Xcode project

```sh
xcodegen
```

This reads `project.yml` and writes `Unrot.xcodeproj`.

### 5. Open it

```sh
open Unrot.xcodeproj
```

### 6. Signing & Capabilities: pick your team

1. Xcode > **Settings** > **Accounts** > **+** > **Apple ID**, and sign in.
2. In the left sidebar click the blue **Unrot** project icon, select the **Unrot** target, then
   the **Signing & Capabilities** tab.
3. Keep **Automatically manage signing** on and choose your name ("Your Name (Personal Team)")
   under **Team**.

If Xcode says the bundle identifier is not available, change `BUNDLE_PREFIX` in
`Config.xcconfig`, run `xcodegen` again and reopen the project.

### 7. Connect your iPhone and turn on Developer Mode

1. Connect the iPhone with a cable, unlock it and tap **Trust** when it asks.
2. On the iPhone: **Settings** > **Privacy & Security** > **Developer Mode** > on, then restart
   the iPhone when it asks. (The switch only shows after the iPhone has been connected to Xcode once.)
3. In Xcode's toolbar, pick your iPhone as the run destination (top center).

### 8. Run

Press **Run** (the ▶ button, or Cmd+R). The first build takes a minute.

### 9. Trust the developer profile (first time only)

If the iPhone says "Untrusted Developer": **Settings** > **General** > **VPN & Device
Management** > your Apple ID > **Trust**. Then press Run again.

### Good to know about free builds

- Apps signed with a free Personal Team **stop opening after 7 days**. Connect the iPhone and
  press Run again to renew them; your data stays.
- A free account can have only a few such apps installed at the same time.

---

## Part 2: Turn on real app blocking (Screen Time build)

1. **Join the [Apple Developer Program](https://developer.apple.com/programs/)** (paid,
   per year). Approval can take a day or two. In Xcode > Settings > Accounts your account then
   shows a team without "(Personal Team)".
2. Optionally put that team's ID into `DEVELOPMENT_TEAM` in `Config.xcconfig`.
3. Generate the Screen Time project:
   ```sh
   cd path/to/this/repository/ios
   xcodegen --spec project.screentime.yml
   open UnrotScreenTime.xcodeproj
   ```
4. **Set the team on all 4 targets** (skip this if `DEVELOPMENT_TEAM` is filled in): click the
   blue project icon, then for each of **Unrot**, **UnrotMonitor**, **UnrotShield** and
   **UnrotShieldAction**: Signing & Capabilities > Team > your paid team. Xcode registers the
   App Group `group.<BUNDLE_PREFIX>.unrot` and the Family Controls capability for you. If it shows
   an error about the App Group, click **Try Again** or the **+** next to App Groups and tick the
   group.
5. Pick your iPhone and press **Run**. (Screen Time blocking does not work in the Simulator.)
6. **In the app**, grant Screen Time access when onboarding asks (or later in
   **Settings > Blocking Issues**). iOS asks for Face ID / Touch ID / passcode and for
   notifications. Allow both.
7. Pick your **distracting apps** (Settings > Distracting apps). You can choose whole categories,
   single apps and websites. They are blocked right away.

### How blocking behaves

- **Distracting apps are always blocked.** Opening one shows the sky-blue "Brainrot blocked"
  screen.
- **Buy scroll time to unlock them.** Spending Brain Coins on the Scroll screen unlocks the
  distracting apps. They lock again as soon as you have *used* them for the minutes you bought,
  or when the unlock window ends.
- **The 15-minute rule.** Apple's DeviceActivity only watches windows of at least 15 minutes. If
  you buy 5 minutes, the apps lock after 5 minutes of use, or 15 minutes after buying if you do
  not use them that long. unrot also re-locks the moment you open it after the bought time has
  passed. A monitoring callback only runs while the iPhone is in use, so a locked phone may show
  the change a little later.
- **Unrot sessions block everything except your allowed apps.** Starting a session (Workout,
  Cook a meal, ...) shields all apps and websites except the ones picked as allowed for sessions.
  iOS never lets apps block a few system apps, such as Phone. The block lifts when the session
  ends.
- **The shield's "Unlock with Brain Coins" button cannot open unrot directly.** Apple does not
  let a shield open its parent app, so the button sends a notification "Open unrot". Tap it to
  jump into unrot and buy scroll time. This needs notifications to be allowed for unrot.

### Publishing on the App Store

The Family Controls capability in Xcode is the **development** version. To ship the Screen Time
build on the App Store or TestFlight you must also request Apple's separate **Family Controls
(Distribution)** entitlement for every bundle ID (the app and the 3 extensions) at
[developer.apple.com/contact/request/family-controls-distribution](https://developer.apple.com/contact/request/family-controls-distribution)
and wait for approval.

---

## Troubleshooting

**`xcodegen: command not found`**
Install it with `brew install xcodegen`. If `brew` is not found either, finish the Homebrew
"Next steps" and reopen Terminal.

**"Failed to register bundle identifier" / "is not available"**
Someone already uses that ID. Change `BUNDLE_PREFIX` in `Config.xcconfig`, run `xcodegen` (or
`xcodegen --spec project.screentime.yml`) again and reopen the project.

**"Signing for Unrot requires a development team"**
Pick a team in Signing & Capabilities (step 6), or set `DEVELOPMENT_TEAM` in `Config.xcconfig`.

**"Personal development teams do not support the Family Controls capability"**
You opened the Screen Time project with a free account. Use `Unrot.xcodeproj` (free build), or
join the Apple Developer Program first.

**The team I picked disappeared**
Running `xcodegen` rebuilds the project and forgets choices made in Xcode. Put your Team ID into
`DEVELOPMENT_TEAM` in `Config.xcconfig` to make it permanent.

**"Untrusted Developer" or the app will not open**
Settings > General > VPN & Device Management > trust your Apple ID. With a free account, also
re-run from Xcode every 7 days.

**"Developer Mode required"**
Settings > Privacy & Security > Developer Mode > on, then restart the iPhone.

**Screen Time shows "unavailable" in the app**
You are running the free build, or the app was signed without the Family Controls capability.
Build `UnrotScreenTime.xcodeproj` with a paid team. On the iPhone, Screen Time must be allowed
(Settings > Screen Time; it must not be restricted by a parent's Family Sharing settings), the
device needs a passcode, and you must be signed in to iCloud.

**Screen Time access was denied**
Settings > Screen Time > Apps With Screen Time Access > turn unrot on (the exact place differs
between iOS versions), or delete and reinstall the app to be asked again.

**Apps don't unlock or re-lock as expected**
Check that all four targets are signed with the same paid team and that each one shows the
App Group `group.<BUNDLE_PREFIX>.unrot` under Signing & Capabilities. Monitoring callbacks run
only while the iPhone is in use. Opening unrot always re-checks the times.

**No "Open unrot" notification from the shield**
Allow notifications: Settings > Notifications > unrot.

**I changed files in `../unrot` and the app still shows the old version**
Product > Clean Build Folder (Shift+Cmd+K), then Run again.

**Debugging the web app on the iPhone**
Debug builds are inspectable: on the Mac open Safari > Settings > Advanced > "Show features for
web developers", then Safari > Develop > *your iPhone* > unrot.

---

## What is in this folder

```
Config.xcconfig              BUNDLE_PREFIX and DEVELOPMENT_TEAM (the only file you edit)
project.yml                  free build spec            -> Unrot.xcodeproj
project.screentime.yml       Screen Time build spec     -> UnrotScreenTime.xcodeproj
project.common.yml           settings both specs include (app target, Info.plist keys)
App/                         the app: SwiftUI shell, WKWebView, bridge, Screen Time manager
Shared/                      code shared by the app and the extensions (stores, selections)
UnrotMonitor/                DeviceActivityMonitor extension (re-locks, ends sessions)
UnrotShield/                 ShieldConfiguration extension (what the block screen looks like)
UnrotShieldAction/           ShieldAction extension (what the block screen's buttons do)
Generated/                   Info.plists written by XcodeGen (not in git)
```

The web app is copied into the app bundle as the folder `web/unrot/` (not `unrot/`, which would
clash with the `Unrot` executable on a case-insensitive disk) and served to the web view from
`app://unrot/index.html`. JavaScript talks to iOS through
`window.webkit.messageHandlers.unrot.postMessage({ method, args })`, which returns a Promise.
The methods are `status`, `authorize`, `pickDistracting`, `pickAllowed`, `unlock`, `relock`,
`startSession`, `endSession`, `saveState` and `openSettings`. In a normal browser
`window.webkit` does not exist and the web app keeps working on the honour system.

GitHub Actions (`.github/workflows/ios.yml`) generates and builds both projects for the
Simulator, without signing, on every push that touches `ios/` or `unrot/`.
