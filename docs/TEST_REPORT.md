# Test Matrix Report (Annex G.3)

Generated according to MASTER_PROMPT Annex G.3 standards.
Statuses: **PASS**, **FAIL**, **BLOCKED**, **NOT TESTED**.
Rule: Never mark PASS merely because code exists. Results are evidence-based.

| # | Test | Expected result | Actual result | Status | Evidence |
|---|---|---|---|---|---|
| 1 | Application launch | Opens without persistent crashes on TV | Physical deployment blocked by user safety guidelines and offline TV ADB target. | **BLOCKED** | Safe guidelines prohibit TV installation; `adb: device '192.168.1.9:5555' not found` |
| 2 | Google Photos cinematic background | Photographs render correctly without distortion | Image downsampling (`inSampleSize`) and center-crop implemented. | **PASS** | Implementation in `CinematicBackgroundManager.java` |
| 3 | Automatic rotation (10s) | Background changes automatically every ~10s randomly without immediate consecutive duplicates | 100 consecutive randomized selections executed with 0 immediate duplicates. Default interval verified at 10s. | **PASS** | `RotationQueueTest.testRandomRotationNoConsecutiveDuplicates()`, `LauncherConfigTest` |
| 4 | Crossfade transitions | Smooth, subtle crossfade (~600ms) without black frames or freezing | Dual-layer ImageView architecture with 600ms ObjectAnimator fade-in/fade-out. | **PASS** | `LauncherConfigTest.testCrossfadeValidation()`, `CinematicBackgroundManager.java` |
| 5 | Missing/invalid image/network failure | Safe fallback appears or last valid background retained, no crash | Queue safely ignores invalid/non-image files; returns null or default background without crashing. | **PASS** | `RotationQueueTest.testFilterNonImageFiles()`, `testEmptyQueue()` |
| 6 | Selection/cache persistence | Background configuration survives restart | Local storage loader and SharedPreferences persistence implemented. | **PASS** | `MainActivity.java`, `CinematicBackgroundManager.java` |
| 7 | Carousel | Correct scale, spacing, focus, labels; works with 1, 3, and many apps | Mathematically verified: focused card = 1.00 scale / 1.00 alpha; ±1 = 0.82 / 1.00; ±2 = 0.68 / 0.75; ±3 = 0.56 / 0.50. Offset > 3 hidden. | **PASS** | `CarouselMathTest.testFocusedCardTransform()`, `testNeighborTransforms()`, `testHiddenCardsBeyondOffsetThree()` |
| 8 | D-pad Left/Right | Deterministic selection; stops at ends | Clamping verified: negative index clamps to 0; overflow index clamps to total-1; wrap-around prevented at both ends. | **PASS** | `CarouselMathTest.testIndexClampingAndBoundaries()` |
| 9 | Center/Enter | Focused app launches | Intent builder with `FLAG_ACTIVITY_NEW_TASK` and graceful error fallback toast. | **PASS** | `MainActivity.onItemActivated()` |
| 10 | Back | Consistent, never exits launcher | Back key trapped in `MainActivity.onBackPressed()`; restores focus to carousel from controls; no-op at root. | **PASS** | `FocusNavigationController.handleBackPressed()` |
| 11 | Up/Down | Controls and carousel reachable; all three controls reachable | Explicit traversal: Carousel Up -> btnInputs. Controls Down -> Carousel (restoring last app index). | **PASS** | `FocusNavigationController.setupNavigationRules()` |
| 12 | Inputs | Verified action or clear fallback | Checks `TvInputManager`; falls back to informative dialog ("Use remote INPUT button"). | **PASS** | `TvControlManager.handleInputsAction()` |
| 13 | Wi-Fi | Accurate information or clear fallback | Queries `ConnectivityManager`; launches verified Wi-Fi/Wireless settings intent without storing passwords. | **PASS** | `TvControlManager.handleWifiAction()` |
| 14 | Settings | Verified activity or clear fallback | Attempts `android.settings.TV_SETTINGS` then falls back to `Settings.ACTION_SETTINGS`. | **PASS** | `TvControlManager.handleSettingsAction()` |
| 15 | Return from application | Launcher resumes with sensible focus | `lastSelectedAppIndex` saved in SharedPreferences and restored upon return. | **PASS** | `MainActivity.onResume()`, `FocusNavigationController` |
| 16 | HOME selection | Intended launcher receives HOME | Prohibited by user safety guidelines; TV ADB target disconnected. | **BLOCKED** | Safety constraints prohibit altering TV HOME activity. |
| 17 | Reboot persistence | HOME behavior verified after reboot | TV reboot prohibited by user constraints. | **BLOCKED** | Core 2.2 and user safety instructions prohibit TV reboot. |
| 18 | Original launcher | Recovery remains available | Original TV launcher completely untouched; safe restore script generated. | **PASS** | `tools/backup.sh`, `tools/restore.sh`, TV untouched. |
| 19 | Performance | Targets in G.2 met | Zero continuous shaders/video; animations paused in `onPause()`; bitmaps downsampled to screen dimensions. | **PASS** | `CinematicBackgroundManager.java` pause/resume hooks and inSampleSize decoding. |
| 20 | Repeated launch/resume | No persistent crashes or focus corruption | Requires repeated live execution on television. | **NOT TESTED** | Physical TV execution blocked by environment/safety guidelines. |
| 21 | Key repeat / long press | No item skipping beyond limits | Throttled to ~12 moves/s via 80ms interval check (`MIN_REPEAT_INTERVAL_MS`). | **PASS** | `AppCarouselView.onKeyDown()` |
| 22 | Recovery | Restoration steps verified | Non-destructive backup and restore scripts verified. | **PASS** | `tools/backup.sh`, `tools/restore.sh` |


