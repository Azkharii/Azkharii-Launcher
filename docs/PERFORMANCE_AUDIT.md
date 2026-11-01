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
- Wallpaper decoding now uses `ImageDecoder` on Android 9+ to resize during decode, with a low-memory/software allocator policy and an aspect-preserving output sized to the TV viewport; Android 8.x keeps the downsample fallback. This avoids first decoding a large source photo and then allocating a second scaled bitmap. The default crossfade is reduced from 600 ms to 400 ms and wallpaper-adaptive HUD tinting from 900 ms to 600 ms, shortening the period of extra full-screen composition/redraw work. The fade still uses two layers temporarily, and the outgoing bitmap is cleared at the animation end.
- Release builds now enable R8 and unused-resource shrinking. The blanket keep-all rule was removed; narrowly scoped keep rules retain the XML-inflated carousel constructors and XML click handler. CI now assembles an unsigned optimized release variant as well as the debug artifact to exercise the shrinker.

### Validation status
- The current branch's GitHub Actions run must pass integrity checks, unit tests, debug APK build and R8 release build before the source change is considered build-validated.
- A same-scene before/after CPU and RAM measurement on the Hisense U7K/U75K is still required. The user's reported 80–100% CPU on Continue Watching versus much higher peaks on pinned/recent navigation remains the baseline; no post-change improvement is claimed yet.
- Visual checks on the physical TV are pending, especially marker tracking during the pinned-wheel slide, the imported-background thumbnails, and the resized top controls.


## Follow-up — wheel animation and repeated wallpaper rotation (2026-10-29)

- **Pinned-app wheel:** cache the small visible icon layers across selections. Compute start/end coordinates, scale and rotation once per keypress, then interpolate simple values during frames instead of repeating trigonometry. The semicircular radius is tightened to 82% of the former value to reduce excessive separation without changing icon size or ordering.
- **Continue Watching:** use the same precomputed-transform approach for the five visible cards and keep their small render layers warm. The title now overlays the artwork with a transparent text view and a translucent bottom scrim; the cover no longer reserves a blank 24 dp strip or places the text over an almost-opaque label rectangle.
- **Wallpaper rotation:** fade in only the incoming full-screen layer above the still-opaque outgoing image, rather than animating both layers' alpha. This keeps the same blended transition with one animated alpha property. After first decode/display, schedule a device-sized JPEG derivative in the background only after the crossfade/HUD transition has settled. Future turns can read this derivative instead of decoding the full source image each time. The cache is stored under app cache, bounded to 32 MiB, keyed by canonical source path + byte length + modification time + target viewport; stale/corrupt entries are discarded, and deleting a managed source deletes its current derivative. Alpha-bearing bitmaps bypass JPEG caching to avoid changing transparency. A source whose derivative has not yet been produced still follows the existing ImageDecoder/BitmapFactory downsampling path.
- **Compact controls:** Entradas is now 48×34 dp and Fotos 32×32 dp. Mis Fondos rows and thumbnails are 44 dp and 60×36 dp, respectively; dialog width, padding, list height and close action are also reduced.
- **Validation boundary:** transform arithmetic is now limited to setup, but this is not a device benchmark. Wallpaper previews can reduce repeated decode work after a file's first visit; initial visits may still spike because the original must be decoded, and cache creation itself consumes CPU after the transition. No measured CPU/RAM reduction is claimed until repeatable before/after samples are captured on the target TV. Physical layout/focus/navigation checks remain required.


## Follow-up — wallpaper-change CPU spike and focus-only glow (2026-10-29)

