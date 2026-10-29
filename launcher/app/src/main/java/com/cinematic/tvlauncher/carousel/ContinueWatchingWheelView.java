package com.cinematic.tvlauncher.carousel;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.cinematic.tvlauncher.WatchNextEntryPolicy;
import com.cinematic.tvlauncher.WatchNextRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Read-only right-edge Watch Next wheel. Rows come exclusively from the system TV provider.
 * Artwork, titles, resume URIs, and progress are never synthesized by this view.
 */
public final class ContinueWatchingWheelView extends FrameLayout {
    public interface OnItemActivatedListener {
        void onItemActivated(WatchNextRepository.Entry entry);
    }

    public interface OnNavigateBackListener {
        void onNavigateBackToRecentApps();
    }

    public interface OnFocusTargetChangedListener {
        void onFocusTargetChanged(View target);
    }

    public interface OnItemsChangedListener {
        void onItemsChanged();
    }

    private static final int MAX_VISIBLE_ITEMS = 5;
    private static final String TAG = "ContinueWatching";
    private static final ExecutorService DATA_EXECUTOR =
            new ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private static final ThreadPoolExecutor ARTWORK_EXECUTOR = new ThreadPoolExecutor(
            3, 3, 15L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    static {
        // Preserve the three-way artwork parallelism, but let idle workers release their stacks.
        ARTWORK_EXECUTOR.allowCoreThreadTimeOut(true);
    }
    private static final int MAX_ARTWORK_SOURCE_BYTES = 6 * 1024 * 1024;
    // Bound poster bitmap retention; 8 MiB still covers the visible wheel without holding a large artwork history.
    private static final LruCache<String, Bitmap> ARTWORK_CACHE = new LruCache<String, Bitmap>(8 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };
    private static final LruCache<String, int[]> ARTWORK_PALETTE_CACHE = new LruCache<>(96);
    private static final int DEFAULT_ACCENT = Color.rgb(168, 232, 255);
    private static final int[] DEFAULT_PALETTE = {
            Color.rgb(80, 170, 255), Color.rgb(110, 190, 255),
            Color.rgb(130, 220, 250), Color.rgb(110, 200, 235),
            Color.rgb(80, 170, 255), Color.rgb(70, 145, 230),
            Color.rgb(85, 155, 245), Color.rgb(100, 180, 250)
    };
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable scheduledRefresh = this::refreshFromSystem;
    private WatchNextRepository watchNextRepository;
    private int refreshGeneration;
    private volatile boolean hostPaused = true;
    private volatile Future<?> dataTask;
    private final AtomicInteger artworkGeneration = new AtomicInteger();
    // Track poster work by the visible item that owns it so off-wheel jobs can be cancelled.
    private final Map<MediaItem, Set<Future<?>>> artworkTasks = new HashMap<>();
    private OnItemsChangedListener itemsChangedListener;
    private final List<FrameLayout> slotViews = new ArrayList<>();

    private final List<MediaItem> items = new ArrayList<>();
    private int selectedIndex = 0;
    private boolean animating = false;
    // Reuse the five visible-card transforms across navigation events.
    private final float[][] fromTransforms = new float[MAX_VISIBLE_ITEMS][4];
    private final float[][] toTransforms = new float[MAX_VISIBLE_ITEMS][4];
    private final float[] incomingFromTransform = new float[4];
    private final float[] incomingToTransform = new float[4];
    private OnItemActivatedListener activationListener;
    private OnNavigateBackListener navigateBackListener;
    private OnFocusTargetChangedListener focusTargetChangedListener;

    public ContinueWatchingWheelView(Context context) {
        super(context);
        init();
    }

    public ContinueWatchingWheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ContinueWatchingWheelView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        watchNextRepository = new WatchNextRepository(getContext().getApplicationContext());
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(false);
        setBackgroundColor(Color.TRANSPARENT);
        setVisibility(View.GONE);
    }

    public void setOnItemActivatedListener(OnItemActivatedListener listener) {
        activationListener = listener;
    }

    public void setOnNavigateBackListener(OnNavigateBackListener listener) {
        navigateBackListener = listener;
    }

