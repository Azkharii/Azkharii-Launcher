package com.cinematic.tvlauncher.carousel;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;


import com.cinematic.tvlauncher.model.AppItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * AppCarouselView — Custom 3D interactive TV carousel view.
 * Implements Annex C.1 layout and Annex D.3 remote navigation.
 */
public class AppCarouselView extends FrameLayout {

    public interface OnCarouselSelectionListener {
        void onItemSelected(int index, AppItem item);
        void onItemActivated(int index, AppItem item);
        default void onItemLongPressed(int index, AppItem item) {}
    }

    private final List<AppItem> items = new ArrayList<>();
    private final List<View> cardViews = new ArrayList<>();
    private final List<View> cardSurfaceViews = new ArrayList<>();
    private final List<Integer> cardColors = new ArrayList<>();
    private final List<AdaptiveGlowDrawable> cardGlows = new ArrayList<>();
    private int selectedIndex = 0;
    private int maxVisibleCardDistance = 3;
    private OnCarouselSelectionListener selectionListener;
    private ValueAnimator positionAnimator;
    // Queue short D-pad repeats instead of cancelling an in-flight carousel slide.
    // This preserves the fixed center slot while cards advance one position at a time.
    private final Deque<Integer> pendingMoves = new ArrayDeque<>();
    private static final int MAX_PENDING_MOVES = 2;

    private long lastKeyTime = 0;
    private boolean centerLongPressHandled = false;
    private static final long MIN_REPEAT_INTERVAL_MS = 80; // ~12 moves/sec per Annex D.3

    public AppCarouselView(Context context) {
        super(context);
        init();
    }

    public AppCarouselView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AppCarouselView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setSelectionListener(OnCarouselSelectionListener listener) {
        this.selectionListener = listener;
    }

    /** Called during card animation frames so external focus markers track the actual artwork. */
    public int getItemCount() {
        return items.size();
    }

    /** Maximum number of visible cards on either side of the selected center card. */
    public void setMaxVisibleCardDistance(int distance) {
        int clamped = Math.max(1, Math.min(3, distance));
        if (maxVisibleCardDistance == clamped) return;
        maxVisibleCardDistance = clamped;
        updateCardPositions(false);
    }

