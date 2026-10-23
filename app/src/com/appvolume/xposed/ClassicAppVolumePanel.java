package com.appvolume.xposed;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 */
final class ClassicAppVolumePanel {
    private static final String TAG = "AppVolumeClassic: ";
    private static final String APP = "com.appvolume";
    private static final String SYSTEMUI = "com.android.systemui";

    /** Material 3 Expressive slider dimensions from the compose volume dialog. */
    private static final int SLIDER_WIDTH_DP = 52;
    private static final int TRACK_WIDTH_DP = 40;
    private static final int THUMB_HEIGHT_DP = 4;
    private static final int ICON_SIZE_DP = 20;
    private static final int TRACK_GAP_DP = 6;
    private static final int TRACK_CORNER_DP = 12;
    private static final int TRACK_INSIDE_CORNER_DP = 2;
    private static final int MARGIN_DP = 6;
    private static final int FALLBACK_HEIGHT_DP = 254;

    private static final int STEP = 5;
    private static final long ANIMATION_MS = 280L;
    private static final long CLOSE_ANIMATION_MS = 180L;

    /** Hidden framework constants, not present in the public android.jar. */
    private static final int TYPE_VOLUME_OVERLAY = 2020;
    private static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 1 << 29;

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private static WeakReference<Dialog> sDialog = new WeakReference<>(null);
    private static WeakReference<Dialog> sVolumeDialog = new WeakReference<>(null);

    private static Dialog sPanel;
    private static FrameLayout sContent;
    private static LinearLayout sRows;
    private static boolean sOpen;
    private static boolean sPositioned;
    private static boolean sRestoreCancelOutside;
    private static int sPanelHeight;
    private static final Map<String, Row> sApps = new LinkedHashMap<>();

    private static final class Row {
        final String pkg;
        final AppSlider slider;

        Row(String pkg, AppSlider slider) {
            this.pkg = pkg;
            this.slider = slider;
        }
    }

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
        if (sOpen && current != null && current != dialog) {
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
        if (!sOpen) {
            return;
        }
        sMain.post(ClassicAppVolumePanel::refreshRows);
    }

