package com.appvolume.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.Bundle;

import com.appvolume.Prefs;
import com.appvolume.Protocol;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 */
public final class EffectManager {
    private static final String TAG = "AppVolumeFx: ";
    private static final String DEBUG_FAKE = "com.appvolume.action.DEBUG_FAKE";
    private static final String DEBUG_CLEAN_LE = "com.appvolume.action.DEBUG_CLEAN_LE";
    private static final UUID LE_TYPE =
            UUID.fromString("fe3199be-aed0-413f-87bb-11260eb63cf1");
    private static final UUID LE_UUID =
            UUID.fromString("fa415329-2034-4bea-b5dc-5b381c8d1e2c");
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

    private final Map<String, Integer> volumes = new HashMap<>();
    private final Map<Integer, String> sessions = new HashMap<>();
    private final Map<Integer, Entry> effects = new HashMap<>();
    private final Map<Integer, Long> failed = new HashMap<>();
    private final BroadcastReceiver receiver;
    private boolean registered;

    /** Packages whose volume was changed locally and must not be overwritten. */
    /**
     * Volumes the user changed in this process. The map holds the value that
     * was set, not a timestamp: a periodic report from the companion app may
     * still contain the old value, so it must not win until it confirms the
     * new one. This used to expire after three seconds, which made the
     * sliders jump back when a stale report arrived.
     */
    private final Map<String, Integer> localEdits = new HashMap<>();

