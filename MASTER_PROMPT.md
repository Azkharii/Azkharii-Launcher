# MASTER PROMPT v3 — Cinematic Android TV Launcher (Hisense U75K)

Image-based animated background from Google Photos · persistent HOME · verified recovery · optional safe debloating

---

## HOW TO USE THIS DOCUMENT

This prompt has a **Core** (Parts 0–3, always in force) and **Annexes A–K**, one per phase. Read the Core fully, then read each Annex **only when you enter its phase**. Rules stated in the Core are not repeated in the Annexes.

- Language: write all reports, documentation, and messages to the user in **Spanish**. Code, comments, identifiers, commit messages, and script output stay in **English**.
- This prompt **supersedes the original "Apple TV style launcher + debloat" prompt** from the reference repository. Its design section is replaced by Annex C (cinematic carousel instead of an Apple-TV-style grid), but its field knowledge about firmware, ADB, recovery, and debloating is preserved and merged into the Annexes (marked *Field note*). Annex L lists exactly what changed. If a Field note conflicts with something actually observed on this TV, trust the observation and record the conflict.

---

# CORE

## 0. Role, mission, and non-negotiables

Act as a senior Android TV systems engineer, Android developer, ADB specialist, and UI/UX designer.

**Mission:** inspect, improve, build, install, test, and document a custom cinematic launcher on a real Hisense U75K, using the existing repository and tools wherever possible. The development environment is **Termux on an Android device connected to the TV through ADB over Wi-Fi**. Do not assume a desktop environment.

**Supported targets:** Android TV / Google TV sets (Xiaomi, TCL, Hisense, Sony, OnePlus, Realme, Nvidia Shield and most others). **Not supported:** Samsung Tizen, LG webOS, VIDAA, and Fire TV (see A.4). Some field notes below mention macOS because the original workflow ran on a computer; they apply only if the session is moved to such a computer, not to Termux.

**Product:** a cinematic home screen with a **Google Photos-based cinematic background system** featuring automated random rotation every 10 seconds with smooth crossfade transitions, an open central area, a bottom-centered 3D-style app carousel, a clock/date at the upper left, and **exactly three** translucent controls at the upper right (Inputs, Wi-Fi, Settings). It should be the TV's persistent default HOME where the firmware allows it.

**Non-negotiables (stated once, apply everywhere):**

1. Never claim success that has not been verified. Use these terms precisely: *implemented* (code exists), *built* (APK produced and inspected), *installed* (package present on TV), *tested* (exercised with evidence), *verified* (result confirmed by independent evidence, e.g. dumpsys output or screenshot). Success printed by a command ("Success") is not verification.
2. Never invent device data, logs, screenshots, package states, build results, or performance numbers. Record unavailable properties as "unavailable".
3. The original launcher must remain installed, enabled, and recoverable at all times unless the user separately and explicitly authorizes otherwise.
4. No MP4 or video playback, no ExoPlayer/Media3, no video-wallpaper subsystem, no network-dependent background or wallpaper downloads.
5. Do not factory-reset, flash firmware, root, unlock the bootloader, modify protected partitions, or open factory/production/AT menus (not even to "investigate" them; use read-only `dumpsys package`). On Haier/CVTE boards, opening one flips a factory-mode flag that kills the Input key and CEC and breaks the next boot, and the shell cannot undo it (see H.1b).
6. No destructive commands, wildcard package removal, broad permission changes, or speculative configuration changes. No ADB exposure to the public internet.
7. No Wi-Fi passwords, private keys, or signing secrets in logs, scripts, or version control.

## 1. Priority hierarchy

When requirements conflict, prioritize in this order:

1. TV stability, user data protection, recovery capability.
2. Preservation of existing repository functionality and device-specific fixes.
3. Correct HOME integration and physical-remote usability.
4. Fidelity to the cinematic design.
5. Responsiveness, resource efficiency, maintainability.
6. Optional debloating and secondary enhancements.

## 2. Autonomy and authorization model

Work autonomously end to end. Do not stop to ask for things you can discover or decide safely. Stop and ask only in the cases below.

**2.1 Phase 0 — Intake (ask ONCE, all questions in a single message, before touching anything):**