- Preview JPEG encoding previously started 700 ms after decode, overlapping the 400 ms crossfade and the following 600 ms wallpaper-adaptive HUD animation. Preview writes are now delayed 1.8 s, coalesced to the latest displayed wallpaper, canceled when paused or manually switching, and use JPEG quality 85. Cached previews still honor the existing 32 MiB cap and source/viewport fingerprint.
- Full-screen crossfade default is now 260 ms (was 400 ms). Adaptive HUD sampling uses 576 pixels instead of 1,344; its color transition is 360 ms instead of 600 ms, with color updates bounded to around 15 per second. Decode and palette sampling remain off the UI thread.
- The recent-app carousel and app library attach their adaptive edge only while they actually own focus, rather than retaining illumination based solely on selected index. RAM and CPU HUD sampling stop together and the overlay is hidden whenever the app library covers the home screen.
- **Measurement status:** the reported CPU increase at a wallpaper change is about 10–11%; no controlled post-change measurement exists yet. These changes target overlapping transition/cache work; compare the same scenario on the TV before concluding the target has been met.


## Follow-up — idle RAM and navigation render-layer lifetime (2026-10-29)

### Source-level changes

- The CPU/PSS diagnostic HUD is no longer instantiated on every home-screen launch. It is debug-only and opt-in using the Activity extra `com.cinematic.tvlauncher.EXTRA_PERFORMANCE_HUD`; normal sessions avoid recurring memory sampling and diagnostic TextView updates.
- Pinned-app icons no longer keep hardware render layers while the launcher is static. Existing wheel cards and the incoming card are promoted for D-pad movement, then returned to software/default layer mode after 1.2 seconds without another move. Pause and destroy also release retained layers.
- Continue Watching similarly releases its visible card layers after 1.2 seconds of navigation inactivity and when background work is paused/detached. The grace interval keeps fast repeated D-pad movement smooth without retaining those GPU layers forever.
- Existing transforms, animation durations/easing, focus logic, glow colors, card layout, and card order are unchanged. The main app carousel already promotes layers during its short position animation and releases them at animation completion, so it is deliberately unchanged.

### Validation boundary

- Hosted integrity checks, unit tests, and Android debug/release builds must pass before this change is build-validated.
- Dynamic hardware-layer promotion trades a small layer-recreation cost on the first key press after idle for lower idle graphics-memory retention. Exact CPU/RAM impact still requires matched before/after measurements on the target TV using `dumpsys meminfo`, `top`, and frame-jank samples. No device-side percentage improvement is claimed before that comparison.


## Corrective regression pass — 2026-10-29 (new PR)

### Reverted change with a plausible navigation cost

- The prior change turned pinned-wheel and Continue Watching cards from persistent hardware layers into layers promoted during navigation and demoted after 1.2 seconds. That required Android/HWUI to recreate layer resources when the next remote move came after the timeout. The new branch restores the prior stable-card-layer behavior for these small, bounded wheel surfaces; the main app carousel retains its existing visible-range animation layer policy.
- This is a source-level correction supported by the code comparison, not proof that it alone caused every CPU peak. The user's reported up-to-147% navigation sample remains the before/after metric to reproduce.

### Previous idle optimizations reconciled with the current source

- Background rotation interval: 30 → 60 seconds when multiple wallpapers are present. No timer is scheduled when the queue is empty or has only one image. Manual background selection remains available.
- Full-screen crossfade: 180 → 160 ms; the transition animation remains enabled.
- Wallpaper lifecycle: cancel rotation and pending decode before yielding from Activity. Automatic foreground JPEG preview encoding is removed; previews already cached by earlier versions remain readable, while uncached originals are downsampled on their next rotation.
- The adaptive HUD's 32×18 wallpaper sample and the 24×24/32×32 app-art samples now use bulk pixel copies; Watch Next poster palette sampling uses reusable row reads. Pixel dimensions, sample strides and color/palette algorithms are unchanged. New wallpaper requests cancel/remove obsolete queued palette jobs before they consume CPU; a running scan observes interruption.
- Focus changes now clear the previous recent-app/library selected state before early-returning on the floating marker, avoiding a stale active glow.

### Explicitly unchanged

- The RAM/CPU HUD remains enabled as before and samples every 3 seconds.
- Card dimensions, transforms, focus navigation, selected-icon marker color, multicolor borders/glow, wallpaper appearance, card animations, Continue Watching data, and app features are preserved.
- Dialog sizes and other styling were not bulk-rewritten from the older helper script because its expected source fragments did not match this branch; that script was not run blindly.

