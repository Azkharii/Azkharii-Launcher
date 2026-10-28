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


## Follow-up pass — event-driven updates and on-demand artwork

### Implementation in `perf/event-driven-refresh-and-cpu`

- Keep the existing disabled-by-default full-screen wallpaper zoom. Wallpaper rotation and crossfade remain unchanged.
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
- El zoom opcional del fondo permanece disponible y desactivado por defecto. La rotación de fondos, crossfade, carruseles, aplicaciones fijadas, Continuar viendo y actualización segura se conservan.

### Verificación pendiente

- La rama fuente y las pruebas se están preparando para CI; aún no hay resultado de validación final.
- La comparación de CPU/RAM en el televisor después de instalar una versión nueva sigue pendiente. No debe darse por corregido el pico hasta medir la misma escena en reposo después de la actualización.


## Deep idle rendering pass — 2026-10-28

### Latest thread-level sample supplied by the user

- The first snapshot showed `RenderThread` at 77.4%, `mali-cmar-backe` at 29.0%, and the two HWUI workers at 6.4% and 3.2%, while the app's main thread reported 0.0%.
- The next snapshot showed the launcher threads sleeping; later snapshots showed only brief low activity. Process RES moved from about 272 MiB to 264 MiB in the captured interval.
- This is evidence of a short graphics-work burst, not sustained CPU use across all five seconds and not proof of a memory leak. Thread-level RES is repeated for the same process and must not be summed.

### Changes made on `perf/deep-idle-render-optimization`

- Set `LauncherConfig.DEFAULT_MOTION_ENABLED` to `false`, matching `config.xml`. The fallback path can no longer accidentally enable the infinite wallpaper zoom if resource loading fails.
- Made wallpaper rotation event-driven: do not schedule a timer when no local wallpapers exist, do not force an immediate crossfade on every resume, and do not repeat the timer after displaying a single available photo. Rotation and the 600 ms crossfade remain available when multiple photos exist.
- Shortened the wallpaper-adaptive HUD color transition from 1.8 s to 0.9 s, limited color updates to at most one every 50 ms, and applied visually negligible RGB changes directly rather than starting an animation.
- Reused the adaptive glow's normalized perimeter palette and gradient when the palette has not changed. Repeated assignments of the same alpha or color filter no longer invalidate the drawable.
- Updated the configuration test to assert that continuous wallpaper motion is disabled by default.

### What was deliberately not removed

No UI component, navigation behavior, Watch Next feature, wallpaper crossfade, or APK resource was deleted. The source evidence so far supports eliminating redundant work, but does not prove that any additional drawable, layout, or subsystem is dead code. Deleting such assets without a reference audit would offer little runtime CPU benefit and could cause a visual regression.

### Validation status

- Source edits are committed on the dedicated branch; automated tests, integrity checks, and APK build must pass in GitHub Actions before considering the changes integration-ready.
- A post-change CPU/RAM capture on the Hisense TV is still required. The optimization is not marked as physically verified until the same idle scene is measured after installing the resulting APK.