    public void setOnFocusTargetChangedListener(OnFocusTargetChangedListener listener) {
        focusTargetChangedListener = listener;
    }

    public void setOnItemsChangedListener(OnItemsChangedListener listener) {
        itemsChangedListener = listener;
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    /**
     * Returns the selected Watch Next poster's current accent for the navigation marker.
     * Poster palettes are sampled off the UI thread and update this value when artwork arrives.
     */
    public int getAccentColorForTarget(View target) {
        Object tag = target == null ? null : target.getTag();
        MediaItem item = null;
        if (tag instanceof MediaItem) {
            item = (MediaItem) tag;
        } else if (tag instanceof CardGlowState) {
            item = ((CardGlowState) tag).item;
        }
        return item == null ? DEFAULT_ACCENT : item.accentColor;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        resumeBackgroundWork();
    }

    @Override
    protected void onDetachedFromWindow() {
        pauseBackgroundWork();
        super.onDetachedFromWindow();
    }

    /** Resume Watch Next polling and poster work only while the launcher is foreground. */
    public void resumeBackgroundWork() {
        if (hostPaused) hostPaused = false;
        watchNextRepository.registerObserver(this::scheduleRefresh);
        scheduleRefresh();
    }

    /** Cancel queued Watch Next queries and poster work when another app is opened. */
    public void pauseBackgroundWork() {
        hostPaused = true;
        refreshGeneration++;
        artworkGeneration.incrementAndGet();
        refreshHandler.removeCallbacks(scheduledRefresh);
        watchNextRepository.unregisterObserver();
        Future<?> query = dataTask;
        dataTask = null;
        if (query != null) query.cancel(true);
        cancelAllArtworkTasks();
    }

    private void scheduleRefresh() {
        if (hostPaused) return;
        refreshHandler.removeCallbacks(scheduledRefresh);
        // Coalesce provider notifications before one background query.
        refreshHandler.postDelayed(scheduledRefresh, 200L);
    }

    public View getCenterChild() {
        return slotViews.isEmpty() ? null : slotViews.get(slotViews.size() / 2);
    }

    /**
     * Reads published rows directly from the system provider on a worker thread. This view
     * never creates media sessions, uses notification text, or synthesizes watch history.
     */
    public void refreshFromSystem() {
        if (hostPaused) return;
        long previousId = items.isEmpty()
                ? -1L : items.get(Math.floorMod(selectedIndex, items.size())).id;
        final int requestId = ++refreshGeneration;

        try {
        dataTask = DATA_EXECUTOR.submit(() -> {
            if (hostPaused || Thread.currentThread().isInterrupted()) return;
            List<WatchNextRepository.Entry> entries = watchNextRepository.queryContinueWatching();
            List<MediaItem> result = new ArrayList<>(entries.size());
            for (WatchNextRepository.Entry entry : entries) result.add(new MediaItem(entry));

            post(() -> {
                if (hostPaused || requestId != refreshGeneration) return;
                boolean sameContent = hasSameRenderedItems(result);
                if (!sameContent) {
                    items.clear();
                    items.addAll(result);
                    selectedIndex = 0;
                    if (previousId >= 0L) {
                        for (int i = 0; i < items.size(); i++) {
                            if (previousId == items.get(i).id) {
                                selectedIndex = i;
                                break;
                            }
                        }
                    }
                    if (items.isEmpty()) {
                        cancelAllArtworkTasks();
                        removeAllViews();
                        slotViews.clear();
                        setVisibility(View.GONE);
                    } else {
                        setVisibility(View.VISIBLE);
                        renderWheel();
                    }
                }
                if (itemsChangedListener != null) itemsChangedListener.onItemsChanged();
            });
        });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The shared query executor may be shutting down with the process.
        }
    }

    private boolean hasSameRenderedItems(List<MediaItem> nextItems) {
        if (items.size() != nextItems.size()) return false;
        for (int i = 0; i < items.size(); i++) {
            MediaItem oldItem = items.get(i);
            MediaItem newItem = nextItems.get(i);
            if (oldItem.id != newItem.id
                    || !oldItem.title.equals(newItem.title)
                    || !java.util.Objects.equals(oldItem.intentUri, newItem.intentUri)
                    || !java.util.Objects.equals(oldItem.posterArtUri, newItem.posterArtUri)
                    || oldItem.lastPlaybackPositionMillis != newItem.lastPlaybackPositionMillis
                    || oldItem.durationMillis != newItem.durationMillis
                    || oldItem.lastSeen != newItem.lastSeen) return false;
        }
        return true;
    }

    private void loadPosterArtworkAsync(MediaItem item, ImageView poster) {
        if (item == null || hostPaused) return;
        poster.setTag(item);
        final int generation = artworkGeneration.get();
        if (item.posterArtUri == null) {
            Log.d(TAG, "No poster or thumbnail URI published for package="
                    + item.entry.getPackageName());
            return;
        }

        String cacheKey = item.posterArtUri.toString();
        Bitmap cached = ARTWORK_CACHE.get(cacheKey);
        if (cached != null) {
            poster.setImageBitmap(cached);
            int[] cachedPalette = ARTWORK_PALETTE_CACHE.get(cacheKey);
            if (cachedPalette != null) {
                applyPaletteToCard((View) poster.getParent(), item, cachedPalette);
            } else {
                // A bitmap may outlive its small palette entry; recalculate off the UI thread.
                submitArtworkTask(generation, item, () -> {
                    int[] palette = sampleArtworkPalette(cached);
                    if (Thread.currentThread().isInterrupted() || hostPaused
                            || generation != artworkGeneration.get()) return;
                    ARTWORK_PALETTE_CACHE.put(cacheKey, palette);
                    post(() -> {
                        if (!hostPaused && generation == artworkGeneration.get() && poster.getTag() == item) {
                            applyPaletteToCard((View) poster.getParent(), item, palette);
                        }
                    });
                });
            }
            return;
        }

        submitArtworkTask(generation, item, () -> {
            Bitmap decoded = ARTWORK_CACHE.get(cacheKey);
            int[] palette = ARTWORK_PALETTE_CACHE.get(cacheKey);
            if (decoded == null) {
                try {
                    decoded = decodePosterArtwork(item.posterArtUri);
                    if (decoded != null) ARTWORK_CACHE.put(cacheKey, decoded);
                    else {
                        Log.d(TAG, "Poster decode returned no bitmap for package="
                                + item.entry.getPackageName() + ", scheme=" + item.posterArtUri.getScheme());
                    }
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) return;
                    Log.d(TAG, "Poster load failed for package=" + item.entry.getPackageName()
                            + ", scheme=" + item.posterArtUri.getScheme(), e);
                }
            }
            if (Thread.currentThread().isInterrupted() || hostPaused
                    || generation != artworkGeneration.get()) return;

            if (decoded != null) {
                if (palette == null) {
                    palette = sampleArtworkPalette(decoded);
                    if (Thread.currentThread().isInterrupted() || hostPaused
                            || generation != artworkGeneration.get()) return;
                    ARTWORK_PALETTE_CACHE.put(cacheKey, palette);
                }
                if (Thread.currentThread().isInterrupted() || hostPaused
                        || generation != artworkGeneration.get()) return;
                Bitmap ready = decoded;
                int[] paletteReady = palette;
                post(() -> {
                    if (!hostPaused && generation == artworkGeneration.get() && poster.getTag() == item) {
                        poster.setImageBitmap(ready);
                        applyPaletteToCard((View) poster.getParent(), item, paletteReady);
                    }
                });
            }
        });
    }

