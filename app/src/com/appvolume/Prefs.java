package com.appvolume;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Persisted per-package volume, in percent. 100 is the system volume (no
 * effect); a stored value is only kept for packages that are quieter than the
 * system volume.
 */
public final class Prefs {
    public static final int MAX = 100;

    private static final String FILE = "app_volume";
    private static final String VOLUME_PREFIX = "vol:";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public synchronized int getVolume(String pkg) {
        return clamp(sp.getInt(VOLUME_PREFIX + pkg, MAX));
    }

    public synchronized void setVolume(String pkg, int volume) {
        volume = clamp(volume);
        SharedPreferences.Editor editor = sp.edit();
        if (volume >= MAX) {
            editor.remove(VOLUME_PREFIX + pkg);
        } else {
            editor.putInt(VOLUME_PREFIX + pkg, volume);
        }
        editor.apply();
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

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX, value));
    }
}