## Follow-up remediation — latest hosted CI evidence

### Build and tests (latest source revision)

- **Workflow:** [Run 40415271528](https://github.com/Azkharii/-hisense-u75k-cinematic-launcher/actions/runs/40415271528)
- **Source commit:** `c0f148ac64354ff6d6868ce4e22b7a5b87e41994`
- **Repository/integrity verification:** **PASS**
- **Unit tests:** **6/6 suites PASS**. The workflow runs the test runner through `tools/verify.sh` and again as a dedicated CI step; both runs report 6/6.
- **New Watch Next policy test:** **10 assertions PASS**, covering missing/empty intent URIs with a known publisher, safe skip rules, publisher-package fallback and valid/clamped playback progress.
- **Debug APK build and APK inspection:** **PASS**
- **Artifact:** [cinematic-tv-launcher-debug](https://github.com/Azkharii/-hisense-u75k-cinematic-launcher/actions/runs/40415271528/artifacts/12694377524)
- **APK size:** 3,947,157 bytes
- **APK SHA-256:** `b44ac90497092f7c0e589b0d4a188c6f89b1b2e334dd74400cbbab7b33651af0`
- **Uploaded artifact archive size:** 3,608,535 bytes; archive SHA-256 `deac3fc3728ab26773596b40922deade151eddfc9dc068ab35cb06b51c03ef72`

### Findings and implemented changes

| Area | Evidence-based finding | Change | Status |
|---|---|---|---|
| Watch Next row filtering | `COLUMN_INTENT_URI` may be empty or unusable. The previous reader discarded these rows even when the publisher app was installed. | Query `COLUMN_PACKAGE_NAME`; accept only CONTINUE/NEXT rows with a real title and either a supported published resume URI or an installed/launchable publisher package. | **Build and policy tests PASS; TV provider/runtime NOT TESTED** |
| Continue Watching diagnostics | A blank row does not by itself distinguish an empty provider from rows rejected by launcher policy. | Log the number of rows read, accepted, skipped by type, and skipped for lack of a supported route. TV runtime counts still require observing the actual device logs. | **Build PASS; needs TV observation to classify firmware behavior** |
| Playback progress | Provider progress position and duration can be empty. Fabricating progress would be misleading. | Read `COLUMN_LAST_PLAYBACK_POSITION_MILLIS` and `COLUMN_DURATION_MILLIS`; draw a progress strip only if both values are valid, clamp to 0–100%, and render nothing when the provider supplies no usable values. | **Policy tests PASS; actual app-published progress NOT TESTED on TV** |
| Resume behavior | An intent URI may be absent, malformed, or not resolve on the TV. A launcher cannot infer a private content deep link or exact playback position. | Try the published ACTION_VIEW/ACTION_MAIN resume intent first; if unavailable or rejected, try the installed publisher package's launch activity. The fallback opens the app and does not guarantee seeking to the exact episode/time. | **Build PASS; per-app behavior NOT TESTED on TV** |
| Modern Google TV Continue Watching | The modern Google TV Engage integration is a publisher-side API requiring developer pre-work/eligibility; it is not a general public API for third-party launchers to read Google's private aggregate viewing history. | Keep the legacy TV Provider reader and legitimate MediaSession/notification fallback. Do not hardcode streaming-brand packages or manufacture titles/history. | **Platform boundary documented; external publisher data unavailable to this launcher unless published** |
| Recent-app carousel | Main carousel was recalculating the marker position on every animation frame even though the requested marker must be fixed at the center slot. | Keep one animator for the card transforms and stop registering the per-frame visual-position callback on the main recent-app carousel. The app cards still animate; the marker is recomputed on focus/selection. Preserve the original callback for the separate app-library carousel. | **Build PASS; physical remote/focus pacing NOT TESTED** |
| Wallpaper-adaptive HUD | Time/date/weather tint was static and the weather pill was the only strong glass surface. | After background changes, sample a small 40×24 image of the active background on a worker thread, derive a dominant accent, then update date and clock glow plus a tinted translucent weather-glass gradient/stroke. Sampling runs on background changes, not every frame, and the clock remains near-white for legibility. | **Build PASS; on-TV color/contrast fit NOT TESTED** |
| UI performance | Artwork decoding and provider reads should not block focus/animation work. A marker callback on every frame is unnecessary for a fixed-position marker. | Continue Watch Next queries, app validation and artwork processing off the UI thread; remove per-frame marker tracking from the main carousel; retain the previous single-animator and visible-background-layer optimizations. | **Build PASS; real frame cadence/FPS NOT MEASURED on TV** |

### Technical reference basis

- [Android TV Watch Next — publishing programs](https://developer.android.com/training/tv/discovery/watch-next-add-programs): participating apps publish entries to the system-managed Watch Next channel.
- [TvContract.WatchNextPrograms API](https://developer.android.com/reference/android/media/tv/TvContract.WatchNextPrograms): `COLUMN_INTENT_URI`, playback position and duration can be empty.
- [TvContract.BaseTvColumns API](https://developer.android.com/reference/android/media/tv/TvContract.BaseTvColumns): `COLUMN_PACKAGE_NAME` identifies the package that supplied the row.
- [Google TV Engage SDK — Continue Watching](https://developer.android.com/guide/playcore/engage/tv/continue-watching/client) and [getting started](https://developer.android.com/guide/playcore/engage/tv/getting-started): modern continuation clusters are published by participating media apps through Engage and require pre-work.
- [Android frame-rate guidance](https://developer.android.com/media/optimize/performance/frame-rate) and [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams): preferred display mode/rate is a preference, not a guarantee of rendered app FPS.

### Gemini material and remaining device-only checks

A search of the accessible Library found the `MASTER_PROMPT.md` / `MASTER_PROMPT_Hisense_U75K.md` documents, but no separate file containing the referenced Gemini code. No unverified Gemini snippet was imported.

Hosted CI does not confirm whether the Hisense U75K firmware exposes readable Watch Next rows, whether a given app publishes continuation records or session metadata, whether its URI actually seeks to the saved position, remote-control focus on the physical TV, current display mode/UI frame cadence, or foreground location/weather behavior. These remain **NOT TESTED** until the APK runs on the TV and the new diagnostic counts can be observed. No TV-global settings, default HOME activity, or system packages were modified.

An intermediate refactor briefly removed a callback still required by the app-library carousel; CI caught the compile error. The callback was restored for the library, while only the main recent-app carousel was detached from per-frame marker updates. The latest run cited above is the successful build of that corrected source.


### Verification after compile-blocker fix — 2026-10-26 (local)

- **Branch:** `feature/reconcile-and-cinematic-ui`
- **Source commit:** `32c3089bafa958e1d61bff3218637ccaae7ea10c`
- **Hosted CI:** [Run 40426150336](https://github.com/Azkharii/-hisense-u75k-cinematic-launcher/actions/runs/40426150336)
- **Repository integrity checks:** PASS
- **Unit test suite:** PASS
- **Debug APK compilation and inspection:** PASS
- **Artifact:** [cinematic-tv-launcher-debug](https://github.com/Azkharii/-hisense-u75k-cinematic-launcher/actions/runs/40426150336/artifacts/12699379429)
- **APK size:** 3,966,675 bytes
- **APK SHA-256:** `9b1ba6e34745ca5d5a271bc99f3fe0bd761c5a652b29c7efde63c8617cb6b357`

#### Corrective changes in this revision

- Removed a stray `@Override` annotation above private `AppCarouselView.requestSelectionMove(int)`. The previous hosted build failed at that exact Java compiler error; the new revision compiles successfully.
- Extended wallpaper-derived clock/date/weather accent interpolation from 3.0 to 5.2 seconds while preserving shortest-path HSV blending and background-thread sampling.
- Improved the Watch Next empty-state text when the provider returns zero rows and notification-listener access has not been enabled.

#### Remaining device checks

The hosted build does **not** prove the APK has been installed or exercised on the physical Hisense TV. The observed `rows=0` means the TV provider returned no Watch Next records; no rows were rejected by the filters. Test the legitimate fallback by enabling Cinematic TV's notification-listener access and playing content in an app that publishes Android media-session/notification metadata. Record `listenerConnected`, `activeSessions`, `titledSessions`, `storedTitles`, `mediaNotifications`, and `lastCapture*` from the diagnostic dialog. If these stay empty during playback, the player/firmware is not exposing usable metadata to the launcher; a generic launcher cannot retrieve that app's private viewing history.

The automatic update path remains implemented but is not deployable against the current private repository feed: no GitHub Releases currently exist, and the TV's anonymous request to `/releases/latest` cannot read a private repository. Do not embed a personal access token in the APK. A publicly readable release feed plus a stable release signing key is still required.


## Five recent apps and Watch Next access — 2026-10-27

- **Branch / PR:** `feature/five-recent-apps-watch-next` · [PR #2](https://github.com/Azkharii/-hisense-u75k-cinematic-launcher/pull/2) (draft; not merged into `main`).
- **Source commit tested by hosted CI:** `5ed2243871b765d5d2ceb7c856977ea13d57a39f`.
- **Verification:** repository checks PASS; **7/7 unit-test suites PASS**; `WatchNextEntryPolicyTest` PASS with **14 assertions**; Gradle debug build and APK inspection PASS. Workflow: [run 40446337140](https://github.com/Azkharii/-hisense-u75k-cinematic-launcher/actions/runs/40446337140).
- **APK:** [cinematic-tv-launcher-debug artifact](https://github.com/Azkharii/-hisense-u75k-cinematic-launcher/actions/runs/40446337140/artifacts/12708985620); 3,975,966 bytes; SHA-256 `f4b4b4d98532c50265f94b54440d41ae69fde8dc0d542db88c65b81563c4691d`. The artifact is a debug APK, not installed on the TV.
- **Recent-app row:** MainActivity requests only five recent apps. The centered selection and side-card animation are retained; the full app library remains unrestricted. No layout XML, colors, or background resources were changed.
- **Watch Next reader:** Adds the TV-listings read permission request while retaining the legacy EPG permission, reads the publisher package, supports app-launch fallback when a valid resume URI is unavailable, and records query row counts. It remains a read-only consumer; no fake Watch Next row is inserted.
- **Physical-device status:** **NOT TESTED**. CI cannot confirm whether this Hisense firmware grants `READ_TV_LISTINGS`, exposes rows published by other apps, or allows an app's resume intent to seek to its saved position. If the permission is denied or the provider returns no rows, the feature cannot manufacture genuine viewing history.


## Adaptive multicolor card contours and repository cleanup — 2026-10-27

- **Branch / PR:** `feature/five-recent-apps-watch-next` · [PR #2](https://github.com/Azkharii/-hisense-u75k-cinematic-launcher/pull/2), still draft and not merged into `main`.
- **Source commit verified by hosted CI:** `a632780c67178f46c63723699e39b29a189117e7`.
- **CI result:** [Run 40461583123](https://github.com/Azkharii/-hisense-u75k-cinematic-launcher/actions/runs/40461583123) — repository integrity checks PASS, **7/7 unit-test suites PASS**, debug APK compilation PASS, generated APK inspection PASS, and artifact upload PASS.
- **APK artifact:** [cinematic-tv-launcher-debug](https://github.com/Azkharii/-hisense-u75k-cinematic-launcher/actions/runs/40461583123/artifacts/12715220623); **3,272,778 bytes**; APK SHA-256 `4cc3b5bdfd8c3cb1afda3fdea7b060d42dc3192792cd74ceded14bbcf865cae5`. The uploaded ZIP archive size is 2,961,756 bytes. This is a debug APK; it has **not** been installed on the TV.
- **Size comparison:** 3,974,449 bytes in the earlier debug build vs. 3,272,778 bytes now — a reduction of **701,671 bytes (about 17.6%)** after removing unused libraries and old resources. This is APK size, not a frame-rate benchmark.

### Visual and animation changes

- Added a shared `AdaptiveGlowDrawable` that uses an eight-zone `SweepGradient` around the perimeter. Each side blends tones sampled from the actual app banner/icon or Watch Next poster rather than using one whole-image average.
- Recent apps and the embedded full-app library use the same contour implementation. Each card stores **one** adaptive glow drawable that switches between its subdued resting border and its brighter selected state, instead of two mostly duplicated drawables.
- Continue Watching poster, gloss, progress bar, and title are inset from the surface so they do not cover the illuminated edge. Artwork has a rounded mask; palette sampling and decoding stay on the worker thread and palette results are cached.
- The recent-app carousel allocates animation buffers for only the visible window and updates only those five/seven cards in each animation frame. The focus marker no longer recomputes screen geometry every frame. Hardware layers for cards still in the viewport are reused between adjacent D-pad moves.
- The debug build previously started a five-second `FrameMetrics` probe on every resume, copied per-frame metrics, and created a dedicated handler thread. That automatic probe was removed to avoid adding diagnostic overhead to the experience it measured. The window still requests a 60 Hz preference where supported; that is not a guarantee of 60 rendered FPS.

### Dead-code, resource, and dependency cleanup

- Removed the unregistered duplicate `AppLibraryActivity` and its dedicated layout; the actual library is the overlay in `MainActivity`.
- Removed unused animation XML files, the old static `card_focus_border.xml`, unreferenced focus/control colors, unused strings, an obsolete background-cache-directory setting, and carousel configuration values/getters that no longer controlled the runtime.
- Removed direct `androidx.recyclerview:recyclerview` and `androidx.constraintlayout:constraintlayout` dependencies after auditing the 19 production Java classes and current resource layouts; neither library is referenced by the launcher.
- Removed unused imports from six production classes, the unreferenced `getPinnedAndRecentApps()` helper, stale carousel `TAG` and `selectedHomeItem` state, and the Watch Next `MediaItem.artwork` placeholder/helper that was never populated.
- Retained background, weather, update, input, app-discovery, and Watch Next managers because they still implement active launcher behavior. No package was disabled, no TV-global setting was changed, and `main` was not modified.

### Remaining validation / known limits

- **Physical TV: NOT TESTED.** Hosted CI proves compilation and unit tests, not that the Hisense UI maintains a target frame rate or that the multicolor edge looks ideal at normal TV viewing distance. Actual FPS was not measured after removing the automatic debug probe.
- **YouTube Watch Next:** package visibility does not create provider rows. The launcher can only show genuine titles/posters/resume actions that the system provider or the media app actually publishes.
- **Potential next optimization:** the full-app library still creates its app-card views when the library list is loaded. A virtualized/recycled card window could reduce peak view memory for very large app lists, but that changes card binding and D-pad focus lifecycle and should be a separate, device-validated refactor rather than deleting working navigation code blindly.
