package com.appvolume.xposed;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The row of application sliders shared by both volume-dialog flavours.
 *
 * <p>The compose dialog and the classic dialog place the row in different
 * windows, but the row itself behaves in exactly the same way: the same set
 * of playing applications, the same one-percent steps, the same opening and
 * closing animation and the same order. Keeping that logic in one place means
 * the classic panel cannot drift away from the compose panel again.
 */
final class SliderPanel {
    /**
     * The volume is changed in steps of one percent. Five percent used to
     * make the bottom of the logarithmic volume curve jump by several
     * decibels per step; one percent keeps the same scale but gives the
     * quiet end enough intermediate levels.
     */
    static final int STEP = 1;

    /** Gap between the app sliders and the main volume panel. */
    static final int MARGIN_DP = 6;

    static final long ANIMATION_MS = 280L;
    private static final long OPEN_STAGGER_MS = 45L;
    private static final long CLOSE_STAGGER_MS = 32L;
    private static final long MIN_FADE_MS = 140L;
    private static final int FALLBACK_WIDTH_DP = 52;

    /** One slider row. The view is the item added to the shared container. */
    interface Slider {
        View getView();

        void setPercent(int percent);

        void release();
    }

    interface Factory {
        Slider create(Context context, String pkg, int percent, VolumeListener listener);
    }

    interface VolumeListener {
        void onVolumeChanged(String pkg, int percent);
    }

    private final Context context;
    private final LinearLayout root;
    private final Factory factory;
    private final VolumeListener listener;
    private final Runnable onEmpty;
    private final Map<String, Slider> sliders = new LinkedHashMap<>();

    private Runnable closeEnd;
    private boolean opening;
    private boolean closing;

    SliderPanel(Context context, Factory factory, VolumeListener listener, Runnable onEmpty) {
        this.context = context;
        this.factory = factory;
        this.listener = listener;
        this.onEmpty = onEmpty;

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        // The host attaches the row before its final position is known.
        // Keep it invisible until animateOpen() places it, otherwise the first
        // frame can flash in the corner of the window.
        root.setVisibility(View.INVISIBLE);
        root.setAlpha(0f);
    }

    LinearLayout getView() {
        return root;
    }

    boolean isOpening() {
        return opening;
    }

    boolean isClosing() {
        return closing;
    }

    boolean isEmpty() {
        return sliders.isEmpty();
    }

    /**
     * Adds, removes and updates the rows so that they match the playing
     * applications. Returns true when the panel still has at least one row.
     */
    boolean sync(int width, int height, Runnable after) {
        if (closing) {
            return false;
        }
        EffectManager manager = EffectManager.get();
        if (manager == null) {
            empty();
            return false;
        }

        List<String> packages = manager.getActivePackages();
        if (packages.isEmpty()) {
            empty();
            return false;
        }

        List<String> removed = new ArrayList<>();
        for (String pkg : sliders.keySet()) {
            if (!packages.contains(pkg)) {
                removed.add(pkg);
            }
        }
        for (String pkg : removed) {
            Slider slider = sliders.remove(pkg);
            if (slider != null) {
                root.removeView(slider.getView());
                slider.release();
            }
        }

        int rowWidth = width > 0 ? width : dp(context, FALLBACK_WIDTH_DP);
        int rowHeight = height > 0 ? height : ViewGroup.LayoutParams.MATCH_PARENT;
        for (String pkg : packages) {
            Slider slider = sliders.get(pkg);
            if (slider == null) {
                slider = factory.create(context, pkg, manager.getVolume(pkg), listener);
                if (slider == null) {
                    continue;
                }
                sliders.put(pkg, slider);
                root.addView(slider.getView(),
                        new LinearLayout.LayoutParams(rowWidth, rowHeight));
            } else {
                slider.setPercent(manager.getVolume(pkg));
            }
        }

        if (sliders.isEmpty()) {
            empty();
            return false;
        }
        if (after != null) {
            after.run();
        }
        return true;
    }

    /** Updates a slider after the volume changed somewhere else. */
    void onVolumeChanged(String pkg, int volume) {
        Slider slider = sliders.get(pkg);
        if (slider != null) {
            slider.setPercent(volume);
        }
    }