    public void setItems(List<AppItem> newItems) {
        pendingMoves.clear();
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        rebuildViews();
        if (selectedIndex >= items.size()) {
            selectedIndex = Math.max(0, items.size() - 1);
        }
        updateCardPositions(false);
        notifySelection();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    /** Returns the actual focused card so companion UI can track its position. */
    public View getSelectedCardView() {
        if (selectedIndex >= 0 && selectedIndex < cardViews.size()) {
            return cardSurfaceViews.get(selectedIndex);
        }
        return null;
    }

    /** Accent sampled from the selected app's real banner/icon, cached at card creation time. */
    public int getSelectedAccentColor() {
        if (selectedIndex >= 0 && selectedIndex < cardColors.size()) {
            return cardColors.get(selectedIndex);
        }
        return Color.rgb(168, 232, 255);
    }

    public void setSelectedIndex(int index) {
        pendingMoves.clear();
        int target = CarouselLayoutCalculator.clampIndex(index, items.size());
        if (target != selectedIndex) {
            selectedIndex = target;
            updateCardPositions(true);
            notifySelection();
        }
    }

    private void rebuildViews() {
        removeAllViews();
        cardViews.clear();
        cardSurfaceViews.clear();
        cardColors.clear();
        cardGlows.clear();

        Context context = getContext();
        float density = getResources().getDisplayMetrics().density;
        int cardWidth = dp(118);
        int cardHeight = dp(66);
        int reflectionWidth = Math.max(dp(10), dp(16));
        int cardPadding = dp(5);
        int labelHeight = dp(18);

        for (int i = 0; i < items.size(); i++) {
            AppItem item = items.get(i);
            FrameLayout card = new FrameLayout(context);
            LayoutParams outerLp = new LayoutParams(cardWidth, cardHeight, Gravity.CENTER);
            card.setLayoutParams(outerLp);
            card.setClipChildren(true);
            card.setClipToPadding(true);
            card.setBackgroundColor(Color.TRANSPARENT);

            Drawable artwork = item.getBanner() != null ? item.getBanner() : item.getIcon();
            int[] palette = sampleColorPalette(artwork);
            int sampledColor = averagePaletteColor(palette);
            cardColors.add(sampledColor);
            cardGlows.add(new AdaptiveGlowDrawable(palette, density, 12f, false));

            FrameLayout surface = new FrameLayout(context);
            LayoutParams surfaceLp = new LayoutParams(cardWidth, cardHeight, Gravity.CENTER);
            surface.setLayoutParams(surfaceLp);
            surface.setBackground(createCardBackground(sampledColor, false));
            surface.setForeground(cardGlows.get(i));
            surface.setClipChildren(true);
            surface.setClipToPadding(true);
            surface.setClipToOutline(true);
            card.addView(surface);
            cardSurfaceViews.add(surface);

            ImageView artworkView = new ImageView(context);
            artworkView.setLayoutParams(new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
            artworkView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            artworkView.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
            if (artwork != null) artworkView.setImageDrawable(copyDrawable(artwork));
            surface.addView(artworkView);

            // The mirror is an inset overlay clipped by this exact card surface.
            // No reflection view extends outside the hitbox or changes carousel measurements.
            if (artwork != null) {
                FrameLayout reflection = new FrameLayout(context);
                LayoutParams reflectionLp = new LayoutParams(
                        reflectionWidth, LayoutParams.MATCH_PARENT, Gravity.RIGHT | Gravity.TOP);
                reflection.setLayoutParams(reflectionLp);
                reflection.setClipChildren(true);
                reflection.setClipToPadding(true);

                ImageView mirrorView = new ImageView(context);
                mirrorView.setLayoutParams(new LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                mirrorView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                mirrorView.setImageDrawable(copyDrawable(artwork));
                mirrorView.setScaleX(-1f);
                mirrorView.setAlpha(0.28f);
                reflection.addView(mirrorView);

                View reflectionFade = new View(context);
                GradientDrawable fade = new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[] { 0x00000000, 0x44090C14, 0xCC090C14 });
                reflectionFade.setBackground(fade);
                reflection.addView(reflectionFade, new LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                surface.addView(reflection);
            }

            // In-card gloss and title strip remain clipped to the original 16:9 frame.
            View gloss = new View(context);
            GradientDrawable glossGradient = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { 0x26FFFFFF, 0x08FFFFFF, Color.TRANSPARENT });
            gloss.setBackground(glossGradient);
            gloss.setClickable(false);
            gloss.setFocusable(false);
            surface.addView(gloss, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            TextView title = new TextView(context);
            title.setText(item.getLabel());
            title.setTextColor(Color.WHITE);
            title.setTextSize(9);
            title.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.BOLD));
            title.setGravity(Gravity.CENTER);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setIncludeFontPadding(false);
            title.setPadding(dp(4), dp(1), dp(4), dp(1));
            GradientDrawable titleGlass = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { 0x52090C14, 0xE6090C14 });
            title.setBackground(titleGlass);
            title.setShadowLayer(dp(2), 0f, dp(1), 0xCC000000);
            title.setFocusable(false);
            title.setClickable(false);
            surface.addView(title, new LayoutParams(
                    LayoutParams.MATCH_PARENT, labelHeight, Gravity.BOTTOM));

            final int itemIndex = i;
            card.setFocusable(true);
            card.setClickable(true);
            card.setContentDescription(item.getLabel());
            card.setOnLongClickListener(v -> {
                if (selectionListener != null && itemIndex < items.size()) {
                    selectionListener.onItemLongPressed(itemIndex, items.get(itemIndex));
                }
                return true;
            });
            addView(card);
            cardViews.add(card);
        }
    }