    private void submitArtworkTask(int generation, MediaItem item, Runnable work) {
        if (item == null || hostPaused || generation != artworkGeneration.get()) return;
        synchronized (artworkTasks) {
            artworkTasks.entrySet().removeIf(entry -> {
                entry.getValue().removeIf(Future::isDone);
                return entry.getValue().isEmpty();
            });
            if (hostPaused || generation != artworkGeneration.get()) return;
            Future<?> task = ARTWORK_EXECUTOR.submit(() -> {
                if (hostPaused || generation != artworkGeneration.get()
                        || Thread.currentThread().isInterrupted()) return;
                work.run();
            });
            artworkTasks.computeIfAbsent(item, ignored -> new HashSet<>()).add(task);
        }
    }

    private void cancelArtworkTasks(MediaItem item) {
        if (item == null) return;
        synchronized (artworkTasks) {
            Set<Future<?>> tasks = artworkTasks.remove(item);
            if (tasks == null) return;
            for (Future<?> task : tasks) {
                task.cancel(true);
                if (task instanceof Runnable) ARTWORK_EXECUTOR.remove((Runnable) task);
            }
        }
    }

    private void cancelAllArtworkTasks() {
        synchronized (artworkTasks) {
            for (Set<Future<?>> tasks : artworkTasks.values()) {
                for (Future<?> task : tasks) {
                    task.cancel(true);
                    if (task instanceof Runnable) ARTWORK_EXECUTOR.remove((Runnable) task);
                }
            }
            artworkTasks.clear();
        }
    }

