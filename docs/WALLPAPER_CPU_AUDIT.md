# Wallpaper CPU and Liquid Glass audit

## Root cause identified

The previous wallpaper rotation path decoded a source image and then synchronously encoded the decoded bitmap as WebP (quality 86) on every cache miss before it could be passed to the crossfade. This work ran on a worker thread, so it did not directly block the UI thread, but it still consumed CPU in the launcher process during wallpaper changes. The cache path also calculated a SHA-256 source/viewport fingerprint and periodically enumerated, sorted, and trimmed preview files.

The exact CPU percentage caused by each stage must be measured on the Hisense TV; hosted CI cannot reproduce its decoder and graphics workload.

## Changes in this revision

- Removed the per-rotation preview cache completely: no WebP compression, hashing, preview reads/writes, temporary files, or cache trimming occurs when a wallpaper changes.
- Kept the existing 20-second rotation cadence, two ImageView layers, 160 ms single-layer fade, wallpaper order, original imported files, and image crossfade.
- Added WallpaperCropPolicy, which calculates the centered crop matching ImageView.CENTER_CROP. On API 28+, ImageDecoder receives this crop and a bounded output size up to 2560×1440. This avoids producing pixels that the view immediately discards from portrait, 4:3, or ultra-wide photos. Older API fallback remains unchanged.
- Added timing logs for decode dimensions/duration (CinematicBackgroundMgr) and adaptive HUD palette sampling (MainActivity) so a TV log can distinguish image decode cost from the smaller colour-sampling step.
- The wallpaper worker now runs at Android background thread priority, yielding to the UI thread and foreground work on constrained TV hardware. This improves scheduling fairness; it does not by itself eliminate decoder CPU time.
- Track local wallpaper-directory discovery as a cancellable Future. A newer scan cancels/removes the superseded scan. Pausing the Activity cancels an unfinished scan and invalidates its UI delivery; resume restarts discovery only if it did not complete. The in-Activity application library remains part of the home surface and does not pause rotation.
- Strengthened the Liquid Glass text effect only on the «Biblioteca de aplicaciones» heading: a visible cool gradient contour and a fine inner highlight, with transparent glyph interiors. Original font, size, placement, and card labels are retained.

## Regression tests

WallpaperCropPolicyTest covers:
- 4:3 sources cropped vertically.
- Ultra-wide sources cropped horizontally.
- Exact 16:9 sources.
- Sources smaller than the viewport (no crop enlargement).
- Portrait sources cropped to a centered landscape window.

## Verification status

- JVM crop geometry tests: pending hosted CI.
- Repository integrity and Android Debug/Release/R8 builds: pending hosted CI.
- Signed APK verification: pending hosted CI.
- Hisense TV CPU/RAM measurements, transition smoothness, image sharpness, and title legibility over bright/dark photos: pending physical-device verification.

No numeric CPU reduction is claimed until the new APK is installed and the same rotation sequence is measured on the TV.
