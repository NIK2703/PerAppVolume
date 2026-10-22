package com.appvolume;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app-volume bottom sheet. It is a dialog-style activity so that it can
 * be launched directly from the SystemUI volume panel by the LSPosed hook and
 * still appears above the shade as a sheet sliding up from the bottom.
 */
public final class PanelActivity extends Activity {
    private static final long REFRESH_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private Prefs prefs;

    private LinearLayout root;
    private MaxHeightScrollView scroll;
    private LinearLayout list;
    private TextView emptyView;
    private LinearLayout buttonsRow;

    private boolean resumed;
    private boolean scanning;
    private boolean dragging;
    private String lastSignature;

    private int basePaddingLeft;
    private int basePaddingTop;
    private int basePaddingRight;
    private int basePaddingBottom;

    private final Runnable refreshLoop = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "app-volume-panel-scan");
            t.setDaemon(true);
            return t;
        });

        setContentView(buildUi());

        Window window = getWindow();
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.BOTTOM);
        window.setDimAmount(0.55f);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            view.setPadding(basePaddingLeft, basePaddingTop, basePaddingRight,
                    basePaddingBottom + Math.max(0, bottom));
            return insets;
        });

        scroll.setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.5f));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        sendAllVolumes();
        if (prefs.hasAny()) {
            VolumeService.ensureRunning(this);
        }
        handler.removeCallbacks(refreshLoop);
        handler.post(refreshLoop);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refreshLoop);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            finish();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private View buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipToOutline(true);

        GradientDrawable background = new GradientDrawable();
        background.setColor(panelColor());
        float radius = dp(28);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        root.setBackground(background);

        basePaddingLeft = dp(24);
        basePaddingTop = dp(24);
        basePaddingRight = dp(24);
        basePaddingBottom = dp(20);
        root.setPadding(basePaddingLeft, basePaddingTop, basePaddingRight, basePaddingBottom);

        TextView title = new TextView(this);
        title.setText(R.string.app_volume);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(primaryTextColor());
        title.setAccessibilityHeading(true);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new MaxHeightScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(24);
        root.addView(scroll, scrollLp);

        emptyView = new TextView(this);
        emptyView.setText(R.string.app_volume_no_active_apps);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        emptyView.setTextColor(secondaryTextColor());
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, dp(32), 0, dp(32));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buttonsRow = new LinearLayout(this);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.topMargin = dp(24);
        root.addView(buttonsRow, buttonsLp);

        Button settings = makeButton(R.string.settings, false);
        settings.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
            } catch (Throwable ignored) {
            }
            finish();
        });
        buttonsRow.addView(settings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Space spacer = new Space(this);
        buttonsRow.addView(spacer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button done = makeButton(R.string.done, true);
        done.setOnClickListener(v -> finish());
        buttonsRow.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private Button makeButton(int textRes, boolean filled) {
        int accent = accentColor();
        int onAccent = isLight(accent) ? Color.BLACK : Color.WHITE;
        int outline = withAlpha(secondaryTextColor(), 0x55);

        Button button = new Button(this);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(filled ? onAccent : accent);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(24), 0, dp(24), 0);

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(24));
        if (filled) {
            shape.setColor(accent);
        } else {
            shape.setColor(Color.TRANSPARENT);
            shape.setStroke(dp(1), outline);
        }
        int ripple = filled ? withAlpha(Color.WHITE, 0x33) : withAlpha(accent, 0x22);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, null));
        button.setStateListAnimator(null);
        return button;
    }

    private void refresh() {
        if (!resumed || dragging || scanning) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            showPermission();
            return;
        }
        scanning = true;
        executor.execute(() -> {
            List<PlayerScanner.Player> players;
            try {
                players = PlayerScanner.scan(PanelActivity.this);
            } catch (Throwable t) {
                players = java.util.Collections.emptyList();
            }
            final List<PlayerScanner.Player> result = players;
            handler.post(() -> {
                scanning = false;
                if (resumed && !dragging) {
                    render(result);
                }
            });
        });
    }

    private void showPermission() {
        scroll.setVisibility(View.GONE);
        emptyView.setText(R.string.permission_text);
        emptyView.setVisibility(View.VISIBLE);
        list.removeAllViews();
        lastSignature = null;
    }

    private void render(List<PlayerScanner.Player> players) {
        if (players.isEmpty()) {
            scroll.setVisibility(View.GONE);
            emptyView.setText(R.string.app_volume_no_active_apps);
            emptyView.setVisibility(View.VISIBLE);
            list.removeAllViews();
            lastSignature = null;
            return;
        }

        StringBuilder signature = new StringBuilder();
        for (PlayerScanner.Player player : players) {
            signature.append(player.pkg).append(':');
            for (Integer session : player.sessions) {
                signature.append(session).append(',');
            }
            signature.append('#').append(prefs.getVolume(player.pkg)).append(';');
        }
        if (signature.toString().equals(lastSignature)) {
            return;
        }
        lastSignature = signature.toString();

        emptyView.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        int scrollY = scroll.getScrollY();
        list.removeAllViews();
        for (PlayerScanner.Player player : players) {
            list.addView(createRow(player));
        }
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private View createRow(PlayerScanner.Player player) {
        int accent = accentColor();
        int volume = prefs.getVolume(player.pkg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, dp(16));

        TextView label = new TextView(this);
        label.setText(player.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(primaryTextColor());
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout sliderBox = new FrameLayout(this);
        row.addView(sliderBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        SeekBar bar = new SeekBar(this);
        bar.setMax(20);
        bar.setProgress(Math.round(volume / 5f));
        bar.setSplitTrack(false);
        bar.setPadding(0, 0, dp(32), 0);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(
                withAlpha(secondaryTextColor(), 0x55)));
        bar.setContentDescription(player.label);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(accent);
        thumb.setCornerRadius(dp(2));
        thumb.setSize(dp(4), dp(32));
        bar.setThumb(thumb);
        bar.setThumbOffset(0);
        sliderBox.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        ImageButton icon = new ImageButton(this);
        icon.setImageDrawable(player.icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(4), dp(4), dp(4), dp(4));
        icon.setContentDescription(player.label);
        icon.setAlpha(volume == 0 ? 0.38f : 1.0f);
        Drawable ripple = borderlessRipple();
        if (ripple != null) {
            icon.setBackground(ripple);
        }
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                dp(32), dp(32), Gravity.END | Gravity.CENTER_VERTICAL);
        sliderBox.addView(icon, iconLp);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int newVolume = progress * 5;
                icon.setAlpha(newVolume == 0 ? 0.38f : 1.0f);
                prefs.setVolume(player.pkg, newVolume);
                sendVolume(player.pkg, newVolume);
                VolumeService.ensureRunning(PanelActivity.this);
                VolumeService.requestRefresh();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                dragging = false;
                refresh();
            }
        });

        icon.setOnClickListener(v -> {
            int current = prefs.getVolume(player.pkg);
            prefs.setMuted(player.pkg, current > 0);
            int newVolume = prefs.getVolume(player.pkg);
            bar.setProgress(Math.round(newVolume / 5f));
            icon.setAlpha(newVolume == 0 ? 0.38f : 1.0f);
            sendVolume(player.pkg, newVolume);
            VolumeService.ensureRunning(PanelActivity.this);
            VolumeService.requestRefresh();
        });

        return row;
    }

    private void sendVolume(String pkg, int volume) {
        Intent intent = new Intent(Protocol.ACTION_SET_VOLUME)
                .setPackage(Protocol.SYSTEMUI)
                .putExtra(Protocol.EXTRA_PACKAGE, pkg)
                .putExtra(Protocol.EXTRA_VOLUME, volume);
        sendBroadcast(intent);
    }

    private void sendAllVolumes() {
        Bundle bundle = new Bundle();
        for (Map.Entry<String, Integer> entry : prefs.all().entrySet()) {
            bundle.putInt(entry.getKey(), entry.getValue());
        }
        Intent intent = new Intent(Protocol.ACTION_SYNC_VOLUMES)
                .setPackage(Protocol.SYSTEMUI)
                .putExtra(Protocol.EXTRA_VOLUMES, bundle);
        sendBroadcast(intent);
    }

    private Drawable borderlessRipple() {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, value, true)
                || value.resourceId == 0) {
            return null;
        }
        try {
            return getDrawable(value.resourceId);
        } catch (Throwable t) {
            return null;
        }
    }

    private int accentColor() {
        return attrColor(android.R.attr.colorAccent, 0xFF4F8EF7);
    }

    private int primaryTextColor() {
        return attrColor(android.R.attr.textColorPrimary, 0xFF1C1B1F);
    }

    private int secondaryTextColor() {
        return attrColor(android.R.attr.textColorSecondary, 0xFF5F6368);
    }

    private int panelColor() {
        int floating = attrColor(android.R.attr.colorBackgroundFloating, 0);
        if (floating != 0) {
            return floating;
        }
        return attrColor(android.R.attr.colorBackground, 0xFF1C1B1F);
    }

    private int attrColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(attr, value, true)) {
            return fallback;
        }
        if (value.resourceId != 0) {
            try {
                return getColor(value.resourceId);
            } catch (Throwable t) {
                try {
                    return getResources().getColorStateList(value.resourceId, getTheme())
                            .getDefaultColor();
                } catch (Throwable ignored) {
                    return fallback;
                }
            }
        }
        return value.data;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static boolean isLight(int color) {
        double luminance = 0.2126 * (Color.red(color) / 255.0)
                + 0.7152 * (Color.green(color) / 255.0)
                + 0.0722 * (Color.blue(color) / 255.0);
        return luminance > 0.6;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
