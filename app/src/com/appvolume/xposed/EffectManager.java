package com.appvolume.xposed;

import android.content.Context;
import android.media.audiofx.DynamicsProcessing;
import android.os.Handler;
import android.os.Looper;

import com.appvolume.PlayerScanner;
import com.appvolume.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedBridge;

/**
 * Applies per-package volume inside SystemUI.
 *
 * <p>crDroid does this in AudioFlinger, where the package volume is multiplied
 * into every track. SystemUI has MODIFY_AUDIO_ROUTING, so it can attach a
 * DynamicsProcessing effect to the session of another application. For a linear
 * volume v in 0..1 the input gain 2000*log10(v) mB multiplies the track by the
 * same factor, which is the same audible result for the range used by the
 * crDroid sliders.
 *
 * <p>The effect is used with all of its dynamics stages disabled, as a plain
 * gain. It processes overlapping FFT frames, so a gain update is applied as a
 * short per sample crossfade instead of a step on an audio buffer boundary.
 * This is what keeps the application volume free of clicks. The effect is also
 * a volume controller, so AudioFlinger feeds it the system volume separately
 * through its output gain, and that gain is smoothed in the same way.
 *
 * <p>The active players are scanned inside SystemUI. The companion app and the
 * DUMP permission grant it needed are not part of the module any more.
 */
public final class EffectManager {
    private static final String TAG = "AppVolumeFx: ";
    private static final long SCAN_INTERVAL_MS = 1_500L;
    private static final long RETRY_MS = 15_000L;
    /**
     * Frame of the DSP effect. The effect processes overlapping frames, so a
     * gain change is applied as a short per sample crossfade instead of a step
     * on an audio buffer boundary. This is what makes the slider silent.
     */
    private static final float FRAME_MS = 10.0f;
    /** Below this level the application is considered muted. */
    private static final int MUTE_MB = -9600;

    private static volatile EffectManager instance;

    private final Context context;
    private final Prefs prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, Integer> volumes = new HashMap<>();
    private final Map<Integer, String> sessions = new HashMap<>();
    private final Map<Integer, Entry> effects = new HashMap<>();
    private final Map<Integer, Long> failed = new HashMap<>();

    private ScheduledExecutorService scanner;
    private boolean scannerStarted;
    private boolean sessionsKnown;

    private static final class Entry {
        final int sessionId;
        String pkg;
        int targetGainMb;
        final DynamicsProcessing effect;

        Entry(int sessionId, String pkg, int targetGainMb, DynamicsProcessing effect) {
            this.sessionId = sessionId;
            this.pkg = pkg;
            this.targetGainMb = targetGainMb;
            this.effect = effect;
        }
    }

    private EffectManager(Context context) {
        this.context = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        prefs = new Prefs(this.context);
        for (Map.Entry<String, Integer> item : prefs.all().entrySet()) {
            volumes.put(item.getKey(), clamp(item.getValue()));
        }
    }

    public static void ensureStarted(Context context) {
        if (context == null || instance != null) {
            return;
        }
        synchronized (EffectManager.class) {
            if (instance != null) {
                return;
            }
            EffectManager manager = new EffectManager(context);
            instance = manager;
            manager.startScanner();
            XposedBridge.log(TAG + "started in " + context.getPackageName());
        }
    }

    public static EffectManager get() {
        return instance;
    }

    /**
     * Scans the playback sessions from this process. SystemUI has the DUMP
     * permission itself, so no permission has to be granted to a helper app.
     */
    private synchronized void startScanner() {
        if (scannerStarted) {
            return;
        }
        scannerStarted = true;
        scanner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AppVolumeSessionScanner");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        scanner.scheduleWithFixedDelay(this::scanSessions, 0L,
                SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void scanSessions() {
        final List<PlayerScanner.Player> players;
        try {
            players = PlayerScanner.scan(context);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "session scan failed: " + t);
            return;
        }
        if (players == null) {
            // The dump itself failed. Keep the last known sessions instead of
            // releasing every effect because of a transient error.
            return;
        }
        mainHandler.post(() -> applySessions(players));
    }