### Validation boundaries

- Hosted CI must run repository integrity, unit tests, debug APK build, and optimized release build for the new head.
- Physical TV measurements and visual review are still needed. Do not claim quantified CPU/RAM improvement from a code diff alone.


## Bounded artwork palette cache and pinned-wheel buffers — 2026-10-29

### Source-level changes
- The full app-library carousel rebuild samples an app banner/icon to generate its eight-zone edge palette, average card color, and icon accent. Those values are now cached per package in a bounded 128-entry LRU, so reopening the library can reuse samples instead of rasterizing every artwork again.
- App-artwork palettes and both MainActivity icon-accent caches are invalidated when Android reports package changes, and on resume when LauncherApps callbacks are unavailable. This avoids retaining stale colors after app updates.
- Pinned-app wheel navigation now fills fixed five-card transform buffers instead of allocating a copied moving-card list and temporary float arrays for each card on every key press. The end-of-animation slot ordering, transform formula, duration, focus movement, artwork, and illumination remain unchanged.
- The existing cache stores only a small number of sampled colors; it does not cache full-size bitmaps. The first opening of a cold/evicted library can still sample all app artwork, and this change makes no claim about the cost of creating all library views.

### Validation boundary
- Source-anchor checks confirmed the bounded cache, invalidation paths, five-card transform buffer bounds, and preserved transform formula.
- **Hosted validation PASS:** GitHub Actions Build run #122 for source commit `1867e1ae8c1ac56920289f9234c210c853adc214` passed repository integrity, all JVM test suites, debug APK compilation, optimized release/R8 compilation, APK inspection, and artifact upload.
- **Signing validation PASS:** workflow run #76 built the signed APK and verified its signature.
- Debug artifact SHA-256: `c25497969641d08c2f46adf0295b14948c404ab9cea369a43409a366c2f5ec64` (2,998,786 bytes).
- **Physical TV validation remains pending:** repeat same-scene CPU/RAM samples and check focus, icon coloring, glow and navigation on the Hisense before claiming a measured performance improvement.


## Text-only weather and event-driven day greeting — 2026-10-30

- Removed the weather-condition vector-icon mapping, drawable attachment code, stored WMO-condition parsing, and seven icon drawables. Weather acquisition is now limited to the current temperature; locality, persisted results, foreground refresh cadence, and tap-to-refresh are preserved.
- Reduced the text-only weather card padding, maximum width, font size, and line spacing to use less area after removing the icon.
- The small greeting above the digital clock uses local device time: 06:00–11:59 morning, 12:00–18:59 afternoon, 19:00–05:59 night. It is recalculated only as part of the already-existing clock/date event, which is registered only while the launcher is foregrounded. No independent timer or polling loop was added.
- No unrelated launcher animation, wallpaper, app-discovery, Watch Next, or navigation code was changed in this pass. Hosted tests/build and physical TV verification remain pending.


## Follow-up regression audit — 2026-10-31

### Continue Watching: confirmed source-level rebuild trigger

- The Watch Next provider publishes both structural card data and playback metadata. The prior comparison treated lastPlaybackPositionMillis, durationMillis, and lastEngagementTimeUtcMillis as card identity. During playback, those fields can change even when the same five titles remain in the same order.
- Every such metadata change took the full renderWheel() path: cancel poster work, remove all child views, recreate the visible card hierarchy and hardware layers, and restart artwork/palette tasks. This is a confirmed unnecessary rebuild path. The exact frequency of provider notifications on the Hisense TV is not available from repository evidence, so it is not claimed as a device-measured explanation for the 100–160% sample.
- The provider observer's former 200 ms trailing debounce could still execute repeated queries during a stream of notifications. The fix caps query starts at one per 1.2 seconds, retains existing card views when identity/order is unchanged, updates the progress strip in place, and notifies the Activity to recalculate section bounds only when the card structure changes.
- Card identity still includes provider row ID, title, poster URI, resume URI, publisher package, and Watch Next type. A changed item or changed provider ordering still rebuilds the wheel, preserving correct ordering and activation behavior.