    /** Last non zero volume per package, used to unmute. */
    private final Map<String, Integer> lastVolumes = new HashMap<>();

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
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                try {
                    handle(intent);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
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
            manager.register();
            instance = manager;
            XposedBridge.log(TAG + "started in " + context.getPackageName());
        }
    }

    public static EffectManager get() {
        return instance;
    }

    private void register() {
        if (registered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Protocol.ACTION_SET_VOLUME);
        filter.addAction(Protocol.ACTION_SYNC_VOLUMES);
        filter.addAction(Protocol.ACTION_SESSIONS);
        filter.addAction(Protocol.ACTION_CLEAR);
        filter.addAction(DEBUG_CLEAN_LE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            registered = true;
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void handle(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (DEBUG_CLEAN_LE.equals(action)) {
            cleanLegacyLe();
            return;
        }
        if (DEBUG_FAKE.equals(action)) {
            // Debug helper: pretends that an application is playing, so that
            // the sliders can be inspected without starting a media app.
            String pkg = intent.getStringExtra(Protocol.EXTRA_PACKAGE);
            if (pkg != null) {
                sessions.put(999_999, pkg);
                reconcile();
                SystemUiHook.onSessionsChanged(true);
            }
            return;
        }
        if (!isFromPanelApp()) {
            return;
        }
        if (Protocol.ACTION_SET_VOLUME.equals(action)) {
            String pkg = intent.getStringExtra(Protocol.EXTRA_PACKAGE);
            int volume = intent.getIntExtra(Protocol.EXTRA_VOLUME, Prefs.MAX);
            if (pkg != null) {
                setVolume(pkg, volume);
            }
        } else if (Protocol.ACTION_SYNC_VOLUMES.equals(action)) {
            applyVolumes(intent.getBundleExtra(Protocol.EXTRA_VOLUMES));
            reconcile();
        } else if (Protocol.ACTION_SESSIONS.equals(action)) {
            applyVolumes(intent.getBundleExtra(Protocol.EXTRA_VOLUMES));
            Bundle bundle = intent.getBundleExtra(Protocol.EXTRA_SESSIONS);
            sessions.clear();
            if (bundle != null) {
                for (String pkg : bundle.keySet()) {
                    int[] ids = bundle.getIntArray(pkg);
                    if (ids == null) {
                        continue;
                    }
                    for (int id : ids) {
                        if (id > 0) {
                            sessions.put(id, pkg);
                        }
                    }
                }
            }
            reconcile();
            SystemUiHook.onSessionsChanged(hasActiveApps());
        } else if (Protocol.ACTION_CLEAR.equals(action)) {
            synchronized (this) {
                volumes.clear();
                localEdits.clear();
                sessions.clear();
            }
            releaseAll();
            SystemUiHook.onSessionsChanged(false);
        }
    }

    /**
     * The receiver is exported so that the companion app can send to SystemUI.
     * Accept broadcasts from the companion app only; otherwise another app
     * could silently change the gain of an arbitrary session.
     */
    private boolean isFromPanelApp() {
        try {
            Method method = BroadcastReceiver.class.getMethod("getSentFromUid");
            Object value = method.invoke(receiver);
            if (!(value instanceof Integer)) {
                return true;
            }
            int uid = (Integer) value;
            if (uid < 0) {
                return true;
            }
            int appUid = context.getPackageManager().getPackageUid("com.appvolume", 0);
            return uid == appUid;
        } catch (Throwable ignored) {
            // Older platforms do not expose the sending uid; the action is
            // package-targeted to SystemUI anyway.
            return true;
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

    public synchronized boolean hasActiveApps() {
        return !sessions.isEmpty();
    }

    /** Current volume of a package in percent (0..100). */
    public synchronized int getVolume(String pkg) {
        Integer value = volumes.get(pkg);
        return value == null ? Prefs.MAX : value;
    }

    public synchronized Map<String, Integer> getVolumes() {
        return new HashMap<>(volumes);
    }

    public Context getContext() {
        return context;
    }

    /** Remembers the last non zero volume of a package, used by the mute key. */
    public synchronized void rememberVolume(String pkg, int value) {
        if (value > 0) {
            lastVolumes.put(pkg, value);
        }
    }

    /** The volume a muted package returns to. */
    public synchronized int getLastVolume(String pkg) {
        Integer value = lastVolumes.get(pkg);
        if (value != null && value > 0) {
            return value;
        }
        return Prefs.MAX;
    }

    /**
     * Takes over the volumes reported by the companion app. A volume that was
     * changed locally is kept, so that a report that is still on its way does
     * not undo the change the user just made.
     */
    private void applyVolumes(Bundle bundle) {
        synchronized (this) {
            if (bundle != null) {
                for (String key : bundle.keySet()) {
                    int value = clamp(bundle.getInt(key, Prefs.MAX));
                    Integer local = localEdits.get(key);
                    if (local != null) {
                        if (local != value) {
                            // The companion app has not stored the new value
                            // yet. Keep what the user set and wait for the
                            // report that confirms it.
                            continue;
                        }
                        // The companion app has caught up, the periodic
                        // reports may own this package again.
                        localEdits.remove(key);
                    }
                    volumes.put(key, value);
                    rememberVolume(key, value);
                }
            }
        }
    }

    /**
     * Applies a new volume for a package right away. The audible change is
     * ramped, so dragging a slider does not produce clicks.
     */
    public void setVolume(String pkg, int percent) {
        if (pkg == null) {
            return;
        }
        synchronized (this) {
            volumes.put(pkg, clamp(percent));
            localEdits.put(pkg, clamp(percent));
        }
        reconcile();
        SystemUiHook.onVolumeChanged(pkg, clamp(percent));
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
     * Debug helper. Older builds used a LoudnessEnhancer and a killed SystemUI
     * could leave it enabled without a client, adding an unknown static gain
     * (and compression) on top of the new effect. Attaching with a negative
     * priority only obtains control when nobody else owns the effect, so an
     * enhancer that belongs to the application itself is never touched.
     */
    private void cleanLegacyLe() {
        List<Integer> ids;
        synchronized (this) {
            ids = new ArrayList<>(sessions.keySet());
        }
        for (int sessionId : ids) {
            AudioEffect effect = null;
            try {
                Constructor<AudioEffect> constructor = AudioEffect.class
                        .getDeclaredConstructor(UUID.class, UUID.class, int.class, int.class);
                constructor.setAccessible(true);
                effect = constructor.newInstance(LE_TYPE, LE_UUID, -1, sessionId);
                Method setParameter = AudioEffect.class
                        .getMethod("setParameter", int.class, int.class);
                Object status = setParameter.invoke(effect, 0, 0);
                if (Integer.valueOf(AudioEffect.SUCCESS).equals(status)) {
                    effect.setEnabled(true);
                    effect.setEnabled(false);
                    XposedBridge.log(TAG + "disabled legacy loudness enhancer on session "
                            + sessionId);
                } else {
                    XposedBridge.log(TAG + "legacy loudness enhancer on session " + sessionId
                            + " is owned by another client");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "cannot clean legacy effect on session " + sessionId
                        + ": " + t);
            } finally {
                if (effect != null) {
                    try {
                        effect.release();
                    } catch (Throwable ignored) {
                    }
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

    private void releaseAll() {
        for (Entry entry : effects.values()) {
            release(entry);
        }
        effects.clear();
        failed.clear();
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