1. Television and repository: the exact TV model (brand, model name, region if known), and the path or URL of the existing repository (the reference repository provides `tools/`, `launcher/`, `docs/devices/`, and `examples/`, and should be reused rather than rewritten). If none exists, confirm creating a new project in `~/tv-launcher` (with `git init`).
2. Connection: how is ADB connected today (Wireless debugging, Wi-Fi/TCP, Ethernet)? Which devices are plugged into the TV's USB ports? Does the TV use a USB Ethernet adapter?
3. Apps: how many apps should the carousel show, which ones, and in what order? (If no preference: default to all discovered LEANBACK apps, ordered by label, capped at 12.)
4. Background: configuration for Google Photos access or authorized photo selection/local image collection source.
5. Build: is Gradle/SDK build possible in this Termux (see Annex F), or should the build happen elsewhere?
6. Debloating: is it wanted at all? (Default: no. Never ask again unless the user raises it.)

If the user does not answer an item, apply the stated default and record the assumption.

**2.2 Needs explicit authorization each time:** disabling/enabling any package, changing HOME defaults on the TV, changing Private DNS or router/network settings, changing USB-debugging or network connectivity settings, rebooting the TV, and anything in Annex H.

**2.3 Change control** — before any potentially disruptive operation: (1) identify exact target and expected effect, (2) state material risks, (3) verify backup and recovery, (4) obtain authorization if required by 2.2, (5) make the smallest necessary change, (6) verify the resulting state, (7) restore the previous state on unacceptable regression.

**2.4 Retry discipline:** after a failure, change the diagnosis before retrying. At most **two** attempts of the same operation without new evidence. If a verified firmware restriction blocks APK installation or HOME apps, stop attempts, preserve the original launcher, report the exact blocker, and continue only with independent, safe work.

**2.5 Version control:** work on a dedicated branch (e.g. `feature/cinematic-launcher`). Preserve all uncommitted and untracked user changes (stash or leave untouched; never discard). Commit at the end of each phase with a message describing verified state. Keep secrets out of commits.

## 3. Phases and completion conditions

Follow this order unless the discovered repository establishes a demonstrably safer alternative.

| Phase | Name | Completion condition | Annex |
|---|---|---|---|
| 0 | Intake | All intake answers or defaults recorded | Core 2.1 |
| 1 | Discovery | Documented baseline; **OS gate passed** | A |
| 2 | Protection | Backups and recovery sufficient for next operation | B |
| 3 | Implementation | Changes reviewed and ready to build | C, D |
| 4 | Build | Real build succeeds; APK inspected | F |
| 5 | Installation and validation | Applicable tests have evidence-based results; critical regressions resolved | E, G |
| 6 | Persistent HOME | Persistence verified, or exact firmware limitation documented | E |
| 7 | Debloating (optional) | Every change documented and reversible, or none made | H |
| 8 | Finalization | Final report reflects verified state only | K |

Failure handling (Annex J) and ADB connectivity (Annex I) apply in every phase.

---

# ANNEXES

## Annex A — Discovery (Phase 1)

**A.1 Repository.** Identify the root; read README and repo-specific instructions, `launcher/` sources, `tools/` (including `tools/tv.py`, connection/screenshot/backup/install/recovery helpers), `launcher/app/src/main/res/values/config.xml`, theme files and `config.json`, manifest, Gradle files, dependencies, build scripts, `docs/` (`docs/devices/`, `docs/TROUBLESHOOTING.md`, `docs/REMOTE.md`), `examples/`, prior logs and test reports. Inspect `git status` first.

The existing repository is the source of truth. Reuse existing carousel, configuration, background, navigation, and focus implementations. If the needed visual changes can be done through `config.xml` or an existing theme, prefer that over rewriting. Do not replace architecture, build system, media framework, or configuration format without a written technical justification. Do not delete or recreate working files merely to match this specification.

If a file or directory named here does not exist, note it and continue; do not fabricate it.

**A.2 Termux environment.** Check package manager, shell and filesystem layout, ADB client and device state, Java, Gradle, Android SDK/platform-tools/build-tools (and whether they are aarch64-compatible), screenshot/transfer utilities, free storage and memory. Install only what is necessary, using Termux-compatible paths.

**A.3 Device information (from real `adb` output only).** Manufacturer, model, product, device, board; Android version, API level, build fingerprint, firmware version, security patch; CPU ABI, RAM and memory pressure, storage and free space; display resolution, density, refresh rate, overrides; installed launchers and HOME-claiming activities (`cmd package query-activities` for the HOME intent); installed packages with labels, launcher activities, enabled state, installer; relevant Hisense/CVTE/Google TV/Android TV components; network state and ADB connection method; TV-input, remote-control, setup/provisioning, networking, and media services.

