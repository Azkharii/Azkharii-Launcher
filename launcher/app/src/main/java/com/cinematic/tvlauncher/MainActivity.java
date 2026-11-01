package com.cinematic.tvlauncher;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.content.pm.LauncherApps;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Log;
import android.view.Window;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.media.tv.TvView;

import androidx.appcompat.app.AppCompatActivity;

import com.cinematic.tvlauncher.background.CinematicBackgroundManager;
import com.cinematic.tvlauncher.carousel.AppCarouselView;
import com.cinematic.tvlauncher.carousel.ContinueWatchingWheelView;
import com.cinematic.tvlauncher.UpdateManager;
import com.cinematic.tvlauncher.config.LauncherConfig;
import com.cinematic.tvlauncher.controls.TvControlManager;
import com.cinematic.tvlauncher.discovery.AppDiscoveryManager;
import com.cinematic.tvlauncher.model.AppItem;
import com.cinematic.tvlauncher.navigation.FocusNavigationController;
import com.cinematic.tvlauncher.navigation.PinnedWheelSlotOrder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import android.graphics.Color;

/**
 * MainActivity — Central Home activity for Cinematic Android TV Launcher.
 * Implements persistent HOME lifecycle, local custom wallpaper rotation,
 * 3D bottom carousel, and deterministic remote focus.
 */
public class MainActivity extends AppCompatActivity implements AppCarouselView.OnCarouselSelectionListener {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "cinematic_launcher_prefs";
    private static final String KEY_LAST_APP_INDEX = "last_app_index";
    private static final String KEY_LAST_APP_PACKAGE = "last_app_package";
    private static final String KEY_PINNED_APPS = "pinned_apps";
    // Persist selection after navigation settles instead of writing on every D-pad repeat.
    private int pendingRecentIndex = -1;
    private String pendingRecentPackage;
    private final Runnable persistRecentSelectionRunnable = () -> {
        if (pendingRecentIndex < 0 || pendingRecentPackage == null) return;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putInt(KEY_LAST_APP_INDEX, pendingRecentIndex)
                .putString(KEY_LAST_APP_PACKAGE, pendingRecentPackage).apply();
        pendingRecentIndex = -1;
        pendingRecentPackage = null;
    };
    private static final String KEY_TV_LISTINGS_PERMISSION_REQUESTED = "tv_listings_permission_requested";
    private static final String PERMISSION_READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    private static final int REQUEST_TV_LISTINGS_PERMISSION = 4701;
    private static final ThreadPoolExecutor HUD_COLOR_EXECUTOR =
            new ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    private CinematicBackgroundManager backgroundManager;
    private AppDiscoveryManager discoveryManager;
    private TvControlManager controlManager;
    private PerformanceHud performanceHud;
    private FocusNavigationController focusController;
    private LauncherConfig launcherConfig;

    private TextView tvGreeting;
    private TextView tvClock;
    private TextView tvDate;
    private TextView tvWeather;
    // Generation is read on the palette worker as well as the main thread.
    private volatile int hudColorGeneration = 0;
    private java.util.concurrent.Future<?> hudColorSampleTask;
    private ValueAnimator hudColorAnimator;
    private GradientDrawable weatherGlassBackground;
    private int currentHudAccent = Color.rgb(168, 232, 255);
    private boolean hudAccentApplied;
    private long lastHudAccentFrameAtMs;
    private final float[] hudAnimationStartHsv = new float[3];
    private final float[] hudAnimationTargetHsv = new float[3];
    private final float[] hudAnimationFrameHsv = new float[3];
    private final float[] hudAccentHsv = new float[3];
    private final float[] hudClockTextHsv = new float[3];
    private final float[] hudWeatherTextHsv = new float[3];
    private final int[] hudGlassColors = new int[3];
    private final int[] lastAppliedHudGlassColors = {
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE
    };
    private int lastClockShadowColor = Integer.MIN_VALUE;
    private int lastDateShadowColor = Integer.MIN_VALUE;
    private int lastWeatherShadowColor = Integer.MIN_VALUE;
    private int lastWeatherGlassStrokeColor = Integer.MIN_VALUE;
    private UpdateManager updateManager;
    private WeatherWidgetManager weatherWidgetManager;
    private TextView tvFocusedAppLabel;
    private AppCarouselView carouselView;
    private View safeContainer;
    private View libraryOverlay;
    private AppCarouselView libraryCarousel;
    private TextView librarySelectedLabel;
    private ContinueWatchingWheelView continueWatchingWheel;
    private TextView continueWatchingHeading;
    private FrameLayout rootContainer;
    private FrameLayout pinnedAppsWheel;
    private FrameLayout pinnedAppsScroll;
    private View focusPositionDot;
    private GradientDrawable focusPositionDotBackground;
    private boolean focusPositionUsesSelectionAccent = false;
    // Reuse geometry buffers: this method runs on every carousel animation frame.
    private final android.graphics.Rect focusVisibleRect = new android.graphics.Rect();
    private final int[] focusRootLocation = new int[2];
    private static final int PINNED_WHEEL_VISIBLE_ITEMS = 5;
    private final List<AppItem> pinnedWheelItems = new java.util.ArrayList<>();
    // Wheel navigation reuses these five-card transform buffers instead of allocating on each key.
    private final float[][] pinnedWheelFromTransforms = new float[PINNED_WHEEL_VISIBLE_ITEMS][4];
    private final float[][] pinnedWheelToTransforms = new float[PINNED_WHEEL_VISIBLE_ITEMS][4];
    private final float[] pinnedWheelIncomingFromTransform = new float[4];
    private final float[] pinnedWheelIncomingToTransform = new float[4];
    private final List<ImageView> pinnedWheelSlotViews = new ArrayList<>();
    // Cache per-package icon-derived colors for the navigation marker only.
    private final android.util.LruCache<String, Integer> iconAccentCache =
            new android.util.LruCache<>(128);
    // Avoid rasterizing the same icon repeatedly while its wheel slot gains focus.
    private final android.util.LruCache<String, Integer> appFocusGlowAccentCache =
            new android.util.LruCache<>(128);
    private int pinnedWheelSelectedIndex = 0;
    private boolean pinnedWheelAnimating = false;
    private long lastPinnedDotUpdateAtMs;
    private boolean rebuildingPinnedWheel = false;
    private boolean libraryVisible = false;
    // Keep the root-level navigation marker still while the two library panels move.
    private boolean focusDotTransitionInProgress;
    private AnimatorSet libraryTransitionAnimator;
    private ViewTreeObserver.OnPreDrawListener focusDotPreDrawListener;
    private boolean watchNextPermissionRequestInFlight = false;
    private boolean optionsOpenedFromPinnedWheel = false;
    private int optionsPinnedIndex = -1;

    private BroadcastReceiver timeTickReceiver;
    private boolean timeTickReceiverRegistered;
    private String lastClockText;
    private String lastDateKey;
    private Locale lastDateLocale;
    private LauncherApps launcherApps;
    private boolean launcherAppsCallbackRegistered;
    private boolean activityResumed;
    private final Handler lifecycleHandler = new Handler(Looper.getMainLooper());

    private final Runnable packageRefreshRunnable = () -> {
        if (!activityResumed || isFinishing() || isDestroyed() || discoveryManager == null) return;
        discoveryManager.invalidateInstalledAppsCache();
        loadApps();
        if (libraryVisible && libraryCarousel != null) {
            int selectedIndex = libraryCarousel.getSelectedIndex();
            libraryCarousel.setItems(discoveryManager.discoverApps(0));
            libraryCarousel.setSelectedIndex(selectedIndex);
        }
    };

