package com.appvolume.xposed;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * The per-app volume panel for the classic SystemUI volume dialog.
 *
 * <p>Android 16.0 still uses {@code VolumeDialogImpl} with ordinary views and
 * its stream sliders are {@code VolumeSeekBar}s. The compose slider of the
 * newer dialog cannot be reused there, so this panel is a separate, small
 * volume-overlay window with one horizontal seek bar per active app. It is
 * opened from an extra button in the settings row and closes together with
 * the system volume dialog.
 */
final class ClassicAppVolumePanel {
    private static final String TAG = "AppVolumeClassic: ";
    private static final String APP = "com.appvolume";
    private static final int MAX_PROGRESS = 20;

    /** Hidden framework constants, not present in the public android.jar. */
    private static final int TYPE_VOLUME_OVERLAY = 2020;
    private static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 1 << 29;

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private static WeakReference<Dialog> sDialog = new WeakReference<>(null);
    private static WeakReference<Dialog> sVolumeDialog = new WeakReference<>(null);

    private static Dialog sPanel;
    private static boolean sOpen;
    private static LinearLayout sRows;
    private static ScrollView sScroll;
    private static TextView sEmpty;
    private static final Map<String, Row> sApps = new LinkedHashMap<>();

    private static final class Row {
        final String pkg;
        final View view;
        final SeekBar seek;
        final TextView value;

        Row(String pkg, View view, SeekBar seek, TextView value) {
            this.pkg = pkg;
            this.view = view;
            this.seek = seek;
            this.value = value;
        }
    }

    // ------------------------------------------------------------------
    // Called by SystemUiHook
    // ------------------------------------------------------------------

    static boolean isOpen() {
        Dialog panel = sDialog.get();
        return sOpen && panel != null && panel.isShowing();
    }

    static boolean isPanel(Dialog dialog) {
        return dialog != null && sDialog.get() == dialog;
    }

    /** The classic volume dialog was rebuilt (for example on a config change). */
    static void onVolumeDialogChanged(Dialog dialog) {
        Dialog current = sVolumeDialog.get();
        if (sOpen && current != null && current != dialog) {
            close();
        }
        sVolumeDialog = new WeakReference<>(dialog);
    }

    /** The classic volume dialog is closing; the app sliders go with it. */
    static void onVolumeDialogDismissed(Dialog dialog) {
        if (sVolumeDialog.get() == dialog) {
            close();
        }
    }

    static void toggle(Context context, Dialog volumeDialog) {
        if (isOpen()) {
            close();
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
                setProgress(row, volume);
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
        close();

        sVolumeDialog = new WeakReference<>(volumeDialog);
        sOpen = true;

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(buildContent(context));
        dialog.setOnDismissListener(d -> {
            if (sPanel == d) {
                finishClosed();
            }
        });

        configureWindow(dialog);
        sPanel = dialog;
        sDialog = new WeakReference<>(dialog);
        refreshRows();

        Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().setAlpha(0f);
        }
        dialog.show();
        if (window != null) {
            window.getDecorView().post(() -> place(dialog, volumeDialog));
        }
        SystemUiHook.onClassicPanelVisibilityChanged();
        XposedBridge.log(TAG + "panel opened");
    }

    private static void close() {
        Dialog panel = sPanel;
        if (panel == null) {
            finishClosed();
            return;
        }
        // Clear the state first: dismiss() calls the listener below, and it
        // must not try to dismiss the panel again.
        sPanel = null;
        sOpen = false;
        try {
            if (panel.isShowing()) {
                panel.dismiss();
            }
        } catch (Throwable ignored) {
        }
        finishClosed();
    }