    private Drawable copyDrawable(Drawable source) {
        if (source == null) return null;
        Drawable.ConstantState state = source.getConstantState();
        return state == null ? source : state.newDrawable(getResources()).mutate();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updateCardPositions(boolean animate) {
        int count = cardViews.size();
        if (positionAnimator != null) {
            positionAnimator.cancel();
            positionAnimator = null;
        }
        if (count == 0) return;

        float density = getResources().getDisplayMetrics().density;
        float baseWidth = 118f * density;
        float spacing = 12f * density;
        // Only the current five/seven-card viewport animates. Allocate motion buffers for
        // visible cards, not every installed app in the full library.
        int visibleCapacity = Math.min(count, maxVisibleCardDistance * 2 + 1);
        int[] visibleIndices = new int[visibleCapacity];
        float[] fromX = new float[visibleCapacity];
        float[] fromScaleX = new float[visibleCapacity];
        float[] fromScaleY = new float[visibleCapacity];
        float[] fromAlpha = new float[visibleCapacity];
        float[] fromRotationY = new float[visibleCapacity];
        float[] toX = new float[visibleCapacity];
        float[] toScale = new float[visibleCapacity];
        float[] toAlpha = new float[visibleCapacity];
        float[] toRotationY = new float[visibleCapacity];
        int visibleCount = 0;

        for (int i = 0; i < count; i++) {
            View card = cardViews.get(i);
            int offset = i - selectedIndex;
            CarouselLayoutCalculator.CardTransform transform =
                    CarouselLayoutCalculator.calculateTransform(offset, baseWidth, spacing);
            boolean visible = transform.visible && Math.abs(offset) <= maxVisibleCardDistance;
            if (!visible) {
                // Drop GPU resources as a card leaves the viewport; retained visible cards
                // keep their hardware layer through adjacent D-pad moves.
                if (card.getLayerType() != View.LAYER_TYPE_NONE) {
                    card.setLayerType(View.LAYER_TYPE_NONE, null);
                }
                card.setVisibility(View.GONE);
                continue;
            }

            card.setVisibility(View.VISIBLE);
            boolean focused = offset == 0;
            View surface = cardSurfaceViews.get(i);
            if (card.isSelected() != focused) {
                card.setSelected(focused);
                surface.setSelected(focused);
                surface.setBackground(createCardBackground(cardColors.get(i), focused));
                cardGlows.get(i).setFocused(focused);
            }
            card.setElevation(focused ? dp(6)
                    : Math.max(dp(1), dp(3) - dp(Math.abs(offset) * 0.5f)));

            int slot = visibleCount++;
            visibleIndices[slot] = i;
            fromX[slot] = card.getTranslationX();
            fromScaleX[slot] = card.getScaleX();
            fromScaleY[slot] = card.getScaleY();
            fromAlpha[slot] = card.getAlpha();
            fromRotationY[slot] = card.getRotationY();
            toX[slot] = transform.translationX;
            toScale[slot] = transform.scale;
            toAlpha[slot] = transform.alpha;
            toRotationY[slot] = transform.rotationY;
        }

        if (!animate) {
            for (int slot = 0; slot < visibleCount; slot++) {
                View card = cardViews.get(visibleIndices[slot]);
                card.setTranslationX(toX[slot]);
                card.setScaleX(toScale[slot]);
                card.setScaleY(toScale[slot]);
                card.setAlpha(toAlpha[slot]);
                card.setRotationY(toRotationY[slot]);
            }
            return;
        }

        final int animatedCardCount = visibleCount;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        positionAnimator = animator;
        animator.setDuration(150);
        animator.setInterpolator(new DecelerateInterpolator(1.2f));
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            // Critical hot path: iterate only cards that are actually on screen.
            for (int slot = 0; slot < animatedCardCount; slot++) {
                View card = cardViews.get(visibleIndices[slot]);
                card.setTranslationX(fromX[slot] + (toX[slot] - fromX[slot]) * t);
                card.setScaleX(fromScaleX[slot] + (toScale[slot] - fromScaleX[slot]) * t);
                card.setScaleY(fromScaleY[slot] + (toScale[slot] - fromScaleY[slot]) * t);
                card.setAlpha(fromAlpha[slot] + (toAlpha[slot] - fromAlpha[slot]) * t);
                card.setRotationY(fromRotationY[slot]
                        + (toRotationY[slot] - fromRotationY[slot]) * t);
            }
        });