    private final LauncherApps.Callback launcherAppsCallback = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String packageName, UserHandle user) {
            schedulePackageRefresh();
        }
        @Override public void onPackageChanged(String packageName, UserHandle user) {
            schedulePackageRefresh();
        }
        @Override public void onPackageRemoved(String packageName, UserHandle user) {
            schedulePackageRefresh();
        }
        @Override public void onPackagesAvailable(String[] packageNames, UserHandle user,
                boolean replacing) {
            schedulePackageRefresh();
        }
        @Override public void onPackagesUnavailable(String[] packageNames, UserHandle user,
                boolean replacing) {
            schedulePackageRefresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        launcherConfig = LauncherConfig.loadFromResources(this);
        discoveryManager = new AppDiscoveryManager(this);
        updateManager = new UpdateManager(this);

        initViews();
        setupClockReceiver();
        loadApps();
        setupLibraryOverlay();

        // loadApps restores the app by package identity and defaults to the third recent card.
        focusController.recordSelectedApp(carouselView.getSelectedIndex());
        carouselView.requestFocus();
    }

    private void initViews() {
        ImageView bgImageA = findViewById(R.id.bg_image_a);
        ImageView bgImageB = findViewById(R.id.bg_image_b);
        tvGreeting = findViewById(R.id.tv_greeting);
        tvClock = findViewById(R.id.tv_clock);
        tvDate = findViewById(R.id.tv_date);
        tvWeather = findViewById(R.id.tv_weather);
        weatherWidgetManager = new WeatherWidgetManager(this, tvWeather);
        tvFocusedAppLabel = findViewById(R.id.tv_focused_app_label);
        carouselView = findViewById(R.id.app_carousel);
        // Show exactly five cards: selected in the middle, two neighbors on each side.
        carouselView.setMaxVisibleCardDistance(2);
        safeContainer = findViewById(R.id.safe_container);
        rootContainer = findViewById(R.id.root_container);
        if (rootContainer != null) performanceHud = new PerformanceHud(this, rootContainer);
        libraryOverlay = findViewById(R.id.app_library_overlay);
        libraryCarousel = findViewById(R.id.library_carousel);
        librarySelectedLabel = findViewById(R.id.library_selected_label);
        continueWatchingWheel = findViewById(R.id.continue_watching_wheel);
        continueWatchingHeading = findViewById(R.id.continue_watching_heading);
        pinnedAppsWheel = findViewById(R.id.pinned_apps_wheel);
        pinnedAppsScroll = findViewById(R.id.pinned_apps_scroll);
        if (safeContainer instanceof ViewGroup) {
            ((ViewGroup) safeContainer).setClipChildren(false);
            ((ViewGroup) safeContainer).setClipToPadding(false);
        }
        if (rootContainer != null) {
            focusPositionDot = new View(this);
            focusPositionDotBackground = new GradientDrawable();
            focusPositionDotBackground.setShape(GradientDrawable.OVAL);
            focusPositionDotBackground.setColor(currentHudAccent);
            focusPositionDotBackground.setStroke(dp(1), 0xCCFFFFFF);
            focusPositionDot.setBackground(focusPositionDotBackground);
            focusPositionDot.setElevation(dp(24));
            focusPositionDot.setVisibility(View.GONE);
            focusPositionDot.setFocusable(false);
            focusPositionDot.setClickable(false);
            focusPositionDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            rootContainer.addView(focusPositionDot,
                    new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP | Gravity.LEFT));

            rootContainer.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
                boolean recentAppsFocused = newFocus != null && carouselView != null
                        && (newFocus == carouselView || isDescendantOf(newFocus, carouselView));
                if (carouselView != null) carouselView.setSelectionActive(recentAppsFocused);
                boolean libraryFocused = newFocus != null && libraryCarousel != null
                        && (newFocus == libraryCarousel || isDescendantOf(newFocus, libraryCarousel));
                if (libraryCarousel != null) libraryCarousel.setSelectionActive(libraryFocused);
                // Ignore marker placement only after clearing the previous carousel's active glow.
                if (newFocus == focusPositionDot) return;
                // The marker is outside safeContainer; defer geometry until both panels settle.
                if (focusDotTransitionInProgress) return;
                if (newFocus == null) {
                    focusPositionDot.setVisibility(View.GONE);
                    return;
                }
                // The manual-update control is intentionally only a floating arrow, not a card
                // or a target for the carousel's positional marker.
                if (newFocus.getId() == R.id.btn_check_updates) {
                    focusPositionDot.setVisibility(View.GONE);
                    return;
                }
                if (rebuildingPinnedWheel) return;
                // Dedicated carousel callbacks use the card surface/edge, avoiding a transient
                // marker jump when focus itself is on a containing view.
                if (continueWatchingWheel != null && isDescendantOf(newFocus, continueWatchingWheel)) return;
                if (carouselView != null && (newFocus == carouselView || isDescendantOf(newFocus, carouselView))) {
                    updateFocusPositionDot(carouselView.getSelectedCardView());
                } else if (libraryCarousel != null
                        && (newFocus == libraryCarousel || isDescendantOf(newFocus, libraryCarousel))) {
                    updateFocusPositionDot(libraryCarousel.getSelectedCardView());
                } else {
                    updateFocusPositionDot(newFocus);
                }
            });
        }
        if (pinnedAppsScroll != null) {
            pinnedAppsScroll.setClipChildren(false);
            pinnedAppsScroll.setClipToPadding(false);
        }
        if (pinnedAppsWheel != null) {
            pinnedAppsWheel.setClipChildren(false);
            pinnedAppsWheel.setClipToPadding(false);
        }

        View btnCheckUpdates = findViewById(R.id.btn_check_updates);
        View btnInputs = findViewById(R.id.btn_inputs);
        View btnWifi = findViewById(R.id.btn_wifi);
        View btnSettings = findViewById(R.id.btn_settings);
        View btnSearch = findViewById(R.id.btn_search);
        View btnPhotos = findViewById(R.id.btn_photos);
        TextView tvWifiIcon = findViewById(R.id.tv_wifi_icon);

        setupTopControlIllumination(btnInputs, btnWifi, btnSettings, btnPhotos, btnSearch);
        if (btnCheckUpdates != null) {
            TextView updateArrow = (TextView) ((ViewGroup) btnCheckUpdates).getChildAt(0);
            btnCheckUpdates.setOnClickListener(v -> {
                if (updateManager != null) updateManager.checkNow();
            });
            btnCheckUpdates.setOnFocusChangeListener((view, hasFocus) -> {
                view.setAlpha(hasFocus ? 1f : 0.72f);
                view.setScaleX(hasFocus ? 1.12f : 1f);
                view.setScaleY(hasFocus ? 1.12f : 1f);
                if (updateArrow != null) {
                    updateArrow.setTextColor(hasFocus ? Color.WHITE : Color.rgb(184, 232, 255));
                }
            });
        }

        backgroundManager = new CinematicBackgroundManager(this, bgImageA, bgImageB, launcherConfig);
        backgroundManager.setOnBackgroundChangedListener(this::scheduleAdaptiveHudColors);
        View inputOverlay = findViewById(R.id.input_overlay);
        TvView inputTvView = findViewById(R.id.tv_input_view);
        controlManager = new TvControlManager(this, btnInputs, btnWifi, btnSettings, btnPhotos,
                btnSearch, tvWifiIcon, inputOverlay, inputTvView, backgroundManager);
        focusController = new FocusNavigationController(carouselView, btnCheckUpdates,
                btnInputs, btnWifi, btnSettings, btnPhotos, btnSearch);

        carouselView.setSelectionListener(this);
        // The navigation marker stays in the fixed center slot. It is updated on selection,
        // not once per animation frame, to keep remote navigation work off the frame hot path.
        if (continueWatchingWheel != null) {
            continueWatchingWheel.setOnItemActivatedListener(this::launchContinueWatchingApp);
            continueWatchingWheel.setOnNavigateBackListener(() -> carouselView.requestFocus());
            continueWatchingWheel.setOnFocusTargetChangedListener(target -> updateFocusPositionDot(target, true));
            continueWatchingWheel.setOnItemsChangedListener(() -> {
                updateContinueWatchingSectionVisibility();
                updateContinueWatchingViewportBounds();
            });
        }
        if (continueWatchingHeading != null) continueWatchingHeading.setVisibility(View.GONE);
        if (safeContainer != null) {
            safeContainer.addOnLayoutChangeListener((v, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> {
                updatePinnedAppsViewportBounds();
                updateContinueWatchingViewportBounds();
            });
            safeContainer.post(() -> {
                updatePinnedAppsViewportBounds();
                updateContinueWatchingViewportBounds();
            });
        }
    }

    private void loadApps() {
        List<AppItem> discoveredApps = discoveryManager.discoverRecentApps(5);
        if (discoveredApps.isEmpty()) {
            tvFocusedAppLabel.setText(R.string.no_apps_found);
        }

        // UsageStats may reorder the list while the launcher is paused. Preserve the selected
        // package identity, otherwise put the third recent app in the fixed visual center slot.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String rememberedPackage = prefs.getString(KEY_LAST_APP_PACKAGE, null);
        int targetIndex = Math.min(2, Math.max(0, discoveredApps.size() - 1));
        if (rememberedPackage != null) {
            for (int i = 0; i < discoveredApps.size(); i++) {
                if (rememberedPackage.equals(discoveredApps.get(i).getPackageName())) {
                    targetIndex = i;
                    break;
                }
            }
        }

        carouselView.setItems(discoveredApps);
        carouselView.setSelectedIndex(targetIndex);
        focusController.recordSelectedApp(targetIndex);
        if (targetIndex >= 0 && targetIndex < discoveredApps.size()) {
            AppItem selected = discoveredApps.get(targetIndex);
            prefs.edit().putInt(KEY_LAST_APP_INDEX, targetIndex)
                    .putString(KEY_LAST_APP_PACKAGE, selected.getPackageName()).apply();
        }
        refreshPinnedAppsWheel();
    }

    private void invalidateAppCaches() {
        if (discoveryManager != null) discoveryManager.invalidateInstalledAppsCache();
        AppCarouselView.clearArtworkCaches();
        iconAccentCache.evictAll();
        appFocusGlowAccentCache.evictAll();
    }

    private void schedulePackageRefresh() {
        invalidateAppCaches();
        if (!activityResumed) return;
        lifecycleHandler.removeCallbacks(packageRefreshRunnable);
        lifecycleHandler.postDelayed(packageRefreshRunnable, 300L);
    }

    private void registerLauncherAppsCallback() {
        if (launcherAppsCallbackRegistered) return;
        try {
            if (launcherApps == null) {
                launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            }
            if (launcherApps != null) {
                launcherApps.registerCallback(launcherAppsCallback, lifecycleHandler);
                launcherAppsCallbackRegistered = true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "LauncherApps callbacks unavailable; resume-time refresh remains enabled.", e);
        }
    }

    private void unregisterLauncherAppsCallback() {
        lifecycleHandler.removeCallbacks(packageRefreshRunnable);
        if (!launcherAppsCallbackRegistered || launcherApps == null) return;
        try {
            launcherApps.unregisterCallback(launcherAppsCallback);
        } catch (RuntimeException e) {
            Log.d(TAG, "LauncherApps callback was already unregistered", e);
        } finally {
            launcherAppsCallbackRegistered = false;
        }
    }

    private void setupClockReceiver() {
        timeTickReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateClockAndDate();
            }
        };
        updateClockAndDate();
    }

    /** Receive minute/time changes only while the launcher is foregrounded. */
    private void registerTimeTickReceiver() {
        if (timeTickReceiver == null || timeTickReceiverRegistered || !activityResumed) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        try {
            registerReceiver(timeTickReceiver, filter);
            timeTickReceiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Clock receiver could not be registered", e);
        }
    }

    private void unregisterTimeTickReceiver() {
        if (timeTickReceiver == null || !timeTickReceiverRegistered) return;
        try {
            unregisterReceiver(timeTickReceiver);
        } catch (RuntimeException e) {
            Log.d(TAG, "Clock receiver was already unregistered", e);
        } finally {
            timeTickReceiverRegistered = false;
        }
    }

    private void updateClockAndDate() {
        Date now = new Date();
        java.util.Calendar localTime = java.util.Calendar.getInstance();
        localTime.setTime(now);
        String greeting = DayGreetingPolicy.forHour(
                localTime.get(java.util.Calendar.HOUR_OF_DAY));
        if (tvGreeting != null && !greeting.equals(tvGreeting.getText().toString())) {
            tvGreeting.setText(greeting);
        }
        Locale locale = Locale.getDefault();
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", locale);
        if (tvClock != null) {
            String timeText = timeFormat.format(now);
            if (!timeText.equals(lastClockText)) {
                tvClock.setText(timeText);
                lastClockText = timeText;
            }
        }

        // Date formatting and TextView invalidation only happen when the day or locale changes.
        String dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now);
        if (tvDate != null && (!dayKey.equals(lastDateKey) || !locale.equals(lastDateLocale))) {
            String dateText = new SimpleDateFormat("EEEE, d 'de' MMMM", locale).format(now);
            if (!dateText.isEmpty()) {
                dateText = Character.toUpperCase(dateText.charAt(0)) + dateText.substring(1);
            }
            if (!dateText.equals(tvDate.getText().toString())) tvDate.setText(dateText);
            lastDateKey = dayKey;
            lastDateLocale = locale;
        }
    }

    @Override
    public void onItemSelected(int index, AppItem item) {
        if (item != null && tvFocusedAppLabel != null) {
            tvFocusedAppLabel.setText(item.getLabel());
            focusController.recordSelectedApp(index);
            if (!libraryVisible && carouselView != null) {
                pendingRecentIndex = index;
                pendingRecentPackage = item.getPackageName();
                lifecycleHandler.removeCallbacks(persistRecentSelectionRunnable);
                lifecycleHandler.postDelayed(persistRecentSelectionRunnable, 300L);
                Runnable updateRecentFocusDot = () -> {
                    View focus = getCurrentFocus();
                    if (focus == carouselView || (focus != null && isDescendantOf(focus, carouselView))) {
                        updateFocusPositionDot(carouselView.getSelectedCardView());
                    }
                };
                carouselView.post(updateRecentFocusDot);
            }
        }
    }

    public void closeInputOverlayFromView(View view) {
        if (controlManager != null) controlManager.closeInputOverlay();
    }

    @Override
    public void onItemLongPressed(int index, AppItem item) {
        showAppOptions(item);
    }

    @Override
    public void onItemActivated(int index, AppItem item) {
        if (item == null) return;
        lifecycleHandler.removeCallbacks(persistRecentSelectionRunnable);
        pendingRecentIndex = -1;
        pendingRecentPackage = null;

        // Persist last opened app index across restarts
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_LAST_APP_INDEX, index)
                .putString(KEY_LAST_APP_PACKAGE, item.getPackageName())
                .apply();

        launchApp(item);
    }



    private boolean isPinned(AppItem item) {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getStringSet(KEY_PINNED_APPS, java.util.Collections.emptySet())
                .contains(item.getPackageName());
    }

    private void togglePinned(AppItem item) {
        int previousPinnedIndex = -1;
        boolean restorePinnedFocus = false;
        View currentFocus = getCurrentFocus();
        if (currentFocus != null && pinnedAppsWheel != null && isDescendantOf(currentFocus, pinnedAppsWheel)) {
            restorePinnedFocus = true;
            previousPinnedIndex = pinnedWheelSelectedIndex;
        } else if (optionsOpenedFromPinnedWheel) {
            restorePinnedFocus = true;
            previousPinnedIndex = optionsPinnedIndex;
        }
        optionsOpenedFromPinnedWheel = false;
        optionsPinnedIndex = -1;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        java.util.Set<String> pinned = new java.util.HashSet<>(
                prefs.getStringSet(KEY_PINNED_APPS, java.util.Collections.emptySet()));
        if (!pinned.add(item.getPackageName())) pinned.remove(item.getPackageName());
        prefs.edit().putStringSet(KEY_PINNED_APPS, pinned).apply();
        loadApps();
        if (libraryVisible) {
            int selected = libraryCarousel.getSelectedIndex();
            libraryCarousel.setItems(discoveryManager.discoverApps(0));
            libraryCarousel.setSelectedIndex(selected);
        }
        if (restorePinnedFocus && pinnedAppsWheel.getChildCount() > 0) {
            if (!pinnedWheelItems.isEmpty()) {
                pinnedWheelSelectedIndex = Math.min(Math.max(0, previousPinnedIndex), pinnedWheelItems.size() - 1);
                renderPinnedWheel();
            }
            View target = getPinnedWheelCenterChild();
            if (target != null) target.post(target::requestFocus);
        } else if (restorePinnedFocus && pinnedAppsWheel.getChildCount() == 0) {
            carouselView.requestFocus();
        }
        Toast.makeText(this, pinned.contains(item.getPackageName()) ? "Aplicación fijada en acceso rápido" : "Aplicación retirada de acceso rápido", Toast.LENGTH_SHORT).show();
    }

    private void refreshPinnedAppsWheel() {
        if (pinnedAppsWheel == null || pinnedAppsScroll == null || discoveryManager == null) return;

        String previouslySelectedPackage = null;
        if (!pinnedWheelItems.isEmpty()) {
            int oldIndex = Math.floorMod(pinnedWheelSelectedIndex, pinnedWheelItems.size());
            previouslySelectedPackage = pinnedWheelItems.get(oldIndex).getPackageName();
        }

        pinnedWheelItems.clear();
        java.util.Set<String> pinned = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getStringSet(KEY_PINNED_APPS, java.util.Collections.emptySet());
        List<AppItem> all = discoveryManager.discoverApps(pinned);
        for (AppItem candidate : all) {
            if (candidate != null && pinned.contains(candidate.getPackageName())) {
                pinnedWheelItems.add(candidate);
            }
        }

        if (pinnedWheelItems.isEmpty()) {
            pinnedWheelSelectedIndex = 0;
            pinnedAppsWheel.removeAllViews();
            pinnedAppsWheel.setVisibility(View.GONE);
            pinnedAppsScroll.setVisibility(View.GONE);
            return;
        }

        int preservedIndex = -1;
        if (previouslySelectedPackage != null) {
            for (int i = 0; i < pinnedWheelItems.size(); i++) {
                if (previouslySelectedPackage.equals(pinnedWheelItems.get(i).getPackageName())) {
                    preservedIndex = i;
                    break;
                }
            }
        }
        if (preservedIndex >= 0) pinnedWheelSelectedIndex = preservedIndex;
        else pinnedWheelSelectedIndex = Math.min(pinnedWheelSelectedIndex, pinnedWheelItems.size() - 1);

        pinnedAppsScroll.setVisibility(View.VISIBLE);
        pinnedAppsWheel.setVisibility(View.VISIBLE);
        renderPinnedWheel();
    }

    /**
     * Render only the five items on the visible half of the wheel. The wheel's
     * center is at the physical left edge; its front half arcs into the screen,
     * while its other half travels beyond the left edge during rotation.
     */
    private void renderPinnedWheel() {
        if (pinnedAppsWheel == null) return;
        View currentFocus = getCurrentFocus();
        boolean restoreWheelFocus = currentFocus != null && isDescendantOf(currentFocus, pinnedAppsWheel);
        rebuildingPinnedWheel = true;
        // Rebuild only for pin/list changes; normal D-pad rotation reuses attached views.
        pinnedAppsWheel.removeAllViews();
        pinnedWheelSlotViews.clear();
        int total = pinnedWheelItems.size();
        if (total == 0) {
            pinnedWheelAnimating = false;
            pinnedAppsWheel.setVisibility(View.GONE);
            if (pinnedAppsScroll != null) pinnedAppsScroll.setVisibility(View.GONE);
            rebuildingPinnedWheel = false;
            if (focusPositionDot != null) focusPositionDot.setVisibility(View.GONE);
            return;
        }
        int visibleCount = Math.min(PINNED_WHEEL_VISIBLE_ITEMS, total);
        int centerSlot = visibleCount / 2;
        for (int slot = 0; slot < visibleCount; slot++) {
            int itemIndex = Math.floorMod(pinnedWheelSelectedIndex + slot - centerSlot, total);
            ImageView iconView = createPinnedWheelIcon(pinnedWheelItems.get(itemIndex));
            pinnedWheelSlotViews.add(iconView);
            pinnedAppsWheel.addView(iconView, new FrameLayout.LayoutParams(dp(48), dp(48)));
            placePinnedWheelIcon(iconView, pinnedWheelAngle(slot, visibleCount));
        }
        pinnedAppsWheel.post(() -> {
            layoutPinnedWheelIcons();
            View center = getPinnedWheelCenterChild();
            if (restoreWheelFocus && center != null) {
                center.requestFocus();
                updateFocusPositionDot(center);
            }
            rebuildingPinnedWheel = false;
        });
    }

    private ImageView createPinnedWheelIcon(AppItem item) {
        ImageView iconView = new ImageView(this);
        iconView.setId(View.generateViewId());
        iconView.setTag(item);
        iconView.setContentDescription(item.getLabel());
        iconView.setFocusable(true);
        iconView.setClickable(true);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int padding = dp(7);
        iconView.setPadding(padding, padding, padding, padding);
        iconView.setBackgroundResource(R.drawable.pinned_app_icon_bg);
        if (item.getIcon() != null) iconView.setImageDrawable(item.getIcon());
        // Keep the five compact wheel layers warm across D-pad moves. Recreating them after
        // an idle timeout caused unnecessary GPU-layer uploads on the next keypress.
        iconView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        iconView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                applyAppFocusGlow(iconView, (AppItem) iconView.getTag());
                updateFocusPositionDot(iconView);
            } else {
                iconView.setBackgroundResource(R.drawable.pinned_app_icon_bg);
                iconView.setElevation(0f);
            }
        });
        final boolean[] longPressHandled = {false};
        iconView.setOnClickListener(v -> {
            if (!pinnedWheelAnimating) launchApp((AppItem) v.getTag());
        });
        iconView.setOnLongClickListener(v -> {
            if (pinnedWheelAnimating) return true;
            if (!longPressHandled[0]) {
                longPressHandled[0] = true;
                AppItem focusedItem = (AppItem) v.getTag();
                if (focusedItem != null) showAppOptions(focusedItem);
            }
            return true;
        });
        iconView.setOnKeyListener((v, keyCode, event) -> {
            if (pinnedWheelAnimating) return true;

            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    rotatePinnedWheel(keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                }
                return true;
            }

            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
                if (!longPressHandled[0]) {
                    longPressHandled[0] = true;
                    AppItem focusedItem = (AppItem) iconView.getTag();
                    if (focusedItem != null) showAppOptions(focusedItem);
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (longPressHandled[0]) longPressHandled[0] = false;
                else if (event.getRepeatCount() == 0) {
                    AppItem focusedItem = (AppItem) iconView.getTag();
                    if (focusedItem != null) launchApp(focusedItem);
                }
                return true;
            }
            return true;
        });
        iconView.setNextFocusUpId(iconView.getId());
        iconView.setNextFocusDownId(iconView.getId());
        iconView.setNextFocusLeftId(iconView.getId());
        iconView.setNextFocusRightId(R.id.app_carousel);
        return iconView;
    }

    private float pinnedWheelAngle(int slot, int visibleCount) {
        if (visibleCount <= 1) return 0f;
        return (float) (-Math.PI / 2.0 + (Math.PI * slot / (visibleCount - 1)));
    }

    private float[] pinnedWheelTransform(float angle) {
        float[] transform = new float[4];
        fillPinnedWheelTransform(angle, transform);
        return transform;
    }

    /** Fills a caller-owned transform so repeated wheel navigation does not allocate arrays. */
    private void fillPinnedWheelTransform(float angle, float[] transform) {
        if (transform == null || transform.length < 4) return;
        float iconSize = dp(48);
        float viewportHeight = pinnedAppsWheel != null && pinnedAppsWheel.getHeight() > 0
                ? pinnedAppsWheel.getHeight() : dp(310);
        // Keep the existing arc geometry and visual scale exactly the same.
        float radius = Math.max(dp(1), (viewportHeight - iconSize) / 2f) * 0.82f;
        float centerX = iconSize / 2f;
        float centerY = viewportHeight / 2f;
        transform[0] = centerX + radius * (float) Math.cos(angle) - iconSize / 2f;
        transform[1] = centerY + radius * (float) Math.sin(angle) - iconSize / 2f;
        transform[2] = 0.82f + 0.18f * Math.max(0f, (float) Math.cos(angle));
        transform[3] = Math.max(-18f, Math.min(18f,
                -angle * 18f / (float) (Math.PI / 2.0)));
    }

    private void applyPinnedWheelTransform(View icon, float[] transform) {
        if (icon == null || transform == null) return;
        icon.setX(transform[0]);
        icon.setY(transform[1]);
        icon.setScaleX(transform[2]);
        icon.setScaleY(transform[2]);
        icon.setRotationY(transform[3]);
        icon.setAlpha(1f);
    }

    private void applyInterpolatedPinnedWheelTransform(View icon, float[] from, float[] to, float progress) {
        if (icon == null || from == null || to == null) return;
        icon.setX(from[0] + (to[0] - from[0]) * progress);
        icon.setY(from[1] + (to[1] - from[1]) * progress);
        float scale = from[2] + (to[2] - from[2]) * progress;
        icon.setScaleX(scale);
        icon.setScaleY(scale);
        icon.setRotationY(from[3] + (to[3] - from[3]) * progress);
        icon.setAlpha(1f);
    }

    private void placePinnedWheelIcon(View icon, float angle) {
        if (pinnedAppsWheel == null || icon == null) return;
        applyPinnedWheelTransform(icon, pinnedWheelTransform(angle));
    }

    private void layoutPinnedWheelIcons() {
        int count = pinnedWheelSlotViews.size();
        for (int slot = 0; slot < count; slot++) placePinnedWheelIcon(pinnedWheelSlotViews.get(slot), pinnedWheelAngle(slot, count));
    }

    private View getPinnedWheelCenterChild() {
        return pinnedWheelSlotViews.isEmpty() ? null : pinnedWheelSlotViews.get(pinnedWheelSlotViews.size()/2);
    }

    private void rotatePinnedWheel(int delta) {
        if (pinnedWheelAnimating || pinnedAppsWheel == null || pinnedWheelItems.isEmpty()
                || (delta != -1 && delta != 1)) return;
        int count = pinnedWheelSlotViews.size();
        if (count <= 1) return;
        int newSelection = Math.floorMod(pinnedWheelSelectedIndex + delta, pinnedWheelItems.size());
        float step = (float)(Math.PI / (count - 1));
        int centerSlot = count / 2;
        int incomingSlot = delta > 0 ? count - 1 : 0;
        float incomingAngle = pinnedWheelAngle(incomingSlot, count) + delta * step;
        float targetAngle = pinnedWheelAngle(incomingSlot, count);
        int incomingItemIndex = Math.floorMod(newSelection + incomingSlot - centerSlot, pinnedWheelItems.size());
        ImageView incoming = createPinnedWheelIcon(pinnedWheelItems.get(incomingItemIndex));
        incoming.setFocusable(false);
        incoming.setClickable(false);
        pinnedAppsWheel.addView(incoming, new FrameLayout.LayoutParams(dp(48), dp(48)));

        for (int i = 0; i < count; i++) {
            fillPinnedWheelTransform(pinnedWheelAngle(i, count), pinnedWheelFromTransforms[i]);
            fillPinnedWheelTransform(
                    pinnedWheelAngle(i, count) - delta * step, pinnedWheelToTransforms[i]);
            pinnedWheelSlotViews.get(i).setClickable(false);
        }
        fillPinnedWheelTransform(incomingAngle, pinnedWheelIncomingFromTransform);
        fillPinnedWheelTransform(targetAngle, pinnedWheelIncomingToTransform);
        applyPinnedWheelTransform(incoming, pinnedWheelIncomingFromTransform);

        // All attached wheel cards already own stable hardware layers; do not toggle them.
        pinnedWheelAnimating = true;
        lastPinnedDotUpdateAtMs = 0L;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(280);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            // Geometry is computed once per keypress; frames perform interpolation only.
            for (int i = 0; i < pinnedWheelSlotViews.size(); i++) {
                applyInterpolatedPinnedWheelTransform(pinnedWheelSlotViews.get(i),
                        pinnedWheelFromTransforms[i], pinnedWheelToTransforms[i], t);
            }
            applyInterpolatedPinnedWheelTransform(incoming, pinnedWheelIncomingFromTransform,
                    pinnedWheelIncomingToTransform, t);
            // Avoid global-visible-rectangle traversal on every animation frame.
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastPinnedDotUpdateAtMs >= 50L) {
                lastPinnedDotUpdateAtMs = now;
                View focused = getCurrentFocus();
                if (focused != null && isDescendantOf(focused, pinnedAppsWheel)) {
                    updateFocusPositionDot(focused);
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                ImageView outgoing = PinnedWheelSlotOrder.outgoing(pinnedWheelSlotViews, delta);
                List<ImageView> nextSlots = PinnedWheelSlotOrder.rotate(pinnedWheelSlotViews, incoming, delta);
                incoming.setFocusable(true);
                incoming.setClickable(true);
                pinnedWheelSlotViews.clear();
                pinnedWheelSlotViews.addAll(nextSlots);
                // Shift focus to the next center while all existing card views are still attached.
                View nextCenter=getPinnedWheelCenterChild();
                if(nextCenter!=null) {
                    nextCenter.requestFocus();
                    updateFocusPositionDot(nextCenter);
                }
                if (outgoing != null) pinnedAppsWheel.removeView(outgoing);
                for(ImageView card:pinnedWheelSlotViews) {
                    card.setFocusable(true);
                    card.setClickable(true);
                    pinnedAppsWheel.bringChildToFront(card);
                }
                pinnedWheelSelectedIndex=newSelection;
                pinnedWheelAnimating=false;
            }
        });
        animator.start();
    }

    private int getAppIconAccent(AppItem item) {
        if (item == null) return IconAccentSampler.sample(null);
        String packageName = item.getPackageName();
        if (packageName != null) {
            Integer cached = iconAccentCache.get(packageName);
            if (cached != null) return cached;
        }
        // The source Drawable is read only for color sampling. No tint/filter is applied.
        int accent = IconAccentSampler.sample(item.getIcon());
        if (packageName != null) iconAccentCache.put(packageName, accent);
        return accent;
    }

    private void updateFocusPositionDot(View anchor) {
        updateFocusPositionDot(anchor, false);
    }

    private void updateFocusPositionDot(View anchor, boolean placeOnLeftEdge) {
        if (focusDotTransitionInProgress || focusPositionDot == null
                || rootContainer == null || anchor == null) return;

        View target = anchor;
        boolean fixedCarouselSlot = false;
        boolean usesSelectionAccent = false;
        int selectionAccent = currentHudAccent;

        if (carouselView != null && (anchor == carouselView || isDescendantOf(anchor, carouselView))) {
            target = carouselView.getSelectedCardView();
            fixedCarouselSlot = true;
            usesSelectionAccent = true;
            selectionAccent = carouselView.getSelectedAccentColor();
        } else if (libraryCarousel != null
                && (anchor == libraryCarousel || isDescendantOf(anchor, libraryCarousel))) {
            target = libraryCarousel.getSelectedCardView();
            fixedCarouselSlot = true;
            usesSelectionAccent = true;
            selectionAccent = libraryCarousel.getSelectedAccentColor();
        } else if (pinnedAppsWheel != null
                && (anchor == pinnedAppsWheel || isDescendantOf(anchor, pinnedAppsWheel))) {
            View pinnedTarget = anchor == pinnedAppsWheel ? getPinnedWheelCenterChild() : anchor;
            if (pinnedTarget != null) target = pinnedTarget;
            Object tag = target.getTag();
            if (tag instanceof AppItem) {
                selectionAccent = getAppIconAccent((AppItem) tag);
                usesSelectionAccent = true;
            }
        } else if (continueWatchingWheel != null
                && (anchor == continueWatchingWheel || isDescendantOf(anchor, continueWatchingWheel))) {
            selectionAccent = continueWatchingWheel.getAccentColorForTarget(anchor);
            usesSelectionAccent = true;
        }

        if (target == null || target.getVisibility() != View.VISIBLE) {
            focusPositionUsesSelectionAccent = usesSelectionAccent;
            focusPositionDot.setVisibility(View.GONE);
            return;
        }

        // Selection in either app carousel is always represented at the fixed center slot.
        // Do not chase a card through every animation frame; that geometry work caused jank.
        if (fixedCarouselSlot) {
            View carouselAnchor = carouselView != null
                    && (anchor == carouselView || isDescendantOf(anchor, carouselView))
                    ? carouselView : libraryCarousel;
            if (carouselAnchor == null || !carouselAnchor.getGlobalVisibleRect(focusVisibleRect)) {
                focusPositionDot.setVisibility(View.GONE);
                return;
            }
        } else if (!target.getGlobalVisibleRect(focusVisibleRect)) {
            focusPositionDot.setVisibility(View.GONE);
            return;
        }

        rootContainer.getLocationOnScreen(focusRootLocation);
        int dotSize = dp(10);
        float edgeX;
        float centerY;
        if (fixedCarouselSlot) {
            float centerX = focusVisibleRect.centerX() - focusRootLocation[0];
            centerY = focusVisibleRect.centerY() - focusRootLocation[1];
            float halfCardWidth = dp(59); // half of the carousel's 118dp center card
            edgeX = centerX + (placeOnLeftEdge ? -halfCardWidth : halfCardWidth);
        } else {
            edgeX = (placeOnLeftEdge ? focusVisibleRect.left : focusVisibleRect.right)
                    - focusRootLocation[0];
            centerY = focusVisibleRect.centerY() - focusRootLocation[1];
        }
        float proposedX = edgeX - dotSize / 2f;
        float proposedY = centerY - dotSize / 2f;
        float maxX = rootContainer.getWidth() - dotSize;
        float maxY = rootContainer.getHeight() - dotSize;
        if (proposedX < 0 || proposedX > maxX || proposedY < 0 || proposedY > maxY) {
            focusPositionUsesSelectionAccent = usesSelectionAccent;
            focusPositionDot.setVisibility(View.GONE);
            return;
        }

        focusPositionUsesSelectionAccent = usesSelectionAccent;
        if (focusPositionDotBackground != null) {
            int markerColor = usesSelectionAccent ? selectionAccent : currentHudAccent;
            android.content.res.ColorStateList existingColor = focusPositionDotBackground.getColor();
            if (existingColor == null || existingColor.getDefaultColor() != markerColor) {
                // The attached background invalidates its host view when its color changes.
                // Reassigning the static white stroke and invalidating the view again is redundant.
                focusPositionDotBackground.setColor(markerColor);
            }
        }
        focusPositionDot.setX(proposedX);
        focusPositionDot.setY(proposedY);
        focusPositionDot.setVisibility(View.VISIBLE);
    }

    /**
     * Sample the just-selected wallpaper off the UI thread and adapt the HUD accent.
     * Sampling is intentionally tiny and runs only after a wallpaper transition/resume,
     * not on every animation frame.
     */
    private void scheduleAdaptiveHudColors() {
        if (backgroundManager == null) return;
        Drawable current = backgroundManager.getCurrentBackgroundDrawable();
        if (current == null || current.getConstantState() == null) return;
        Drawable snapshot = current.getConstantState().newDrawable(getResources()).mutate();
        int request = ++hudColorGeneration;

        // Palette results from obsolete wallpapers are discarded anyway; cancel them before
        // sampling so rapid/manual changes do not leave CPU work queued behind stale images.
        cancelHudColorSampleTask();
        try {
            hudColorSampleTask = HUD_COLOR_EXECUTOR.submit(() -> {
                if (Thread.currentThread().isInterrupted() || request != hudColorGeneration) return;
                long sampleStartedAt = android.os.SystemClock.elapsedRealtime();
                int accent = sampleHudAccent(snapshot);
                Log.d(TAG, "Wallpaper HUD palette sample took "
                        + (android.os.SystemClock.elapsedRealtime() - sampleStartedAt) + " ms");
                if (Thread.currentThread().isInterrupted() || request != hudColorGeneration) return;
                runOnUiThread(() -> {
                    if (request != hudColorGeneration || isFinishing() || isDestroyed()) return;
                    hudColorSampleTask = null;
                    animateAdaptiveHudAccent(accent);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            hudColorSampleTask = null;
        }
    }

    private void cancelHudColorSampleTask() {
        java.util.concurrent.Future<?> task = hudColorSampleTask;
        hudColorSampleTask = null;
        if (task == null) return;
        task.cancel(true);
        // Future returned by ThreadPoolExecutor.submit is a RunnableFuture. Removing it avoids
        // draining cancelled wallpaper samples from the unbounded queue one by one later.
        if (task instanceof Runnable) HUD_COLOR_EXECUTOR.remove((Runnable) task);
    }

    private int sampleHudAccent(Drawable wallpaper) {
        Bitmap sample = null;
        try {
            final int sampleWidth = 32;
            final int sampleHeight = 18;
            final int binCount = 24; // 15-degree hue buckets soften small hue variations.
            sample = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sample);
            wallpaper.setBounds(0, 0, sampleWidth, sampleHeight);
            wallpaper.draw(canvas);

            // Pick the dominant family of usable colors instead of averaging unrelated hues
            // across the entire wallpaper (which can create an artificial neon/grey midpoint).
            double[] hueX = new double[binCount];
            double[] hueY = new double[binCount];
            double[] saturationSum = new double[binCount];
            double[] valueSum = new double[binCount];
            double[] weightSum = new double[binCount];
            float[] hsv = new float[3];
            int[] pixels = new int[sampleWidth * sampleHeight];
            sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight);

            for (int pixel : pixels) {
                if (Thread.currentThread().isInterrupted()) return Color.rgb(155, 203, 215);
                if (Color.alpha(pixel) < 96) continue;
                Color.colorToHSV(pixel, hsv);
                // Ignore near-neutral highlights, crushed shadows and clipped whites.
                if (hsv[1] < 0.18f || hsv[2] < 0.16f || hsv[2] > 0.94f) continue;

                int bin = Math.min(binCount - 1, (int) (hsv[0] / (360f / binCount)));
                double weight = hsv[1] * (0.55d + 0.45d * (1d - Math.abs(hsv[2] - 0.55d)));
                double radians = Math.toRadians(hsv[0]);
                hueX[bin] += Math.cos(radians) * weight;
                hueY[bin] += Math.sin(radians) * weight;
                saturationSum[bin] += hsv[1] * weight;
                valueSum[bin] += hsv[2] * weight;
                weightSum[bin] += weight;
            }

            int dominantBin = 0;
            for (int i = 1; i < binCount; i++) {
                if (weightSum[i] > weightSum[dominantBin]) dominantBin = i;
            }

            // Blend the winning bucket with its immediate neighbors so the result does not
            // flicker when a wallpaper color sits close to a bucket boundary.
            double sumHueX = 0d, sumHueY = 0d, sumSaturation = 0d, sumValue = 0d, sumWeight = 0d;
            for (int offset = -1; offset <= 1; offset++) {
                int bin = (dominantBin + offset + binCount) % binCount;
                sumHueX += hueX[bin];
                sumHueY += hueY[bin];
                sumSaturation += saturationSum[bin];
                sumValue += valueSum[bin];
                sumWeight += weightSum[bin];
            }

            if (sumWeight <= 0.0001d) return Color.rgb(155, 203, 215);
            double hue = Math.toDegrees(Math.atan2(sumHueY, sumHueX));
            if (hue < 0d) hue += 360d;

            // Keep the wallpaper hue, but reduce saturation and brightness so HUD text/glass
            // complements the image rather than looking like a saturated overlay.
            float saturation = (float) Math.max(0.26d, Math.min(0.48d,
                    (sumSaturation / sumWeight) * 0.78d));
            float value = (float) Math.max(0.78d, Math.min(0.90d, sumValue / sumWeight));
            return Color.HSVToColor(new float[] {(float) hue, saturation, value});
        } catch (Exception e) {
            Log.d(TAG, "Could not sample wallpaper accent; using a muted cool fallback", e);
            return Color.rgb(155, 203, 215);
        } finally {
            if (sample != null && !sample.isRecycled()) sample.recycle();
        }
    }

    /**
     * Smoothly blend wallpaper-derived accents with bounded redraw work.
     * HSV endpoints are computed once and UI properties update at most 30 times/second.
     */
    private void animateAdaptiveHudAccent(int targetAccent) {
        if (hudColorAnimator != null) {
            hudColorAnimator.cancel();
            hudColorAnimator = null;
        }
        final int startAccent = currentHudAccent;
        int colorDistance = Math.abs(Color.red(startAccent) - Color.red(targetAccent))
                + Math.abs(Color.green(startAccent) - Color.green(targetAccent))
                + Math.abs(Color.blue(startAccent) - Color.blue(targetAccent));
        // Avoid scheduling a full UI color animation for visually negligible changes.
        if (colorDistance < 30) {
            applyAdaptiveHudAccent(targetAccent);
            return;
        }

        Color.colorToHSV(startAccent, hudAnimationStartHsv);
        Color.colorToHSV(targetAccent, hudAnimationTargetHsv);
        float hueDelta = hudAnimationTargetHsv[0] - hudAnimationStartHsv[0];
        if (hueDelta > 180f) hueDelta -= 360f;
        else if (hueDelta < -180f) hueDelta += 360f;
        final float targetHueDelta = hueDelta;
        final float targetSaturationDelta = hudAnimationTargetHsv[1] - hudAnimationStartHsv[1];
        final float targetValueDelta = hudAnimationTargetHsv[2] - hudAnimationStartHsv[2];
        lastHudAccentFrameAtMs = 0L;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        hudColorAnimator = animator;
        // Keep hue adaptation smooth but brief, avoiding a second long redraw burst
        // after the full-screen wallpaper transition.
        animator.setDuration(360L);
        animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            long now = android.os.SystemClock.uptimeMillis();
            if (fraction < 1f && now - lastHudAccentFrameAtMs < 66L) return;
            lastHudAccentFrameAtMs = now;

            hudAnimationFrameHsv[0] = (hudAnimationStartHsv[0] + fraction * targetHueDelta + 360f) % 360f;
            hudAnimationFrameHsv[1] = hudAnimationStartHsv[1] + fraction * targetSaturationDelta;
            hudAnimationFrameHsv[2] = hudAnimationStartHsv[2] + fraction * targetValueDelta;
            applyAdaptiveHudAccent(Color.HSVToColor(hudAnimationFrameHsv));
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                // A cancelled animation can also receive onAnimationEnd. Only the active
                // animator may commit its target, otherwise an obsolete wallpaper tint jumps in.
                if (hudColorAnimator != animator) return;
                hudColorAnimator = null;
                applyAdaptiveHudAccent(targetAccent);
            }
            @Override public void onAnimationCancel(Animator animation) {
                if (hudColorAnimator == animator) hudColorAnimator = null;
            }
        });
        animator.start();
    }

    /** Update adaptive HUD properties only when their rendered values actually change. */
    private void applyAdaptiveHudAccent(int accent) {
        if (hudAccentApplied && accent == currentHudAccent) return;
        currentHudAccent = accent;
        hudAccentApplied = true;
        int r = Color.red(accent);
        int g = Color.green(accent);
        int b = Color.blue(accent);
        Color.colorToHSV(accent, hudAccentHsv);
        // Keep HUD text bright for TV-distance readability while following the wallpaper hue.
        hudClockTextHsv[0] = hudAccentHsv[0];
        hudClockTextHsv[1] = Math.max(0.12f, Math.min(0.30f, hudAccentHsv[1] * 0.62f));
        hudClockTextHsv[2] = 0.98f;
        int clockTextColor = Color.HSVToColor(hudClockTextHsv);
        hudWeatherTextHsv[0] = hudAccentHsv[0];
        hudWeatherTextHsv[1] = Math.max(0.18f, Math.min(0.40f, hudAccentHsv[1] * 0.90f));
        hudWeatherTextHsv[2] = 0.96f;
        int weatherTextColor = Color.HSVToColor(hudWeatherTextHsv);

        if (focusPositionDotBackground != null && !focusPositionUsesSelectionAccent) {
            android.content.res.ColorStateList existingColor = focusPositionDotBackground.getColor();
            if (existingColor == null || existingColor.getDefaultColor() != accent) {
                // setColor() invalidates the attached view through Drawable.Callback.
                focusPositionDotBackground.setColor(accent);
            }
        }
        if (tvClock != null) {
            if (tvClock.getCurrentTextColor() != clockTextColor) tvClock.setTextColor(clockTextColor);
            int shadowColor = Color.argb(118, r, g, b);
            if (lastClockShadowColor != shadowColor) {
                tvClock.setShadowLayer(dp(6), 0f, 0f, shadowColor);
                lastClockShadowColor = shadowColor;
            }
        }
        if (tvGreeting != null && tvGreeting.getCurrentTextColor() != accent) {
            tvGreeting.setTextColor(accent);
        }
        if (tvDate != null) {
            if (tvDate.getCurrentTextColor() != accent) tvDate.setTextColor(accent);
            int shadowColor = Color.argb(92, r, g, b);
            if (lastDateShadowColor != shadowColor) {
                tvDate.setShadowLayer(dp(3), 0f, 0f, shadowColor);
                lastDateShadowColor = shadowColor;
            }
        }
        if (tvWeather != null) {
            if (tvWeather.getCurrentTextColor() != weatherTextColor) tvWeather.setTextColor(weatherTextColor);
            hudGlassColors[0] = Color.argb(24, r, g, b);
            hudGlassColors[1] = Color.argb(88, 8, 14, 26);
            hudGlassColors[2] = Color.argb(36, r, g, b);
            if (weatherGlassBackground == null) {
                weatherGlassBackground = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM, hudGlassColors.clone());
                weatherGlassBackground.setCornerRadius(dp(14));
                tvWeather.setBackground(weatherGlassBackground);
                System.arraycopy(hudGlassColors, 0, lastAppliedHudGlassColors, 0, hudGlassColors.length);
            } else if (lastAppliedHudGlassColors[0] != hudGlassColors[0]
                    || lastAppliedHudGlassColors[1] != hudGlassColors[1]
                    || lastAppliedHudGlassColors[2] != hudGlassColors[2]) {
                weatherGlassBackground.setColors(hudGlassColors);
                System.arraycopy(hudGlassColors, 0, lastAppliedHudGlassColors, 0, hudGlassColors.length);
            }
            int strokeColor = Color.argb(92, r, g, b);
            if (lastWeatherGlassStrokeColor != strokeColor) {
                weatherGlassBackground.setStroke(Math.max(1, dp(1)), strokeColor);
                lastWeatherGlassStrokeColor = strokeColor;
            }
            int shadowColor = Color.argb(72, r, g, b);
            if (lastWeatherShadowColor != shadowColor) {
                tvWeather.setShadowLayer(dp(2), 0f, 0f, shadowColor);
                lastWeatherShadowColor = shadowColor;
            }
        }
    }

    private void setupTopControlIllumination(View... controls) {
        if (controls == null) return;
        for (View control : controls) {
            if (control == null) continue;
            control.setBackground(createControlCardGlow(control.hasFocus()));
            control.setElevation(dp(control.hasFocus() ? 10 : 4));
            control.setOnFocusChangeListener((view, hasFocus) -> {
                view.setBackground(createControlCardGlow(hasFocus));
                view.setElevation(dp(hasFocus ? 10 : 4));
                view.setTranslationZ(dp(hasFocus ? 3 : 0));
            });
            applyControlTextGlow(control);
        }
    }

    private Drawable createControlCardGlow(boolean focused) {
        int edgeOuter = focused ? 90 : 30;
        int edgeMiddle = focused ? 155 : 72;
        int edgeInner = focused ? 238 : 132;
        int fillOuter = focused ? 18 : 7;
        int fillMiddle = focused ? 20 : 9;
        int fillInner = focused ? 32 : 14;

        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.RECTANGLE);
        outer.setCornerRadius(dp(23));
        outer.setColor(android.graphics.Color.argb(fillOuter, 120, 105, 255));
        outer.setStroke(dp(focused ? 2 : 1), android.graphics.Color.argb(edgeOuter, 125, 125, 255));

        GradientDrawable middle = new GradientDrawable();
        middle.setShape(GradientDrawable.RECTANGLE);
        middle.setCornerRadius(dp(20));
        middle.setColor(android.graphics.Color.argb(fillMiddle, 115, 170, 255));
        middle.setStroke(dp(1), android.graphics.Color.argb(edgeMiddle, 155, 185, 255));

        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.RECTANGLE);
        inner.setCornerRadius(dp(18));
        inner.setColor(android.graphics.Color.argb(fillInner, 255, 255, 255));
        inner.setStroke(dp(focused ? 2 : 1), android.graphics.Color.argb(edgeInner, 218, 210, 255));

        android.graphics.drawable.LayerDrawable glow =
                new android.graphics.drawable.LayerDrawable(new Drawable[]{outer, middle, inner});
        glow.setLayerInset(0, -dp(focused ? 6 : 3), -dp(focused ? 6 : 3),
                -dp(focused ? 6 : 3), -dp(focused ? 6 : 3));
        glow.setLayerInset(1, -dp(focused ? 3 : 1), -dp(focused ? 3 : 1),
                -dp(focused ? 3 : 1), -dp(focused ? 3 : 1));
        return glow;
    }

    private void applyControlTextGlow(View control) {
        if (!(control instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) control;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                TextView label = (TextView) child;
                label.setShadowLayer(dp(3), 0f, 0f, 0xB0D6D0FF);
            } else if (child instanceof ViewGroup) {
                applyControlTextGlow(child);
            }
        }
    }

    private void applyAppFocusGlow(ImageView view, AppItem item) {
        int color = getCachedAppFocusGlowAccent(item);
        int red = android.graphics.Color.red(color), green = android.graphics.Color.green(color), blue = android.graphics.Color.blue(color);
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(android.graphics.Color.argb(10, red, green, blue));
        outer.setStroke(dp(1), android.graphics.Color.argb(65, red, green, blue));
        GradientDrawable middle = new GradientDrawable();
        middle.setShape(GradientDrawable.OVAL);
        middle.setColor(android.graphics.Color.argb(18, red, green, blue));
        middle.setStroke(dp(1), android.graphics.Color.argb(130, red, green, blue));
        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(android.graphics.Color.argb(32, red, green, blue));
        inner.setStroke(dp(2), color);
        android.graphics.drawable.LayerDrawable halo = new android.graphics.drawable.LayerDrawable(new Drawable[]{outer, middle, inner});
        halo.setLayerInset(0, -dp(5), -dp(5), -dp(5), -dp(5));
        halo.setLayerInset(1, -dp(2), -dp(2), -dp(2), -dp(2));
        view.setBackground(halo);
        view.setElevation(dp(8));
    }

    private int getCachedAppFocusGlowAccent(AppItem item) {
        if (item == null) return sampleAppColor(null);
        String packageName = item.getPackageName();
        if (packageName != null) {
            Integer cached = appFocusGlowAccentCache.get(packageName);
            if (cached != null) return cached;
        }
        int color = sampleAppColor(item.getIcon());
        if (packageName != null) appFocusGlowAccentCache.put(packageName, color);
        return color;
    }

    private int sampleAppColor(Drawable source) {
        if (source == null) return android.graphics.Color.rgb(120, 170, 255);
        Bitmap bitmap = null;
        try {
            Drawable drawable = source.getConstantState() == null ? source : source.getConstantState().newDrawable().mutate();
            bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, 24, 24);
            drawable.draw(canvas);
            int[] pixels = new int[24 * 24];
            bitmap.getPixels(pixels, 0, 24, 0, 0, 24, 24);
            long red = 0, green = 0, blue = 0, count = 0;
            for (int pixel : pixels) {
                if (android.graphics.Color.alpha(pixel) < 96) continue;
                int r = android.graphics.Color.red(pixel), g = android.graphics.Color.green(pixel), b = android.graphics.Color.blue(pixel);
                int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                if (max - min < 24 && max < 210) continue;
                red += r; green += g; blue += b; count++;
            }
            if (count == 0) return android.graphics.Color.rgb(120, 170, 255);
            return android.graphics.Color.rgb((int)(red / count), (int)(green / count), (int)(blue / count));
        } catch (Exception e) {
            return android.graphics.Color.rgb(120, 170, 255);
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private int getCenteredWheelTop() {
        int screenHeight = rootContainer != null && rootContainer.getHeight() > 0
                ? rootContainer.getHeight() : getResources().getDisplayMetrics().heightPixels;
        return (screenHeight - getWheelViewportHeight()) / 2;
    }

    private int getWheelViewportHeight() {
        int screenHeight = rootContainer != null && rootContainer.getHeight() > 0
                ? rootContainer.getHeight() : getResources().getDisplayMetrics().heightPixels;
        return Math.min(dp(310), Math.max(dp(220), screenHeight - dp(80)));
    }

    private void updatePinnedAppsViewportBounds() {
        if (safeContainer == null || pinnedAppsScroll == null || rootContainer == null) return;
        int viewportHeight = getWheelViewportHeight();
        int top = getCenteredWheelTop();
        int railWidth = Math.min(dp(184), Math.max(dp(180), rootContainer.getWidth() / 10));
        if (!(pinnedAppsScroll.getLayoutParams() instanceof RelativeLayout.LayoutParams)) return;
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) pinnedAppsScroll.getLayoutParams();
        int topMargin = top - safeContainer.getPaddingTop();
        // Shift a few dp past the bezel so the semicircle feels physically edge-attached.
        int leftMargin = -safeContainer.getPaddingLeft() - dp(8);
        if (lp.height != viewportHeight || lp.topMargin != topMargin || lp.width != railWidth
                || lp.leftMargin != leftMargin) {
            lp.height = viewportHeight;
            lp.width = railWidth;
            lp.topMargin = topMargin;
            lp.leftMargin = leftMargin;
            lp.bottomMargin = 0;
            lp.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE);
            lp.removeRule(RelativeLayout.ALIGN_PARENT_START);
            lp.removeRule(RelativeLayout.BELOW);
            lp.removeRule(RelativeLayout.ABOVE);
            pinnedAppsScroll.setLayoutParams(lp);
            pinnedAppsScroll.post(this::layoutPinnedWheelIcons);
        }
    }

    private void refreshContinueWatchingWheel() {
        if (continueWatchingWheel == null) return;
        continueWatchingWheel.refreshFromSystem();
        updateContinueWatchingSectionVisibility();
        updateContinueWatchingViewportBounds();
    }

    private void updateContinueWatchingSectionVisibility() {
        boolean hasItems = continueWatchingWheel != null && continueWatchingWheel.hasItems();
        if (continueWatchingHeading != null) {
            continueWatchingHeading.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        }
    }

    private void updateContinueWatchingViewportBounds() {
        if (safeContainer == null || continueWatchingWheel == null || rootContainer == null) return;
        int viewportHeight = getWheelViewportHeight();
        int top = getCenteredWheelTop();
        int railWidth = dp(210);
        int rightMargin = -safeContainer.getPaddingRight() - dp(8);

        if (continueWatchingWheel.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) continueWatchingWheel.getLayoutParams();
            int topMargin = top - safeContainer.getPaddingTop();
            if (lp.height != viewportHeight || lp.topMargin != topMargin || lp.width != railWidth
                    || lp.rightMargin != rightMargin) {
                lp.height = viewportHeight;
                lp.width = railWidth;
                lp.topMargin = topMargin;
                lp.rightMargin = rightMargin;
                lp.bottomMargin = 0;
                lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, RelativeLayout.TRUE);
                lp.removeRule(RelativeLayout.ALIGN_PARENT_END);
                lp.removeRule(RelativeLayout.BELOW);
                lp.removeRule(RelativeLayout.ABOVE);
                continueWatchingWheel.setLayoutParams(lp);
            }
        }

        if (continueWatchingHeading != null
                && continueWatchingHeading.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams headingLp =
                    (RelativeLayout.LayoutParams) continueWatchingHeading.getLayoutParams();
            int headingTop = top - safeContainer.getPaddingTop() - dp(24);
            if (headingLp.width != railWidth || headingLp.height != dp(18)
                    || headingLp.topMargin != headingTop || headingLp.rightMargin != rightMargin) {
                headingLp.width = railWidth;
                headingLp.height = dp(18);
                headingLp.topMargin = headingTop;
                headingLp.rightMargin = rightMargin;
                headingLp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, RelativeLayout.TRUE);
                headingLp.removeRule(RelativeLayout.ALIGN_PARENT_END);
                continueWatchingHeading.setLayoutParams(headingLp);
            }
        }

    }

    /**
     * Prefer the publisher's own resume URI. If the publisher did not provide a usable URI,
     * open that publisher's launch activity rather than inventing a deep link or playback position.
     */
    private void launchContinueWatchingApp(WatchNextRepository.Entry entry) {
        if (entry == null) return;

        String intentUri = entry.getIntentUri();
        if (intentUri != null && !intentUri.trim().isEmpty()) {
            try {
                Intent resumeIntent = Intent.parseUri(intentUri.trim(), Intent.URI_INTENT_SCHEME);
                String action = resumeIntent.getAction();
                android.net.Uri data = resumeIntent.getData();
                String scheme = data == null ? null : data.getScheme();
                if (action == null && ("http".equalsIgnoreCase(scheme)
                        || "https".equalsIgnoreCase(scheme))) {
                    resumeIntent.setAction(Intent.ACTION_VIEW);
                    action = Intent.ACTION_VIEW;
                }
                if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_MAIN.equals(action)) {
                    resumeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(resumeIntent);
                    return;
                }
                Log.w(TAG, "Watch Next resume URI has an unsupported action for " + entry.getTitle());
            } catch (Exception e) {
                Log.w(TAG, "Could not open published Watch Next URI for " + entry.getTitle()
                        + "; trying its publisher app.", e);
            }
        }

        String packageName = entry.getPackageName();
        if (packageName != null && !packageName.trim().isEmpty()) {
            try {
                Intent appIntent = getPackageManager().getLaunchIntentForPackage(packageName.trim());
                if (appIntent != null) {
                    appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(appIntent);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not launch Watch Next publisher package " + packageName, e);
            }
        }

        Toast.makeText(this,
                "No se pudo abrir el contenido ni su aplicación de origen.",
                Toast.LENGTH_LONG).show();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showAppOptions(AppItem item) {
        if (item == null) return;
        View originFocus = getCurrentFocus();
        optionsOpenedFromPinnedWheel = originFocus != null && pinnedAppsWheel != null
                && isDescendantOf(originFocus, pinnedAppsWheel);
        optionsPinnedIndex = optionsOpenedFromPinnedWheel ? pinnedWheelSelectedIndex : -1;
        Dialog dialog = new Dialog(this);
        View panel = getLayoutInflater().inflate(R.layout.dialog_app_options, null, false);
        dialog.setContentView(panel);
        TextView title = panel.findViewById(R.id.app_options_title);
        TextView pin = panel.findViewById(R.id.app_option_pin);
        TextView details = panel.findViewById(R.id.app_option_details);
        TextView uninstall = panel.findViewById(R.id.app_option_uninstall);
        TextView cancel = panel.findViewById(R.id.app_option_cancel);
        title.setText(item.getLabel());
        pin.setText(isPinned(item) ? R.string.app_option_unpin : R.string.app_option_pin);
        pin.setOnClickListener(v -> { dialog.dismiss(); togglePinned(item); });
        details.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + item.getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "No se pudieron abrir los detalles de la aplicación", Toast.LENGTH_SHORT).show();
            }
        });
        uninstall.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                Intent intent = new Intent(Intent.ACTION_DELETE,
                        android.net.Uri.parse("package:" + item.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if (intent.resolveActivity(getPackageManager()) == null) {
                    Toast.makeText(this, "Android no ofrece desinstalación para esta aplicación del sistema", Toast.LENGTH_LONG).show();
                    return;
                }
                startActivity(intent);
            } catch (Exception e) {
                Log.w(TAG, "Unable to open package uninstaller for " + item.getPackageName(), e);
                Toast.makeText(this, "No se pudo abrir la desinstalación; puede ser una aplicación del sistema", Toast.LENGTH_LONG).show();
            }
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.show();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setDimAmount(0.18f);
            window.setLayout((int)(420 * getResources().getDisplayMetrics().density),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        pin.requestFocus();
    }

    private void launchApp(AppItem item) {
        if (item == null) return;
        try {
            Intent launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.setClassName(item.getPackageName(), item.getActivityName());
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launchIntent);
            // Record only after Android accepts the launch. System usage timestamps remain
            // authoritative whenever the user has granted Usage Access.
            if (discoveryManager != null) discoveryManager.recordAppLaunch(item.getPackageName());
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch application: " + item.getPackageName(), e);
            Toast.makeText(this, "No se pudo abrir " + item.getLabel(), Toast.LENGTH_SHORT).show();
        }
    }

    private void setupLibraryOverlay() {
        if (libraryCarousel == null || librarySelectedLabel == null) return;
        libraryCarousel.setSelectionListener(new AppCarouselView.OnCarouselSelectionListener() {
            @Override
            public void onItemSelected(int index, AppItem item) {
                if (item != null) librarySelectedLabel.setText(item.getLabel());
                if (libraryVisible && libraryCarousel != null) {
                    libraryCarousel.post(() -> updateFocusPositionDot(libraryCarousel.getSelectedCardView()));
                }
            }

            @Override
            public void onItemActivated(int index, AppItem item) {
                launchApp(item);
            }

            @Override
            public void onItemLongPressed(int index, AppItem item) {
                showAppOptions(item);
            }
        });
    }

    private boolean isDescendantOf(View child, ViewGroup parent) {
        android.view.ViewParent p = child.getParent();
        while (p != null) {
            if (p == parent) return true;
            if (!(p instanceof android.view.View)) break;
            p = p.getParent();
        }
        return false;
    }

    private int getTransitionHeight() {
        int height = findViewById(R.id.root_container).getHeight();
        return height > 0 ? height : getResources().getDisplayMetrics().heightPixels;
    }

    private void cancelPendingFocusDotUpdate() {
        ViewTreeObserver.OnPreDrawListener pending = focusDotPreDrawListener;
        focusDotPreDrawListener = null;
        if (pending == null || rootContainer == null) return;
        ViewTreeObserver observer = rootContainer.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(pending);
    }

    /** Keep the marker hidden while the two screen panels are translating. */
    private void beginLibraryTransition() {
        cancelPendingFocusDotUpdate();
        focusDotTransitionInProgress = true;
        if (focusPositionDot != null) focusPositionDot.setVisibility(View.GONE);
    }

    /**
     * Recompute marker geometry on the first pre-draw after panel positions, visibility
     * and focus have settled. A plain post() can run before visible-rect geometry is final.
     */
    private void scheduleFocusDotUpdateAfterPreDraw() {
        cancelPendingFocusDotUpdate();
        if (rootContainer == null) {
            focusDotTransitionInProgress = false;
            return;
        }
        focusDotTransitionInProgress = true;
        final ViewTreeObserver observer = rootContainer.getViewTreeObserver();
        if (!observer.isAlive()) {
            focusDotTransitionInProgress = false;
            return;
        }
        ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewTreeObserver currentObserver = rootContainer.getViewTreeObserver();
                if (currentObserver.isAlive()) currentObserver.removeOnPreDrawListener(this);
                if (focusDotPreDrawListener == this) focusDotPreDrawListener = null;
                focusDotTransitionInProgress = false;

                View target = null;
                if (libraryVisible && libraryOverlay != null
                        && libraryOverlay.getVisibility() == View.VISIBLE
                        && libraryCarousel != null) {
                    target = libraryCarousel.getSelectedCardView();
                } else if (!libraryVisible && carouselView != null) {
                    target = carouselView.getSelectedCardView();
                }
                if (target != null && target.isShown()) {
                    updateFocusPositionDot(target);
                } else if (focusPositionDot != null) {
                    focusPositionDot.setVisibility(View.GONE);
                }
                return true;
            }
        };
        focusDotPreDrawListener = listener;
        observer.addOnPreDrawListener(listener);
        rootContainer.invalidate();
    }

    private void cancelLibraryTransition() {
        AnimatorSet running = libraryTransitionAnimator;
        libraryTransitionAnimator = null;
        if (running != null) running.cancel();
    }

    /** Leave both panels at a stable endpoint if HOME is covered mid-transition. */
    private void settleLibraryTransitionForPause() {
        cancelPendingFocusDotUpdate();
        cancelLibraryTransition();
        if (safeContainer == null || libraryOverlay == null) return;
        if (libraryVisible) {
            safeContainer.setTranslationY(-getTransitionHeight());
            libraryOverlay.setTranslationY(0f);
            libraryOverlay.setVisibility(View.VISIBLE);
        } else {
            safeContainer.setTranslationY(0f);
            libraryOverlay.setTranslationY(0f);
            libraryOverlay.setVisibility(View.GONE);
        }
        focusDotTransitionInProgress = false;
        if (focusPositionDot != null) focusPositionDot.setVisibility(View.GONE);
    }

    private void finishLibraryTransition(Animator animation, boolean showing) {
        if (libraryTransitionAnimator != animation) return;
        if (showing) {
            safeContainer.setTranslationY(-getTransitionHeight());
            libraryOverlay.setTranslationY(0);
            libraryCarousel.requestFocus();
        } else {
            safeContainer.setTranslationY(0);
            libraryOverlay.setTranslationY(0);
            libraryOverlay.setVisibility(View.GONE);
            carouselView.requestFocus();
        }
        libraryTransitionAnimator = null;
        // Recompute against stable viewport geometry on the first frame after the transition.
        scheduleFocusDotUpdateAfterPreDraw();
        if (!showing && performanceHud != null && activityResumed) performanceHud.start();
    }

    private void showAppLibrary() {
        if (libraryVisible || libraryOverlay == null || libraryCarousel == null) return;
        libraryVisible = true;
        beginLibraryTransition();
        cancelLibraryTransition();
        // The performance HUD measures only the actual home screen, not the app library.
        if (performanceHud != null) performanceHud.stop();

        List<AppItem> apps = discoveryManager.discoverApps(0);
        libraryCarousel.setItems(apps);
        librarySelectedLabel.setText(apps.isEmpty() ? getString(R.string.no_apps_found) : apps.get(0).getLabel());

        int height = getTransitionHeight();
        libraryOverlay.setVisibility(View.VISIBLE);
        libraryOverlay.setTranslationY(height);
        safeContainer.setTranslationY(0);
        AnimatorSet transition = new AnimatorSet();
        libraryTransitionAnimator = transition;
        transition.playTogether(
                ObjectAnimator.ofFloat(safeContainer, View.TRANSLATION_Y, -height),
                ObjectAnimator.ofFloat(libraryOverlay, View.TRANSLATION_Y, 0f));
        transition.setDuration(280);
        transition.setInterpolator(new DecelerateInterpolator());
        transition.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                finishLibraryTransition(animation, true);
            }
        });
        transition.start();
    }

    private void hideAppLibrary() {
        if (!libraryVisible || libraryOverlay == null || carouselView == null) return;
        libraryVisible = false;
        beginLibraryTransition();
        cancelLibraryTransition();

        int height = getTransitionHeight();
        AnimatorSet transition = new AnimatorSet();
        libraryTransitionAnimator = transition;
        transition.playTogether(
                ObjectAnimator.ofFloat(safeContainer, View.TRANSLATION_Y, 0f),
                ObjectAnimator.ofFloat(libraryOverlay, View.TRANSLATION_Y, height));
        transition.setDuration(160);
        transition.setInterpolator(new DecelerateInterpolator());
        transition.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                finishLibraryTransition(animation, false);
            }
        });
        transition.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleFocusDotUpdateAfterPreDraw();
        activityResumed = true;
        registerLauncherAppsCallback();
        registerTimeTickReceiver();
        updateClockAndDate();
        if (performanceHud != null) {
            if (libraryVisible || (libraryOverlay != null && libraryOverlay.getVisibility() == View.VISIBLE)) {
                performanceHud.stop();
            } else {
                performanceHud.start();
            }
        }
        if (backgroundManager != null) {
            backgroundManager.onResume();
        }
        if (continueWatchingWheel != null) continueWatchingWheel.resumeBackgroundWork();
        if (controlManager != null) {
            controlManager.onResume();
        }
        // If this firmware does not expose LauncherApps callbacks, refresh metadata on resume
        // so installs/removals made while another app was foreground are still discovered.
        if (!launcherAppsCallbackRegistered) {
            invalidateAppCaches();
        }
        loadApps();
        refreshContinueWatchingWheel();
        if (weatherWidgetManager != null) weatherWidgetManager.refreshIfAllowed();
        if (weatherWidgetManager != null && weatherWidgetManager.requestInitialPermissionIfNeeded()) {
            return;
        }
        requestWatchNextReadPermissionIfNeeded();
        if (!watchNextPermissionRequestInFlight) promptUsageAccessIfNeeded();
        if (updateManager != null) updateManager.onResume();
    }

    private void requestWatchNextReadPermissionIfNeeded() {
        if (checkSelfPermission(PERMISSION_READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_TV_LISTINGS_PERMISSION_REQUESTED, false)) return;

        // Request once so a denied permission does not cause a dialog on every HOME launch.
        prefs.edit().putBoolean(KEY_TV_LISTINGS_PERMISSION_REQUESTED, true).apply();
        watchNextPermissionRequestInFlight = true;
        try {
            requestPermissions(new String[] { PERMISSION_READ_TV_LISTINGS },
                    REQUEST_TV_LISTINGS_PERMISSION);
        } catch (RuntimeException e) {
            watchNextPermissionRequestInFlight = false;
            Log.w(TAG, "This TV does not expose runtime access to searchable TV listings.", e);
        }
    }

    private void promptUsageAccessIfNeeded() {
        if (discoveryManager == null || discoveryManager.hasUsageAccess()) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean("usage_access_prompted", false)) return;
        prefs.edit().putBoolean("usage_access_prompted", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Aplicaciones recientes")
                .setMessage("Para ordenar las cinco aplicaciones usadas más recientemente en todo el televisor, autoriza el acceso de uso para Cinematic TV. Si prefieres no concederlo, la fila seguirá el historial de aplicaciones abiertas desde este launcher y completará los espacios restantes por nombre.")
                .setPositiveButton("Abrir ajustes", (dialog, which) -> {
                    try {
                        startActivity(discoveryManager.createUsageAccessSettingsIntent());
                    } catch (Exception e) {
                        Toast.makeText(this, "No se pudieron abrir los ajustes de acceso de uso", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Ahora no", null)
                .show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU
                    || keyCode == KeyEvent.KEYCODE_TV_CONTENTS_MENU) {
                Log.i(TAG, "Remote settings/menu key received: "
                        + KeyEvent.keyCodeToString(keyCode));
                if (controlManager != null) {
                    if (event.getRepeatCount() > 0) return true;
                    if (controlManager.openSettingsFromRemote()) return true;
                }
                // If our own route fails, preserve Android/Hisense's native key handling.
            }
            if (controlManager != null && (keyCode == KeyEvent.KEYCODE_SEARCH
                    || keyCode == KeyEvent.KEYCODE_ASSIST
                    || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST)) {
                if (event.getRepeatCount() == 0) controlManager.openAssistantFromRemote();
                return true;
            }
            if (controlManager != null && keyCode == KeyEvent.KEYCODE_BACK
                    && controlManager.closeInputOverlay()) return true;
            if (libraryVisible && (keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                hideAppLibrary();
                return true;
            }
            if (!libraryVisible && keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    && carouselView != null && getCurrentFocus() == carouselView) {
                // The app library is a vertical navigation destination, not a visible button.
                showAppLibrary();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && carouselView != null
                    && getCurrentFocus() == carouselView && carouselView.getSelectedIndex() == 0
                    && pinnedAppsWheel != null && pinnedAppsWheel.getVisibility() == View.VISIBLE
                    && !pinnedWheelSlotViews.isEmpty()) {
                View center = getPinnedWheelCenterChild();
                if (center != null) center.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && carouselView != null
                    && getCurrentFocus() == carouselView
                    && carouselView.getItemCount() > 0
                    && carouselView.getSelectedIndex() >= carouselView.getItemCount() - 1) {
                if (continueWatchingWheel != null && continueWatchingWheel.hasItems()) {
                    View center = continueWatchingWheel.getCenterChild();
                    if (center != null) center.requestFocus();
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pinnedAppsWheel != null) {
                View focus = getCurrentFocus();
                if (focus != null && isDescendantOf(focus, pinnedAppsWheel)) {
                    carouselView.requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        if (backgroundManager != null) backgroundManager.onPause();
        super.onPause();
        lifecycleHandler.removeCallbacks(persistRecentSelectionRunnable);
        persistRecentSelectionRunnable.run();
        activityResumed = false;
        settleLibraryTransitionForPause();
        if (carouselView != null) carouselView.cancelPendingAnimation();
        if (libraryCarousel != null) libraryCarousel.cancelPendingAnimation();
        unregisterLauncherAppsCallback();
        unregisterTimeTickReceiver();
        if (performanceHud != null) performanceHud.stop();
        lifecycleHandler.removeCallbacks(packageRefreshRunnable);
        if (controlManager != null) controlManager.onPause();
        if (updateManager != null) updateManager.onPause();
        // Stop weather and adaptive-HUD work while another app is foreground.
        if (weatherWidgetManager != null) weatherWidgetManager.onPause();
        if (continueWatchingWheel != null) continueWatchingWheel.pauseBackgroundWork();
        hudColorGeneration++;
        cancelHudColorSampleTask();
        if (hudColorAnimator != null) {
            hudColorAnimator.cancel();
            hudColorAnimator = null;
        }
    }

    @Override
    protected void onDestroy() {
        activityResumed = false;
        cancelHudColorSampleTask();
        if (performanceHud != null) performanceHud.close();
        unregisterLauncherAppsCallback();
        if (updateManager != null) updateManager.close();
        if (hudColorAnimator != null) hudColorAnimator.cancel();
        super.onDestroy();
        if (weatherWidgetManager != null) weatherWidgetManager.close();
        if (backgroundManager != null) {
            backgroundManager.onDestroy();
        }
        unregisterTimeTickReceiver();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_TV_LISTINGS_PERMISSION) {
            watchNextPermissionRequestInFlight = false;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                Log.i(TAG, "Read access to searchable TV listings was granted.");
                refreshContinueWatchingWheel();
            } else {
                Log.w(TAG, "TV listings access was denied; other apps' Watch Next rows may be hidden.");
                Toast.makeText(this,
                        "Sin permiso de lectura de TV, «Continuar viendo» puede no mostrar contenido de otras aplicaciones.",
                        Toast.LENGTH_LONG).show();
            }
            promptUsageAccessIfNeeded();
            return;
        }
        if (weatherWidgetManager != null && weatherWidgetManager.handlesPermissionRequest(requestCode)) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            weatherWidgetManager.onLocationPermissionResult(granted);
            promptUsageAccessIfNeeded();
            updateClockAndDate();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (controlManager != null) controlManager.handleActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (libraryVisible) {
            hideAppLibrary();
            return;
        }
        // Annex D.3: Back never exits launcher accidentally.
        View currentFocus = getCurrentFocus();
        focusController.handleBackPressed(currentFocus);
    }
}
