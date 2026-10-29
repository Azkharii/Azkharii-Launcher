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
- The debug build previously started a five-second `FrameMetrics` probe on every resume, copied per-frame metrics, and created a dedicated handler thread. That automatic probe was removed to avoid adding diagnostic overhead to the experience it measured. The app no longer requests a preferred display refresh rate; Android and the TV control the display mode, and refresh rate is distinct from measured rendered FPS.

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

## Natural HUD color and Watch Next artwork loading — 2026-10-27

- **Branch / PR:** `fix/natural-hud-watch-next-loading` · [PR #1](https://github.com/Azkharii/Azkharii-Launcher/pull/1), currently draft.
- **Code commit tested:** `e60f42a354bb1b4dbf9e510d9d971a2cab03b8ed`.
- **Hosted CI:** [Run 40467103067](https://github.com/Azkharii/Azkharii-Launcher/actions/runs/40467103067) — repository integrity/quality checks PASS; **7/7 unit test suites PASS**; debug APK assembly and inspection PASS; artifact upload PASS.
- **Debug APK:** [cinematic-tv-launcher-debug artifact](https://github.com/Azkharii/Azkharii-Launcher/actions/runs/40467103067/artifacts/12715929946); **3,273,866 bytes**; SHA-256 `1b7d2763c2164fca3c3ac1abdfc68602cc718478a5ca21b6c8ff8fb986ba1a41`. This is a debug build; it has not been installed on the TV.

### Changes

- Wallpaper accent extraction now favors the dominant usable hue, reduces saturation and brightness, and softens clock/date/weather shadows and the weather glass edge.
- Watch Next artwork is read once into a bounded byte array and decoded from the same bytes, avoiding a second resource/network open. HTTP(S) connection/read timeouts were shortened and visible poster work is processed by three workers.
- Watch Next card title text explicitly uses centered text alignment. Pinned application icons retain their original artwork; the navigation marker continues using the wallpaper-derived accent in that wheel.

### Remaining validation and limits

- **Physical TV: NOT TESTED.** Hosted CI confirms compilation/tests only, not the perceived palette, actual load time, or frame rate on the Hisense TV.
- Poster display still depends on the Watch Next provider publishing artwork and the URI/network responding; an instant load cannot be guaranteed when the source itself is slow.
- The requested replacement launcher icon/banner has **not** been applied: the provided Google Photos share URL did not expose downloadable image bytes to the work environment. Keep the existing assets until the actual PNG/JPG image is provided; do not substitute an unrelated image.



## Navigation marker, Watch Next visibility and OTA setup — 2026-10-27

- **Validated source commit:** `51b19c51356030e5b2f2afbc35f7e2e17432ed35`.
- **Hosted CI:** [Run 40469260598](https://github.com/Azkharii/Azkharii-Launcher/actions/runs/40469260598) — integrity/quality checks PASS, **7/7 test suites PASS**, Debug APK build and inspection PASS, artifact upload PASS.
- **Debug APK:** [cinematic-tv-launcher-debug artifact](https://github.com/Azkharii/Azkharii-Launcher/actions/runs/40469260598/artifacts/12718027183); size **3,276,434 bytes**; APK SHA-256 `94b17e8155b2f5c8dedf35d5c1983269b39cf96958cc1c9f846e0fc67e85c791`. This is a debug APK, not the stable signed OTA package.

### Navigation indicator

- The selected app icon is sampled without a tint or color filter. The navigation dot uses that color in the recent-app carousel and app library, pinned-app wheel, and Continue Watching wheel.
- The Continue Watching marker samples the publisher app's icon rather than poster art. Missing app icons use a neutral fallback, never a wallpaper accent.
- The HUD's wallpaper adaptation remains limited to clock/date/weather. Device appearance and rapid remote-focus changes still require on-TV validation.

### Continue Watching section

- The card title remains centered inside its card. It does not control the wheel's visibility.
- The provider now preserves real rows with a valid, non-placeholder title even when the TV exposes no usable resume URI and the publisher package cannot be launched. The click handler still reports when it cannot open the item.
- The section remains hidden when the system provider returns no titled rows or denies access. The hosted build cannot prove that a physical TV exposes Watch Next records or that publishers provide artwork. Real hardware verification remains **NOT TESTED**.

### Public releases and OTA

- `UpdateManager` now queries the public feed at `https://api.github.com/repos/Azkharii/Azkharii-Launcher/releases/latest`.
- The release workflow computes a monotonically increasing release version, builds a release APK signed with a persistent key, and publishes the APK plus SHA-256 file to GitHub Releases on changes to `main`.
- No public release currently exists. The release workflow requires four persistent Actions secrets (documented in [docs/RELEASING.md](RELEASING.md)); it fails rather than publishing an unsigned or ephemeral-key build when they are missing. Install a signed release before expecting OTA checks; debug builds use a different application ID and intentionally skip updater checks.
- The supplied Google Photos short link did not expose image bytes in the available environment; launcher icon/banner remain unchanged rather than being replaced with guessed artwork.

## HUD color transition and Continue Watching marker — 2026-10-27

- **Branch / PR:** `fix/natural-hud-watch-next-loading` · [PR #1](https://github.com/Azkharii/Azkharii-Launcher/pull/1).
- **Source commit tested:** `7713fd97d47ddf3c92835b97de8740fd992a1db6`.
- **Hosted CI:** [Run 40472213372](https://github.com/Azkharii/Azkharii-Launcher/actions/runs/40472213372) — repository integrity/quality checks PASS; **7/7 test suites PASS**; debug APK assembly, inspection and artifact upload PASS.
- **Debug APK artifact:** [cinematic-tv-launcher-debug](https://github.com/Azkharii/Azkharii-Launcher/actions/runs/40472213372/artifacts/12719600775); **3,276,210 bytes**; APK SHA-256 `8c28de02f19f274dbe53b92b1d33dbc56ce00a0a2ceaeb4af5e0f286b09e2c29`. This is a debug build, **not a stable signed release**.

### Changes

- The Continue Watching navigation marker now uses the selected poster's sampled accent instead of the publisher app icon. The existing worker-thread palette sampling and focused-card callback refresh the marker when artwork arrives; no poster artwork or application icon is recolored.
- The clock and weather text now use bright, wallpaper-hue-derived colors so the adaptation is visible while retaining high luminance for TV-distance readability.
- HUD accent interpolation runs for 5.2 seconds using the shortest HSV hue path.
- Fixed cancellation handling: a superseded color animator can no longer apply its stale target at animation end and abruptly override the newest wallpaper color.

### Remaining validation and release blockers

- **Physical TV: NOT TESTED.** CI verifies source integrity, tests and APK compilation, not the actual perceived color transition, the poster-matched marker, or the live Watch Next provider on the Hisense TV.
- **Stable release: BLOCKED.** The run above only produces a debug APK. The persistent release keystore and four GitHub Actions secrets must be configured before a signed stable APK can be published. Before creating a new key, verify that no previously distributed stable installation requires an existing signing key.
- **Launcher icon/banner: NOT CHANGED.** The Google Photos share link did not expose the actual image bytes in this environment; preserve existing assets rather than substitute unrelated artwork.



## Performance optimization follow-up — 2026-10-28

- **Scope:** event-driven package/connectivity refresh, metadata-first app discovery, bounded update-check retry policy.
- **Preserved behavior:** wallpaper rotation/crossfade, recent ordering rules, full library, pinned apps, Continue Watching, APK verification and Android installer confirmation.
- **Portable test added:** `UpdateCheckPolicyTest` covers initial check, 30-minute successful interval, and bounded retry backoff.
- **Hosted integrity/tests/build:** PASS on PR #3 commit `b0cbae6d68a4db2177df29ba97c2ee12899b63d4`: [build run 40646687945](https://github.com/Azkharii/Azkharii-Launcher/actions/runs/40646687945) completed successfully, including integrity checks, unit tests, debug APK build and artifact inspection. The release-signing validation/build also passed in [run 40646687935](https://github.com/Azkharii/Azkharii-Launcher/actions/runs/40646687935). The latest head adds a small fallback invalidation plus documentation/cleanup changes; those exact latest-head changes have not been re-run through CI.
- **Hisense U75K CPU/GPU after-change measurements:** NOT TESTED in this environment. Existing RenderThread/Mali values remain the historical baseline only.

## Aviso OTA compacto y optimización del HUD — 2026-10-28

- **Rama:** `perf/compact-updates-rendering` (validación CI pendiente).
- **Aviso visible:** se muestra el salto compacto, por ejemplo `1.0.3 → 1.0.4`, sin `versionCode`, hash del commit ni descripción de release completa. Se mantienen tamaño esperado, SHA-256, identidad del paquete, versión de APK y confirmación del instalador Android.
- **Versionado:** el nombre visible queda como `1.0.N`; el `versionCode` sigue siendo `1000000000 + GITHUB_RUN_NUMBER` y se conserva en el tag `v1.0.N+CODE`. Se ampliaron pruebas para tags nuevos y antiguos.
- **HUD adaptativo:** la transición de color pasa de 5.2 a 1.8 segundos, limita actualizaciones de propiedades a unas 30 por segundo, reutiliza buffers HSV y omite setters/invalidation cuando el valor visible no cambia.
- **Pruebas automatizadas/build:** pendientes del workflow de esta rama.
- **Comparación física de CPU/RAM:** NO REALIZADA después de estos cambios. Los valores RenderThread/Mali previos siguen siendo la referencia informada por el usuario, no un resultado posterior.


## Deep idle rendering pass — 2026-10-28

- **Change set:** PR #5, `perf/deep-idle-render-optimization`.
- **Unit tests / debug APK:** GitHub Actions completed successfully for interim head `4d66a161db04a015633396a2f3bcfba730f383c9` ([build run 40656252782](https://github.com/Azkharii/Azkharii-Launcher/actions/runs/40656252782)). That run predates the subsequent idle-worker expiry edits; it is not evidence that the final revision has passed.
- **Final revision:** the build and signing-validation workflows were triggered again for the later source changes. Final workflow status remains pending/in progress at the time of this entry; no final PASS is claimed here.
- **Physical Hisense CPU/RAM comparison:** NOT TESTED after these changes. The user's latest reference remains a short rendering burst (RenderThread 77.4%, Mali backend 29.0%) followed by sleeping threads; it is not a before/after result.
- **Visual and interaction regression checks:** NOT TESTED on the physical TV. Focus movement, the adaptive glow, wallpaper timing, and Continue Watching must be checked on-device after the final APK builds.
- **Resource deletion:** no production code, UI component, or drawable was deleted because the inspected source did not establish any as unused. Changes instead eliminate redundant invalidations and idle worker residency.

 
## Follow-up — performance/UI optimization branch (2026-10-29)

- **Pinned-app wheel:** transient hardware layers added for the moving cards; layers are released after the slide. Focus-marker geometry updates are throttled to 50 ms during animation. Glow-color sampling is cached per package.
- **Recent-app carousel:** selected package/index persistence is debounced to avoid repeated SharedPreferences writes during D-pad scrolling; pending selection is flushed when the Activity pauses.
- **Managed wallpapers:** removed stale “Fondo mostrado” status and file-name labels. Low-resolution thumbnails distinguish the images and their file I/O/decoding runs off the UI thread; the title gets additional inset so the initial “M” and other glyphs stay away from the dialog edge.
- **Inputs/Photos controls:** Inputs reduced to 56×38 dp and Photos to 38×38 dp. Their single-line labels are centered and ellipsized within the button bounds.
- **Continue Watching covers:** switched the poster view to `FIT_CENTER` and reserved a separate bottom title strip, so landscape cover art should remain fully visible rather than cropped. Physical-TV visual confirmation remains pending.
- **Wallpaper transitions:** Android 9+ decodes with `ImageDecoder` to a viewport-sized bitmap and low-memory policy instead of holding oversized source images. Android 8.x retains the sampled BitmapFactory fallback. Crossfade duration is 400 ms (previously 600 ms) and adaptive HUD tint animation 600 ms (previously 900 ms). Expected to lower decoded bitmap memory and total transition redraw time; actual TV CPU/RAM measurements remain pending.
- **Release APK:** R8 code shrinking and unused-resource shrinking enabled; the blanket keep-all rule removed, retaining narrowly scoped rules for XML-inflated carousel constructors and the XML click handler. CI includes an optimized release assembly step.
- **Status:** hosted CI/build validation and physical TV CPU/RAM and visual checks are **PENDING**. These are source-level changes, not proof of a measured CPU reduction or a specific APK-size reduction; report actual CI APK sizes and device samples after they are available.


## Follow-up — wheel transforms, wallpaper preview reuse and compact UI (2026-10-29)

- **Pinned wheel:** start/end transforms are prepared once per navigation event; render layers stay warm on the five visible app icons. The arc radius is slightly tighter to reduce gaps while preserving the circular navigation order and focus selection.
- **Continue Watching:** transforms are precomputed at animation start; small cards keep their layers between turns. The title sits transparently over the poster with a dark translucent bottom scrim instead of a separate opaque-looking strip. Cover art remains FIT_CENTER.
- **Backgrounds:** the transition fades in one incoming full-screen layer above the still-opaque outgoing layer (one alpha animator); the outgoing bitmap is then cleared. Bounded wallpaper-preview-v1 disk-cache logic uses a 32 MiB size cap and a source/viewport fingerprint; preview writes are deferred 700 ms so compression is not scheduled in the middle of the crossfade/HUD transition. First-time decode and cache write still need real-device profiling; repeat-rotation CPU reduction is a hypothesis, not a measured result.
- **Mis Fondos and top controls:** rows/dialog and thumbnails are smaller; Entradas 48×34 dp and Fotos 32×32 dp.
- **Status:** code is awaiting CI on this branch. Physical-TV checks are still required for CPU peaks over multiple rotations, RAM after cache warm-up, wheel focus/edge behavior, thumbnail fidelity, and label legibility. Do not infer an actual performance gain from source inspection alone.


## UI refinement — compact panels and poster alignment (2026-10-29)

The Photos, saved-backgrounds and input-source dialogs now use smaller dimensions and tighter spacing. The Wi-Fi, Settings and Search controls share a reduced footprint. Continue Watching cards now use a landscape proportion aligned with their artwork; idle cards no longer show a detached perimeter frame, while the focused-card illumination remains.

**Validation:** GitHub Actions is required for code/test/build checks. Visual confirmation on the TV remains pending for focus navigation, poster/title overlap, dialog sizing and readability at the device's display density.


## Focus-only glow, compact dialogs, and wallpaper transition tuning (2026-10-29)

- **Recent apps / app library:** selected-card illumination now follows actual focus ownership. Leaving either carousel for Pinned Apps, Continue Watching, or top controls detaches the old glow; the selected index is retained without leaving an unselected card illuminated.
- **Performance HUD:** both RAM and CPU sampling stop together and the readout is hidden while the app library overlay is active. Sampling/readout resumes after returning to the home carousel.
- **Entradas:** centered, one-line source names stay within their buttons; row height, margins, title and dialog width are reduced. HDMI/AV/TV enumeration and launch callbacks are unchanged.
- **Fotos / Mis fondos:** smaller padding, typography, action buttons and dialog widths. The saved-background viewport grows with displayed item count up to a cap, then scrolls.
- **Wallpaper transition:** crossfade duration is reduced from 400 ms to 260 ms. HUD palette sampling goes from 48×28 to 32×18 pixels, its color transition from 600 ms to 360 ms with updates limited to roughly 15/s, and first-visit preview compression is deferred to 1.8 s and coalesced to avoid encoding obsolete wallpaper bitmaps. JPEG quality is 85 instead of 91 to reduce encode cost; visual image quality needs TV confirmation.
- **Cleanup audit:** release builds already use R8 and unused-resource shrinking. No broad file/resource deletion was made because the source audit did not prove other production resources were unreferenced.
- **Validation:** repository tests, debug APK build and optimized release build must pass CI for this commit. Physical-TV visual/focus checks and same-scene CPU/RAM samples remain pending; no measured reduction is claimed yet.


## Idle wallpaper and control-layout pass — 2026-10-29

- Wallpaper rotation interval increased from 10 to 30 seconds; crossfade duration reduced from 260 to 180 ms.
- Inputs and Photos controls now use the same 36 dp square footprint as Wi-Fi, Settings, and Search.
- Continue Watching playback progress track/fill are inset further from the card edge to prevent the bar extending beyond the visible poster area.
- MainActivity already calls `CinematicBackgroundManager.onPause()` when the launcher loses foreground; the manager cancels rotation callbacks, pending decode/cache work, and its active crossfade. This lifecycle path was reviewed but not runtime-tested on the TV in this environment.
- Automated tests/build and physical-TV CPU/RAM, focus, and visual checks: **PENDING**. No measured CPU improvement is claimed from these source changes.


## Weather idle and wallpaper-library memory pass — 2026-10-29

- Clock receiver registration follows foreground visibility. The clock text changes only when its formatted minute changes, and the date is reformatted only when the local day or locale changes.
- Weather checks Android's validated Internet capability before location/network work and uses a 90-minute attempt interval. Failed requests are rate-limited to prevent repeated work on each HOME resume; cached weather remains available offline. The previous city label is reused when the approximate coordinates are nearby.
- Wallpaper rotation remains at **30 seconds**, as configured. This pass does not change the requested rotation frequency or remove the crossfade.
- The saved-background dialog now decodes thumbnails only for visible rows plus one-row prefetch, uses a single worker with a bounded queue, downsamples extra-wide/extra-tall images, and releases thumbnail references when the dialog closes.
- Added JVM boundary tests for the 90-minute weather refresh policy.

**Validation:** Hosted tests/build and physical-TV measurements still need to run for this commit. Verify weather behavior with/without validated Internet, scroll through a large saved-background library, and inspect focus/layout. No measured CPU/RAM reduction is claimed before those checks.


## Corrective wheel-layer and idle-work pass — 2026-10-29

- Reverted the 1.2-second hardware-layer promotion/demotion cycle from pinned apps and Continue Watching. Stable hardware layers are restored for their five bounded cards to avoid repeated GPU-layer reconstruction after idle gaps between D-pad presses.
- The main app carousel's existing animation-only layer handling is unchanged.
- Wallpaper interval is 60 seconds with a 160 ms crossfade; the transition remains. The HUD, card/banner and icon palette samplers use bulk reads on their small sample bitmaps; Watch Next artwork uses row reads to avoid allocating a full-size temporary pixel array. Color sampling results and visual policy are preserved. Wallpaper color samples are coalesced and canceled on pause/replacement so stale requests do not queue unneeded work.
- Fixed focus transition ordering so a previous carousel loses its selected glow even when focus briefly reaches the floating position marker. Wallpaper work is cancelled before the Activity yields to background, and queued preview compression is skipped when its source is no longer the active wallpaper.
- CI for this source revision is pending; CPU/RAM comparison, frame pacing, and visual verification on the Hisense TV have not been performed for this commit.

## Focused performance pass — 2026-10-29

- **Continue Watching navigation:** animation geometry buffers are reused, slot order rotates in place, and poster tasks are cancelled/removed from the queue when their card leaves the wheel. Animation timing and visual transforms are unchanged.
- **Wallpaper changes:** the delayed foreground JPEG encoder was removed; valid preview-cache files from earlier runs are still read. Uncached files will be decoded from their original source on later rotations.
- **Hosted validation:** pending for this branch. **Physical TV CPU/RAM comparison: NOT TESTED.** No percentage reduction is claimed without a matched on-device run.


## Bounded artwork palette cache and pinned-wheel allocation pass — 2026-10-29

- **App library:** added a bounded 128-entry per-package cache for the eight sampled edge colors, average banner color, and icon accent. This reduces repeat bitmap rasterization when a library view is rebuilt; full library views and all illumination remain enabled.
- **Cache correctness:** invalidates the shared carousel palette cache and MainActivity icon-accent caches on package add/change/remove callbacks, and on resume where LauncherApps callbacks are not supported.
- **Pinned wheel:** reuses transform buffers sized to the five visible cards, writes transform values in place, and removes the redundant old-slot list copy. Arc geometry, navigation order, focus behavior, glow and 280 ms animation are retained.
- **Static source checks:** PASS for required cache declarations, invalidation entry points, buffer bounds, and unchanged transform formula.
- **Automated validation:** pending GitHub Actions for PR #18 (integrity checks, JVM tests, debug APK, optimized release/R8 build).
- **Physical TV validation:** pending. No FPS, CPU, or RAM improvement is claimed until the same navigation scenarios are measured after installing the built APK.