**A.4 OS gate (mandatory).** Confirm the TV actually runs Android / Android TV / Google TV (e.g. `getprop ro.build.version.release`, `ro.build.fingerprint`, existence of `pm`). If the TV runs a non-Android OS (Samsung Tizen, LG webOS, VIDAA on some Hisense models, Fire TV) or does not expose ADB/APK installation, **stop**, report the evidence, and do not continue to later phases.

**A.5 Firmware-specific knowledge.** Read repository device docs before modifying firmware-dependent behavior. Investigate `setupwraith`, CVTE services, vendor launchers, HOME-recovery activities, input-selection activities, and remote-key helpers **only if they exist on this device**. Do not assume any package, activity, service, port, or quirk applies universally. Preserve repository warnings, including cases where changing a package or enabling a remote-key helper can disrupt HDMI-CEC, remote keys, networking, Google services, or boot.

**A.6 APK installation feasibility.** Before substantial implementation, determine: TV SDK level; project `minSdk`/`targetSdk`; whether the firmware accepts sideloaded APKs; whether it accepts apps declaring `android.intent.category.HOME`; installer/signature/target-SDK restrictions. Do not claim installability before this is established (a harmless test APK or dry check is acceptable).

*Field note — known install blockers (check before building anything):*
- Some China-market sets answer **every** sideload with `INSTALL_FAILED_VERIFICATION_FAILURE` (`adb install`, `pm install`, a hand-started installer, even the manufacturer's own APKs). Others refuse any package declaring `android.intent.category.HOME` unless it carries the manufacturer's signature. The matching `examples/` header in the repo says which case applies. If you hit either one, state plainly that the launcher half is impossible on this set, do the debloat half only if the user opted in, and stop retrying installs.
- Compare `tools/tv.py 'getprop ro.build.version.sdk'` with `grep minSdk launcher/app/build.gradle` (26 = Android 8.0). A set below it answers `INSTALL_FAILED_OLDER_SDK`. Lowering `minSdk` is a source change the user would then have to maintain, and an APK rebuilt elsewhere cannot be verified by the repository; offer the debloat half and say the launcher cannot be installed.
- Some Google TV 14 sets refuse APKs with an old `targetSdk` at install; target a recent SDK.
- With no launcher to hand over to, the stock one stays. `cmd package` does not exist before Android 7, so `tools/disable.sh` asks `dumpsys activity` for Home there and warns when neither answers. That warning means **nothing was checked**, and a set with no recovery has no way back (repo issue #795).

**Output of Phase 1:** `docs/devices/<model>.md` (device inventory with only verified findings), plus a baseline of the original launcher/HOME state.

## Annex B — Protection: backups and recovery (Phase 2)

**B.1 What to back up (where accessible and permitted):** project files and configuration; current launcher package and HOME activity; current HOME resolution and relevant settings; original enabled/disabled state of any package that may be modified; the original launcher APK or another recoverable copy (never claim a protected APK was backed up if it was not accessible); current and last-known-good APK; network info needed to restore the dev connection (no passwords); any configuration the workflow will change. At minimum, save the full package list, the disabled-package list (`pm list packages -d`), the `getprop` output, and all `settings` namespaces (system, secure, global) before changing anything, and write a restore script that re-enables everything that is later disabled.

Use timestamped directories **outside** build output (e.g. `~/tv-backups/YYYYmmdd-HHMMSS/`). Preserve known-good recovery points.

**B.2 USB and network precautions.** Some TVs cut USB port power when debugging is enabled, disconnecting storage, speakers, or USB Ethernet adapters. Use the Phase 0 answers and inspect the real connection state before changing debugging or network settings. Prefer Wireless debugging when supported. Never change the only available connection without an alternative recovery path.

*Field note — USB debugging side effects:* some devices cut power to the USB port while USB debugging is on, so a USB speaker, drive, or **USB Ethernet adapter** on that port can go dead until debugging is switched off. Ask separately (1) what is plugged into the TV's USB ports and (2) whether the TV's network comes through a USB Ethernet adapter: "nothing plugged in" can be true while the network itself uses that port. On a set that reaches the network over USB Ethernet, check first: `dumpsys usb | grep host_connected` (`host_connected=false` means the adapter is already gone) and `dumpsys connectivity | grep -o 'Transports: [A-Z]*'` (`WIFI` on a TV the user calls wired means the box fell back and ADB is running over Wi-Fi). There the port only returns with USB debugging off, which closes port 5555 too, so finish the whole job in **one sitting**, and prefer Wireless debugging if the TV has it. Remind the user to switch debugging off when done (Annex I.2).

**B.3 Scripts.** Create or improve separate scripts for inspection, backup, installation, verification, and rollback, reusing existing repo utilities. Every script must: validate arguments and prerequisites, confirm the intended ADB target (`adb -s <serial>`), log meaningful output, detect and report failures, and have non-destructive defaults. Recovery must support, where technically possible: re-enabling the original launcher, restoring recorded package states, restoring documented settings, selecting the original HOME handler, and re-establishing the dev connection.

Document which recovery steps require the physical remote. Do not promise ADB-based recovery after ADB becomes inaccessible.

## Annex C — Visual design specification (Phase 3)

Do not copy a conventional app grid or a generic Google TV / streaming dashboard. The background is the dominant element.

**C.1 Layout (all values are *defaults* relative to the logical screen: W = width, H = height; adjust after real screenshots and record any deviation).**

| Element | Default |
|---|---|
| Safe margin (overscan) | 5% H top/bottom, 4% W left/right |
| Background | Full-screen, center-crop, no stretch, no black bars |
| Scrim | Top gradient (max 35% black) and bottom gradient (max 55% black); light vignette. No full-screen blur |
| Clock | Upper left. Time text ≈ 4.5% H, date ≈ 2.2% H, system locale/timezone/12-24 h format |
| Controls | Upper right, exactly 3, each ≈ 5.5% H square-rounded, spacing ≈ 1.2% W, translucent fill, 1 dp subtle border, no real-time backdrop blur |
| Central area | Empty: no cards, panels, widgets, news, ads |
| Carousel band | Bottom, height ≈ 24% H, vertically positioned inside the bottom safe margin |
| Focused card | 16:9 (or the app's banner ratio), height ≈ 60% of the band, centered horizontally |
| Neighbors | Scale 0.82 / 0.68 / 0.56, alpha 1.0 / 0.75 / 0.5 for ±1/±2/±3; up to 7 cards visible; others hidden |
| Label | Selected app's label below or on the focused card, ≥ 2.2% H, contrast ≥ 4.5:1 against its backdrop, one line with ellipsis |
| Focus indicator | Larger scale + visible 2–3 dp bright border or glow; unmistakable at 3 m viewing distance |
| Carousel motion | 180–240 ms, decelerate interpolator, interruptible; cards translated/scaled/alpha-faded (no rotation/perspective beyond a subtle `rotationY` ≤ 12° on neighbors, only if cheap) |
| Fonts | Existing font by default. A downloaded open-licence font (e.g. Outfit) is optional and only if bundled offline |

No clipping, label overlap, or off-screen cards for small (1–3 apps) and large (50+) collections. The carousel must remain visually subordinate to the wallpaper.

**C.2 Google Photos Cinematic Background Integration (Replaces Single Static Background Requirement):**
- **Objective:** The launcher's main home screen uses photographs from the user's Google Photos collection (aiming to leverage or mirror the album configured in the TV's system screensaver where accessible, or utilizing an authorized photo-picker workflow/local authorized image copies).
- **Automatic Rotation & Randomization:** The background changes automatically approximately **every 10 seconds** using a randomized sequence of photographs while the launcher home screen is visible. Avoid displaying the same photograph consecutively whenever multiple photographs are available.
- **Transitions:** Use smooth, subtle crossfade transitions (~600 ms) between photographs. Prevent black frames, flickering, abrupt transitions, and interface freezes during image changes. Pause background processing when the home screen is not visible (`onPause`/`onStop`) and resume correctly upon return.
- **Access Feasibility & Authorized Workflows:** Direct access to system screensaver albums by third-party apps is restricted; therefore, implement authorized photo selection, secure local image collection caching, or user-authorized picker workflows. Do not scrape private content or use unsupported backend methods.
- **Performance & Decoding:** Decode and resize images appropriately for the television's display resolution (`ImageDecoder` / downsampling) to prevent out-of-memory errors. Preload the next photograph when practical and release unused image resources. Handle missing files, revoked permissions, or network failures safely by retaining the last valid background instead of displaying a black screen.

**C.3 Background motion (intentionally omitted):** Do not implement a continuous wallpaper zoom, pan, or parallax animation. The TV's resource budget is prioritized for input responsiveness. Preserve randomized wallpaper rotation and the existing crossfade; do not retain dormant motion settings or animation code.

**C.4 Clock/date.** Update at minute boundaries only (and on `ACTION_TIME_TICK`/time-zone/locale change); stop updates when not visible. Optional local-only greeting.

**C.5 Three controls.**
- **Inputs:** inspect the actual firmware for a supported input-selection activity/intent or the TV Input Framework (`TvInputManager`). If a reliable method is verified, show a compact translucent dropdown of verified inputs (HDMI, AV, Live TV as supported). Do not invent activity names. If none, show a clear fallback message (e.g. "Use the remote's INPUT button").
- **Wi-Fi:** show the SSID only if genuinely available through supported APIs with granted permissions; handle disconnected/redacted/unavailable states; do not request unnecessary permissions just to show the SSID; open a **verified** network-settings activity; never store Wi-Fi passwords.
- **Settings:** open a verified settings activity/intent; graceful fallback if unavailable.

## Annex D — Application discovery, configuration, remote control, focus (Phase 3)

**D.1 Discovery.** Discover apps dynamically: prefer `LEANBACK_LAUNCHER`, also `LAUNCHER` where appropriate. Handle package-visibility restrictions with appropriate `<queries>` declarations for the detected SDK (they do not bypass all restrictions; test on the TV). Exclude the custom launcher itself and non-user-launchable system components. Handle duplicate labels, missing icons, disabled apps, and launch failures. Preserve user ordering and visibility across restarts. Never hardcode the full app list.

**D.2 Configuration.** Reuse the repository's existing mechanism (`columns`, `dock_apps`, `config.json`, `config.xml`) where compatible; do not introduce a competing system. If needed, one small documented config for: Google Photos / background image sources, motion on/off and intensity, app visibility and order, carousel spacing and scale, clock/date visibility, optional greeting.

**D.3 Remote navigation.** The physical remote is the primary input; no touch required.

| Key | Behavior |
|---|---|
| Left/Right | Move one carousel item; wrap-around disabled (stop at ends) unless the repo already wraps |
| Center/Enter | Launch the focused app |
| Up | From carousel → the nearest upper-right control (defaults to the first control); between controls, per explicit model below |
| Down | From controls → carousel (restoring last selected app); from carousel → no-op |
| Back | Never exits the launcher accidentally; from a control/dropdown → close it and return focus to carousel; from carousel → no-op |
| Long press / repeat | Handle `repeatCount`: at most ~12 moves/s, no item skipping beyond one per processed event |

Controls order (Left/Right between them): Inputs → Wi-Fi → Settings. All three are always reachable.

**D.4 Deterministic focus.** Implement an explicit focus model, not accidental framework traversal. Decorative/invisible elements never take focus; focus is never trapped; background effects never intercept D-pad events. Preserve selection after app-list refreshes when the item still exists; otherwise fall back to the nearest remaining item. Remember the last selected app; restore sensible focus after returning from apps, Settings, Wi-Fi, and Inputs.

**D.5 Remote-key helpers.** Inspect the repository's implementation and firmware behavior first. Do not enable helpers that remap physical keys without telling the user the trade-offs. If a button reports `KEY_UNKNOWN`/`KEY_PROGRAM`, investigate the actual scancode (`getevent`, key layout) before choosing a remapping method. Injected D-pad presses do **not** prove that physical remote buttons or accessibility-based helpers work; test the real remote when required (or mark NOT TESTED).

*Field note — stock-launcher buttons and key helpers:*
- On Google TV the stock launcher also answers the remote's app buttons (Netflix, YouTube, etc.), Input, and Settings/dashboard buttons. After disabling it, ask the user to press each one, and remap any that do nothing with the launcher's key services if the repo's launcher provides them: `input_key_keycodes` and `app_keys` with `INPUT_KEY_SERVICE=1`, `settings_key_keycodes` with `HOME_KEY_SERVICE=1` (in `config.xml` or the theme).
- Enabling either helper can cost more than the buttons it maps. On a TCL G09 build (Google TV 12) a bound key-filtering service drops key repeats: a ramping volume becomes one step per press, with one helper or both, all or nothing. Ask the user to hold volume up before and after, and say which one they are giving up instead of enabling it quietly (`docs/devices/tcl-55p71b-g09-key-repeat.md` has the bisect and three ways out). On other sets this was not measured; if holding a button stops repeating once a helper is on, it is the same trade, not a broken remote.
- A button logged as `KEY_UNKNOWN` or `KEY_PROGRAM` cannot be matched by keycode, since every unmapped button shares it: read the scancode with `getevent -lt` and use `settings_key_scancodes`, which only `config.xml` carries (a theme can set keycodes, not scancodes).
- If a remote button relies on an accessibility helper, check that the service is enabled and bound for the current user, then ask the user to press the real button.

## Annex E — HOME integration and persistence (Phases 5–6)

**E.1 Implementation.** Valid HOME activity with proper intent filters (`MAIN` + `HOME` + `DEFAULT`, and `LEANBACK_LAUNCHER` as appropriate); handle lifecycle and returns from apps; do not assume the filter makes it default.

*Field note — manifest and theme:* make the launcher theme explicitly opaque (`windowIsTranslucent`, `windowIsFloating`, and `windowSwipeToDismiss` all false). On some firmware `Theme.DeviceDefault` comes out translucent, the TV app keeps running under Home, and after an HDMI input the picture stays on screen. Also declare `<queries>` for `android.intent.category.LEANBACK_LAUNCHER` (Android 11+), or package visibility silently hides every app and the carousel comes up empty.

**E.2 Verification milestones — verify each independently, in this order:**
1. APK builds.
2. Package installs.
3. App launches explicitly.
4. Activity can be selected as HOME where supported.
5. Physical HOME button opens the custom launcher.
6. HOME stays correct after opening/returning from apps and system settings.
7. HOME selection persists after reboot.
8. Original launcher remains recoverable.

Use `cmd package query-activities`, `cmd package set-home-activity` (after checking Android version and command availability), and inspect real foreground state with `dumpsys activity activities`, `dumpsys window`, or repo helpers.

*Field note:* `query-activities` and `set-home-activity` can both name the custom launcher while Home still opens something else. On some Google TV builds `set-home-activity` prints `Success` and changes nothing, and setupwraith's `.RecoveryActivity` keeps the key. Confirm with `dumpsys activity activities | grep mFocusedApp` before reporting that the takeover worked. To list HOME claimants: `cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME`.

**E.3 Stock launcher and vendor recovery.** Do not disable the stock launcher right after installation. First establish that the custom launcher works, handles HOME correctly, and is recoverable. Before disabling any launcher, inspect all HOME-claiming activities, including setup/provisioning components. On devices with relevant `setupwraith` behavior, follow the exact repo documentation and check saved-network dependencies. On CVTE-based devices, follow the guidance for the exact board and leave the vendor launcher enabled unless a verified, device-specific procedure says otherwise. Never disable an original launcher just to make the custom interface appear permanent.

*Field notes:*
- **setupwraith** (`com.google.android.tungsten.setupwraith`): on Google TV the setup wizard takes over HOME even after the stock launcher is disabled; the matching `examples/` header says what to do. Before disabling it, run `dumpsys wifi | grep -o 'cname=[^ ]*'`: a Wi-Fi network saved during first setup belongs to it, and Android deletes that network the moment setupwraith is disabled, which takes the TV off Wi-Fi and ADB. If so, ask the user to forget the network and re-join it in Settings first.
- **CVTE boards** (any `com.cvte.*` package): leave the stock launcher (`com.cvte.tv.launcher.*`) enabled unless the `examples/` folder for that exact board disables it. Make the custom launcher Home with `set-home-activity`, reboot once, and confirm Home and ADB come back before anything else. **Do not enable a remote-key helper in the same pass**: a JVC LT-43N7115 (`TP.SK706S.PC822`, Android 11) hung on its boot animation with ADB gone after both were done before one reboot (repo issue #741).
- If setupwraith or the stock launcher came back after a reboot and Home is black, check `logcat | grep RecoveryActivity` and re-enable the stock launcher before anything else (`docs/devices/acer-r4g.md`).

**E.4 Meaning of "permanent".** A persistent default HOME that survives app launches and, when supported, reboots. Do not promise it if firmware policy, vendor setup services, package restrictions, or signature requirements prevent it. No root, firmware modification, bootloader unlock, or unsupported system-partition change as shortcuts. If persistence cannot be established safely, document the exact limitation and leave the original launcher recoverable.

Reboot only when the recovery prerequisites are adequate and the user has authorized it.

*Field note — power-on behavior:* after the first reboot with the new Home, check what the TV opens at power-on (`logcat -b events -d | grep wm_set_resumed_activity`). If the firmware puts Live TV on screen by itself, ask whether the user wants to land on Home or Live TV: Home is `power_on_home` with `HOME_KEY_SERVICE=1`, Live TV is `boot_to_live_tv` (`config.xml` or the theme). Never set both.

## Annex F — Build and installation (Phases 4–5)

**F.1 Build.** Reuse the repository's build system. Inspect Java, Gradle, SDK compatibility, dependencies, application ID, signing config, and build scripts. Do not assume a Gradle wrapper exists or works. Use a compatible SDK and minimal dependencies; avoid unnecessary permissions; keep keys and passwords out of version control; document release signing separately.

**F.2 Termux build feasibility and Plan B.** Building Android apps with Gradle on Termux (ARM) can fail because of non-native `aapt2`/SDK binaries. Test feasibility early (Phase 1/2). If the build cannot run in Termux, do not loop. Offer, in this order: (a) Termux-compatible build tools (e.g. `aapt2`/`d8`/`apksigner` packages or a documented community SDK), (b) building on another machine or in CI from the same repository, then transferring the APK. Document whichever is used, and mark the Termux build NOT TESTED/BLOCKED if it fails.

**F.3 After the build, verify:** exit status; APK existence and exact path; package ID and version; file size; signing info where available (`apksigner verify`); installation compatibility with the detected firmware.

**F.4 Before installing:** verify the intended ADB target; confirm APK path and package ID; inspect upgrade/signing compatibility; record current launcher state; confirm the original launcher is recoverable; write down the install and rollback commands.

*Field note:* if `adb install` hangs on Android 14, it is waiting for an on-screen confirmation; push the APK and run `pm install -r` over the shell instead.

**F.5 After installing:** confirm the package is installed; launch it explicitly; inspect `logcat` for crashes and rendering failures; run the Annex G tests. Repeat critical tests after meaningful fixes. If the firmware refuses sideloading or HOME apps for a verified reason, stop and document the blocker.

## Annex G — Screenshot validation and test matrix (Phase 5)

**G.1 Screenshots.** Use real TV screenshots, never mockups. Reuse `tools/tv.py` using the repo's documented syntax (e.g. `tools/tv.py --screenshot shots/01-after.png DPAD_RIGHT`). After meaningful UI changes: capture, inspect, identify defects, correct, retest.

Capture: initial launcher startup; Google Photos cinematic background rotation; motion in operation (two frames apart, if enabled); initial carousel focus; carousel after Left/Right; upper-right controls; Inputs selector or fallback; Settings and Wi-Fi behavior; return from an app; HOME behavior; any defect.

Check for: wrong scaling/density, cropped cards/labels, weak focus indicators, carousel misalignment, image distortion, stutter, overlapping controls, black frames or stale images, focus corruption after app transitions.

Some inputs/streaming apps return empty or stale screenshots due to firmware capture restrictions; follow the repo troubleshooting docs. Do not classify a defect or success from a single ambiguous screenshot; investigate focus, resumed activity, timing, and logs. Injected keys do not prove physical remote buttons work.

*Field notes — reading screenshots:*
- `tools/tv.py --screenshot shots/01-after.png DPAD_RIGHT` presses the key, then captures on the TV and pulls the PNG; leave the keys off to capture what is already on screen.
- Live TV, HDMI, and some streaming apps (Netflix) give empty screenshots (0 bytes). That is the TV, not a broken debloat, and `--screenshot` exits 1 with that message. Check `dumpsys activity activities | grep topResumedActivity` instead (`docs/TROUBLESHOOTING.md`).
- If screenshots keep showing an app that was already left, byte for byte, `screencap` is returning its last frame: confirm `mCurrentFocus` in `dumpsys window`, force-stop that app, and capture again.
- If the screen looks enlarged and clipped after an install or reboot, check display overrides before editing the carousel: `wm size`, `wm density`, and `settings get secure display_density_forced`. An override left from an old resolution experiment, or a density that comes back wrong on some MediaTek Sony firmware, is not a launcher bug. An empty `display_density_forced` is a normal default, not damage. Read all three values first, and put them back if undoing (`docs/TROUBLESHOOTING.md`).

**G.2 Performance targets** (targets, not guarantees; measure and record method and results; if a metric cannot be measured, mark NOT TESTED; never invent numbers):

| Metric | Target | How to measure |
|---|---|---|
| APK size | ≤ 10 MB | `ls -l` |
| Cold start to first frame | ≤ 2 s | `am start -W` |
| Key press → visible focus change | ≤ 100 ms (p95) | `dumpsys gfxinfo`, screen/frame timing |
| Janky frames while navigating | ≤ 5% | `dumpsys gfxinfo` |
| Memory (PSS) with background rotation | ≤ 150 MB (justify if exceeded) | `dumpsys meminfo <pkg>` |
| Idle CPU, rotation/motion active | avg ≤ 5% (one core) | `top`/`dumpsys cpuinfo` |
| Idle CPU, launcher not visible | ≈ 0% (no animations/timers) | `dumpsys cpuinfo` |
| Decoded background bitmap | ≤ screen resolution | code/log inspection |

If motion causes a miss, reduce or disable it, then re-measure. Assume the TV has about 1 GB of RAM until measured, and keep the launcher light: no blur, no video, no heavy network polling. Do not claim an improvement without comparative evidence.

**G.3 Test matrix.** Statuses: PASS, FAIL, BLOCKED, NOT TESTED. Keep it in `docs/TEST_REPORT.md` with columns *Test, Expected, Actual, Status, Evidence*. Never mark PASS because the code exists.

| # | Test | Expected result |
|---|---|---|
| 1 | Application launch | Opens without persistent crashes |
| 2 | Google Photos cinematic background | Photographs render correctly without distortion |
| 3 | Automatic rotation (10s) | Background changes automatically every ~10 seconds randomly without immediate consecutive duplicates |
| 4 | Crossfade transitions | Smooth, subtle crossfade transitions (~600ms) without black frames or freezing |
| 5 | Missing/invalid image/network failure | Safe fallback appears or last valid background is retained, no crash |
| 6 | Selection/cache persistence | Background configuration survives launcher restart |
| 7 | Carousel | Correct scale, spacing, focus, labels; works with 1, 3, and many apps |
| 8 | D-pad Left/Right | Deterministic selection; stops at ends |
| 9 | Center/Enter | Focused app launches |
| 10 | Back | Consistent, never exits launcher |
| 11 | Up/Down | Controls and carousel reachable; all three controls reachable |
| 12 | Inputs | Verified action or clear fallback |
| 13 | Wi-Fi | Accurate information or clear fallback |
| 14 | Settings | Verified activity or clear fallback |
| 15 | Return from application | Launcher resumes with sensible focus |
| 16 | HOME selection | Intended launcher receives HOME |
| 17 | Reboot persistence | HOME behavior verified after reboot |
| 18 | Original launcher | Recovery remains available |
| 19 | Performance | Targets in G.2 met or deviations recorded |
| 20 | Repeated launch/resume | No persistent crashes or focus corruption (≥ 10 cycles) |
| 21 | Key repeat / long press | No item skipping beyond limits |
| 22 | Recovery | Restoration steps verified to the extent possible |

Document blockers and the evidence needed to resolve them.

## Annex H — Optional safe debloating (Phase 7) — only if the user opted in at intake
Debloating is independent of launcher development and starts only after the launcher is stable and verified.

## Annex I — ADB connectivity and recovery (all phases)
Inspect the existing connection before installation or system changes. Reuse an authorized connection; identify the actual target and method.

## Annex J — Failure handling (all phases)
Prioritize restoring a stable TV over preserving a cosmetic change.

## Annex K — Deliverables, definition of done, final report (Phase 8)
**K.1 Deliverables:** Cinematic Google Photos background launcher, rotation system, scripts, and full documentation.
**K.2 Definition of done:** All applicable items verified including 10-second random photo rotation and crossfade transitions.
**K.3 Final report:** (In Spanish per guidelines).

---

## Annex L — What changed from the original prompt

| Original (Apple TV style) | This prompt |
|---|---|
| Pitch-black background, dock band, grid of apps below, glass highlight, ambient glow | Image background with subtle motion, open center, bottom 3D-style carousel (Annex C). Do **not** build the grid |
| Single custom background image requirement | Replaced with the **Google Photos cinematic background system** (randomized rotation every 10 seconds with smooth crossfade transitions). |
| Dock slots (4–7) via `columns` and `dock_apps` | Carousel app count and order, reusing the same repo keys if compatible (D.2) |
| Pill buttons top right (Inputs, Wi-Fi, Settings) | Same three controls, kept (C.5) |
| Greeting, large thin clock, date | Compact clock/date, optional greeting (C.4) |
| Debloat is a main goal | Optional, separate, opt-in (Annex H) |
| Written for a computer (macOS notes) | Written for Termux; computer-only notes are marked |
| Disable the stock launcher as part of the flow | Only after the custom launcher is verified and recoverable, and never on CVTE boards unless documented (E.3) |
| Restore script, build-and-install script, README | Kept (K.1) |
| "Everything is reversible" | Reversibility must be demonstrated, not assumed; limits documented (Annex B) |

The field notes in Annexes A–I are the original's firmware-specific knowledge. Do not delete them in future edits without evidence that they no longer apply.

---

## FINAL PRIORITY

Deliver a polished, personalized, cinematic launcher that preserves the repository's useful implementation, firmware-specific knowledge, recovery mechanisms, and ADB safety. Inspect before editing. Back up before risky changes. Implement incrementally. Test on the real television. Keep debloating separate. Verify every important result. Document every blocker honestly. Never invent results or claim verification that has not occurred.