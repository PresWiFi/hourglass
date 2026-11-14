# Hourglass

Screen Time for Android, modeled on iOS Screen Time, and built to be hard to get around. Sideload only (not on Play).

## What it does

| iOS Screen Time | Hourglass |
|---|---|
| Daily/weekly report, category chart | Day and Week views, hourly/daily bars colored by category, % change vs last week |
| Most Used (apps, categories, websites) | Same, plus tap an app to change its category or add a limit |
| Pickups, first used after pickup | Same |
| Notifications per app | Same (needs Notification access, optional) |
| Downtime (every day or custom days, Block at Downtime) | Same, plus "Turn On Downtime Until Tomorrow" and a 5-minute warning |
| App Limits (apps, categories, websites, custom days, Block at End of Limit) | Same, with a 5-minute warning |
| One More Minute / Ask For More Time (15 min, 1 hour, all day) | Same; "Ask" needs the passcode |
| Always Allowed | Same (default dialer and SMS app added at setup) |
| Content & Privacy: block apps, installing/deleting apps, adult sites, allowed websites only | Same, for Chrome, Samsung Internet, Firefox, Edge, Brave, DuckDuckGo, Opera |
| Screen Time passcode, with lockout after wrong tries | 4 digits, PBKDF2-hashed, 1/5/15/60 minute lockouts, plus a recovery code |
| Weekly report notification | Sundays at 7 PM |
| Not in iOS | Not Counted apps (e.g. Maps while driving): left out of totals, charts and limits, still subject to Downtime |
| Not in iOS | Emergency breaks: 2 × 5 minutes a day (passcode to change), tamper log, locks for Date & time / Accounts / Developer options / Private DNS |

Not included: Communication Limits (Android only allows the default phone app to block calls), Screen Distance, sharing across devices.

## Hard to delete

While Screen Time is on (a passcode is set and it hasn't been turned off):
- **Device admin** makes Android refuse to uninstall it.
- **The accessibility service** backs out of any Settings, installer or Play Store screen that shows Hourglass alongside words like Uninstall, Force stop, Deactivate, Accessibility, Usage access or Battery. This covers app info, the uninstall dialog, device admin, and the accessibility list.
- If the accessibility service gets switched off anyway, the always-on monitor notices, sends a notification and logs it. Force-stops also go in the tamper log.
- To uninstall properly: Settings › Turn Off Screen Time (needs the passcode). That also removes device admin.
- **Pause Protection for 10 Minutes** (needs the passcode) lets you update Hourglass or change a locked system setting. Limits stay on during the pause.

What Android doesn't let a normal app stop: a factory reset, Safe Mode (third-party apps don't run there), and `adb` from a computer. Turn on the Developer Options lock to close the adb route.

## Build

Windows (Android Studio installed):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest assembleRelease
```

`local.properties` needs `sdk.dir=...`. The release APK is signed with the debug key, so it installs without a keystore: `app/build/outputs/apk/release/app-release.apk`.

## Install and set up

1. Sideload the APK. On Android 13+, if Accessibility is greyed out: Settings › Apps › Hourglass › ⋮ › **Allow restricted settings**.
2. Open Hourglass and work through the permission checklist **before** setting the passcode. Once the passcode is set, those settings pages are locked: Usage access, Accessibility, Display over other apps, Uninstall protection, Notifications, Battery, and Notification access (optional).
3. OnePlus: also set Settings › Apps › Hourglass › Battery to **Unrestricted**, and lock Hourglass in Recents.
4. Set the passcode and write down the recovery code. For a strict setup, have someone else type the passcode.

## How it works

- `Engine` replays Android's usage events once a second while the screen is on. That tells it what's on screen and today's time per app, and it shows `BlockActivity` when a rule applies.
- `GuardService` (accessibility) triggers checks instantly, reads browser address bars, and backs out of protected screens.
- `MonitorService` is a foreground service that keeps the process alive and shows today's total.
- `Logic.kt` holds all the rules as pure functions, with unit tests in `LogicTest.kt`.
- Data stays in one JSON file in app storage. No internet permission, no accounts, `allowBackup=false`.
