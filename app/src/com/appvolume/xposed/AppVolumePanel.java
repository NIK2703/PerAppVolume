package com.appvolume.xposed;

import android.app.Dialog;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The per-app volume sliders.
 *
 * <p>They live inside the window of the SystemUI volume dialog: the window of
 * the dialog is widened so that there is room next to the panel, and the
 * sliders are put behind the panel, so that they unfold from underneath it
 * while the volume panel itself stays on the screen. Every slider looks like
 * the vertical slider of the volume panel and the row of them stands directly
 * to the left of it.
 */
final class AppVolumePanel {
    private static final String TAG = "AppVolumePanel: ";
    private static final String SYSTEMUI = "com.android.systemui";

    private static final long KEEP_ALIVE_MS = 700L;
    private static final long ANIMATION_MS = 280L;
    private static final long CLOSE_STAGGER_MS = 32L;
    private static final int MARGIN_DP = 6;

    private static final android.os.Handler sMain =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static WeakReference<Dialog> sDialog = new WeakReference<>(null);
    private static ViewGroup sPanel;
    private static LinearLayout sItems;
    private static final Map<String, AppInfo> sApps = new LinkedHashMap<>();
    private static int sOriginalWidth = Integer.MIN_VALUE;
    private static int sMainWidth;
    private static int sMarginPx;
    private static boolean sOpen;
    private static boolean sClosing;
    private static boolean sOpening;
    private static WeakReference<View> sMainSlider = new WeakReference<>(null);
    private static WeakReference<ViewGroup> sAlignPanel = new WeakReference<>(null);
    private static AlignListener sAlignListener;
    private static final int[] sMainLocation = new int[2];
    private static final int[] sPanelLocation = new int[2];

    /** Actions that have to run after the closing animation, e.g. a dismiss. */
    private static final List<Runnable> sAfterClose = new ArrayList<>();

    private static final Runnable sKeepAlive = new Runnable() {
        @Override
        public void run() {
            keepDialogAlive();
            sMain.postDelayed(this, KEEP_ALIVE_MS);
        }
    };

    private static final class AppInfo {
        final String pkg;
        RomAppSlider slider;
        View item;

        AppInfo(String pkg) {
            this.pkg = pkg;
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Called when the app-volume button of the volume panel is pressed. */
    static void toggle(Dialog dialog) {
        if (sOpen && sDialog.get() == dialog) {
            closeAnimated();
        } else {
            open(dialog);
        }
    }

    static boolean isOpen() {
        return sOpen;
    }

    static boolean isClosing() {
        return sClosing;
    }

    /** True while the dialog still owns the rows, even during the close animation. */
    static boolean shouldHoldDismiss(Dialog dialog) {
        return dialog != null && sDialog.get() == dialog && (sPanel != null || sClosing);
    }

    /**
     * The panel started to disappear. Start the reverse animation while the
     * ROM is still animating its own panel out, not after the window is gone.
     */
    static void onDialogClosing(Dialog dialog) {
        if (dialog != null && sDialog.get() == dialog) {
            closeAnimated();
        }
    }

    /** Runs the action after the rows have collapsed, or immediately if already closed. */
    static void afterClose(Runnable action) {
        if (action == null) {
            return;
        }
        if (!sClosing && sPanel == null) {
            action.run();
            return;
        }
        sAfterClose.add(action);
        if (!sClosing) {
            closeAnimated();
        }
    }

    /** The volume dialog was closed, drop the sliders with it. */
    static void onDialogStopped(Dialog dialog) {
        if (sDialog.get() == dialog) {
            closeImmediate();
        }
        restoreWindow(dialog);
    }

    /** The set of playing apps changed, sync the list of sliders. */
    static void onSessionsChanged() {
        if (!sOpen || sClosing) {
            return;
        }
        sMain.post(AppVolumePanel::syncRows);
    }

    /** A volume was changed somewhere else, update the matching slider. */
    static void onVolumeChanged(String pkg, int volume) {
        if (!sOpen || sClosing) {
            return;
        }
        sMain.post(() -> {
            AppInfo info = sApps.get(pkg);
            if (info != null) {
                setProgress(info, volume);
            }
        });
    }

    // ------------------------------------------------------------------
    // Opening and closing
    // ------------------------------------------------------------------

    private static void open(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) {
            return;
        }
        closeImmediate();

        Context context = dialog.getWindow().getDecorView().getContext();
        ViewGroup content = findContentFrame(dialog);
        if (content == null) {
            XposedBridge.log(TAG + "no content frame");
            return;
        }

        sDialog = new WeakReference<>(dialog);
        sOpen = true;
        sClosing = false;
        sOpening = true;
        sMainWidth = 0;
        sMainSlider = new WeakReference<>(null);
        SystemUiHook.onPanelVisibilityChanged();

        widenWindow(dialog);

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        // The panel is attached before its final position is known. Keep it
        // invisible until placePanel() has set the position, otherwise the
        // first frame shows all rows in the top-left corner of the screen.
        panel.setVisibility(View.INVISIBLE);
        panel.setAlpha(0f);
        sPanel = panel;
        sItems = panel;

        content.addView(panel, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.TOP));
        registerTouchable(panel);