    /** Replaces the active session table with a fresh scan. */
    private void applySessions(List<PlayerScanner.Player> players) {
        Map<Integer, String> found = new HashMap<>();
        if (players != null) {
            for (PlayerScanner.Player player : players) {
                if (player == null || player.pkg == null) {
                    continue;
                }
                for (Integer sessionId : player.sessions) {
                    if (sessionId != null && sessionId > 0) {
                        found.put(sessionId, player.pkg);
                    }
                }
            }
        }

        boolean changed;
        synchronized (this) {
            changed = !found.equals(sessions);
            sessions.clear();
            sessions.putAll(found);
        }

        // Reconcile even when the set did not change: an effect that failed
        // earlier is retried after RETRY_MS.
        reconcile();

        if (changed || !sessionsKnown) {
            sessionsKnown = true;
            SystemUiHook.onSessionsChanged(!found.isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // Public API used by the volume panel that lives inside the dialog
    // ------------------------------------------------------------------

    /** Packages that currently produce audio. */
    public synchronized List<String> getActivePackages() {
        Set<String> packages = new HashSet<>(sessions.values());
        List<String> result = new ArrayList<>(packages);
        Collections.sort(result);
        return result;
    }

    /** Current volume of a package in percent (0..100). */
    public synchronized int getVolume(String pkg) {
        Integer value = volumes.get(pkg);
        return value == null ? prefs.getVolume(pkg) : value;
    }

    /**
     * Applies a new volume for a package right away. The value is stored in
     * SystemUI's own preferences, so it survives a restart without a companion
     * app or a root helper.
     */
    public void setVolume(String pkg, int percent) {
        if (pkg == null) {
            return;
        }
        int value = clamp(percent);
        synchronized (this) {
            volumes.put(pkg, value);
        }
        prefs.setVolume(pkg, value);
        reconcile();
        SystemUiHook.onVolumeChanged(pkg, value);
    }

    private void reconcile() {
        Map<Integer, Desired> wanted = new HashMap<>();
        synchronized (this) {
            for (Map.Entry<Integer, String> item : sessions.entrySet()) {
                int sessionId = item.getKey();
                String pkg = item.getValue();
                Integer volume = volumes.get(pkg);
                if (volume == null) {
                    continue;
                }
                // A playing application that was never made quieter does not
                // need an effect. An effect that already exists is kept at
                // 0 dB: removing it while the track is running would be a
                // change of the audio chain, audible as a click.
                if (volume >= Prefs.MAX && !effects.containsKey(sessionId)) {
                    continue;
                }
                wanted.put(sessionId, new Desired(pkg, gainMb(volume)));
            }
        }

        Iterator<Map.Entry<Integer, Entry>> iterator = effects.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Entry> item = iterator.next();
            if (!wanted.containsKey(item.getKey())) {
                release(item.getValue());
                iterator.remove();
                failed.remove(item.getKey());
            }
        }

        for (Map.Entry<Integer, Desired> item : wanted.entrySet()) {
            int sessionId = item.getKey();
            Desired desired = item.getValue();
            Entry entry = effects.get(sessionId);
            if (entry == null) {
                Long failedAt = failed.get(sessionId);
                if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MS) {
                    continue;
                }
                try {
                    DynamicsProcessing effect = createEffect(sessionId, desired.gainMb);
                    effects.put(sessionId, new Entry(sessionId, desired.pkg,
                            desired.gainMb, effect));
                    failed.remove(sessionId);
                    XposedBridge.log(TAG + "attached session " + sessionId
                            + " (" + desired.pkg + ") gain=" + desired.gainMb + "mB");
                } catch (Throwable t) {
                    failed.put(sessionId, System.currentTimeMillis());
                    XposedBridge.log(TAG + "cannot attach session " + sessionId
                            + " (" + desired.pkg + "): " + t);
                }
            } else if (entry.targetGainMb != desired.gainMb || !entry.pkg.equals(desired.pkg)) {
                entry.targetGainMb = desired.gainMb;
                entry.pkg = desired.pkg;
                try {
                    entry.effect.setInputGainAllChannelsTo(desired.gainMb / 100.0f);
                    XposedBridge.log(TAG + "target session " + sessionId
                            + " (" + desired.pkg + ") gain=" + desired.gainMb + "mB");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "cannot set session " + sessionId + ": " + t);
                }
            }
        }
    }

    /**
     * Builds a pure gain effect. All dynamics stages (equalizers, compressor,
     * limiter) are disabled, so only the input gain of the application volume
     * is applied. The effect is a volume controller: AudioFlinger sends it the
     * system volume as well, and the effect applies that in its separate
     * output gain stage.
     *
     * <p>The gain is changed inside an overlapping FFT, which turns every
     * update into a short per sample crossfade. That is what keeps the slider
     * free of clicks, unlike a step of the loudness enhancer applied on an
     * audio buffer boundary.
     */
    private static DynamicsProcessing createEffect(int sessionId, int gainMb) {
        DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,        // channels; the effect adapts to the real count
                false, 0, // pre EQ
                false, 0, // multi band compressor
                false, 0, // post EQ
                false)    // limiter
                .setPreferredFrameDuration(FRAME_MS)
                .build();
        DynamicsProcessing effect = new DynamicsProcessing(0, sessionId, config);
        effect.setInputGainAllChannelsTo(gainMb / 100.0f);
        effect.setEnabled(true);
        return effect;
    }

    private static int gainMb(int volumePercent) {
        if (volumePercent <= 0) {
            return MUTE_MB;
        }
        if (volumePercent >= Prefs.MAX) {
            return 0;
        }
        double gain = 2000.0 * Math.log10(volumePercent / (double) Prefs.MAX);
        return (int) Math.max(MUTE_MB, Math.min(0, Math.round(gain)));
    }

    private void release(Entry entry) {
        try {
            entry.effect.setEnabled(false);
        } catch (Throwable ignored) {
        }
        try {
            entry.effect.release();
        } catch (Throwable ignored) {
        }
        XposedBridge.log(TAG + "released session " + entry.sessionId + " (" + entry.pkg + ")");
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(Prefs.MAX, value));
    }

    private static final class Desired {
        final String pkg;
        final int gainMb;

        Desired(String pkg, int gainMb) {
            this.pkg = pkg;
            this.gainMb = gainMb;
        }
    }
}
