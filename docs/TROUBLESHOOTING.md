# Android TV Launcher Troubleshooting & Firmware Field Notes

Preserved field knowledge from MASTER_PROMPT Annexes A–I.

## 1. Firmware & Vendor Quirks

### 1.1 CVTE Boards (Haier, JVC, generic Android TVs)
- **Warning:** On CVTE-based boards (`com.cvte.*`), never disable the vendor launcher (`com.cvte.tv.launcher.*`) unless a verified procedure exists for the exact board.
- **Boot Hang Hazard:** Never enable a remote-key helper and change HOME in the same pass before a reboot. A JVC LT-43N7115 (`TP.SK706S.PC822`, Android 11) hung on boot animation with ADB disabled after both were executed together (issue #741).
- **Factory Menu Danger:** Never open factory/production/AT menus. On Haier/CVTE boards, opening one flips an internal factory-mode flag that kills the Input key, disables CEC, and breaks subsequent boots, and the ADB shell cannot revert it.

### 1.2 Google TV & SetupWraith
- **Package:** `com.google.android.tungsten.setupwraith`
- **Wi-Fi Deletion Risk:** Before disabling setupwraith, check:
  `dumpsys wifi | grep -o 'cname=[^ ]*'`
  A Wi-Fi network saved during initial TV setup belongs to setupwraith. Android removes that network immediately upon disabling setupwraith, disconnecting the TV from Wi-Fi and terminating wireless ADB. If present, forget the network and reconnect via standard Settings first.
- **RecoveryActivity Takeover:** On Google TV, `set-home-activity` can print `Success` while setupwraith's `.RecoveryActivity` continues intercepting the HOME key. Verify with `dumpsys activity activities | grep mFocusedApp`.

### 1.3 TCL & Remote Key Repeat Drop
- On TCL G09 builds (Google TV 12), binding a key-filtering accessibility service drops key repeats: holding volume-up or D-pad becomes a single step per press instead of continuous scrolling.
- Scancode mapping: buttons reporting `KEY_UNKNOWN` or `KEY_PROGRAM` cannot be mapped by keycode. Identify scancodes with `getevent -lt` and configure via `settings_key_scancodes` in `config.xml`.

### 1.4 Display Overrides & Density
- If screen content appears enlarged or clipped after installation:
  - Check: `wm size`, `wm density`, and `settings get secure display_density_forced`.
  - An empty `display_density_forced` is standard default. Do not change resolution unless an unintended override is active.

### 1.5 Opaque Theme Requirement
- Ensure `styles.xml` declares:
  ```xml
  <item name="android:windowIsTranslucent">false</item>
  <item name="android:windowIsFloating">false</item>
  <item name="android:windowSwipeToDismiss">false</item>
  ```
  On certain firmware, default themes resolve as translucent, causing background Live TV or HDMI inputs to bleed through under the launcher interface.

## 2. Screenshot & Capture Troubleshooting
- Capturing screenshots via `screencap` on HDMI inputs, Live TV, or HDCP streaming apps (e.g. Netflix) produces empty (0-byte) files. This is an OS security restriction, not a script defect.
- If repeated screenshots show a previously closed app, check `dumpsys window | grep mCurrentFocus` to verify whether `screencap` is returning a cached frame.