    /** Updates the size of every row after the main slider was measured. */
    boolean setRowSize(int width, int height) {
        boolean changed = false;
        for (Slider slider : sliders.values()) {
            View view = slider.getView();
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params == null) {
                params = new LinearLayout.LayoutParams(
                        width > 0 ? width : dp(context, FALLBACK_WIDTH_DP),
                        height > 0 ? height : ViewGroup.LayoutParams.MATCH_PARENT);
                changed = true;
            } else {
                if (width > 0 && params.width != width) {
                    params.width = width;
                    changed = true;
                }
                if (height != Integer.MIN_VALUE && params.height != height) {
                    params.height = height;
                    changed = true;
                }
            }
            view.setLayoutParams(params);
        }
        return changed;
    }

    /**
     * Slides the row out from underneath the main panel and fades every slider
     * in from left to right, exactly like the compose volume dialog.
     *
     * @param hiddenX translation where the row starts (under the main panel)
     * @param targetX translation where the row stops (left of the panel)
     * @param targetY vertical translation of the row
     */
    void animateOpen(float hiddenX, float targetX, float targetY) {
        animateOpen(hiddenX, targetX, targetY, null);
    }

    void animateOpen(float hiddenX, float targetX, float targetY, Runnable after) {
        opening = true;
        closing = false;
        cancelClose();
        root.animate().cancel();
        for (int i = 0; i < root.getChildCount(); i++) {
            root.getChildAt(i).animate().cancel();
        }

        root.setTranslationY(targetY);
        root.setTranslationX(hiddenX);
        root.setAlpha(1f);
        root.setVisibility(View.VISIBLE);
        for (int i = 0; i < root.getChildCount(); i++) {
            root.getChildAt(i).setAlpha(0f);
        }

        root.animate()
                .translationX(targetX)
                .setDuration(ANIMATION_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    opening = false;
                    if (after != null) {
                        after.run();
                    }
                })
                .start();

        for (int i = 0; i < root.getChildCount(); i++) {
            root.getChildAt(i).animate()
                    .alpha(1f)
                    .setStartDelay(i * OPEN_STAGGER_MS)
                    .setDuration(ANIMATION_MS)
                    .start();
        }
    }

    /**
     * Slides the row back under the main panel. The slider nearest to the main
     * panel disappears first, the reverse of the opening order.
     */
    void animateClose(float hiddenX, Runnable after) {
        if (closing) {
            return;
        }
        closing = true;
        opening = false;
        cancelClose();
        root.animate().cancel();

        int count = root.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = root.getChildAt(i);
            child.animate().cancel();
            long delay = (count - 1 - i) * CLOSE_STAGGER_MS;
            child.animate()
                    .alpha(0f)
                    .setStartDelay(delay)
                    .setDuration(Math.max(MIN_FADE_MS, ANIMATION_MS - CLOSE_STAGGER_MS))
                    .start();
        }

        root.animate()
                .translationX(hiddenX)
                .setDuration(ANIMATION_MS)
                .setInterpolator(new AccelerateInterpolator(1.4f))
                .start();

        long childEnd = Math.max(0, count - 1) * CLOSE_STAGGER_MS
                + Math.max(MIN_FADE_MS, ANIMATION_MS - CLOSE_STAGGER_MS);
        Runnable end = () -> {
            if (!closing) {
                return;
            }
            closeEnd = null;
            closing = false;
            if (after != null) {
                after.run();
            }
        };
        closeEnd = end;
        root.postDelayed(end, Math.max(ANIMATION_MS, childEnd) + 40L);
    }

    /**
     * Drops every row immediately. The host removes or dismisses the window;
     * the shared container is left empty but attached.
     */
    void closeImmediate() {
        opening = false;
        closing = false;
        cancelClose();
        root.animate().cancel();
        for (int i = 0; i < root.getChildCount(); i++) {
            root.getChildAt(i).animate().cancel();
        }
        for (Slider slider : sliders.values()) {
            slider.release();
        }
        sliders.clear();
        root.removeAllViews();
        root.setVisibility(View.INVISIBLE);
        root.setAlpha(0f);
        root.setTranslationX(0f);
        root.setTranslationY(0f);
    }

    private void empty() {
        if (onEmpty != null) {
            onEmpty.run();
        }
    }

    private void cancelClose() {
        if (closeEnd != null) {
            root.removeCallbacks(closeEnd);
            closeEnd = null;
        }
    }

    /** Snaps a volume to the one-percent grid. */
    static int snap(int value) {
        int clamped = Math.max(0, Math.min(100, value));
        return Math.round(clamped / (float) STEP) * STEP;
    }

    private static int dp(Context context, float value) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
