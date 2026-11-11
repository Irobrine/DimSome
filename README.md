# App Dimmer

A small, local-only Android app that dims **only the apps you choose**. It draws a
full-screen black overlay over selected apps, with opacity adjustable from
`0%` (no change) to `100%`. The overlay never intercepts input — taps,
scrolling, typing, and gestures pass straight through to the app underneath.

There are two overlay engines:

| Mode | Max darkness | Requires | Draws with |
|---|---|---|---|
| **Full black** | 100% | Accessibility access | `TYPE_ACCESSIBILITY_OVERLAY` |
| **Compatibility** | 80% (Android limit) | Display over other apps + Usage access | `TYPE_APPLICATION_OVERLAY` in a foreground service |

Compatibility mode is independent of Accessibility and is the better choice on
devices that stop accessibility services randomly.

## Features

- Pick any launchable app with a searchable list.
- Master on/off switch.
- Overlay mode selector (Full black / Compatibility).
- Opacity slider from 0 to 100, with a live preview.
- Overlay only appears over the selected apps; the home screen, lock screen,
  our own UI, and unselected apps are untouched.
- Ignores the keyboard, so typing does not make the overlay disappear.
- Recovers from rotations, missed events, and dropped overlay windows.
- Remembers your selection and settings across restarts and reboots.
- No network permission and no analytics. Everything stays on the device.

## Requirements

- Android 8.0 (API 26) or newer.
- **Full black mode:** Accessibility access. App Dimmer reads only the
  foreground **package name**; it cannot read screen content or control the
  device.
- **Compatibility mode:** "Display over other apps" and "Usage access". The
  foreground app is detected through `UsageStatsManager`; Accessibility is not
  needed.

## Build

Requirements: JDK 17 and an Android SDK with platform 37.

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install it with:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Setup

1. Open App Dimmer and choose an **Overlay mode**.
2. Grant the permissions that mode needs:
   - **Full black:** tap **Grant** next to *Accessibility access*, then enable
     **App Dimmer** under **Settings → Accessibility**.
   - **Compatibility:** grant *Display over other apps* and *Usage access*.
     Usage access lives under **Settings → Apps → Special app access → Usage
     access → App Dimmer**.
3. Turn on the switch in the top-right corner.
4. Choose one or more apps and set the dim level.

At `0%` the overlay is removed entirely. In Full black mode, `100%` is completely
black, including the status and navigation bars. In Compatibility mode, Android
caps non-interactive application overlays at 80%; the UI shows this limit and
the preview reflects the effective darkness.

Touches still work underneath. Pressing Home or Recents removes the overlay
because the foreground app changes.

## How it works

### Full black engine

- `ForegroundAppAccessibilityService` watches `TYPE_WINDOW_STATE_CHANGED` and
  `TYPE_WINDOWS_CHANGED` accessibility events and tracks the top-most active
  **application** window's package name. System windows (status bar,
  notification shade, IME, lock screen) are ignored.
- The dim layer is a `TYPE_ACCESSIBILITY_OVERLAY` window with
  `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE`, so all input passes through.
  Accessibility overlays are trusted and can be fully opaque.
- Rotations re-apply full-screen bounds and the correct alpha without creating a
  second window. A short grace period prevents the transient launcher/system
  window that appears during rotation from hiding the overlay.
- A one-second watchdog re-reads the active window and repairs missed events, a
  dropped overlay window, or an interrupted fade.

### Compatibility engine

- `DimOverlayService` is a `specialUse` foreground service. It owns one
  `TYPE_APPLICATION_OVERLAY` window and updates its window alpha directly, so the
  80% system cap is respected without double-dimming.
- It polls `UsageStatsManager` every 500 ms for the last resumed activity. This
  path does not require an AccessibilityService at all.
- The overlay hides for the lock screen, screen-off, our own UI, and unselected
  apps. A 600 ms grace period prevents transient rotation windows from causing
  flicker.
- A `BOOT_COMPLETED` receiver restarts the foreground service after a reboot if
  the mode is enabled and its permissions are still granted.
- The foreground service shows a low-priority notification with a **Stop**
  action, as required by Android for long-running foreground services.

### Shared behavior

- Opacity, the master switch, the selected package set, and the engine choice
  are stored in `SharedPreferences`; both services listen for changes.
- Switching engines stops the other engine before the new one starts, so two
  overlays are never active at the same time.
- Overlay windows are removed synchronously, so duplicate/stacked windows cannot
  accumulate.

## Project layout

```
app/src/main/java/com/mono/appdimmer/
├── MainActivity.kt                       Compose host + settings shortcuts
├── MainViewModel.kt                      UI state, permissions, service routing
├── data/
│   ├── AppSettings.kt                    Settings + overlay engine enum
│   ├── InstalledApps.kt                  Launchable app discovery + icons
│   └── PermissionUtils.kt                Accessibility/overlay/usage checks
├── service/
│   ├── ForegroundAppAccessibilityService.kt   Full-black engine
│   └── DimOverlayService.kt                   Compatibility engine (FGS)
├── receiver/
│   └── BootReceiver.kt                   Restarts the compatibility service
└── ui/
    ├── AppDimmerScreen.kt                Mode picker, app picker, slider
    └── theme/Theme.kt                    Dark Material 3 theme
```

## Notes

- If the overlay ever stops in Full black mode, switch to **Compatibility**.
  It is independent of the accessibility service and generally survives
  aggressive OEM background management better.
- Compatibility mode requires a persistent low-priority notification because it
  uses a foreground service.
- Some OEM skins delay or suppress accessibility window events. The full-black
  engine re-checks the foreground every second as a fallback.
- On aggressive OEM skins, exclude App Dimmer from battery optimization and
  allow auto-start if the system is prone to killing background services.
- The app is intended for personal/sideloaded use.
