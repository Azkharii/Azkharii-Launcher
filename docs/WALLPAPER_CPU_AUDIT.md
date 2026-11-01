# Wallpaper CPU and Liquid Glass audit

## Root cause identified

The previous wallpaper rotation path decoded a source image and then synchronously encoded the decoded bitmap as WebP (quality 86) on every cache miss before it could be passed to the crossfade. This work ran on a worker thread, so it did not directly block the UI thread, but it still consumed CPU in the launcher process during wallpaper changes. The cache path also calculated a SHA-256 source/viewport fingerprint and periodically enumerated, sorted, and trimmed preview files.

The exact CPU percentage caused by each stage must be measured on the Hisense TV; hosted CI cannot reproduce its decoder and graphics workload.

## Changes in this revision

- Removed the per-rotation preview cache completely: no WebP compression, hashing, preview reads/writes, temporary files, or cache trimming occurs when a wallpaper changes.
- Kept the existing 20-second rotation cadence, two ImageView layers, 160 ms single-layer fade, wallpaper order, original imported files, and image crossfade.
- Reduced the maximum decoded surface from 2560×1440 to 2048×1152. With ARGB_8888 this bounds one wallpaper bitmap to 9 MiB and the two simultaneously held transition layers to about 18 MiB, versus about 14 MiB and 28 MiB at the previous cap (about 36% less bitmap allocation during a full two-layer transition). Actual PSS still includes other process/native/graphics allocations and is measured on device.
- Removed the duplicate default-wallpaper source from the initially transparent second ImageView and from the inactive layer restored after deleting the current photo. Only the visible fallback layer carries the default bitmap.
- Wallpaper decoder logs now include actual allocation bytes, bitmap config, decoded dimensions, requested size, display metrics, and elapsed decode time.
- Hisense Android 12 logs exposed a concrete PNG failure: `ImageDecoder.setCrop()` received source-pixel coordinates (e.g. 5200×3087) after the decoder had already scaled the image to 1920×1080, producing `IllegalStateException: Subset ... not contained by scaled image bounds`. Normal-orientation PNGs now use `BitmapRegionDecoder` with an ARGB_8888 region so transparency is retained and the crop is performed in source coordinates. If region decode is unavailable, ImageDecoder receives no crop; it decodes proportionally and the existing ImageView CENTER_CROP performs the viewport crop. Logs now identify the decoder path.
- The previous release's WebP preview read and the older BitmapFactory fallback used RGB_565; PR #26 changed those paths to ARGB_8888. That doubled decoded bitmap bytes for the same pixel dimensions. The new path uses BitmapRegionDecoder + RGB_565 for normal-orientation JPEG photos (including files with no EXIF orientation tag), and retains ImageDecoder for PNG/WebP or JPEGs needing EXIF rotation so transparency/orientation is preserved.
- The JPEG region sampler uses a bounded power-of-two sample size near the 2048×1152 viewport. Added test cases cover 4K-to-half, very-large-to-quarter, and near-viewport full-resolution decisions.
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
