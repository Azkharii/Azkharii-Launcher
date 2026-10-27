# Cinematic Android TV Launcher Architecture

## 1. Overview
The Cinematic Android TV Launcher is engineered for high performance, visual immersion, and deterministic physical remote control on Android TV and Google TV devices (specifically optimized for the Hisense U75K platform).

## 2. Component Structure

```
launcher/app/src/main/
├── AndroidManifest.xml
├── java/com/cinematic/tvlauncher/
│   ├── MainActivity.java                     # Main Home activity and lifecycle coordinator
│   ├── background/
│   │   ├── CinematicBackgroundManager.java   # Dual-view crossfade & subtle pan/zoom motion
│   │   └── RotationQueue.java                # Random 10s queue without consecutive duplicates
│   ├── carousel/
│   │   ├── AppCarouselView.java              # 3D interactive bottom TV carousel
│   │   └── CarouselLayoutCalculator.java     # Pure mathematical layout & scale/alpha transforms
│   ├── config/
│   │   └── LauncherConfig.java               # Centralized configuration with runtime constraints
│   ├── controls/
│   │   └── TvControlManager.java             # Top-right controls: Inputs, Wi-Fi, Settings
│   ├── discovery/
│   │   └── AppDiscoveryManager.java          # Leanback & standard app discovery with filtering
│   ├── model/
│   │   └── AppItem.java                      # Model representing launchable applications
│   └── navigation/
│       └── FocusNavigationController.java    # Deterministic focus & Back key handling
└── res/
    ├── layout/activity_main.xml              # Master TV layout (Safe margins, Scrims, Carousel)
    ├── values/
    │   ├── config.xml                        # Master timing, scaling, and layout parameters
    │   ├── colors.xml                        # Scrims, borders, and typography palette
    │   ├── strings.xml                       # Localized user-facing strings (Spanish)
    │   └── styles.xml                        # Explicitly opaque TV theme (Field Note E.1)
    └── drawable/                             # Vector drawables, focus indicators, and fallback assets
```

## 3. Core Subsystems

### 3.1 Google Photos Cinematic Background Engine (Annex C.2, C.3)
- **Automatic Rotation:** Rotates every 10 seconds (`background_rotation_interval_sec = 10`).
- **Randomization without Consecutive Repetition:** `RotationQueue` tracks the last rendered image and strictly rejects immediate duplicates when 2+ photographs exist.
- **Crossfade Transitions:** Managed by dual overlapping `ImageView`s (`bg_image_a`, `bg_image_b`) with a 600 ms `ObjectAnimator` fade transition to guarantee zero black frames, blinking, or screen tearing.
- **Memory Safety & Downsampling:** Bitmaps are dynamically downsampled using `calculateInSampleSize` matching television display bounds, preventing OutOfMemory errors.
- **Subtle Motion:** Optional slow ping-pong zoom (scale 1.00 → 1.06 over 40s) using hardware-accelerated `ValueAnimator`, automatically paused in `onPause()` to guarantee 0% CPU usage when the TV is running other apps.

### 3.2 3D App Carousel (Annex C.1, D.3)
- **Geometry & Hierarchy:** Bottom band occupying ~24% screen height within safe overscan margins (5% H, 4% W).
- **Transforms:**
  - Focused item: scale 1.00, alpha 1.00, elevation 10dp, 0° rotation.
  - Neighbor ±1: scale 0.82, alpha 1.00, rotationY ±8°.
  - Neighbor ±2: scale 0.68, alpha 0.75, rotationY ±12°.
  - Neighbor ±3: scale 0.56, alpha 0.50, rotationY ±12°.
  - Cards > ±3 are hidden (`View.GONE`).
- **Adaptive Focus Indicator:** A shared `AdaptiveGlowDrawable` samples eight perimeter zones from each app banner/icon and blends their colors around the rounded edge. Watch Next samples the actual poster on a worker thread. A small inset keeps the artwork from covering the luminous frame.
- **Repeat Throttling:** D-pad navigation is throttled to about 12 repeated moves/s (`MIN_REPEAT_INTERVAL_MS = 80ms`). The selected-card marker stays at the fixed center slot and is not recalculated per animation frame.

### 3.3 Top-Right Controls & Navigation (Annex C.5, D.4)
- **Exactly Three Translucent Buttons:**
  1. **Inputs:** Queries `TvInputManager`; falls back to safe remote dialog.
  2. **Wi-Fi:** Queries active network state; launches system Wi-Fi settings; never stores passwords.
  3. **Settings:** Launches TV Leanback settings (`android.settings.TV_SETTINGS`) with fallback to standard settings.
- **Deterministic Traversal:**
  - Up from carousel navigates directly to Inputs.
  - Left / Right moves cyclically between the 3 controls without getting trapped.
  - Down returns focus to the carousel, restoring the exact selected app.
  - Back key is intercepted: closes menus or returns to carousel; never exits the launcher.