        syncRows();
        // Apply the insets before measuring the final position: the ROM moves
        // the main slider vertically when it handles window insets.
        requestInsets(panel);
        panel.post(() -> placePanel(panel, content, 0));
        sMain.post(sKeepAlive);
        XposedBridge.log(TAG + "panel opened");
    }

    /** Removes the rows right away, used before a new panel is built. */
    private static void closeImmediate() {
        sMain.removeCallbacks(sKeepAlive);
        if (sPanel != null) {
            try {
                sPanel.animate().cancel();
                for (int i = 0; i < sPanel.getChildCount(); i++) {
                    sPanel.getChildAt(i).animate().cancel();
                }
            } catch (Throwable ignored) {
            }
        }
        finishClose();
    }

    /**
     * Slides the rows back under the main panel and fades them out in the
     * opposite order of the opening animation. The views stay alive until the
     * animation ends; removing them here is what used to make the panel
     * disappear instantly.
     */
    private static void closeAnimated() {
        if (sClosing) {
            return;
        }
        sClosing = true;
        // Drop the button ripple/background right away; the rows may keep
        // collapsing for another few hundred milliseconds.
        SystemUiHook.onPanelVisibilityChanged();
        ViewGroup panel = sPanel;
        if (panel == null) {
            finishClose();
            return;
        }

        sMain.removeCallbacks(sKeepAlive);
        unregisterTouchable(panel);

        int count = panel.getChildCount();
        float width = sMainWidth > 0 ? sMainWidth : panel.getWidth();
        float targetX = panel.getTranslationX() + Math.max(width, 0f);

        panel.animate().cancel();
        for (int i = 0; i < count; i++) {
            View child = panel.getChildAt(i);
            child.animate().cancel();
            // The last child is nearest to the main panel, so it goes first,
            // exactly the reverse of the staggered opening.
            long delay = (count - 1 - i) * CLOSE_STAGGER_MS;
            child.animate()
                    .alpha(0f)
                    .setStartDelay(delay)
                    .setDuration(Math.max(140L, ANIMATION_MS - CLOSE_STAGGER_MS))
                    .start();
        }

        panel.animate()
                .translationX(targetX)
                .setDuration(ANIMATION_MS)
                .setInterpolator(new AccelerateInterpolator(1.4f))
                .start();

        long childEnd = Math.max(0, count - 1) * CLOSE_STAGGER_MS
                + Math.max(140L, ANIMATION_MS - CLOSE_STAGGER_MS);
        sMain.postDelayed(() -> {
            if (sClosing && sPanel == panel) {
                finishClose();
            }
        }, Math.max(ANIMATION_MS, childEnd) + 40L);
        XposedBridge.log(TAG + "panel closing");
    }