### Weather: confirmed WMO category conflation

- The advice policy grouped WMO weather codes 1, 2, and 3 into the same “Cielo nublado” phrase. Those codes represent mainly clear, partly cloudy, and overcast conditions respectively. This made a mostly clear/sunny sky appear as cloudy.
- The policy now gives codes 0–3 distinct descriptions and tests each category. Existing heat, cold, rain, fog, and thunderstorm priority remains unchanged.

### Validation boundary

- Source-level regression paths identified and targeted in branch fix/watch-next-incremental-refresh-weather-wmo-20261031.
- Hosted integrity/unit/build validation and physical Hisense CPU/RAM comparison are pending. No percentage improvement is claimed until the same Continue Watching navigation scene is measured on the TV.


## Seguimiento — decodificación de fondos, HUD y navegación Biblioteca (2026-11-01)

### Evidencia recopilada en Hisense Android 12, después de fusionar PR #28

- Proceso observado: PID `18993`; la secuencia de memoria mantuvo el mismo PID durante seis muestras de 20 segundos.
- PSS total (KB): 53.869 → 83.699 → 70.220 → 70.120 → 66.396 → 70.364. El pico inicial descendió y las últimas cuatro muestras oscilaron alrededor de 66–70 MB; esta secuencia no demuestra una fuga monotónica.
- `top` mostró pulsos de CPU de aproximadamente 13–19%, picos aislados de 31%, 33%, 42% y un máximo observado de 70%. RES subió desde unos 201 MB, alcanzó un pico de 329 MB y luego volvió a aproximadamente 192–206 MB.
- Los registros del decodificador mostraron JPEG entre 99 y 738 ms y PNG entre 717 y 984 ms. El muestreo de paleta HUD tardó 1–4 ms. Las PNG se decodificaron correctamente mediante `PNG_REGION_ARGB8888` después del merge de PR #28; el fallo anterior de `ImageDecoder.checkSubset` dejó de aparecer en el periodo nuevo.
- Las muestras de `top` y los registros de decodificación no se capturaron con una base temporal común. No se atribuye el pico máximo de CPU a una operación concreta sin esa correlación.

### Cambios fuente de este seguimiento

- Los PNG grayscale/RGB sin alfa y sin transparencia `tRNS`, verificados a partir de sus chunks, pueden decodificarse con `RGB_565`. PNG con canal alfa, paleta, `tRNS`, cabecera inválida o formato desconocido permanecen en `ARGB_8888`.
- Los logs de decodificación incluyen tiempo de CPU del hilo además del tiempo de pared, para distinguir trabajo efectivo de espera/latencia.
- Los bitmaps cuyo callback llega a la UI después de quedar obsoleto se reciclan en esa ruta de carrera, en vez de quedar retenidos por un callback ya inválido.
- El HUD conserva CPU cada 3 segundos y refresca PSS cada 15 segundos, reduciendo llamadas frecuentes a `Debug.getMemoryInfo()` sin quitar el panel.
- El indicador de foco se oculta durante la transición entre Recientes y Biblioteca y se vuelve a ubicar en el primer pre-draw después de fijar posiciones, visibilidad y foco finales.
- El encabezado de Biblioteca cambia de doble trazo blanco/cian con halo fuerte a un relleno de vidrio frío-neutro, borde fino y sombra tenue. El cambio solo afecta a `LiquidGlassTextView`, usado por el título de Biblioteca.

### Validación pendiente

- La suite pura de Java añade pruebas para transparencia PNG y selección segura de configuración. El resultado de GitHub Actions de este seguimiento debe consultarse antes de marcar CI como PASS.
- La mejora real de CPU/RAM, el aspecto del encabezado a distancia de TV y la estabilidad del indicador requieren instalar el APK resultante y repetir la captura en el Hisense. No se consideran validados por el cambio fuente ni por la compilación.
- No se modifican la frecuencia de rotación de 20 segundos, la calidad/resolución objetivo de los fondos, el diseño de tarjetas, ni las funciones de la Biblioteca/Recientes.
