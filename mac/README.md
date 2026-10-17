# unrot for Mac

This folder holds the Mac app. It shows the same web app as the iPhone version
([`../unrot`](../unrot)) in its own window, and it really **blocks your distracting apps** until
you spend Brain Coins on scroll time.

You do **not** need Xcode, a developer account or any money to use it.

---

## Install it (easiest way)

1. Open the release page:
   **[github.com/mrbae84/test1/releases/tag/unrot-latest](https://github.com/mrbae84/test1/releases/tag/unrot-latest)**
2. Under **Assets**, click **`unrot-mac.zip`**. It downloads to your **Downloads** folder.
3. Double-click `unrot-mac.zip` (if Safari did not unzip it already). You get **Unrot**.
4. Drag **Unrot** into your **Applications** folder (in Finder: Go > Applications).
5. Open it for the first time (see the next section). After that, open it like any other app.

### The first time you open it

Because unrot is not notarized by Apple, macOS stops it the first time with a message like
*"Apple could not verify that Unrot is free of malware"* or *"Unrot cannot be opened because the
developer cannot be verified"*. That is expected. Do this once:

**macOS 14 Sonoma (and older):**

1. In **Applications**, **right-click** (or Control-click) **Unrot** and choose **Open**.
2. Click **Open** in the message that appears.

**macOS 15 Sequoia and newer** (right-click > Open no longer skips the check):

1. Double-click **Unrot**. When the message appears, click **Done** (or **OK**).
2. Open **System Settings** > **Privacy & Security** and scroll all the way down.
3. Next to *"Unrot was blocked to protect your Mac"*, click **Open Anyway**, then enter your
   password (or use Touch ID).
4. Click **Open Anyway** once more.

**Why this happens:** Apple only notarizes apps from members of the paid Apple Developer Program
($99/year). unrot is built automatically by GitHub from the code in this repository and signed
"ad hoc" (without an Apple account). You can read every line of it here.

### Turn on Open at Login (recommended)

unrot can only block apps **while it is running**. Click the brain icon in the menu bar (top
right of the screen) and tick **Open at Login**, so it starts with your Mac. If macOS asks, allow
it under System Settings > General > Login Items.

---

## How blocking works on the Mac

Apple's Screen Time blocking (what the iPhone app uses) does not exist for Mac apps. So unrot
works like other Mac focus apps: it watches which app you switch to and steps in.

- **Pick your distracting apps** in unrot (Settings > Distracting apps). A list of the apps on
  your Mac opens; tick the ones that steal your time.
- **They stay locked until you buy scroll time.** When you switch to a locked app, unrot
  **hides** it and comes to the front. Spend Brain Coins on the Scroll screen to unlock them for
  exactly the minutes you bought. When the time is up, they lock again.
- **Unrot sessions block everything else.** During a session (Workout, Cook a meal, ...) every
  app is hidden except the **allowed apps** you picked, plus Finder, System Settings and
  Activity Monitor, which always stay usable.
- **unrot lives in the menu bar.** Closing the window does not quit it; the brain icon shows your
  Brain Coins and whether your apps are locked. Choose **Quit unrot** there to stop it (which
  also stops all blocking).

### Limits (good to know)

- **unrot must be running.** If you quit it, nothing is blocked. Turn on **Open at Login**.
- **Apps are hidden, not quit.** Nothing you were doing is lost; the app just cannot stay in
  front while it is locked.
- **Websites are not blocked.** unrot blocks apps. If your browser is where the scrolling happens,
  add the browser itself to your distracting apps (and keep a second browser in the allowed apps
  for work, if you need one).
- **The app list** shows apps from `/Applications`, `/Applications/Utilities`,
  `/System/Applications` and `~/Applications` (not from subfolders).

---

## Build it yourself (optional)

You only need this if you want to change the code.

1. Install **Xcode** from the Mac App Store and open it once.
2. Install [Homebrew](https://brew.sh) and then XcodeGen:
   ```sh
   brew install xcodegen
   ```
3. Generate the Xcode project and open it:
   ```sh
   cd path/to/this/repository/mac
   xcodegen
   open UnrotMac.xcodeproj
   ```
4. Press **Run** (Cmd+R). No Apple ID or team is needed: the project signs "ad hoc"
   (`CODE_SIGN_IDENTITY = "-"`, "Sign to Run Locally").

The `.xcodeproj` is **generated** from [`project.yml`](project.yml) and not stored in git; run
`xcodegen` again after pulling new code. Changes to files in `../unrot` show up after
Product > Clean Build Folder and Run.

Debug builds let you inspect the web page: Safari > Settings > Advanced > "Show features for web
developers", then Safari > Develop > *this Mac* > unrot.

### What is in this folder

```
project.yml          XcodeGen spec              -> UnrotMac.xcodeproj
Config.xcconfig      BUNDLE_PREFIX (bundle ID is <BUNDLE_PREFIX>.unrot.mac)
App/
  UnrotMacApp.swift  the app: main window, menu bar extra, app delegate
  AppModel.swift     shared state: window, app picker, Brain Coins, Open at Login
  ContentView.swift  the main window: web view + app picker sheet
  MacWebView.swift   WKWebView showing app://unrot/index.html
  MacBridge.swift    the web <-> Mac bridge (same contract as the iPhone app, build "mac")
  MacBlocker.swift   hides blocked apps (NSWorkspace), unlock and session timers
  AppPicker.swift    the "Distracting apps" / "Allowed apps" sheet
  Assets.xcassets    app icon
Generated/           Info.plist written by XcodeGen (not in git)
```

The file server for `app://` (`BundleSchemeHandler`) is shared with the iPhone app and lives in
[`../ios/WebShared`](../ios/WebShared).

### The bridge on the Mac

The web app talks to the Mac app exactly like to the iPhone app:
`window.webkit.messageHandlers.unrot.postMessage({ method, args })` returns a Promise, and
`window.__unrotNative = { build: "mac", savedState }` is defined before the page loads. The
methods are `status`, `authorize`, `pickDistracting`, `pickAllowed`, `unlock`, `relock`,
`startSession`, `endSession`, `saveState` and `openSettings`. On the Mac, `status` always reports
`screenTime: "approved"` (hiding apps needs no permission), and when an app was hidden the Mac
app calls `window.__unrotBlocked({ name, reason })` with `reason` `"locked"` or `"session"`.

GitHub Actions ([`.github/workflows/apps.yml`](../.github/workflows/apps.yml)) builds the app on
every push, launches it once as a smoke test and publishes `unrot-mac.zip` on the
[unrot-latest release](https://github.com/mrbae84/test1/releases/tag/unrot-latest).
