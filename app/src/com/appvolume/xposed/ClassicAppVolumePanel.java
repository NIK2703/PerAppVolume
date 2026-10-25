package com.appvolume.xposed;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XposedBridge;

/**
 * The per-app volume sliders for the classic SystemUI volume dialog.
 *
 * <p>Android 16.0 still uses {@code VolumeDialogImpl}, whose sliders are
 * ordinary views. The compose slider of newer builds cannot be reused there,
 * so this class draws a small replica of the Material 3 Expressive volume
 * slider used by Android 16 QPR1 and Android 17. The row of app sliders is a
 * separate transparent volume-overlay window placed immediately to the left
 * of the classic panel.
 *
 * <p>The row itself, its volume handling and both animations come from
 * {@link SliderPanel}, the same code the compose dialog uses. Only the host
 * window and the slider drawing are specific to the classic dialog.
 */
final class ClassicAppVolumePanel {
    private static final String TAG = "AppVolumeClassic: ";
    private static final String SYSTEMUI = "com.android.systemui";

    /** Material 3 Expressive slider dimensions from the compose volume dialog. */
    private static final int SLIDER_WIDTH_DP = 52;
    private static final int TRACK_WIDTH_DP = 40;
    private static final int THUMB_HEIGHT_DP = 4;
    private static final int ICON_SIZE_DP = 20;
    private static final int TRACK_GAP_DP = 6;
    private static final int TRACK_CORNER_DP = 12;
    private static final int TRACK_INSIDE_CORNER_DP = 2;
    private static final int FALLBACK_HEIGHT_DP = 254;

    /** Hidden framework constants, not present in the public android.jar. */
    private static final int TYPE_VOLUME_OVERLAY = 2020;
    private static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 1 << 29;

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private static WeakReference<Dialog> sDialog = new WeakReference<>(null);
    private static WeakReference<Dialog> sVolumeDialog = new WeakReference<>(null);

    private static Dialog sPanel;
    private static FrameLayout sContent;
    private static SliderPanel sSliders;
    private static boolean sOpen;
    private static boolean sClosing;
    private static boolean sPositioned;
    private static boolean sRestoreCancelOutside;
    private static int sPanelHeight;

    // ------------------------------------------------------------------
    // Called by SystemUiHook
    // ------------------------------------------------------------------

    static boolean isOpen() {
        Dialog dialog = sDialog.get();
        return sOpen && dialog != null && dialog.isShowing();
    }

    static boolean isPanel(Dialog dialog) {
        return dialog != null && sDialog.get() == dialog;
    }

    /** The classic volume dialog was rebuilt (for example on a config change). */
    static void onVolumeDialogChanged(Dialog dialog) {
        Dialog current = sVolumeDialog.get();
        if (current != null && current != dialog) {
            closeImmediate();
        }
        sVolumeDialog = new WeakReference<>(dialog);
    }

    /** The classic volume dialog is closing; the app sliders go with it. */
    static void onVolumeDialogDismissed(Dialog dialog) {
        if (sVolumeDialog.get() == dialog) {
            closeImmediate();
        }
    }

    static void toggle(Context context, Dialog volumeDialog) {
        if (isOpen()) {
            closeAnimated();
        } else {
            open(context, volumeDialog);
        }
    }

    static void onSessionsChanged() {
        if (!sOpen || sClosing) {
            return;
        }
        sMain.post(ClassicAppVolumePanel::refreshRows);
    }

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