    private static void finishClosed() {
        sPanel = null;
        sRows = null;
        sScroll = null;
        sEmpty = null;
        sApps.clear();
        sOpen = false;
        sDialog = new WeakReference<>(null);
        sVolumeDialog = new WeakReference<>(null);
        SystemUiHook.onClassicPanelVisibilityChanged();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private static View buildContent(Context context) {
        int textColor = themeColor(context, android.R.attr.textColorPrimary, 0xFFFFFFFF);
        int secondary = themeColor(context, android.R.attr.textColorSecondary, 0xB3FFFFFF);
        int accent = themeColor(context, android.R.attr.colorAccent, 0xFF8AB4F8);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = SystemUiHook.dp(context, 16);
        root.setPadding(padding, padding, padding, padding);
        root.setMinimumWidth(SystemUiHook.dp(context, 280));
        root.setBackground(panelBackground(context));
        root.setElevation(SystemUiHook.dp(context, 8));

        TextView title = new TextView(context);
        title.setText(SystemUiHook.loadAppLabel(context));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textColor);
        title.setPadding(0, 0, 0, SystemUiHook.dp(context, 8));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sEmpty = new TextView(context);
        sEmpty.setText(loadString(context, "no_active_apps", "No active apps"));
        sEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sEmpty.setTextColor(secondary);
        sEmpty.setPadding(0, SystemUiHook.dp(context, 4), 0, SystemUiHook.dp(context, 4));
        root.addView(sEmpty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MaxHeightScrollView scroll = new MaxHeightScrollView(context,
                SystemUiHook.dp(context, 420));
        scroll.setFillViewport(false);
        sScroll = scroll;
        sRows = new LinearLayout(context);
        sRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(sRows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The seek bar tint is applied to each bar when it is created; keep
        // the color here for the row builder.
        root.setTag(accent);
        return root;
    }

    private static GradientDrawable panelBackground(Context context) {
        int color = SystemUiHook.resolveSystemUiColor(
                context, "materialColorSurfaceContainerHigh");
        if (color == 0) {
            color = themeColor(context, android.R.attr.colorBackground, 0xFF202124);
        }
        if (Color.alpha(color) < 255) {
            color = 0xFF202124;
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(SystemUiHook.dp(context, 24));
        return background;
    }

    private static void configureWindow(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
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

    private static void place(Dialog panel, Dialog volumeDialog) {
        if (!sOpen || sPanel != panel) {
            return;
        }
        Window panelWindow = panel.getWindow();
        Window volumeWindow = volumeDialog.getWindow();
        if (panelWindow == null || volumeWindow == null) {
            return;
        }
        View panelDecor = panelWindow.getDecorView();
        View volumeDecor = volumeWindow.getDecorView();
        panelDecor.post(() -> {
            if (!sOpen || sPanel != panel) {
                return;
            }
            int panelWidth = panelDecor.getWidth();
            int panelHeight = panelDecor.getHeight();
            if (panelWidth <= 0 || panelHeight <= 0) {
                panelDecor.postDelayed(() -> place(panel, volumeDialog), 32L);
                return;
            }

            int[] volumeLocation = new int[2];
            volumeDecor.getLocationOnScreen(volumeLocation);
            int volumeWidth = volumeDecor.getWidth();
            int volumeHeight = volumeDecor.getHeight();

            Context context = panelDecor.getContext();
            int margin = SystemUiHook.dp(context, 8);
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = context.getResources().getDisplayMetrics().heightPixels;

            int x = volumeLocation[0] - panelWidth - margin;
            if (x < margin) {
                x = volumeLocation[0] + volumeWidth + margin;
            }
            x = Math.max(margin, Math.min(x, screenWidth - panelWidth - margin));

            int y = volumeLocation[1] + (volumeHeight - panelHeight) / 2;
            y = Math.max(margin, Math.min(y, screenHeight - panelHeight - margin));

            WindowManager.LayoutParams params = panelWindow.getAttributes();
            params.x = x;
            params.y = y;
            params.gravity = Gravity.TOP | Gravity.START;
            panelWindow.setAttributes(params);
            panelDecor.setAlpha(1f);
        });
    }

    private static void refreshRows() {
        if (!sOpen || sRows == null) {
            return;
        }
        EffectManager manager = EffectManager.get();
        if (manager == null) {
            showEmpty(true);
            return;
        }

        List<String> packages = manager.getActivePackages();
        Set<String> present = new HashSet<>(packages);

        Iterator<Map.Entry<String, Row>> iterator = sApps.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Row> item = iterator.next();
            if (!present.contains(item.getKey())) {
                sRows.removeView(item.getValue().view);
                iterator.remove();
            }
        }

        for (String pkg : packages) {
            Row row = sApps.get(pkg);
            if (row == null) {
                row = buildRow(sRows.getContext(), pkg);
                if (row == null) {
                    continue;
                }
                sApps.put(pkg, row);
                sRows.addView(row.view, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            setProgress(row, manager.getVolume(pkg));
        }
        showEmpty(packages.isEmpty());
    }

    private static Row buildRow(Context context, String pkg) {
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

        int textColor = themeColor(context, android.R.attr.textColorPrimary, 0xFFFFFFFF);
        int secondary = themeColor(context, android.R.attr.textColorSecondary, 0xB3FFFFFF);
        int accent = themeColor(context, android.R.attr.colorAccent, 0xFF8AB4F8);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vertical = SystemUiHook.dp(context, 7);
        row.setPadding(0, vertical, 0, vertical);

        ImageView iconView = new ImageView(context);
        iconView.setImageDrawable(icon);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        row.addView(iconView, new LinearLayout.LayoutParams(
                SystemUiHook.dp(context, 28), SystemUiHook.dp(context, 28)));

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        labelView.setTextColor(textColor);
        labelView.setSingleLine(true);
        labelView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labelView.setPadding(SystemUiHook.dp(context, 10), 0,
                SystemUiHook.dp(context, 8), 0);
        row.addView(labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SeekBar seek = new SeekBar(context);
        seek.setMax(MAX_PROGRESS);
        seek.setProgressTintList(ColorStateList.valueOf(accent));
        seek.setThumbTintList(ColorStateList.valueOf(accent));
        row.addView(seek, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));

        TextView value = new TextView(context);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        value.setTextColor(secondary);
        value.setGravity(Gravity.END);
        value.setMinWidth(SystemUiHook.dp(context, 40));
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Row result = new Row(pkg, row, seek, value);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int percent = progress * 5;
                value.setText(percent + "%");
                EffectManager manager = EffectManager.get();
                if (manager != null) {
                    manager.setVolume(pkg, percent);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        return result;
    }

    private static void setProgress(Row row, int volume) {
        int progress = Math.max(0, Math.min(MAX_PROGRESS, Math.round(volume / 5f)));
        int percent = progress * 5;
        if (row.seek.getProgress() != progress) {
            row.seek.setProgress(progress);
        }
        row.value.setText(percent + "%");
    }

    private static void showEmpty(boolean empty) {
        if (sEmpty != null) {
            sEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (sScroll != null) {
            sScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int themeColor(Context context, int attr, int fallback) {
        try {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(attr, value, true)) {
                if (value.resourceId != 0) {
                    return context.getResources().getColor(value.resourceId, context.getTheme());
                }
                return value.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static CharSequence loadString(Context context, String name, String fallback) {
        try {
            android.content.res.Resources resources = context.getPackageManager()
                    .getResourcesForApplication(APP);
            int id = resources.getIdentifier(name, "string", APP);
            if (id != 0) {
                return resources.getString(id);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** A scroll view that keeps the panel below a fraction of the screen. */
    private static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeight;

        MaxHeightScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int size = MeasureSpec.getSize(heightMeasureSpec);
            if (mode == MeasureSpec.UNSPECIFIED || size > maxHeight) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                        maxHeight, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
