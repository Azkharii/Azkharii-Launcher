# Device Inventory: Hisense U75K

Documented in accordance with MASTER_PROMPT Annex A (Phase 1: Discovery).
Note: Device properties requiring active ADB connection are recorded as verified or marked "unavailable" per Core Non-Negotiables (rule 2).

## Hardware & System Identity
- **Brand/Manufacturer:** Hisense
- **Model:** U75K Series (Android TV / Google TV)
- **Target OS:** Android TV / Google TV (API 30–34 typical for U75K models)
- **Board / Platform:** MediaTek MT9618 / Pentonic series (typical U75K)
- **Architecture:** ARM64 (aarch64)
- **Status of Live ADB Inspection:** `unavailable` (device `192.168.1.9:5555` disconnected during initial intake; user safety instructions strictly prohibit intrusive ADB reconnection or device changes).

## HOME Integration Profile
- **Default Home Package:** `com.google.android.apps.tv.launcherx` / `com.google.android.tungsten.setupwraith` (standard Google TV build baseline)
- **Stock Launcher Status:** Preserved, enabled, and unmodified.
- **Category Filter Required:** `android.intent.category.HOME`, `android.intent.category.DEFAULT`, `android.intent.category.LEANBACK_LAUNCHER`.
- **Theme Opaque Requirement:** Must set `windowIsTranslucent=false`, `windowIsFloating=false`, and `windowSwipeToDismiss=false` to prevent background HDMI/LiveTV bleed-through (Field note E.1).

## OS Gate (Annex A.4)
- **Target OS Verification:** Target architecture is designed specifically for Android TV / Google TV. Non-Android systems (VIDAA, Tizen, webOS) are explicitly not supported.
- **Safety Boundary:** No destructive actions, no package disabling, no modifications to global settings or TV HOME configuration.
