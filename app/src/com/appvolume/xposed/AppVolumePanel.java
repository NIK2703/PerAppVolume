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
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The per-app volume sliders of the compose volume dialog.
 *
 * <p>They live inside the window of the SystemUI volume dialog: the window of
 * the dialog is widened so that there is room next to the panel, and the
 * sliders are put behind the panel, so that they unfold from underneath it
 * while the volume panel itself stays on the screen. Every slider is the one
 * of the volume panel and the row of them stands directly to the left of it.
 */
final class AppVolumePanel {
    private static final String TAG = "AppVolumePanel: ";
    private static final String SYSTEMUI = "com.android.systemui";

    private static final android.os.Handler sMain =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static WeakReference<Dialog> sDialog = new WeakReference<>(null);
    private static ViewGroup sPanel;
    private static SliderPanel sSliders;
    private static int sOriginalWidth = Integer.MIN_VALUE;
    private static WeakReference<Dialog> sWindowDialog = new WeakReference<>(null);
    private static int sMainWidth;
    private static int sMarginPx;
    private static boolean sOpen;
    private static boolean sClosing;
    private static WeakReference<View> sMainSlider = new WeakReference<>(null);
    private static WeakReference<ViewGroup> sAlignPanel = new WeakReference<>(null);
    private static AlignListener sAlignListener;
    private static final int[] sMainLocation = new int[2];
    private static final int[] sPanelLocation = new int[2];

    /** Actions that have to run after the closing animation, e.g. a dismiss. */
    private static final List<Runnable> sAfterClose = new ArrayList<>();

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
            SliderPanel sliders = sSliders;
            if (sliders != null) {
                sliders.onVolumeChanged(pkg, volume);
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
        sMainWidth = 0;
        sMainSlider = new WeakReference<>(null);
        SystemUiHook.onPanelVisibilityChanged();

        prepareWindow(dialog);

        SliderPanel sliders = new SliderPanel(context,
                AppVolumePanel::createSlider,
                AppVolumePanel::applyVolume,
                AppVolumePanel::closeImmediate);
        sSliders = sliders;
        ViewGroup panel = sliders.getView();
        sPanel = panel;

        content.addView(panel, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.TOP));
        registerTouchable(panel);

        syncRows();
        if (sPanel != panel) {
            return;
        }
        // Apply the insets before measuring the final position: the ROM moves
        // the main slider vertically when it handles window insets.
        requestInsets(panel);
        panel.post(() -> placePanel(panel, content, 0));
        XposedBridge.log(TAG + "panel opened");
    }

    /** Removes the rows right away, used before a new panel is built. */
    private static void closeImmediate() {
        ViewGroup panel = sPanel;
        if (panel != null) {
            try {
                panel.animate().cancel();
                for (int i = 0; i < panel.getChildCount(); i++) {
                    panel.getChildAt(i).animate().cancel();
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
        SliderPanel sliders = sSliders;
        if (panel == null || sliders == null) {
            finishClose();
            return;
        }

        unregisterTouchable(panel);

        float width = sMainWidth > 0 ? sMainWidth : panel.getWidth();
        float targetX = panel.getTranslationX() + Math.max(width, 0f);
        sliders.animateClose(targetX, AppVolumePanel::finishClose);
        XposedBridge.log(TAG + "panel closing");
    }

    private static void finishClose() {
        ViewGroup panel = sPanel;
        SliderPanel sliders = sSliders;
        sPanel = null;
        sSliders = null;
        stopAlignment();

        if (panel != null) {
            try {
                panel.animate().cancel();
                unregisterTouchable(panel);
                ViewGroup parent = (ViewGroup) panel.getParent();
                if (parent != null) {
                    parent.removeView(panel);
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
        if (sliders != null) {
            sliders.closeImmediate();
        }

        sMainSlider = new WeakReference<>(null);
        sMainWidth = 0;
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
        if (panel != sPanel || sClosing || sSliders == null) {
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
        if (mainWidth <= 0 || mainHeight <= 0 || panel.getWidth() <= 0) {
            panel.post(() -> placePanel(panel, content, attempt + 1));
            return;
        }
        sMainWidth = mainWidth;
        sMarginPx = dp(context, SliderPanel.MARGIN_DP);
        sMainSlider = new WeakReference<>(main);
        if (sSliders.setRowSize(mainWidth, ViewGroup.LayoutParams.MATCH_PARENT)) {
            panel.requestLayout();
            panel.post(() -> placePanel(panel, content, attempt + 1));
            return;
        }

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

        int targetX = mainLeft - sMarginPx - panel.getWidth();
        float targetTranslationX = targetX - panel.getLeft();
        float targetTranslationY = mainTop - panel.getTop();

        // All values are set while the panel is still invisible. It becomes
        // visible at the hidden-under-the-panel position and only then starts
        // the slide, so it cannot flash in the corner.
        sSliders.animateOpen(targetTranslationX + mainWidth, targetTranslationX,
                targetTranslationY);
        startAlignment(panel, main);
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
            SliderPanel sliders = sSliders;
            if (panel != sPanel || sClosing || sliders == null
                    || panel.getVisibility() != View.VISIBLE) {
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

            if (!sliders.isOpening()) {
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
    // Rows
    // ------------------------------------------------------------------

    /**
     * Builds the row exactly like the compose sliders of the ROM: the native
     * slider fills the cell, its icon is part of the slider, and no separate
     * label or mute button is added.
     */
    private static SliderPanel.Slider createSlider(Context context, String pkg, int volume,
            SliderPanel.VolumeListener listener) {
        RomAppSlider slider = RomAppSlider.create(context, pkg, volume,
                (p, percent) -> listener.onVolumeChanged(p, percent));
        if (slider == null) {
            return null;
        }

        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.addView(slider.getView(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        SystemUiHook.registerSlider(slider);

        return new SliderPanel.Slider() {
            @Override
            public View getView() {
                return item;
            }

            @Override
            public void setPercent(int percent) {
                slider.setPercent(percent);
            }

            @Override
            public void release() {
                SystemUiHook.unregisterSlider(slider);
            }
        };
    }

    /** Adds, removes and updates the sliders so that they match the players. */
    private static void syncRows() {
        if (!sOpen || sClosing || sSliders == null) {
            return;
        }
        View main = findMainSlider(sSliders.getView().getContext());
        int width = main != null && main.getWidth() > 0
                ? main.getWidth() : dp(sSliders.getView().getContext(), 52);
        sSliders.sync(width, ViewGroup.LayoutParams.MATCH_PARENT, () -> {
            if (sPanel != null) {
                requestInsets(sPanel);
            }
        });
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
            Dialog previous = sWindowDialog.get();
            if (previous != null && previous != dialog) {
                restoreWindow(previous);
            }
            Window window = dialog.getWindow();
            WindowManager.LayoutParams params = window.getAttributes();
            if (sWindowDialog.get() != dialog) {
                sWindowDialog = new WeakReference<>(dialog);
                sOriginalWidth = params.width;
            }
            if (params.width == WindowManager.LayoutParams.MATCH_PARENT) {
                return;
            }
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(params);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void restoreWindow(Dialog dialog) {
        if (dialog == null || sWindowDialog.get() != dialog) {
            return;
        }
        sWindowDialog = new WeakReference<>(null);
        if (dialog.getWindow() == null || sOriginalWidth == Integer.MIN_VALUE) {
            sOriginalWidth = Integer.MIN_VALUE;
            return;
        }
        try {
            WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
            params.width = sOriginalWidth;
            dialog.getWindow().setAttributes(params);
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