        // Keep hardware layers for visible cards between quick D-pad moves. Recreating them
        // after every short slide adds GPU work and can cause visible stutter on TV hardware.
        for (int slot = 0; slot < visibleCount; slot++) {
            View card = cardViews.get(visibleIndices[slot]);
            if (card.getLayerType() != View.LAYER_TYPE_HARDWARE) {
                card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
        }
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (positionAnimator == animator) positionAnimator = null;
                runNextPendingMove();
            }

            @Override public void onAnimationCancel(Animator animation) {
                if (positionAnimator == animator) positionAnimator = null;
            }
        });
        animator.start();
    }

    private GradientDrawable createCardBackground(int color, boolean focused) {
        float density = getResources().getDisplayMetrics().density;
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(12f * density);
        if (focused) {
            int red = Color.red(color);
            int green = Color.green(color);
            int blue = Color.blue(color);
            // Soft radial illumination + color-matched edge, all inside the same
            // rounded card. This is intentionally a fill glow, not a detached outline.
            background.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            background.setGradientCenter(0.5f, 0.44f);
            background.setGradientRadius(92f * density);
            background.setColors(new int[] {
                    Color.argb(78, red, green, blue),
                    Color.argb(36, red, green, blue),
                    Color.argb(12, red, green, blue)
            });
            // AdaptiveGlowDrawable renders the multi-tone edge separately; keep this layer
            // focused on the soft radial fill to avoid a competing single-color border.
        } else {
            background.setColor(0x08FFFFFF);
        }
        return background;
    }

    /**
     * Samples eight perimeter zones from a tiny drawable snapshot. Sampling happens only
     * when cards are built, never during D-pad focus or animation frames.
     */
    private int[] sampleColorPalette(Drawable drawable) {
        int fallback = Color.rgb(112, 190, 255);
        int[] palette = new int[8];
        if (drawable == null) {
            java.util.Arrays.fill(palette, fallback);
            return palette;
        }

        Bitmap bitmap = null;
        android.graphics.Rect oldBounds = new android.graphics.Rect(drawable.getBounds());
        try {
            final int size = 24;
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);

            long allRed = 0, allGreen = 0, allBlue = 0, allCount = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int pixel = bitmap.getPixel(x, y);
                    if (Color.alpha(pixel) < 100) continue;
                    int max = Math.max(Color.red(pixel), Math.max(Color.green(pixel), Color.blue(pixel)));
                    if (max < 20) continue;
                    allRed += Color.red(pixel);
                    allGreen += Color.green(pixel);
                    allBlue += Color.blue(pixel);
                    allCount++;
                }
            }
            if (allCount > 0) fallback = Color.rgb((int)(allRed / allCount),
                    (int)(allGreen / allCount), (int)(allBlue / allCount));

            // Palette order is top-left, top, top-right, right, bottom-right,
            // bottom, bottom-left, left, matching AdaptiveGlowDrawable's contract.
            int[][] zones = {
                    {0, 0, 8, 6}, {8, 0, 16, 6}, {16, 0, 24, 6},
                    {18, 6, 24, 18}, {16, 18, 24, 24}, {8, 18, 16, 24},
                    {0, 18, 8, 24}, {0, 6, 6, 18}
            };
            for (int i = 0; i < zones.length; i++) {
                long red = 0, green = 0, blue = 0, count = 0;
                int[] zone = zones[i];
                for (int y = zone[1]; y < zone[3]; y++) {
                    for (int x = zone[0]; x < zone[2]; x++) {
                        int pixel = bitmap.getPixel(x, y);
                        if (Color.alpha(pixel) < 100) continue;
                        int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
                        if (Math.max(r, Math.max(g, b)) < 20) continue;
                        red += r;
                        green += g;
                        blue += b;
                        count++;
                    }
                }
                int sampled = count == 0 ? fallback
                        : Color.rgb((int)(red / count), (int)(green / count), (int)(blue / count));
                palette[i] = liftEdgeColor(sampled);
            }
            return palette;
        } catch (Exception ignored) {
            java.util.Arrays.fill(palette, liftEdgeColor(fallback));
            return palette;
        } finally {
            drawable.setBounds(oldBounds);
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private int liftEdgeColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] >= 0.12f) hsv[1] = Math.min(0.88f, hsv[1] * 1.08f);
        hsv[2] = Math.min(0.96f, Math.max(0.48f, hsv[2] * 1.12f));
        return Color.HSVToColor(hsv);
    }

    private int averagePaletteColor(int[] palette) {
        if (palette == null || palette.length == 0) return Color.rgb(112, 190, 255);
        long red = 0, green = 0, blue = 0;
        for (int color : palette) {
            red += Color.red(color);
            green += Color.green(color);
            blue += Color.blue(color);
        }
        return Color.rgb((int)(red / palette.length), (int)(green / palette.length),
                (int)(blue / palette.length));
    }

    private void notifySelection() {
        if (selectionListener != null && selectedIndex >= 0 && selectedIndex < items.size()) {
            selectionListener.onItemSelected(selectedIndex, items.get(selectedIndex));
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (!centerLongPressHandled && selectionListener != null
                    && selectedIndex >= 0 && selectedIndex < items.size()) {
                selectionListener.onItemActivated(selectedIndex, items.get(selectedIndex));
            }
            centerLongPressHandled = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Keep the focused card at offset zero (the third slot in a five-card row).
     * Repeated remote inputs are serialized so a new key press cannot cancel a
     * partially completed slide and leave the row visually out of phase.
     */
    private void requestSelectionMove(int direction) {
        if (items.isEmpty()) return;
        if (positionAnimator != null) {
            if (!pendingMoves.isEmpty() && pendingMoves.peekLast() == -direction) {
                pendingMoves.removeLast();
                return;
            }
            if (pendingMoves.size() < MAX_PENDING_MOVES) pendingMoves.addLast(direction);
            return;
        }
        applySelectionMove(direction);
    }

    private void applySelectionMove(int direction) {
        int target = selectedIndex + direction;
        if (target < 0 || target >= items.size()) return;
        selectedIndex = target;
        updateCardPositions(true);
        notifySelection();
    }

    private void runNextPendingMove() {
        while (positionAnimator == null && !pendingMoves.isEmpty()) {
            int direction = pendingMoves.removeFirst();
            int target = selectedIndex + direction;
            if (target < 0 || target >= items.size()) continue;
            applySelectionMove(direction);
            return;
        }
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Center/Enter repeats are reserved for long-press menus and must bypass
        // directional-repeat throttling.
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (event.getRepeatCount() > 0) {
                        if (!centerLongPressHandled && selectionListener != null
                                && selectedIndex >= 0 && selectedIndex < items.size()) {
                            centerLongPressHandled = true;
                            selectionListener.onItemLongPressed(selectedIndex, items.get(selectedIndex));
                        }
                        return true;
                    }
                    centerLongPressHandled = false;
                    return true;
            }
        }
        long now = System.currentTimeMillis();
        // Throttle held-key repeats, never a fresh press in the opposite direction.
        if (event.getRepeatCount() > 0 && now - lastKeyTime < MIN_REPEAT_INTERVAL_MS) return true;
        lastKeyTime = now;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                requestSelectionMove(-1);
                return true; // Consume the key; the focused card stays in the center slot.

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                requestSelectionMove(1);
                return true; // Consume the key; the focused card stays in the center slot.


            case KeyEvent.KEYCODE_DPAD_DOWN:
                // MainActivity intercepts DOWN on the home carousel and transitions
                // vertically into the full app library. Keep consumed as a safe fallback.
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                // Up moves to controls -> let framework or focus controller handle
                return super.onKeyDown(keyCode, event);
        }

        return super.onKeyDown(keyCode, event);
    }
}