    /** Applies poster colors on the UI thread after sampling has completed on the worker. */
    private void applyPaletteToCard(View surface, MediaItem item, int[] palette) {
        if (surface == null || item == null || palette == null) return;
        Object tag = surface.getTag();
        if (!(tag instanceof CardGlowState)) return;
        CardGlowState glowState = (CardGlowState) tag;
        glowState.updatePalette(palette);

        View outer = surface.getParent() instanceof View ? (View) surface.getParent() : null;
        boolean focused = outer != null && outer.hasFocus();
        applySurfaceStyle(surface, item, glowState, focused);
        if (focused && focusTargetChangedListener != null) {
            // The indicator follows the corrected poster palette if art arrives after focus.
            focusTargetChangedListener.onFocusTargetChanged(surface);
        }
    }

    private Bitmap decodePosterArtwork(Uri uri) throws IOException {
        // Read the source once. Reopening remote/provider URIs for bounds and pixels could make
        // each poster wait through two network timeouts before it appeared on the TV.
        byte[] sourceBytes;
        try (InputStream input = openArtworkStream(uri)) {
            if (input == null) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int totalBytes = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                totalBytes += count;
                if (totalBytes > MAX_ARTWORK_SOURCE_BYTES) {
                    throw new IOException("Watch Next artwork exceeds the 6 MiB source limit");
                }
                output.write(buffer, 0, count);
            }
            sourceBytes = output.toByteArray();
        }
        if (sourceBytes.length == 0) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 360) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.length, options);
    }

    /**
     * Watch Next permits network URLs as well as provider/resource/file URIs. ContentResolver
     * cannot open HTTP(S) URLs, so handle those on the artwork worker with bounded timeouts.
     */
    private InputStream openArtworkStream(Uri uri) throws IOException {
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            URLConnection connection = new URL(uri.toString()).openConnection();
            // Bound slow publisher endpoints so later visible posters are not held up
            // behind multiple long network timeouts in the three-worker queue.
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2500);
            connection.setUseCaches(true);
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.5");
            return connection.getInputStream();
        }
        if ("content".equalsIgnoreCase(scheme)
                || "android.resource".equalsIgnoreCase(scheme)
                || "file".equalsIgnoreCase(scheme)) {
            return getContext().getApplicationContext().getContentResolver().openInputStream(uri);
        }
        throw new IOException("Unsupported artwork URI scheme: " + scheme);
    }

    private void renderWheel() {
        cancelAllArtworkTasks();
        if (items.isEmpty()) {
            removeAllViews();
            setVisibility(View.GONE);
            return;
        }
        View currentFocus = findFocus();
        boolean restoreFocus = currentFocus != null && isDescendantOf(currentFocus, this);
        removeAllViews();
        slotViews.clear();

        int visibleCount = Math.min(MAX_VISIBLE_ITEMS, items.size());
        int centerSlot = visibleCount / 2;
        for (int slot = 0; slot < visibleCount; slot++) {
            int itemIndex = Math.floorMod(selectedIndex + slot - centerSlot, items.size());
            MediaItem item = items.get(itemIndex);
            FrameLayout card = createCard(item);
            slotViews.add(card);
            addView(card, new FrameLayout.LayoutParams(dp(88), dp(50)));
            placeCard(card, wheelAngle(slot, visibleCount));
        }

        post(() -> {
            layoutWheelCards();
            if (restoreFocus) {
                View center = getCenterChild();
                if (center != null) center.requestFocus();
            }
        });
    }

    private FrameLayout createCard(MediaItem item) {
        int cardWidth = dp(88);
        int cardHeight = dp(50);

        FrameLayout outer = new FrameLayout(getContext());
        outer.setId(View.generateViewId());
        outer.setTag(item);
        outer.setFocusable(true);
        outer.setClickable(true);
        outer.setClipChildren(false);
        outer.setClipToPadding(false);
        outer.setBackgroundColor(Color.TRANSPARENT);
        // These five bounded cards retain warm hardware layers between D-pad moves.
        outer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        outer.setContentDescription(item.title);
        outer.setOnClickListener(v -> activate(item));
        // Keep the artwork card clean: no mirrored side strip or black fade beside the poster.
        FrameLayout surface = new FrameLayout(getContext());
        LayoutParams surfaceLp = new LayoutParams(cardWidth, cardHeight, Gravity.TOP | Gravity.RIGHT);
        surface.setLayoutParams(surfaceLp);
        CardGlowState glowState = new CardGlowState(item, getResources().getDisplayMetrics().density);
        surface.setTag(glowState);
        surface.setBackground(createCardBackground(item.accentColor, false));
        surface.setClipChildren(true);
        surface.setClipToPadding(true);
        surface.setClipToOutline(true);
        ImageView poster = new ImageView(getContext());
        // Publisher artwork is often 16:9; CENTER_CROP cut off the left/right of the actual
        // cover inside this compact near-square wheel card. FIT_CENTER preserves the complete art.
        poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable posterMask = new GradientDrawable();
        posterMask.setShape(GradientDrawable.RECTANGLE);
        posterMask.setCornerRadius(dp(5));
        posterMask.setColor(Color.TRANSPARENT);
        poster.setBackground(posterMask);
        poster.setClipToOutline(true);
        LayoutParams posterLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        // Reserve the title strip below the artwork instead of letting it hide the cover's bottom.
        posterLp.setMargins(dp(2), dp(2), dp(2), dp(2));
        surface.addView(poster, posterLp);

        View gloss = new View(getContext());
        gloss.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x26FFFFFF, 0x08FFFFFF, Color.TRANSPARENT}));
        LayoutParams glossLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        glossLp.setMargins(dp(4), dp(4), dp(4), dp(4));
        surface.addView(gloss, glossLp);

        // A translucent bottom scrim lets the title sit over the poster instead of a separate box.
        View titleScrim = new View(getContext());
        titleScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00090C14, 0x52090C14, 0xC0090C14}));
        LayoutParams titleScrimLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, dp(24), Gravity.BOTTOM);
        titleScrimLp.setMargins(dp(2), 0, dp(2), dp(2));
        surface.addView(titleScrim, titleScrimLp);

        int progressPercent = WatchNextEntryPolicy.progressPercent(
                item.lastPlaybackPositionMillis, item.durationMillis);
        if (progressPercent >= 0 && progressPercent < 100) {
            View progressTrack = new View(getContext());
            progressTrack.setBackgroundColor(0x77070B12);
            LayoutParams trackLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM);
            trackLp.setMargins(dp(4), 0, dp(4), dp(20));
            surface.addView(progressTrack, trackLp);

            View progressFill = new View(getContext());
            GradientDrawable progressBackground = new GradientDrawable();
            progressBackground.setColor(0xFFE3F8FF);
            progressBackground.setCornerRadius(dp(2));
            progressFill.setBackground(progressBackground);
            LayoutParams progressLp = new LayoutParams(
                    Math.max(dp(2), Math.round((cardWidth - dp(8)) * progressPercent / 100f)),
                    dp(2), Gravity.BOTTOM | Gravity.LEFT);
            progressLp.leftMargin = dp(4);
            progressLp.bottomMargin = dp(20);
            surface.addView(progressFill, progressLp);
        }

        TextView title = new TextView(getContext());
        title.setText(item.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(8);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setIncludeFontPadding(false);
        title.setPadding(dp(3), dp(1), dp(3), dp(1));
        title.setShadowLayer(dp(2), 0f, dp(1), 0xD0000000);
        title.setBackgroundColor(Color.TRANSPARENT);
        LayoutParams titleLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(16), Gravity.BOTTOM);
        titleLp.setMargins(dp(2), 0, dp(2), dp(2));
        surface.addView(title, titleLp);
        outer.addView(surface);
        outer.setOnFocusChangeListener((v, hasFocus) -> {
            applySurfaceStyle(surface, item, glowState, hasFocus);
            if (hasFocus && focusTargetChangedListener != null) {
                focusTargetChangedListener.onFocusTargetChanged(surface);
            }
        });

        applySurfaceStyle(surface, item, glowState, false);
        loadPosterArtworkAsync(item, poster);
        outer.setOnKeyListener((v, keyCode, event) -> {
            if (animating) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    rotateWheel(keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && navigateBackListener != null) {
                    navigateBackListener.onNavigateBackToRecentApps();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) activate(item);
                return true;
            }
            return false;
        });
        outer.setNextFocusRightId(outer.getId());
        outer.setNextFocusUpId(outer.getId());
        outer.setNextFocusDownId(outer.getId());
        return outer;
    }

    private void applySurfaceStyle(View surface, MediaItem item,
            CardGlowState glowState, boolean focused) {
        if (surface == null || item == null || glowState == null) return;
        int accent = item.accentColor;
        surface.setBackground(createCardBackground(accent, focused));
        surface.setElevation(dp(focused ? 6 : 0));
        glowState.glow.setFocused(focused);
        // Keep the poster edge clean at rest; illuminate only the selected artwork.
        if (focused) {
            if (surface.getForeground() != glowState.glow) surface.setForeground(glowState.glow);
        } else if (surface.getForeground() != null) {
            surface.setForeground(null);
        }
    }

    private GradientDrawable createCardBackground(int accent, boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(6));
        background.setColor(focused
                ? Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent))
                : Color.TRANSPARENT);
        return background;
    }

    /** Samples eight zones around the real poster to map local tones to each side of the frame. */
    private int[] sampleArtworkPalette(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() == 0 || bitmap.getHeight() == 0) {
            return DEFAULT_PALETTE.clone();
        }

        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final int stepX = Math.max(1, width / 90);
        final int stepY = Math.max(1, height / 54);
        int fallback = DEFAULT_ACCENT;
        int[] rowPixels = new int[width];
        long allRed = 0, allGreen = 0, allBlue = 0, allCount = 0;
        for (int y = 0; y < height; y += stepY) {
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x += stepX) {
                int pixel = rowPixels[x];
                if (Color.alpha(pixel) < 128) continue;
                int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
                if (Math.max(r, Math.max(g, b)) < 20) continue;
                allRed += r; allGreen += g; allBlue += b; allCount++;
            }
        }
        if (allCount > 0) {
            fallback = Color.rgb((int)(allRed / allCount), (int)(allGreen / allCount),
                    (int)(allBlue / allCount));
        }

        // Regions follow the drawable's perimeter order: TL, top, TR, right,
        // BR, bottom, BL, left. They are deliberately broad enough for small posters.
        int[][] zones = {
                {0, 0, width / 3, height / 4},
                {width / 3, 0, (width * 2) / 3, height / 4},
                {(width * 2) / 3, 0, width, height / 4},
                {(width * 3) / 4, height / 4, width, (height * 3) / 4},
                {(width * 2) / 3, (height * 3) / 4, width, height},
                {width / 3, (height * 3) / 4, (width * 2) / 3, height},
                {0, (height * 3) / 4, width / 3, height},
                {0, height / 4, width / 4, (height * 3) / 4}
        };

        int[] palette = new int[zones.length];
        for (int i = 0; i < zones.length; i++) {
            int[] zone = zones[i];
            long red = 0, green = 0, blue = 0, count = 0;
            for (int y = zone[1]; y < zone[3]; y += stepY) {
                bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1);
                for (int x = zone[0]; x < zone[2]; x += stepX) {
                    int pixel = rowPixels[x];
                    if (Color.alpha(pixel) < 128) continue;
                    int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
                    if (Math.max(r, Math.max(g, b)) < 20) continue;
                    red += r; green += g; blue += b; count++;
                }
            }
            int raw = count == 0 ? fallback
                    : Color.rgb((int)(red / count), (int)(green / count), (int)(blue / count));
            palette[i] = liftEdgeColor(raw);
        }
        return palette;
    }

    private int liftEdgeColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] >= 0.12f) hsv[1] = Math.min(0.88f, hsv[1] * 1.08f);
        hsv[2] = Math.min(0.96f, Math.max(0.48f, hsv[2] * 1.12f));
        return Color.HSVToColor(hsv);
    }

    private int averagePaletteColor(int[] palette) {
        if (palette == null || palette.length == 0) return DEFAULT_ACCENT;
        long red = 0, green = 0, blue = 0;
        for (int color : palette) {
            red += Color.red(color);
            green += Color.green(color);
            blue += Color.blue(color);
        }
        return Color.rgb((int)(red / palette.length), (int)(green / palette.length),
                (int)(blue / palette.length));
    }

    private final class CardGlowState {
        final MediaItem item;
        final AdaptiveGlowDrawable glow;

        CardGlowState(MediaItem item, float density) {
            this.item = item;
            glow = new AdaptiveGlowDrawable(item.accentPalette, density, 6f, false);
        }

        void updatePalette(int[] palette) {
            if (palette == null || palette.length == 0) return;
            item.accentPalette = palette;
            item.accentColor = averagePaletteColor(palette);
            glow.setPerimeterColors(palette);
        }
    }

    private float wheelAngle(int slot, int visibleCount) {
        if (visibleCount <= 1) return 0f;
        return (float) (-Math.PI / 2.0 + (Math.PI * slot / (visibleCount - 1)));
    }

    private float[] cardTransform(float angle) {
        float[] transform = new float[4];
        setCardTransform(angle, transform);
        return transform;
    }

    private void setCardTransform(float angle, float[] transform) {
        if (transform == null || transform.length < 4) return;
        int cardWidth = dp(88);
        int cardHeight = dp(50);
        int viewportHeight = getHeight() > 0 ? getHeight() : dp(310);
        int viewportWidth = getWidth() > 0 ? getWidth() : dp(210);
        float radius = Math.max(dp(1), (viewportHeight - cardHeight) / 2f);
        float centerX = viewportWidth - cardWidth / 2f;
        float centerY = viewportHeight / 2f;
        float cosine = (float) Math.cos(angle);
        transform[0] = centerX - radius * cosine - cardWidth / 2f;
        transform[1] = centerY + radius * (float) Math.sin(angle) - cardHeight / 2f;
        transform[2] = 0.84f + 0.16f * Math.max(0f, cosine);
        transform[3] = Math.max(-16f, Math.min(16f,
                angle * 16f / (float) (Math.PI / 2.0)));
    }

    private void applyCardTransform(View card, float[] transform) {
        if (card == null || transform == null) return;
        card.setX(transform[0]);
        card.setY(transform[1]);
        card.setScaleX(transform[2]);
        card.setScaleY(transform[2]);
        card.setRotationY(transform[3]);
        card.setAlpha(1f);
    }

    private void interpolateCardTransform(View card, float[] from, float[] to, float progress) {
        if (card == null || from == null || to == null) return;
        card.setX(from[0] + (to[0] - from[0]) * progress);
        card.setY(from[1] + (to[1] - from[1]) * progress);
        float scale = from[2] + (to[2] - from[2]) * progress;
        card.setScaleX(scale);
        card.setScaleY(scale);
        card.setRotationY(from[3] + (to[3] - from[3]) * progress);
        card.setAlpha(1f);
    }

    private void placeCard(View card, float angle) {
        if (card == null) return;
        applyCardTransform(card, cardTransform(angle));
    }

    private void layoutWheelCards() {
        int count = slotViews.size();
        for (int slot = 0; slot < count; slot++) {
            placeCard(slotViews.get(slot), wheelAngle(slot, count));
        }
    }

    private void rotateWheel(int delta) {
        if (animating || items.isEmpty() || (delta != -1 && delta != 1)) return;
        int visibleCount = slotViews.size();
        if (visibleCount <= 1) return;

        int newSelection = Math.floorMod(selectedIndex + delta, items.size());
        float step = (float) (Math.PI / (visibleCount - 1));
        int incomingSlot = delta > 0 ? visibleCount - 1 : 0;
        float incomingAngle = wheelAngle(incomingSlot, visibleCount) + delta * step;
        float targetAngle = wheelAngle(incomingSlot, visibleCount);
        int centerSlot = visibleCount / 2;
        int incomingIndex = Math.floorMod(newSelection + incomingSlot - centerSlot, items.size());
        FrameLayout incoming = createCard(items.get(incomingIndex));
        incoming.setFocusable(false);
        incoming.setClickable(false);
        addView(incoming, new FrameLayout.LayoutParams(dp(88), dp(50)));

        for (int i = 0; i < visibleCount; i++) {
            setCardTransform(wheelAngle(i, visibleCount), fromTransforms[i]);
            setCardTransform(wheelAngle(i, visibleCount) - delta * step, toTransforms[i]);
            slotViews.get(i).setClickable(false);
        }
        setCardTransform(incomingAngle, incomingFromTransform);
        setCardTransform(targetAngle, incomingToTransform);
        applyCardTransform(incoming, incomingFromTransform);
        animating = true;

        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(220);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            // Precomputed reusable geometry keeps the animation frame path allocation-free.
            for (int i = 0; i < visibleCount; i++) {
                interpolateCardTransform(slotViews.get(i), fromTransforms[i], toTransforms[i], progress);
            }
            interpolateCardTransform(incoming, incomingFromTransform, incomingToTransform, progress);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                // Rotate the existing slot list in-place instead of copying it twice per keypress.
                FrameLayout outgoing;
                if (delta > 0) {
                    outgoing = slotViews.remove(0);
                    slotViews.add(incoming);
                } else {
                    outgoing = slotViews.remove(slotViews.size() - 1);
                    slotViews.add(0, incoming);
                }
                incoming.setFocusable(true);
                incoming.setClickable(true);

                // Focus the new center before detaching only the edge card.
                View center = getCenterChild();
                if (center != null) center.requestFocus();
                if (outgoing != null) {
                    Object tag = outgoing.getTag();
                    if (tag instanceof MediaItem) cancelArtworkTasks((MediaItem) tag);
                    removeView(outgoing);
                }
                for (FrameLayout card : slotViews) {
                    card.setFocusable(true);
                    card.setClickable(true);
                    bringChildToFront(card);
                }
                selectedIndex = newSelection;
                animating = false;
            }

            @Override public void onAnimationCancel(android.animation.Animator animation) {
                animating = false;
            }
        });
        animator.start();
    }

    private void activate(MediaItem item) {
        if (item != null && item.entry != null && activationListener != null) {
            activationListener.onItemActivated(item.entry);
        }
    }

    private boolean isDescendantOf(View child, ViewGroup parent) {
        android.view.ViewParent current = child.getParent();
        while (current != null) {
            if (current == parent) return true;
            if (!(current instanceof View)) break;
            current = ((View) current).getParent();
        }
        return false;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class MediaItem {
        final WatchNextRepository.Entry entry;
        final long id;
        final String title;
        final long lastSeen;
        final String intentUri;
        final Uri posterArtUri;
        volatile int accentColor = DEFAULT_ACCENT;
        volatile int[] accentPalette = DEFAULT_PALETTE;
        final long lastPlaybackPositionMillis;
        final long durationMillis;

        MediaItem(WatchNextRepository.Entry entry) {
            this.entry = entry;
            this.id = entry.getId();
            this.title = entry.getTitle();
            this.lastSeen = entry.getLastEngagementTimeUtcMillis();
            this.intentUri = entry.getIntentUri();
            this.posterArtUri = entry.getPosterArtUri();
            this.lastPlaybackPositionMillis = entry.getLastPlaybackPositionMillis();
            this.durationMillis = entry.getDurationMillis();
        }
    }
}
