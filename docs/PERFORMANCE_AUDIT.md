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

`CinematicBackgroundManager.startSubtleMotion()` previously created an infinite full-screen wallpaper scaling animation. It was disabled by default, and this follow-up removes the implementation, configuration fields, resource entries, and obsolete test assertions entirely. The crossfade and 10-second rotation remain.

## Latest source follow-up — 2026-10-29

- The unused wallpaper zoom animation, animator state, configuration fields, and resource entries have been removed completely. Wallpaper rotation and crossfade remain.
- OTA checks are user-initiated through the unframed arrow to the left of Entradas; periodic checks and clock-based retry timers have been removed. Manual checking also works in debug builds to open the release page, but a debug APK is never offered as an in-place stable update.
- The release checker falls back to the release's `.sha256` sidecar when GitHub's APK asset metadata omits `digest`. It does not download/install an update without a valid SHA-256.
- Physical Search/Assist handling tries Android's `ACTION_ASSIST`, then `ACTION_VOICE_COMMAND`; the firmware's actual remote key mapping still needs a real-TV check.
- Imported wallpaper selections are copied to app-private storage on a worker thread. The photo dialog now opens a managed library with current wallpaper, Use, and Delete actions. Deleting the active wallpaper restores a valid default image before the remaining collection is refreshed.
- Main carousel movement now updates only the previous/new visible card ranges and reuses animation buffers. This removes the all-installed-app scan and per-move array allocations from each D-pad step; animation/glow effects remain.
- The previous active zoom-enable bullets and old scheduler-policy tests are superseded by the removal above.

## Static review notes

- App discovery queries launcher activities and loads labels/icons/banners. It is called by `loadApps()`; source review should verify its lifecycle call sites before adding caching, because package install/removal and UsageStats can legitimately change the recent row.
- The main app carousel already allocates animation arrays for the visible window and only animates during D-pad moves. Its adaptive glow drawable builds the `SweepGradient` on bounds changes, not inside `draw()`.
- Continue Watching provider reads and poster downloads/decode are submitted to worker executors; refresh notifications are coalesced.
- Wallpaper bitmap decoding and HUD palette sampling are performed off the main thread.
- Historical note (superseded): earlier builds retained a disabled zoom implementation. The current follow-up removes the animator and its configuration entirely. Validate the current branch with a same-scene TV comparison before attributing a CPU reduction.

## Validation status

- **Device baseline:** observed from user-provided `top` and `dumpsys cpuinfo` output.
- **Source change:** committed to the dedicated branch.
- **Build/test:** pending hosted CI.
- **TV before/after comparison:** pending; do not mark as fixed until measured.
- **Stable OTA release:** blocked until the persistent signing key/secrets documented in `docs/RELEASING.md` are configured. Do not publish an APK signed with a temporary key because Android will reject it as an in-place update.


## Follow-up pass — event-driven updates and on-demand artwork

### Implementation in `perf/event-driven-refresh-and-cpu`

- At that time, the disabled-by-default zoom implementation was retained; the 2026-10-29 follow-up removes it entirely. Wallpaper rotation and crossfade remain unchanged.
- Replace repeated app-intent discovery with cached lightweight metadata. The metadata cache is invalidated when Android's `LauncherApps.Callback` reports installed-package or launchability changes; the callback is registered for the Activity lifetime so changes while another app is foreground are not missed. If callback registration is unavailable, the cache is invalidated on resume as a fallback; UI refresh is debounced and only runs while the launcher is resumed.
- Rank recent apps using the same UsageStats/local-history logic before loading artwork, so the main row materializes artwork only for its visible top five.
- Build the pinned-app wheel from the pinned package set instead of loading artwork for every installed app and then discarding most results.
- Update the Wi-Fi indicator from `ConnectivityManager.NetworkCallback` events while foregrounded, with an immediate state snapshot on resume and unregister-on-pause.
- Reduce release-check interval from six hours to 30 minutes while the launcher is foregrounded. Keep network work off the UI thread, retry failures with bounded exponential backoff (5, 10, 20, then 30 minutes), and retain APK identity/version/SHA-256 validation and Android's confirmation step.
- Add pure-Java tests for update interval and retry scheduling.

### Preservation constraints

- No feature is removed; package metadata still refreshes on resume as a safety fallback.
- Recent-app ordering and alphabetical library ordering use the same ranking rules as before.
- Full artwork is still loaded for the complete library when the library is requested; the main row and pinned wheel load only the artwork they show.
- Wallpaper rotation stays at 10 seconds; Continue Watching remains driven by the existing system-provider observer with lifecycle-safe refresh.

### Validation status for this pass

- Source-level implementation: pending hosted CI.
- Physical Hisense CPU/GPU comparison: pending; previous 93.3% RenderThread and 36.6% Mali figures are baseline evidence only, not a post-change result.
- Visual regression, package install/update notification behavior and network callback behavior: pending confirmation on the TV.

## Seguimiento — aviso OTA compacto y reducción de trabajo de renderizado (2026-10-28)

