# Jelly Browser+ (Browser+)

A lightweight, patched build of LineageOS Jelly browser (v16) with proper desktop mode, built-in ad blocking, dark mode for websites, and an in-app tab switcher — designed for minimal resource usage on Android 15/16.

**Package name:** `org.lineageos.jelly.patched` (installs alongside stock Jelly — no conflicts)  
**App name:** Browser+  
**Size:** ~3.1 MB (R8/proguard optimized, PNG crushed)  
**Min SDK:** Android 10+ (SDK 29)  
**Target SDK:** Android 16 (SDK 36)

## ✨ Features over stock Jelly

| Feature | Stock Jelly | Browser+ |
|---|---|---|
| Desktop mode | Only changes User-Agent (broken for most sites) | Proper UA + forced 1280px viewport (sites like GitHub serve actual desktop layout) |
| Dark mode for sites | Broken on Android 12+ (deprecated FORCE_DARK API) | CSS invert filter with images/videos re-inverted for natural colors |
| Ad blocking | ❌ None | ✅ Hosts-based blocker with 3 levels (Lite / Moderate / Aggressive) |
| Tab management | Each tab = separate Android task → clutters Recents | In-app tab switcher (tap the square icon); single entry in Recents |
| New windows | Opens in new task/Recents entry | Opens as in-app tab automatically |
| Long-press links | New tab → clutter | New tab stays inside the app |

## 🛡️ Ad Blocker Levels

- **Lite** (~6,500 domains) — AdAway default hosts, smallest memory footprint
- **Moderate** (~22,000 domains) — AdAway + Dan Pollock + YoYo hosts (**default**, recommended balance)
- **Aggressive** (~99,000 domains) — StevenBlack unified hosts (most thorough, slightly more RAM)

The blocker intercepts ads/trackers at the sub-resource level only — main pages are never blocked, so you won't see blank pages.

## 📥 Install

1. **Uninstall any previous Browser+ v16.0/v16.1 builds** (if you tested earlier patches) to avoid signature conflicts
2. Download the latest APK from [Releases](https://github.com/Rocker14427c/Jelly-Browser-Plus/releases)
3. Install the APK (allow "Install unknown apps" permission when prompted)
4. Optional: disable stock Jelly in Settings → Apps if you don't need it

## 🎯 Recommended Settings (lightest footprint)

- Home page: **about:blank** (or your preferred lightweight start page)
- JavaScript: **On** (required for most sites; turn off per-site if needed)
- Location: **Off** unless you need it
- Do Not Track: **On**
- Ad block: **Moderate** (default)
- Dark mode: Toggle from ⋮ menu → Dark mode

## 🖱️ Usage Tips

- **New tab:** ⋮ menu → New tab
- **Switch tabs:** Tap the square "tabs" icon next to the URL bar → tap any tab to switch; **long-press** a tab to close it
- **Close current tab:** Just navigate back or open the tab switcher and long-press to close
- **Desktop site:** ⋮ menu → Desktop site (proper desktop layout, not just UA spoof)
- **Dark mode:** ⋮ menu → Dark mode (applies CSS inversion to all sites)

## 🔒 Privacy

- No telemetry, no Google Play Services dependency
- Uses system WebView (always updated via your ROM/Google Play)
- Ad blocking done locally — no external VPN/proxy needed
- All settings stored locally in SharedPreferences

## 🔨 Building from source

```bash
# Prerequisites: JDK 17, Android SDK with platform 36 & build-tools 36
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export GRADLE_OPTS="-Xmx1536m -XX:MaxMetaspaceSize=512m"

cd Jelly-Browser-Plus
./gradlew assembleRelease --no-daemon
# Output: app/build/outputs/apk/release/app-release.apk
```

Signed with a release keystore (not platform key) so it installs as a regular user app.

## 📝 Changelog

### v16.2-patched
- Fixed in-app tab switcher (no more Recents clutter — single task)
- Fixed dark mode using CSS invert filter (works on Android 16)
- Fixed desktop mode with proper viewport injection
- Added tab count badge on tabs button
- Long-press to close tabs in tab switcher
- R8/minify + resource shrinking + PNG crunching for smallest APK

### v16.1-patched
- Added 3-level ad blocker
- Added dark mode toggle (FORCE_DARK, broken on A16)
- Added tab button

### v16.0-patched
- Initial patch: desktop mode fix, basic ad block, basic tab handling

## ⚖️ License

Same as LineageOS Jelly — [Apache License 2.0](LICENSE). Patches are released under the same license.

## Credits

- Original Jelly browser: [LineageOS](https://github.com/LineageOS/android_packages_apps_Jelly)
- Ad block hosts: [AdAway](https://adaway.org/), [Dan Pollock](https://someonewhocares.org/), [YoYo](https://pgl.yoyo.org/), [StevenBlack](https://github.com/StevenBlack/hosts)