    private static void finishClose() {
        if (sPanel != null) {
            try {
                stopAlignment();
                sPanel.animate().cancel();
                for (int i = 0; i < sPanel.getChildCount(); i++) {
                    sPanel.getChildAt(i).animate().cancel();
                }
                unregisterTouchable(sPanel);
                ViewGroup parent = (ViewGroup) sPanel.getParent();
                if (parent != null) {
                    parent.removeView(sPanel);
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            sPanel = null;
        }
        sItems = null;
        sMainSlider = new WeakReference<>(null);
        sOpening = false;
        for (AppInfo info : sApps.values()) {
            if (info.slider != null) {
                SystemUiHook.unregisterSlider(info.slider);
            }
        }
        sApps.clear();
        sOpen = false;
        sClosing = false;
        SystemUiHook.onPanelVisibilityChanged();
        sDialog = new WeakReference<>(null);

        List<Runnable> actions = new ArrayList<>(sAfterClose);
        sAfterClose.clear();
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    /**
     * Puts the row of sliders directly to the left of the main volume slider
     * and slides it out from underneath the panel.
     */
    private static void placePanel(ViewGroup panel, ViewGroup content, int attempt) {
        if (panel != sPanel || sClosing) {
            return;
        }
        Context context = panel.getContext();
        View main = findMainSlider(context);
        if (main == null) {
            XposedBridge.log(TAG + "main slider not found");
            return;
        }

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        if (content.getWidth() < screenWidth && attempt < 30) {
            // The window was widened, wait until the new size is applied.
            panel.post(() -> placePanel(panel, content, attempt + 1));
            return;
        }

        int mainWidth = main.getWidth();
        int mainHeight = main.getHeight();
        if (mainWidth <= 0 || mainHeight <= 0) {
            panel.post(() -> placePanel(panel, content, attempt + 1));
            return;
        }
        sMainWidth = mainWidth;
        sMarginPx = dp(context, MARGIN_DP);
        sMainSlider = new WeakReference<>(main);

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (params.height != mainHeight) {
            params.height = mainHeight;
            panel.setLayoutParams(params);
            panel.post(() -> placePanel(panel, content, attempt + 1));
            return;
        }

        main.getLocationOnScreen(sMainLocation);
        content.getLocationOnScreen(sPanelLocation);
        int mainLeft = sMainLocation[0] - sPanelLocation[0];
        int mainTop = sMainLocation[1] - sPanelLocation[1];

        View background = findBackground(context);
        int backgroundTop = -1;
        int backgroundHeight = -1;
        if (background != null) {
            int[] backgroundLocation = new int[2];
            background.getLocationOnScreen(backgroundLocation);
            backgroundTop = backgroundLocation[1] - sPanelLocation[1];
            backgroundHeight = background.getHeight();
        }

        int targetX = mainLeft - sMarginPx - panel.getWidth();
        float targetTranslationX = targetX - panel.getLeft();
        float targetTranslationY = mainTop - panel.getTop();

        // All values are set while the panel is still invisible. It becomes
        // visible at the hidden-under-the-panel position and only then starts
        // the slide, so it cannot flash in the corner.
        panel.setTranslationY(targetTranslationY);
        panel.setTranslationX(targetTranslationX + mainWidth);
        for (int i = 0; i < panel.getChildCount(); i++) {
            panel.getChildAt(i).setAlpha(0f);
        }
        panel.setAlpha(1f);
        panel.setVisibility(View.VISIBLE);
        startAlignment(panel, main);

        panel.animate()
                .translationX(targetTranslationX)
                .setDuration(ANIMATION_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    if (panel == sPanel) {
                        sOpening = false;
                    }
                })
                .start();

        // Every slider appears in turn, like they are pulled out of the panel.
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            child.animate()
                    .alpha(1f)
                    .setStartDelay(i * 45L)
                    .setDuration(ANIMATION_MS)
                    .start();
        }

        XposedBridge.log(TAG + "row placed at " + targetX + " main " + mainLeft
                + " top " + mainTop + " width " + panel.getWidth()
                + " height " + mainHeight + " content " + content.getWidth()
                + "x" + content.getHeight() + " bgTop " + backgroundTop
                + " bgHeight " + backgroundHeight);
    }

    /**
     * Keeps the rows glued to the main slider while they are open. The ROM
     * moves and resizes the main slider when window insets or the captions
     * button change, so a one-time position is not enough.
     */
    private static void startAlignment(ViewGroup panel, View main) {
        stopAlignment();
        AlignListener listener = new AlignListener(panel, main);
        sAlignListener = listener;
        sAlignPanel = new WeakReference<>(panel);
        panel.getViewTreeObserver().addOnPreDrawListener(listener);
    }

    private static void stopAlignment() {
        AlignListener listener = sAlignListener;
        ViewGroup panel = sAlignPanel.get();
        sAlignListener = null;
        sAlignPanel = new WeakReference<>(null);
        if (listener == null || panel == null) {
            return;
        }
        try {
            panel.getViewTreeObserver().removeOnPreDrawListener(listener);
        } catch (Throwable ignored) {
        }
    }

    private static final class AlignListener implements ViewTreeObserver.OnPreDrawListener {
        private final ViewGroup panel;
        private final View main;

        AlignListener(ViewGroup panel, View main) {
            this.panel = panel;
            this.main = main;
        }

        @Override
        public boolean onPreDraw() {
            if (panel != sPanel || sClosing || panel.getVisibility() != View.VISIBLE) {
                if (sAlignListener == this) {
                    stopAlignment();
                } else {
                    panel.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                return true;
            }

            main.getLocationOnScreen(sMainLocation);
            panel.getLocationOnScreen(sPanelLocation);

            int dy = sMainLocation[1] - sPanelLocation[1];
            if (dy != 0) {
                panel.setTranslationY(panel.getTranslationY() + dy);
            }

            if (!sOpening) {
                int desiredX = sMainLocation[0] - sMarginPx - panel.getWidth();
                int dx = desiredX - sPanelLocation[0];
                if (dx != 0) {
                    panel.setTranslationX(panel.getTranslationX() + dx);
                }
            }

            int height = main.getHeight();
            ViewGroup.LayoutParams params = panel.getLayoutParams();
            if (height > 0 && params != null && params.height != height) {
                params.height = height;
                panel.setLayoutParams(params);
            }
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private static View buildItem(Context context, AppInfo info, int volume) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        info.item = item;

        // The slider itself is the one of the volume panel: the very same
        // compose function, only the state of the view model is ours. Its
        // app icon is part of the slider, so no separate label or mute
        // button is added below it.
        RomAppSlider slider = RomAppSlider.create(context, info.pkg, volume,
                (p, percent) -> applyVolume(p, percent));
        if (slider != null) {
            item.addView(slider.getView(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            info.slider = slider;
            SystemUiHook.registerSlider(slider);
        }

        setProgress(info, volume);
        return item;
    }

    private static void setProgress(AppInfo info, int volume) {
        if (info.slider != null) {
            info.slider.setPercent(volume);
        }
    }

    /** Adds, removes and updates the sliders so that they match the players. */
    private static void syncRows() {
        if (!sOpen || sClosing || sItems == null) {
            return;
        }
        EffectManager manager = EffectManager.get();
        if (manager == null) {
            return;
        }
        List<String> packages = manager.getActivePackages();

        List<String> removed = new ArrayList<>();
        for (String pkg : sApps.keySet()) {
            if (!packages.contains(pkg)) {
                removed.add(pkg);
            }
        }
        for (String pkg : removed) {
            AppInfo info = sApps.remove(pkg);
            if (info != null && info.item != null && info.item.getParent() != null) {
                ((ViewGroup) info.item.getParent()).removeView(info.item);
            }
            if (info != null && info.slider != null) {
                SystemUiHook.unregisterSlider(info.slider);
            }
        }

        View main = findMainSlider(sItems.getContext());
        int width = main != null && main.getWidth() > 0 ? main.getWidth() : dp(sItems.getContext(), 52);
        for (String pkg : packages) {
            AppInfo info = sApps.get(pkg);
            if (info == null) {
                info = new AppInfo(pkg);
                sApps.put(pkg, info);
                View item = buildItem(sItems.getContext(), info, manager.getVolume(pkg));
                sItems.addView(item, new LinearLayout.LayoutParams(
                        width, ViewGroup.LayoutParams.MATCH_PARENT));
                continue;
            }
            setProgress(info, manager.getVolume(pkg));
        }
        requestInsets(sPanel);
    }

    // ------------------------------------------------------------------
    // Volume handling
    // ------------------------------------------------------------------

    private static void applyVolume(String pkg, int value) {
        EffectManager manager = EffectManager.get();
        if (manager != null) {
            manager.setVolume(pkg, value);
        }
    }

    // ------------------------------------------------------------------
    // Window handling
    // ------------------------------------------------------------------

    /**
     * Widens the dialog once, before it is shown. Keeping the window wide for
     * the whole lifetime of the dialog means that opening and closing the app
     * rows does not resize the window (and therefore does not move the main
     * volume panel vertically).
     */
    static void prepareWindow(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) {
            return;
        }
        try {
            Window window = dialog.getWindow();
            WindowManager.LayoutParams params = window.getAttributes();
            if (sOriginalWidth == Integer.MIN_VALUE) {
                sOriginalWidth = params.width;
            }
            if (params.width == WindowManager.LayoutParams.MATCH_PARENT) {
                return;
            }
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(params);
            XposedBridge.log(TAG + "window widened, original width " + sOriginalWidth
                    + " height " + params.height);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void widenWindow(Dialog dialog) {
        prepareWindow(dialog);
    }

    private static void restoreWindow(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null
                || sOriginalWidth == Integer.MIN_VALUE) {
            sOriginalWidth = Integer.MIN_VALUE;
            return;
        }
        try {
            WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
            params.width = sOriginalWidth;
            dialog.getWindow().setAttributes(params);
            XposedBridge.log(TAG + "window restored to " + sOriginalWidth);
        } catch (Throwable ignored) {
        }
        sOriginalWidth = Integer.MIN_VALUE;
    }

    /** The vertical slider of the system volume panel. */
    private static View findMainSlider(Context context) {
        Dialog dialog = sDialog.get();
        if (dialog == null || dialog.getWindow() == null) {
            return null;
        }
        int id = identifier(context, "volume_dialog_main_slider_container", "id");
        if (id == 0) {
            return null;
        }
        return dialog.getWindow().getDecorView().findViewById(id);
    }

    /** The rounded background of the volume panel. */
    private static View findBackground(Context context) {
        Dialog dialog = sDialog.get();
        if (dialog == null || dialog.getWindow() == null) {
            return null;
        }
        int id = identifier(context, "volume_dialog_background", "id");
        if (id == 0) {
            return null;
        }
        return dialog.getWindow().getDecorView().findViewById(id);
    }

    private static ViewGroup findContentFrame(Dialog dialog) {
        View decor = dialog.getWindow().getDecorView();
        View content = decor.findViewById(android.R.id.content);
        if (content instanceof ViewGroup) {
            return (ViewGroup) content;
        }
        return null;
    }

    /**
     * Adds the sliders to the touchable bounds of the volume dialog, otherwise
     * the window manager does not deliver touches to them.
     */
    private static void registerTouchable(View view) {
        Object viewModel = SystemUiHook.getViewModel();
        if (viewModel == null) {
            return;
        }
        try {
            Object collection = XposedHelpers.getObjectField(
                    viewModel, "touchableBoundsViews");
            if (collection instanceof java.util.Collection) {
                @SuppressWarnings("unchecked")
                java.util.Collection<Object> views = (java.util.Collection<Object>) collection;
                if (!views.contains(view)) {
                    views.add(view);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void unregisterTouchable(View view) {
        Object viewModel = SystemUiHook.getViewModel();
        if (viewModel == null) {
            return;
        }
        try {
            Object collection = XposedHelpers.getObjectField(
                    viewModel, "touchableBoundsViews");
            if (collection instanceof java.util.Collection) {
                @SuppressWarnings("unchecked")
                java.util.Collection<Object> views = (java.util.Collection<Object>) collection;
                views.remove(view);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void requestInsets(View view) {
        if (view == null) {
            return;
        }
        try {
            View root = view.getRootView();
            if (root != null) {
                root.requestApplyInsets();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Keeps the volume dialog on the screen: the ROM hides it after a timeout,
     * and the timeout is restarted through the visibility interactor.
     */
    private static void keepDialogAlive() {
        if (sClosing) {
            return;
        }
        Dialog dialog = sDialog.get();
        if (dialog == null) {
            return;
        }
        try {
            Object interactor = XposedHelpers.getObjectField(
                    dialog, "visibilityInteractor");
            if (interactor != null) {
                XposedHelpers.callMethod(interactor, "resetDismissTimeout");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "keep alive failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    private static int identifier(Context context, String name, String type) {
        try {
            return context.getResources().getIdentifier(name, type, SYSTEMUI);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