    static void onVolumeChanged(String pkg, int volume) {
        if (!sOpen) {
            return;
        }
        sMain.post(() -> {
            Row row = sApps.get(pkg);
            if (row != null) {
                row.slider.setPercent(volume);
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

        sContent = new FrameLayout(context);
        sContent.setBackgroundColor(Color.TRANSPARENT);
        sContent.setClipChildren(false);
        sContent.setClipToPadding(false);

        sRows = new LinearLayout(context);
        sRows.setOrientation(LinearLayout.HORIZONTAL);
        sRows.setGravity(Gravity.BOTTOM);
        sRows.setClipChildren(false);
        sRows.setClipToPadding(false);
        sRows.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                SystemUiHook.dp(context, FALLBACK_HEIGHT_DP)));
        sContent.addView(sRows, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                SystemUiHook.dp(context, FALLBACK_HEIGHT_DP)));

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(sContent, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
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

        Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().setAlpha(0f);
        }
        try {
            dialog.show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "show failed: " + t);
            closeImmediate();
            return;
        }
        if (window != null) {
            window.getDecorView().post(() -> place(dialog, volumeDialog, 0));
        }
        SystemUiHook.onClassicPanelVisibilityChanged();
        XposedBridge.log(TAG + "panel opened");
    }

    private static void closeAnimated() {
        Dialog panel = sPanel;
        if (panel == null) {
            closeImmediate();
            return;
        }
        sPanel = null;
        sOpen = false;
        sPositioned = false;
        sApps.clear();

        Window window = panel.getWindow();
        if (window == null) {
            closeImmediate();
            return;
        }
        View decor = window.getDecorView();
        int width = decor.getWidth();
        decor.animate()
                .translationX(width + SystemUiHook.dp(decor.getContext(), MARGIN_DP))
                .alpha(0f)
                .setDuration(CLOSE_ANIMATION_MS)
                .withEndAction(() -> {
                    try {
                        if (panel.isShowing()) {
                            panel.dismiss();
                        }
                    } catch (Throwable ignored) {
                    }
                    // Do not tear down a panel that was opened again while
                    // the old one was sliding out.
                    if (sPanel == null && !sOpen) {
                        finishClosed();
                    }
                })
                .start();
        SystemUiHook.onClassicPanelVisibilityChanged();
        XposedBridge.log(TAG + "panel closing");
    }

    private static void closeImmediate() {
        Dialog panel = sPanel;
        sPanel = null;
        sOpen = false;
        sPositioned = false;
        sApps.clear();
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
        sPanel = null;
        sContent = null;
        sRows = null;
        sPanelHeight = 0;
        sPositioned = false;
        sApps.clear();
        sOpen = false;
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
        if (!sOpen || sPanel != panel || attempt > 40) {
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
        if (panelDecor.getWidth() <= 0 || panelDecor.getHeight() < rowsHeight) {
            panelDecor.postDelayed(() -> place(panel, volumeDialog, attempt + 1), 24L);
            return;
        }

        int[] rowsLocation = new int[2];
        rows.getLocationOnScreen(rowsLocation);

        Context context = panelDecor.getContext();
        int margin = SystemUiHook.dp(context, MARGIN_DP);
        int width = panelDecor.getWidth();
        int height = panelDecor.getHeight();
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;

        int x = rowsLocation[0] - width - margin;
        x = Math.max(margin, Math.min(x, screenWidth - width - margin));
        int y = Math.max(margin, Math.min(rowsLocation[1], screenHeight - height - margin));

        WindowManager.LayoutParams params = panelWindow.getAttributes();
        params.x = x;
        params.y = y;
        params.gravity = Gravity.TOP | Gravity.START;
        panelWindow.setAttributes(params);

        if (!sPositioned) {
            sPositioned = true;
            panelDecor.setTranslationX(width + margin);
            panelDecor.setAlpha(0f);
            panelDecor.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(ANIMATION_MS)
                    .start();

            for (int i = 0; i < sRows.getChildCount(); i++) {
                View child = sRows.getChildAt(i);
                child.setAlpha(0f);
                child.animate()
                        .alpha(1f)
                        .setStartDelay(i * 45L)
                        .setDuration(ANIMATION_MS)
                        .start();
            }
        } else {
            panelDecor.setAlpha(1f);
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
        if (sRows != null && sRows.getLayoutParams() != null
                && sRows.getLayoutParams().height != height) {
            sRows.getLayoutParams().height = height;
            sRows.setLayoutParams(sRows.getLayoutParams());
            changed = true;
        }
        if (sRows != null) {
            int count = sRows.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = sRows.getChildAt(i);
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params != null && params.height != height) {
                    params.height = height;
                    child.setLayoutParams(params);
                    changed = true;
                }
            }
        }
        if (sPanelHeight != height) {
            sPanelHeight = height;
            Window window = sPanel != null ? sPanel.getWindow() : null;
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, height);
            }
            changed = true;
        }
        if (changed && panelDecor != null) {
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
        if (!sOpen || sRows == null) {
            return;
        }
        EffectManager manager = EffectManager.get();
        if (manager == null) {
            closeImmediate();
            return;
        }

        List<String> packages = manager.getActivePackages();
        if (packages.isEmpty()) {
            closeImmediate();
            return;
        }

        List<String> removed = new ArrayList<>();
        for (String pkg : sApps.keySet()) {
            if (!packages.contains(pkg)) {
                removed.add(pkg);
            }
        }
        for (String pkg : removed) {
            Row row = sApps.remove(pkg);
            if (row != null) {
                sRows.removeView(row.slider);
            }
        }

        Context context = sRows.getContext();
        int width = sliderWidth(context);
        int height = sRows.getLayoutParams() != null
                ? sRows.getLayoutParams().height : SystemUiHook.dp(context, FALLBACK_HEIGHT_DP);
        for (String pkg : packages) {
            Row row = sApps.get(pkg);
            if (row == null) {
                AppSlider slider = createSlider(context, pkg, manager.getVolume(pkg));
                if (slider == null) {
                    continue;
                }
                row = new Row(pkg, slider);
                sApps.put(pkg, row);
                sRows.addView(slider, new LinearLayout.LayoutParams(width, height));
            } else {
                row.slider.setPercent(manager.getVolume(pkg));
            }
        }

        if (sApps.isEmpty()) {
            closeImmediate();
            return;
        }

        if (sPanel != null) {
            sMain.post(() -> {
                if (sOpen && sPanel != null) {
                    place(sPanel, sVolumeDialog.get(), 0);
                }
            });
        }
    }

    private static AppSlider createSlider(Context context, String pkg, int percent) {
        PackageManager packageManager = context.getPackageManager();
        CharSequence label = pkg;
        Drawable icon = null;
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(pkg, 0);
            label = packageManager.getApplicationLabel(info);
            icon = packageManager.getApplicationIcon(info);
        } catch (Throwable ignored) {
        }
        if (icon == null) {
            try {
                icon = context.getDrawable(android.R.drawable.sym_def_app_icon);
            } catch (Throwable ignored) {
            }
        }

        return new AppSlider(context, pkg, label, icon, percent,
                (p, value) -> {
                    EffectManager manager = EffectManager.get();
                    if (manager != null) {
                        manager.setVolume(p, value);
                    }
                });
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

    // ------------------------------------------------------------------
    // The slider
    // ------------------------------------------------------------------

    private interface AppSliderListener {
        void onChanged(String pkg, int percent);
    }

    /**
     * The Material 3 Expressive vertical volume slider used by the compose
     * panel: a 40dp rounded track, a 52x4dp thumb and the app icon at the top
     * of the track. The active part grows from the thumb to the bottom.
     */
    private static final class AppSlider extends View {
        private final String pkg;
        private final CharSequence label;
        private final Drawable icon;
        private final AppSliderListener listener;

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
                int percent, AppSliderListener listener) {
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

        void setPercent(int value) {
            int next = Math.max(0, Math.min(100, Math.round(value / (float) STEP) * STEP));
            if (next != percent) {
                percent = next;
                updateDescription();
                invalidate();
            }
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

            drawIcon(canvas, centerX, height, thumbCenterY);
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

        private void drawIcon(Canvas canvas, float centerX, int height, float thumbCenterY) {
            if (icon == null) {
                return;
            }
            float top = 0f;
            if (thumbCenterY - thumbHeight / 2f - gap - iconSize < 0f) {
                top = thumbCenterY + thumbHeight / 2f + gap;
            }
            top = Math.max(0f, Math.min(top, height - iconSize));
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
            int value = Math.round(fraction * 100f / STEP) * STEP;
            value = Math.max(0, Math.min(100, value));
            if (value == percent) {
                return;
            }
            percent = value;
            updateDescription();
            invalidate();
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) {
                listener.onChanged(pkg, percent);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int color(Context context, String systemUiName, int attr, int fallback) {
        int value = SystemUiHook.resolveSystemUiColor(context, systemUiName);
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