### Evidencia del Hisense U75K

- La versión instalada reportada fue `1.0.1000000004`.
- En una muestra de reposo de unos 20 segundos, el proceso osciló entre `0%` y `146%` de CPU; la memoria residente (`RES`) varió de `192 MiB` a `251 MiB` y terminó cerca de `208 MiB`.
- En la inspección de hilos, `RenderThread` llegó a `82.5–83%`, `mali-cmar-backend` a `32.5–33.5%` y `hwuiTask0/1` a unos `7.5–8%`. Son capturas puntuales, no promedios completos ni prueba por sí sola de una causa única.

### Cambios de código propuestos en esta rama

- `MainActivity.animateAdaptiveHudAccent()` mantenía una animación del HUD de 5.2 segundos tras el cambio de fondo; el intervalo de rotación del fondo es de 10 segundos. En cada frame se convertían extremos HSV, se asignaban sombras/colores de cristal y se invalidaba el indicador de foco aunque algunos valores no cambiasen.
- La adaptación del color se conserva, pero la transición se reduce a 1.8 segundos y las actualizaciones visibles se limitan a unas 30 por segundo. Los extremos HSV y buffers de color se reutilizan; color de texto, sombras, cristal del tiempo y punto de foco solo se modifican cuando el valor renderizado cambia.
- El diálogo OTA ahora presenta solo la versión instalada → disponible y una frase corta de verificación. El nombre visible pasa a `1.0.N`; el `versionCode` Android permanece creciente y se añade como metadato al tag `v1.0.N+CODE`, de modo que el instalador mantiene sus comparaciones y verificaciones.
- En esa revisión histórica el zoom se conservaba desactivado; después se eliminó por completo. La rotación de fondos, crossfade, carruseles, aplicaciones fijadas, Continuar viendo y actualización segura se conservan.

### Verificación pendiente

- La rama fuente y las pruebas se están preparando para CI; aún no hay resultado de validación final.
- La comparación de CPU/RAM en el televisor después de instalar una versión nueva sigue pendiente. No debe darse por corregido el pico hasta medir la misma escena en reposo después de la actualización.


## Deep idle rendering pass — 2026-10-28

### Latest thread-level sample supplied by the user

- The first snapshot showed `RenderThread` at 77.4%, `mali-cmar-backe` at 29.0%, and the two HWUI workers at 6.4% and 3.2%, while the app's main thread reported 0.0%.
- The next snapshot showed the launcher threads sleeping; later snapshots showed only brief low activity. Process RES moved from about 272 MiB to 264 MiB in the captured interval.
- This is evidence of a short graphics-work burst, not sustained CPU use across all five seconds and not proof of a memory leak. Thread-level RES is repeated for the same process and must not be summed.

### Changes made on `perf/deep-idle-render-optimization`

- In that historical pass the motion default was changed to disabled. The 2026-10-29 follow-up then deleted the motion animator and all related settings.
- Made wallpaper rotation event-driven: do not schedule a timer when no local wallpapers exist, do not force an immediate crossfade on every resume, and do not repeat the timer after displaying a single available photo. Rotation and the 600 ms crossfade remain available when multiple photos exist.
- Shortened the wallpaper-adaptive HUD color transition from 1.8 s to 0.9 s, limited color updates to at most one every 50 ms, and applied visually negligible RGB changes directly rather than starting an animation.
- Reused the adaptive glow's normalized perimeter palette and gradient when the palette has not changed. Repeated assignments of the same alpha or color filter no longer invalidate the drawable.
- Replaced persistent idle worker factories in HUD color sampling, wallpaper decode, weather fetch, OTA checks, and Watch Next data/artwork loading with equivalent executors that expire idle workers after 15 seconds. Watch Next artwork retains its three-way parallel execution; task cancellation and executor shutdown paths remain unchanged.
- Updated the configuration test to assert that continuous wallpaper motion is disabled by default.

### What was deliberately not removed

No UI component, navigation behavior, Watch Next feature, wallpaper crossfade, or APK resource was deleted. The source evidence so far supports eliminating redundant work, but does not prove that any additional drawable, layout, or subsystem is dead code. Deleting such assets without a reference audit would offer little runtime CPU benefit and could cause a visual regression.

### Validation status

- Source edits are committed on the dedicated branch; automated tests, integrity checks, and APK build must pass in GitHub Actions before considering the changes integration-ready.
- A post-change CPU/RAM capture on the Hisense TV is still required. The optimization is not marked as physically verified until the same idle scene is measured after installing the resulting APK.


## Follow-up — remove forced refresh-rate preference and reduce animation setup work (2026-10-28)