    private static void open(Context context, Dialog volumeDialog) {
        if (context == null || volumeDialog == null || volumeDialog.getWindow() == null) {
            return;
        }
        closeImmediate();

        sVolumeDialog = new WeakReference<>(volumeDialog);
        sOpen = true;
        sClosing = false;
        sPositioned = false;
        sPanelHeight = 0;
        // The classic dialog dismisses itself on an outside touch. The app
        // sliders live in a separate window, so a drag would otherwise close
        // the volume panel immediately. Keep the normal timeout working, but
        // ignore outside touches while our row is open.
        try {
            volumeDialog.setCanceledOnTouchOutside(false);
            sRestoreCancelOutside = true;
        } catch (Throwable ignored) {
            sRestoreCancelOutside = false;
        }

        int height = sliderHeight(context);
        sContent = new FrameLayout(context);
        sContent.setBackgroundColor(Color.TRANSPARENT);
        sContent.setClipChildren(false);
        sContent.setClipToPadding(false);
        sContent.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, height));

        SliderPanel sliders = new SliderPanel(context,
                ClassicAppVolumePanel::createSlider,
                ClassicAppVolumePanel::applyVolume,
                ClassicAppVolumePanel::closeImmediate);
        sSliders = sliders;
        View sliderRow = sliders.getView();
        sContent.addView(sliderRow,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height));
        // Adding or removing a player changes the width of the row. The
        // overlay window has to follow that change, otherwise the new row can
        // be clipped by the old window bounds.
        sliderRow.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            Dialog panel = sPanel;
            Dialog dialog = sVolumeDialog.get();
            if (sOpen && !sClosing && panel != null && dialog != null
                    && panel.isShowing()) {
                place(panel, dialog, 0);
            }
        });

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(sContent, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setOnDismissListener(d -> {
            if (sPanel == d) {
                finishClosed();
            }
        });

        configureWindow(dialog);
        sPanel = dialog;
        sDialog = new WeakReference<>(dialog);
        refreshRows();
        if (!sOpen || sPanel != dialog) {
            return;
        }

        try {
            dialog.show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "show failed: " + t);
            closeImmediate();
            return;
        }
        Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().post(() -> place(dialog, volumeDialog, 0));
        }
        SystemUiHook.onClassicPanelVisibilityChanged();
        XposedBridge.log(TAG + "panel opened");
    }

    private static void closeAnimated() {
        if (sClosing) {
            return;
        }
        Dialog panel = sPanel;
        SliderPanel sliders = sSliders;
        if (panel == null || sliders == null) {
            closeImmediate();
            return;
        }

        // Stop syncing rows and hide the button indicator immediately. The
        // window and the views stay alive until SliderPanel finishes the
        // reverse animation.
        sOpen = false;
        sClosing = true;
        sPositioned = false;
        SystemUiHook.onClassicPanelVisibilityChanged();

        int mainWidth = sliderWidth(panel.getContext());
        if (sSliders != null) {
            setWindowSize(panel, sSliders.getView().getWidth() + mainWidth);
        }
        sliders.animateClose(mainWidth, () -> {
            if (sPanel != panel) {
                return;
            }
            sPanel = null;
            try {
                if (panel.isShowing()) {
                    panel.dismiss();
                }
            } catch (Throwable ignored) {
            }
            finishClosed();
        });
        XposedBridge.log(TAG + "panel closing");
    }

    private static void closeImmediate() {
        Dialog panel = sPanel;
        sPanel = null;
        sOpen = false;
        sClosing = false;
        sPositioned = false;
        if (panel != null) {
            try {
                if (panel.isShowing()) {
                    panel.dismiss();
                }
            } catch (Throwable ignored) {
            }
        }
        finishClosed();
    }

    private static void finishClosed() {
        Dialog volumeDialog = sVolumeDialog.get();
        if (sRestoreCancelOutside && volumeDialog != null) {
            try {
                volumeDialog.setCanceledOnTouchOutside(true);
            } catch (Throwable ignored) {
            }
        }
        sRestoreCancelOutside = false;

        SliderPanel sliders = sSliders;
        sSliders = null;
        if (sliders != null) {
            sliders.closeImmediate();
        }
        sPanel = null;
        sContent = null;
        sPanelHeight = 0;
        sPositioned = false;
        sOpen = false;
        sClosing = false;
        sDialog = new WeakReference<>(null);
        sVolumeDialog = new WeakReference<>(null);
        SystemUiHook.onClassicPanelVisibilityChanged();
    }

    // ------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------

    private static void configureWindow(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                // getLocationOnScreen() returns full-display coordinates, so
                // this window must use the same origin.
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        window.setType(TYPE_VOLUME_OVERLAY);
        try {
            // Window.addPrivateFlags is a hidden framework method.
            Window.class.getMethod("addPrivateFlags", int.class)
                    .invoke(window, PRIVATE_FLAG_TRUSTED_OVERLAY);
        } catch (Throwable ignored) {
        }

        WindowManager.LayoutParams params = window.getAttributes();
        params.format = PixelFormat.TRANSLUCENT;
        params.gravity = Gravity.TOP | Gravity.START;
        params.dimAmount = 0f;
        params.setTitle("AppVolumePanel");
        window.setAttributes(params);
        window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static void place(Dialog panel, Dialog volumeDialog, int attempt) {
        if (!sOpen || sClosing || sPanel != panel || attempt > 40) {
            return;
        }
        Window panelWindow = panel.getWindow();
        Window volumeWindow = volumeDialog.getWindow();
        if (panelWindow == null || volumeWindow == null) {
            return;
        }
        View panelDecor = panelWindow.getDecorView();
        View volumeDecor = volumeWindow.getDecorView();
        // Align to the visible slider track, not to the row's rounded
        // background/padding. The compose dialog aligns its floating sliders
        // with the main slider in exactly the same way.
        View rows = findClassicAnchor(volumeDecor);
        if (rows == null) {
            rows = volumeDecor;
        }

        int rowsHeight = rows.getHeight();
        if (rowsHeight <= 0) {
            panelDecor.postDelayed(() -> place(panel, volumeDialog, attempt + 1), 24L);
            return;
        }

        if (applyHeight(panelDecor, rowsHeight)) {
            panelDecor.postDelayed(() -> place(panel, volumeDialog, attempt + 1), 24L);
            return;
        }

        View sliderRow = sSliders != null ? sSliders.getView() : null;
        if (sliderRow == null || sliderRow.getWidth() <= 0) {
            panelDecor.postDelayed(() -> place(panel, volumeDialog, attempt + 1), 24L);
            return;
        }

        int[] rowsLocation = new int[2];
        rows.getLocationOnScreen(rowsLocation);

        Context context = panelDecor.getContext();
        int margin = SystemUiHook.dp(context, SliderPanel.MARGIN_DP);
        int mainWidth = sliderWidth(context);
        int rowWidth = sliderRow.getWidth();
        int height = sPanelHeight > 0 ? sPanelHeight : rowsHeight;
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;

        int x = rowsLocation[0] - rowWidth - margin;
        x = Math.max(margin, Math.min(x, screenWidth - rowWidth - margin));
        int y = Math.max(margin, Math.min(rowsLocation[1], screenHeight - height - margin));

        WindowManager.LayoutParams params = panelWindow.getAttributes();
        params.x = x;
        params.y = y;
        params.gravity = Gravity.TOP | Gravity.START;
        panelWindow.setAttributes(params);

        // The window is exactly as wide as the visible row after opening.
        // While the row is sliding it needs one main-slider width of extra
        // transparent space to the right, otherwise the window surface would
        // clip the half of the row that is still under the main panel.
        setWindowSize(panel, rowWidth + (sPositioned ? 0 : mainWidth));

        if (!sPositioned) {
            sPositioned = true;
            // The compose panel starts under the main slider and slides left
            // by the width of that slider, not by the width of the whole app
            // row. Use the same distance here so several open apps behave in
            // the same way in both dialogs.
            sSliders.animateOpen(mainWidth, 0f, 0f, () -> {
                if (sPanel == panel && sSliders != null) {
                    setWindowSize(panel, sSliders.getView().getWidth());
                }
            });
        } else {
            setWindowSize(panel, rowWidth);
        }
    }

    /** Resizes the transparent overlay window without moving its left edge. */
    private static void setWindowSize(Dialog panel, int width) {
        if (panel == null) {
            return;
        }
        int height = sPanelHeight > 0 ? sPanelHeight : sliderHeight(panel.getContext());
        if (sContent != null && sContent.getLayoutParams() != null
                && sContent.getLayoutParams().width != width) {
            sContent.getLayoutParams().width = width;
            sContent.setLayoutParams(sContent.getLayoutParams());
        }
        Window window = panel.getWindow();
        if (window != null) {
            window.setLayout(Math.max(1, width), Math.max(1, height));
        }
    }

    /**
     * Makes the overlay exactly as tall as the classic volume rows. The
     * compose dialog uses the height of its main slider; the classic rows
     * container is the equivalent view.
     */
    private static boolean applyHeight(View panelDecor, int height) {
        boolean changed = false;
        if (sContent != null && sContent.getLayoutParams() != null
                && sContent.getLayoutParams().height != height) {
            sContent.getLayoutParams().height = height;
            sContent.setLayoutParams(sContent.getLayoutParams());
            changed = true;
        }
        if (sSliders != null) {
            View rows = sSliders.getView();
            ViewGroup.LayoutParams params = rows.getLayoutParams();
            if (params != null && params.height != height) {
                params.height = height;
                rows.setLayoutParams(params);
                changed = true;
            }
            if (sSliders.setRowSize(sliderWidth(panelDecor.getContext()), height)) {
                changed = true;
            }
        }
        if (sPanelHeight != height) {
            sPanelHeight = height;
            changed = true;
        }
        if (changed) {
            panelDecor.requestLayout();
        }
        return changed;
    }

    private static View findClassicAnchor(View volumeDecor) {
        Context context = volumeDecor.getContext();
        int frameId = SystemUiHook.systemUiIdentifier(
                context, "volume_row_slider_frame", "id");
        View frame = frameId != 0 ? volumeDecor.findViewById(frameId) : null;
        if (frame != null && frame.getHeight() > 0) {
            return frame;
        }
        int rowsId = SystemUiHook.systemUiIdentifier(
                context, "volume_dialog_rows", "id");
        return rowsId != 0 ? volumeDecor.findViewById(rowsId) : null;
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    private static void refreshRows() {
        if (!sOpen || sClosing || sSliders == null) {
            return;
        }
        Context context = sSliders.getView().getContext();
        int width = sliderWidth(context);
        int height = sPanelHeight > 0 ? sPanelHeight : sliderHeight(context);
        sSliders.sync(width, height, () -> {
            Dialog panel = sPanel;
            Dialog volumeDialog = sVolumeDialog.get();
            if (sOpen && !sClosing && panel != null && volumeDialog != null) {
                sMain.post(() -> {
                    if (sOpen && !sClosing && sPanel == panel) {
                        place(panel, volumeDialog, 0);
                    }
                });
            }
        });
    }

    private static SliderPanel.Slider createSlider(Context context, String pkg, int percent,
            SliderPanel.VolumeListener listener) {
        Drawable icon = AppIcon.load(context, pkg);
        if (icon == null) {
            try {
                icon = context.getDrawable(android.R.drawable.sym_def_app_icon);
            } catch (Throwable ignored) {
            }
        }
        return new AppSlider(context, pkg, AppIcon.label(context, pkg), icon, percent, listener);
    }

    private static void applyVolume(String pkg, int value) {
        EffectManager manager = EffectManager.get();
        if (manager != null) {
            manager.setVolume(pkg, value);
        }
    }

    private static int sliderWidth(Context context) {
        int id = SystemUiHook.systemUiIdentifier(
                context, "volume_dialog_slider_width", "dimen");
        if (id != 0) {
            try {
                return context.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
            }
        }
        return SystemUiHook.dp(context, SLIDER_WIDTH_DP);
    }

    private static int sliderHeight(Context context) {
        int id = SystemUiHook.systemUiIdentifier(
                context, "volume_dialog_slider_height", "dimen");
        if (id != 0) {
            try {
                return context.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
            }
        }
        return SystemUiHook.dp(context, FALLBACK_HEIGHT_DP);
    }

    // ------------------------------------------------------------------
    // The slider
    // ------------------------------------------------------------------

    /**
     * The Material 3 Expressive vertical volume slider used by the compose
     * panel: a 40dp rounded track, a 52x4dp thumb and the app icon at the
     * bottom of the track. The active part grows from the thumb to the bottom.
     */
    private static final class AppSlider extends View implements SliderPanel.Slider {
        private final String pkg;
        private final CharSequence label;
        private final Drawable icon;
        private final SliderPanel.VolumeListener listener;

        private final Paint inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();

        private final float trackWidth;
        private final float thumbWidth;
        private final float thumbHeight;
        private final float iconSize;
        private final float gap;
        private final float corner;
        private final float insideCorner;

        private int percent;

        AppSlider(Context context, String pkg, CharSequence label, Drawable icon,
                int percent, SliderPanel.VolumeListener listener) {
            super(context);
            this.pkg = pkg;
            this.label = label != null ? label : pkg;
            this.icon = icon;
            this.listener = listener;

            trackWidth = SystemUiHook.dp(context, TRACK_WIDTH_DP);
            thumbWidth = SystemUiHook.dp(context, SLIDER_WIDTH_DP);
            thumbHeight = SystemUiHook.dp(context, THUMB_HEIGHT_DP);
            iconSize = SystemUiHook.dp(context, ICON_SIZE_DP);
            gap = SystemUiHook.dp(context, TRACK_GAP_DP);
            corner = SystemUiHook.dp(context, TRACK_CORNER_DP);
            insideCorner = SystemUiHook.dp(context, TRACK_INSIDE_CORNER_DP);

            int inactive = color(context, "materialColorSurfaceContainerHighest",
                    android.R.attr.colorBackground, 0xFF36343B);
            int active = color(context, "materialColorPrimary",
                    android.R.attr.colorAccent, 0xFF8AB4F8);
            inactivePaint.setColor(inactive);
            inactivePaint.setStyle(Paint.Style.FILL);
            activePaint.setColor(active);
            activePaint.setStyle(Paint.Style.FILL);
            thumbPaint.setColor(active);
            thumbPaint.setStyle(Paint.Style.FILL);

            setClickable(true);
            setFocusable(true);
            setPercent(percent);
            updateDescription();
        }

        @Override
        public View getView() {
            return this;
        }

        @Override
        public void setPercent(int value) {
            int next = SliderPanel.snap(value);
            if (next != percent) {
                percent = next;
                updateDescription();
                invalidate();
            }
        }

        @Override
        public void release() {
        }

        private void updateDescription() {
            setContentDescription(label + ", " + percent + "%");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            float centerX = width / 2f;
            float trackLeft = centerX - Math.min(trackWidth, width) / 2f;
            float trackRight = centerX + Math.min(trackWidth, width) / 2f;
            float thumbWidthPx = Math.min(thumbWidth, width);
            float thumbCenterY = sliderCenterY(height);
            float inactiveEnd = Math.max(0f, thumbCenterY - thumbHeight / 2f - gap);
            float activeStart = Math.min(height, thumbCenterY + thumbHeight / 2f + gap);

            if (inactiveEnd > 0f) {
                rect.set(trackLeft, 0f, trackRight, inactiveEnd);
                drawSegment(canvas, inactivePaint, true);
            }
            if (activeStart < height) {
                rect.set(trackLeft, activeStart, trackRight, height);
                drawSegment(canvas, activePaint, false);
            }

            float thumbLeft = centerX - thumbWidthPx / 2f;
            rect.set(thumbLeft, thumbCenterY - thumbHeight / 2f,
                    thumbLeft + thumbWidthPx, thumbCenterY + thumbHeight / 2f);
            canvas.drawRoundRect(rect, thumbHeight / 2f, thumbHeight / 2f, thumbPaint);

            drawIcon(canvas, centerX, height);
        }

        private float sliderCenterY(int height) {
            float min = thumbHeight / 2f;
            float max = Math.max(min, height - thumbHeight / 2f);
            return max - (max - min) * (percent / 100f);
        }

        private void drawSegment(Canvas canvas, Paint paint, boolean topSegment) {
            float r = Math.min(corner, rect.height() / 2f);
            float inside = Math.min(insideCorner, r);
            float[] radii = topSegment
                    ? new float[]{r, r, r, r, inside, inside, inside, inside}
                    : new float[]{inside, inside, inside, inside, r, r, r, r};
            path.reset();
            path.addRoundRect(rect, radii, Path.Direction.CW);
            canvas.drawPath(path, paint);
        }

        private void drawIcon(Canvas canvas, float centerX, int height) {
            if (icon == null) {
                return;
            }
            // In the compose slider the icon sits at the inactive track end,
            // i.e. at the bottom of the vertical track. It stays there while
            // the active part grows and the thumb moves up.
            float top = Math.max(0f, height - iconSize);
            int left = Math.round(centerX - iconSize / 2f);
            int boxTop = Math.round(top);
            int size = Math.round(iconSize);
            icon.setBounds(left, boxTop, left + size, boxTop + size);
            icon.draw(canvas);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            SystemUiHook.resetClassicTimeout();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    ViewGroup parent = (ViewGroup) getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    updateFromTouch(event.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateFromTouch(event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                    updateFromTouch(event.getY());
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        private void updateFromTouch(float y) {
            int height = getHeight();
            if (height <= 0) {
                return;
            }
            float fraction = 1f - Math.max(0f, Math.min(1f, y / height));
            int value = SliderPanel.snap(Math.round(fraction * 100f));
            if (value == percent) {
                return;
            }
            percent = value;
            updateDescription();
            invalidate();
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) {
                listener.onVolumeChanged(pkg, percent);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int color(Context context, String systemUiName, int attr, int fallback) {
        int value = SystemUiHook.resolveSystemUiColor(context, systemUiName);
        if (value == 0 && systemUiName.startsWith("materialColor")) {
            // Android 16.0 ships the same palette under the settingslib
            // prefix, Android 17 exposes the unprefixed name.
            value = SystemUiHook.resolveSystemUiColor(
                    context, "settingslib_" + systemUiName);
        }
        if (value != 0) {
            return value;
        }
        try {
            android.util.TypedValue typed = new android.util.TypedValue();
            if (context.getTheme().resolveAttribute(attr, typed, true)) {
                if (typed.resourceId != 0) {
                    return context.getResources().getColor(typed.resourceId, context.getTheme());
                }
                return typed.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
