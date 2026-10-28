# Performance audit — 2026-10-28

## Evidence from the Hisense U75K

The user captured repeated `top` samples and `dumpsys cpuinfo` while the launcher was running:

- `com.cinematic.tvlauncher`: approximately 142–151% CPU across repeated samples.
- `RenderThread`: 93.3% in the thread-level snapshot.
- `mali-cmar-backend`: 36.6%; `hwuiTask0` and `hwuiTask1`: 10% each.
- The launcher process showed about 307 MiB RES in the thread snapshot (RES is shared across threads; it must not be summed per thread).
- `com.google.android.katniss:interactor`: around 15% CPU in the corresponding process-level sample.

This pattern points to sustained rendering/composition work, not proof of a package-scanning loop or memory leak. CPU snapshots alone cannot identify the exact drawable/view responsible.

## Confirmed source-level cause candidate

`CinematicBackgroundManager.startSubtleMotion()` creates an infinite `ValueAnimator` (`RESTART`/reverse) that scales a full-screen wallpaper layer continuously for the entire foreground lifetime. The resource default enabled it. This causes continuous full-screen invalidation/composition even when the user is not navigating, and is a high-confidence, low-risk candidate to test first on this low-power TV.

## Change in this branch

- Set `background_motion_enabled` to `false` by default.
- Preserve the existing slow zoom implementation and `background_motion_duration_sec` setting so the effect remains available as an opt-in.
- Keep 10-second wallpaper rotation, 600 ms crossfade, app discovery, recent apps, pinned apps, Continue Watching, wallpaper-aware HUD, controls, and update checking unchanged.

## Static review notes

- App discovery queries launcher activities and loads labels/icons/banners. It is called by `loadApps()`; source review should verify its lifecycle call sites before adding caching, because package install/removal and UsageStats can legitimately change the recent row.
- The main app carousel already allocates animation arrays for the visible window and only animates during D-pad moves. Its adaptive glow drawable builds the `SweepGradient` on bounds changes, not inside `draw()`.
- Continue Watching provider reads and poster downloads/decode are submitted to worker executors; refresh notifications are coalesced.
- Wallpaper bitmap decoding and HUD palette sampling are performed off the main thread.
- The TV evidence does not yet prove whether disabling the continuous zoom is sufficient. Compare CPU after installing a built version with this one setting changed; if RenderThread remains high while idle, capture a short Android GPU/system trace before larger architectural changes.

## Validation status

- **Device baseline:** observed from user-provided `top` and `dumpsys cpuinfo` output.
- **Source change:** committed to the dedicated branch.
- **Build/test:** pending hosted CI.
- **TV before/after comparison:** pending; do not mark as fixed until measured.
- **Stable OTA release:** blocked until the persistent signing key/secrets documented in `docs/RELEASING.md` are configured. Do not publish an APK signed with a temporary key because Android will reject it as an in-place update.