- Removed the Activity's 60 Hz preferred-mode request from both startup and resume. The launcher no longer calls `WindowManager.LayoutParams.preferredRefreshRate` or `preferredDisplayModeId`, nor registers a display listener solely to log refresh changes.
- Removed the now-unused `DisplayModeSelector` helper and its dedicated tests from the standalone unit-test runner. Android/TV display policy now selects the refresh mode; the app does not claim a target refresh rate or equate Hz with rendered FPS.
- Removed boxed per-slot angle storage from the pinned-app wheel animation. The same deterministic starting angle is calculated from the slot index when needed; card trajectories and per-frame focus-marker behavior remain unchanged.
- The current source no longer contains a wallpaper motion animator or motion configuration. Wallpaper changes still crossfade. The adaptive glow builds gradients on bounds/palette changes rather than inside `draw()`; app/Continue Watching data and artwork work is already off the UI thread.
- **Verification still required:** hosted CI/build and on-TV comparison have not run for this new branch yet. Removing the refresh-rate preference is a source-level behavior change, not proof of an FPS improvement. Recheck frame statistics on the TV after a compatible APK is built and installed.


## Follow-up — cold-start wallpaper, Watch Next latency, settings key, and lightweight HUD (2026-10-29)

- On a cold start, the first discovered local wallpaper is now decoded immediately on the existing single worker rather than waiting for the normal 10-second rotation delay. After the first crossfade completes, the regular rotation timer resumes. Later resumes keep the current wallpaper and use normal rotation timing.
- Watch Next HTTP(S) artwork uses 2-second connect and 2.5-second read timeouts instead of 3.5/4 seconds. Poster loading and bitmap decoding remain off the UI thread, with the existing three-worker limit and bounded 6 MiB source size. This reduces worst-case queue stalls but may leave artwork unavailable on exceptionally slow endpoints.
- Reduced the in-memory Watch Next poster bitmap cache from 12 MiB to 8 MiB. This caps retained artwork memory by up to 4 MiB while still leaving room for the visible cards; previously evicted posters may need to be decoded/downloaded again when revisited.
- The physical remote's `KEYCODE_SETTINGS` and `KEYCODE_MENU` events now route to the same settings-intent fallback as the on-screen control. Actual remote key mapping and OEM activity availability still require testing on the Hisense TV.
- Added a compact diagnostic HUD showing this process's PSS memory and CPU percentage, sampled every three seconds only while the launcher is foreground. It stops on pause. This is process-only telemetry, not total TV resource usage; sampling adds a small, non-zero diagnostic cost.
- Dependency review of `launcher/app/build.gradle` found only AppCompat, AndroidX Core, Android TV Provider, and JUnit; no advertising or analytics SDK dependency is declared by this launcher. Google TV system components are outside this APK and must be identified from a read-only package inventory before any device-level disablement. No system packages are changed by this source branch.
- **Validation pending:** hosted tests/build and on-TV checks. Verify cold-start first-wallpaper latency, poster arrival under normal network conditions, both remote settings key codes, HUD overhead/legibility, and CPU/RAM before deciding whether to keep or tune the diagnostic HUD.

 
## Follow-up — pinned-wheel frame cost, wallpaper thumbnails, compact controls and R8 (2026-10-29)

### Source-level findings and changes
- The pinned-app wheel performed five card transforms plus one incoming-card transform on every animation frame without temporary hardware layers. The matching Continue Watching wheel already used temporary hardware layers for moving cards. The pinned wheel now uses hardware layers only for the short slide, then releases them.
- The pinned-wheel focus marker previously ran global-visible-rectangle geometry on every animator frame. Marker geometry updates are now bounded to one every 50 ms during a slide; selection and icon-color resolution remain intact. The pinned focus-glow accent is cached by package so recycled wheel slots do not repeatedly rasterize the same icon into a bitmap.
- The recent-app carousel previously wrote the selected package/index to SharedPreferences on each selection callback. Writes are debounced for 300 ms and flushed on pause; activating an app persists its identity immediately.
- “Mis fondos” no longer shows the potentially stale “Fondo mostrado” label or generated file names. Every managed background gets a small sampled thumbnail decoded off the UI thread after bounds inspection and reduced to RGB_565; the full-resolution wallpaper path remains on its background worker. The dialog title and inner padding were adjusted to keep text away from the border.
- Inputs is reduced to 56×38 dp and Photos to 38×38 dp. Both labels are constrained to their own bounds with centered gravity and ellipsizing.
- Continue Watching poster views switch from `CENTER_CROP` to `FIT_CENTER` and reserve the lower title strip outside the artwork viewport. This prevents landscape poster art from losing its sides or being covered by the title.
- Release builds now enable R8 and unused-resource shrinking. The blanket keep-all rule was removed; narrowly scoped keep rules retain the XML-inflated carousel constructors and XML click handler. CI now assembles an unsigned optimized release variant as well as the debug artifact to exercise the shrinker.

### Validation status
- The current branch's GitHub Actions run must pass integrity checks, unit tests, debug APK build and R8 release build before the source change is considered build-validated.
- A same-scene before/after CPU and RAM measurement on the Hisense U7K/U75K is still required. The user's reported 80–100% CPU on Continue Watching versus much higher peaks on pinned/recent navigation remains the baseline; no post-change improvement is claimed yet.
- Visual checks on the physical TV are pending, especially marker tracking during the pinned-wheel slide, the imported-background thumbnails, and the resized top controls.
