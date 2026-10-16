# adb-auto-enable

Automatically keeps wireless ADB debugging enabled on Meta Quest devices — no root required.

> **Security notice:** Wireless ADB provides full shell access to the device. Only use this on isolated, trusted networks. Never expose port 5555 to the internet or untrusted networks.

## How it works

- **On boot:** `BootReceiver` catches `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` and starts the service immediately, including during encrypted boot.
- **Settings observer:** Watches the `adb_wifi_enabled` global setting. Any change to `0` is immediately reversed.
- **Network callback:** Re-enforces ADB on every WiFi connection and validation event (covers network changes and reconnects).
- **Periodic watchdog:** Fallback check every 60 seconds in case the above hooks are missed.
- **Debounce:** A 500 ms guard prevents rapid-fire re-triggering.
- **VrUsb popup (Meta Quest):** The accessibility service detects the "VrUsb" trust dialog by window title and sends TAB × 3 + ENTER to accept "Always allow", fully automatically.

## Requirements

- Meta Quest (tested on Meta Quest 3, and on several firmware versions)
- `WRITE_SECURE_SETTINGS` permission granted once via ADB from a PC (see Setup)
- Accessibility service enabled on the headset

## Setup

### 1. Build and install

```bash
git clone https://github.com/your-org/adb-auto-enable.git
cd adb-auto-enable
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

Or download a pre-built APK from Releases.

### 2. Grant WRITE_SECURE_SETTINGS (one-time)

This permission cannot be granted from within the app. Connect via USB and run:

```bash
adb shell pm grant eu.project_simple.adbautoenable android.permission.WRITE_SECURE_SETTINGS
```

### 3. Enable the accessibility service

On the headset:

1. Settings → Accessibility
2. Find **ADB Auto-Enable** and enable it
3. Accept the confirmation prompt

The service is also enabled programmatically at startup, but manual activation ensures it survives reboots cleanly.

### 4. Launch the app

Open **ADB Auto-Enable** once (after installing) to start the foreground service. After that it starts automatically on every boot.

## Project structure

```
app/src/main/
├── java/eu/project_simple/adbautoenable/
│   ├── MainActivity.java                  # Starts AdbConfigService, then exits
│   ├── AdbConfigService.java              # Foreground watchdog service
│   ├── BootReceiver.java                  # Boot broadcast receiver
│   └── AdbPopupAccessibilityService.java  # Meta Quest VrUsb popup handler
├── AndroidManifest.xml
└── res/
    ├── xml/adb_popup_accessibility_config.xml
    └── values/strings.xml
```

## Troubleshooting

**Check permission status:**

```bash
adb shell dumpsys package eu.project_simple.adbautoenable | grep WRITE_SECURE_SETTINGS
```

Expected: `android.permission.WRITE_SECURE_SETTINGS: granted=true`

**Re-grant permission after reinstall:**

```bash
adb shell pm grant eu.project_simple.adbautoenable android.permission.WRITE_SECURE_SETTINGS
```

**Force-restart the service:**

```bash
adb shell am force-stop eu.project_simple.adbautoenable
adb shell am start -n eu.project_simple.adbautoenable/.MainActivity
```

**View live logs:**

```bash
adb logcat -s ADBAutoEnable ADBAutoEnable.A11y
```

**Reset permissions if stuck:**

```bash
adb shell cmd appops reset eu.project_simple.adbautoenable
adb shell pm grant eu.project_simple.adbautoenable android.permission.WRITE_SECURE_SETTINGS
```

---

Technical blog post : https://arthurbrugiere.fr/blog/2026/05/mq3-wireless-adb/
