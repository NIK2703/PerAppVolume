package com.appvolume;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Persisted per-package volume, in percent. 100 is the system volume (no
 * effect); 0 is muted. The last non-zero value is kept so that the mute
 * button can restore it, matching crDroid's AppVolumeSliderViewModel.
 */
public final class Prefs {
    public static final int MAX = 100;

    private static final String FILE = "app_volume";
    private static final String VOLUME_PREFIX = "vol:";
    private static final String LAST_PREFIX = "last:";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public synchronized int getVolume(String pkg) {
        return clamp(sp.getInt(VOLUME_PREFIX + pkg, MAX));
    }

    public synchronized int getLastVolume(String pkg) {
        return clamp(sp.getInt(LAST_PREFIX + pkg, MAX));
    }

    public synchronized void setVolume(String pkg, int volume) {
        volume = clamp(volume);
        SharedPreferences.Editor editor = sp.edit();
        if (volume >= MAX) {
            editor.remove(VOLUME_PREFIX + pkg);
        } else {
            editor.putInt(VOLUME_PREFIX + pkg, volume);
        }
        if (volume > 0) {
            editor.putInt(LAST_PREFIX + pkg, volume);
        }
        editor.apply();
    }

    /** Toggles mute. Unmuting restores the last non-zero volume. */
    public synchronized void setMuted(String pkg, boolean muted) {
        if (muted) {
            int current = getVolume(pkg);
            SharedPreferences.Editor editor = sp.edit();
            if (current > 0) {
                editor.putInt(LAST_PREFIX + pkg, current);
            }
            editor.putInt(VOLUME_PREFIX + pkg, 0);
            editor.apply();
        } else {
            int last = getLastVolume(pkg);
            setVolume(pkg, last <= 0 ? MAX : last);
        }
    }

    /** Returns only packages with a stored volume below 100%. */
    public synchronized Map<String, Integer> all() {
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(VOLUME_PREFIX) || !(entry.getValue() instanceof Integer)) {
                continue;
            }
            result.put(key.substring(VOLUME_PREFIX.length()),
                    clamp((Integer) entry.getValue()));
        }
        return result;
    }

    public synchronized boolean hasAny() {
        for (Integer value : all().values()) {
            if (value < MAX) {
                return true;
            }
        }
        return false;
    }

    public synchronized void clear() {
        sp.edit().clear().apply();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX, value));
    }
}
