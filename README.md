<p align="center">
  <img src="assets/jelly-title.gif" alt="Jelly Browser+ animated wordmark" width="600">
</p>

<p align="center">
  <strong>Jelly Browser+</strong> — a feature-packed fork of the LineageOS <em>Jelly</em> browser with
  in-app tabs, a Via-style tab switcher, 3-level ad blocking, and Chrome-style dark mode.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0">
  <a href="https://github.com/Rocker14427c/Jelly-Browser-Plus/releases/latest"><img src="https://img.shields.io/github/v/release/Rocker14427c/Jelly-Browser-Plus?label=release&color=blueviolet" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/target%20SDK-36-orange" alt="Target SDK 36">
</p>

---

## ✨ Features

- **🗂️ In-app tabs** — multiple tabs live inside a single activity, so Android Recents stays clean
  (`documentLaunchMode="never"`, no task spam from `window.open`).
- **🃏 Via-style tab switcher** — full-screen 2-column grid with live preview thumbnails,
  per-card close buttons, Chrome-style **swipe-to-close** (cards follow your finger), a new-tab
  FAB, active-tab highlighting, and a tab-count badge on the toolbar. 20-tab cap (oldest tab is
  recycled).
- **🌙 Chrome-style dark mode** — not a crude page invert. See [How dark mode works](#-how-dark-mode-works).
- **🛡️ Ad blocker, 3 levels** — Lite / Moderate (recommended) / Aggressive, backed by ~99k hosts
  loaded off the UI thread (no freeze on the first search).
- **🖥️ Desktop mode** — modern desktop user agent + `width=1280` viewport injection, per tab.
- **🚫 Popup blocker that doesn't crash** — blocked `window.open` calls are consumed safely
  (no destroyed-WebView handed to the renderer, which is what used to crash search pages).
- **🕶️ Incognito tabs** — per-tab incognito with its own cookie handling.
- **▶️ Background shortcuts** — keep media playing in the background via a foreground service,
  with a proper WebView swap (no more destroyed-WebView-as-active-tab crashes).
- **⭐ Favorites & 🕘 History** — Room-backed, with search.
- **📋 Long-press menu** — open in new tab, **copy link address**, share, add to favorites,
  download (links to files).
- **🔍 Find in page**, **📤 Advanced share** (page screenshot), **🔒 Look lock**, **🤏 Reach mode**,
  **💡 Suggestion providers** (Baidu/Bing/Brave/Duck/Google/Yahoo/history), **🧩 PWA manifest
  support** & *Add to home screen*.
- **📥 Built-in downloader** — no system DownloadManager, no Chrome-style single-connection
  slowness: downloads run in-app with **segmented parallel connections** (up to 4 Range streams
  written into one file), automatic fallback when a server doesn't support ranges, pause /
  resume / retry (progress survives app restarts), an in-app **Downloads screen**, and a
  progress notification. Files land in the public Downloads folder (MediaStore) on Android 10+.
- **👆 Edge-swipe navigation** — Chrome-style: swipe inward from the **left edge** to go back,
  from the **right edge** to go forward. A scrim + chevron follows your finger and turns
  accent-colored when the release will navigate. The gesture zone is configurable
  (Settings → Edge swipe navigation: off, 24/40/64 dp) so page scrolling and content gestures
  are unaffected outside it.
- **🔗 External links** open in-app (`onNewIntent`), no duplicate Recents entries.

## 🐛 Fixes shipped so far

| Version | What was fixed |
|---|---|
| **v16.13** | Tapping a finished download now opens the **right app**: MIME is resolved from the file name (`.apk` → package installer, `.pdf` → PDF readers, …), so the package manager properly appears; single-handler types open directly, others get a chooser with only the relevant apps. MediaStore records get the corrected MIME too. |
| **v16.12** | **Built-in downloader** replaces the system DownloadManager: segmented (parallel Range-request) downloads for much higher speed, automatic single-connection fallback, pause/resume/retry with progress persisted across app restarts, in-app Downloads screen, and a foreground-service notification. Downloads land in the public Downloads folder via MediaStore (Android 10+). |
| **v16.11** | Chrome-style **edge-swipe navigation**: swipe inward from the left edge of a page to go back, from the right edge to go forward, with a scrim + chevron that follows your finger. The gesture zone is **configurable** (Settings → Edge swipe navigation: off / 24 / 40 / 64 dp) so it doesn't interfere with page content. |
| **v16.10** | Long-pressing a link now offers **Copy link address** (clipboard + confirmation snackbar). Build now produces a **stock-package APK** (`org.lineageos.jelly`) alongside the patched one, so it can act as a drop-in replacement for the LineageOS system browser. |
| **v16.9** | Reverted the URL-bar history suggestions from v16.8; swipe-to-close tabs kept as-is. |
| **v16.8** | Chrome-style **swipe-to-close** in the tab switcher (cards follow the finger, dismiss past threshold). |
| **v16.7** | Blocked requests now return an **empty response** instead of a 1×1 GIF placeholder — the GIF "loaded" successfully, so ad-test sites counted those ads as loaded and reported the blocker as ineffective. Removed the built-in self-test page. |
| **v16.6** | Ad blocker actually blocks now: the shipped lists carry a trailing `$` on every line, which the loader stored verbatim — so no request ever matched and blocking was a no-op. Parser rewritten (handles `domain$`, `0.0.0.0 host`, `||domain^`, comments) and blocked requests are logged. |
| **v16.5** | Chrome-style dark mode replaces the aggressive `invert(1) hue-rotate(180deg)` filter; dark mode now applies consistently to all tabs; menu switch persists to settings. |
| **v16.4** | The real search/tab crash: a recycled favicon Bitmap was handed to `TaskDescription` on every page load (search) and tab switch. Favicons are now private copies; all bitmap hand-offs are recycled-bitmap safe. Also: menu no longer self-triggers its switches, real ProGuard rules for the WebView JS interfaces. |
| **v16.3** (original fork work) | Dark mode on Android 16, Via-style tab switcher, no Recents clutter, `uiMode` in `configChanges`. |

## 📦 Download & install

Get the latest APK from the
**[Releases page](https://github.com/Rocker14427c/Jelly-Browser-Plus/releases/latest)**.

```bash
adb install -r JellyBrowserPlus-v16.5-fixed.apk
```

- Package: `org.lineageos.jelly.patched` · minSdk 26 (Android 8.0) · targetSdk 36
  (a `-stock` APK with the `org.lineageos.jelly` package is also attached — see below)
- All releases since v16.3 are signed with the **same key**, so they update in place.
  If you're coming from a build signed with a different key, uninstall first.
- A `-debug` APK is attached to releases for diagnostics (package
  `org.lineageos.jelly.patched.dev`, installs side-by-side, unminified).

## 🛠️ Build from source

### Requirements

- **JDK 17+** (21 recommended)
- **Android SDK** — platform 36, build-tools 36.0.0
- ~2 GB RAM is enough **if you add swap**; a comfortable build wants 8 GB total memory

### 1. Add swap (the 6 GB recipe used for the CI builds of this repo)

```bash
sudo dd if=/dev/zero of=/swapfile bs=1M count=6144 status=progress
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
free -h        # verify: swap should now show 6.0 GiB
```

Make it permanent (optional): add `/swapfile none swap sw 0 0` to `/etc/fstab`.

### 2. Clone & build

```bash
git clone https://github.com/Rocker14427c/Jelly-Browser-Plus.git
cd Jelly-Browser-Plus
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleRelease      # release APKs for BOTH flavors
./gradlew assembleDebug        # debug APKs for BOTH flavors
```

The build produces two flavors (same code, different package):

| Flavor | Package | Output APK |
|---|---|---|
| `patched` | `org.lineageos.jelly.patched` (debug: `.patched.dev`) | `app/build/outputs/apk/patched/release/app-patched-release.apk` |
| `stock` | `org.lineageos.jelly` — the LineageOS system-browser package | `app/build/outputs/apk/stock/release/app-stock-release.apk` |

The stock flavor is a drop-in replacement for the preinstalled Jelly browser. Since it's
signed with this repo's key (not the platform key), remove the system app first on ROMs that
ship it:

```bash
adb shell pm uninstall --user 0 org.lineageos.jelly   # or disable in Settings
adb install JellyBrowserPlus-v16.10-stock.apk
```

### Signing note

`app/build.gradle.kts` references `app/release.keystore`, which is **git-ignored**. The CI build
generates it from the credentials in that file; replace them with your own key for personal
builds (keep the same keystore if you want OTA-style updates to stay compatible).

## 📖 Usage

- **Tabs** — the tabs button (badge shows the count) opens the Via-style switcher: tap a card to
  switch, **×** to close, or **swipe a card sideways** (Chrome-style) to dismiss it — the card
  follows your finger and closes when you release past the threshold. Closing the last tab hands
  you a fresh one.
- **Dark mode** — toggle in the **⋮ menu → "Dark mode for sites"**, or persist it in
  **Settings → Dark mode for websites**. It applies to all open tabs.
- **Ad block** — **Settings → Ad blocker**, then pick the level:
  - *Lite* — basic ad networks only
  - *Moderate* — ads + trackers (default)
  - *Aggressive* — ads + trackers + malware domains (may break some sites)
  The page reloads automatically when you change it.

### 🧪 Verifying the ad blocker

1. **Logcat** — every blocked request is logged:
   ```bash
   adb logcat -s AdBlock
   # Browse an ad-heavy site; you should see "Blocked: https://…" lines
   ```
2. **Ad-block test sites** — e.g. [d3ward.github.io/toolz/adblock](https://d3ward.github.io/toolz/adblock)
   or adblock-tester.com. Compare the blocked percentage with the ad blocker off vs. on — the
   jump is the blocker working. (Sites score a request as blocked when the resource *fails to
   load*; this browser returns an empty response for blocked hosts, so they register correctly.)
3. **A/B test** — load the same ad-heavy site with the blocker on and off; ad slots disappear
   when it's on.

> **What it can't block (by design):** this is a *host*-based blocker, like a hosts file. Ads
> served from the same domain as the content (first-party ads), and the empty ad *slots*
> themselves, aren't removed. Cosmetic filtering (hiding leftover ad containers) needs
> filter-list support — see [Chrome extension support?](#chrome-extension-support) below.
- **Desktop mode** — toggle in the **⋮ menu**; each tab remembers its own setting.
- **Incognito** — **⋮ → New private tab**; cookies/domStorage are disabled per tab.
- **Background playback** — **⋮ → Background shortcuts**; media keeps playing when the screen is off.
- **Downloads** — **⋮ → Downloads** opens the in-app download manager: progress, per-download
  speed, pause/resume, cancel, tap a finished file to open it. Downloads keep running in the
  background with a notification while anything is active; a download interrupted by the app
  being killed shows up as paused and can be resumed.
- **Find in page** — **⋮ → Find in page**, or start typing in the search mode of the URL bar.
- **Edge gestures** — swipe inward from the **left edge** of a page to go back, from the **right
  edge** to go forward (only when the page can actually navigate that way). The gesture area is
  limited to a configurable strip at the screen edge — *Settings → Edge swipe navigation* lets
  you turn it off or pick 24/40/64 dp. Tip: on Android's gesture navigation, the extreme edge
  belongs to the system back gesture, so start your swipe slightly inside the zone.

## 🌙 How dark mode works

Chrome's dark mode is layered, and so is this one:

1. **Native darkening (Android 10+, API 33+ WebView)** — when dark mode is enabled, each tab's
   WebView is created with a dark theme context (`isLightTheme=false`). This makes the WebView
   report `prefers-color-scheme: dark` — sites with their own dark themes (Google, YouTube, …)
   render their *real* dark versions — and enables **Chromium's algorithmic darkening**, the exact
   auto-darkening algorithm Chrome uses, for pages that don't define dark styles. (`setForceDark`
   is a no-op for targetSdk 33+ apps, which is why it was removed.)
2. **Smart JS fallback (older devices / light-context tabs)** — a rewritten stylesheet that uses
   Chrome's dark palette (`#202124` background, `#e8eaed` text, `#8ab4f8` links), inverts only
   color *lightness* while preserving hue/saturation, never touches images/video/background
   images, skips pages that are already dark, sets `color-scheme: dark` for native dark form
   controls, and tracks dynamically-added content with a `MutationObserver`. Toggling it off
   restores the original styles completely.
3. Tabs are recreated with the matching context when you flip the switch — same page-reload
   behavior as toggling dark mode in Chrome — and the WebView starts on `#202124` so there's no
   white flash while pages load.

## 🧩 Chrome extension support?

**Short answer: no — not real Chrome extensions.** Android's WebView (which this browser is built
on) contains no extension runtime: no `chrome.*` APIs, no background service workers, no content
script pipeline. Even Chrome for Android itself doesn't run desktop extensions, and the only
Android browsers that do (e.g. Kiwi) are heavily patched full-Chromium forks, not WebView apps.

What's realistic on this codebase, in increasing order of effort:

| Option | What you get | Effort |
|---|---|---|
| **uBlock-style filter lists** (EasyList/EasyPrivacy/uBlock filters) | Network blocking by pattern + cosmetic element hiding — ~95% of what people use ad-blocking extensions for | Medium — doable on WebView |
| **Userscript manager** (Tampermonkey-style) | Per-site custom JS/CSS injection | Medium-large |
| **Full Chromium fork** (Kiwi-style) | Actual `.crx` extensions | Very large — replaces the whole engine |

The built-in blocker already implements the *network* half of filter lists (hosts-based). The
natural next step is EasyList/uBlock filter syntax + cosmetic hiding. If you'd like that built,
open an issue — it's on the roadmap.

## 🗂️ Project layout

```
app/src/main/java/org/lineageos/jelly/
├── MainActivity.kt          # browser UI, tab orchestration, menu actions
├── ui/
│   ├── TabSwitcherActivity.kt   # Via-style full-screen tab grid
│   ├── UrlBarLayout.kt          # URL/search bar + tab-count badge
│   └── MenuDialog.kt
├── utils/
│   ├── TabUtils.kt          # in-app tab manager (create/switch/close/swap)
│   ├── AdBlock.kt           # hosts-file ad blocker (async loader)
│   └── UrlUtils.kt          # URL/search normalization
├── webview/
│   ├── WebViewExt.kt        # WebView + dark mode + per-tab state
│   ├── ChromeClient.kt      # popups, downloads, favicons, permissions
│   └── WebClient.kt         # ad-block interception, external-app routing
├── favorite/  history/  shortcut/  suggestions/  ...
tools/
└── gen_jelly_gif.py         # regenerates assets/jelly-title.gif
assets/
├── adblock_hosts_{lite,moderate,aggressive}.txt
└── jelly-title.gif          # animated wordmark used at the top of this README
```

## 🎨 Regenerate the wordmark

```bash
pip install pillow          # + gifsicle for the smaller optimized output
python3 tools/gen_jelly_gif.py
```

## 🙏 Credits & license

Based on the LineageOS **Jelly** browser
([android_packages_apps_Jelly](https://github.com/LineageOS/android_packages_apps_Jelly)),
plus the patches in this repo. Licensed under **Apache-2.0** (see `LICENSES/`), REUSE-compliant.
This is a community fork and is not affiliated with or endorsed by the LineageOS project.
