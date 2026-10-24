package com.appvolume.xposed;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.DynamicsProcessing;
import android.os.Handler;
import android.os.Looper;

import com.appvolume.PlayerScanner;
import com.appvolume.Prefs;

import java.lang.reflect.Method;
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
 * <p>crDroid applies the package volume in AudioFlinger, where it is multiplied
 * into every track. SystemUI has {@code MODIFY_AUDIO_ROUTING}, so the audio
 * service returns it the real (not anonymized) active playback configurations.
 * Each configuration exposes a player proxy. Calling
 * {@code PlayerProxy#setVolume(v)} changes the player's volume multiplier in
 * its own process, which is multiplied into the app's AudioTrack. This is the
 * same mechanism the audio service uses for call muting, and it works for Java
 * players as well as for native AAudio fast tracks, where a session effect
 * cannot be attached.
 *
 * <p>A session {@link DynamicsProcessing} effect is kept as a fallback for the
 * rare player that has no usable proxy. That effect can only process normal
 * mixer tracks; it cannot be added to a running fast track. The fallback is
 * skipped for players that the proxy already controls, so the gain is never
 * applied twice.
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
    /** Delay before the audio dump is refreshed after a playback change. */
    private static final long PLAYBACK_SCAN_DELAY_MS = 150L;
    /**
     * The player volume is also used by the audio service for call muting.
     * Reapplying it at most once per second lets the module restore its own
     * gain after a call without fighting a mute in progress.
     */
    private static final long REAPPLY_MS = 1_000L;

    private static volatile EffectManager instance;

    /** {@code AudioPlaybackConfiguration#PLAYER_STATE_STARTED}. */
    private static final int PLAYER_STATE_STARTED = 2;
    /** {@code AudioPlaybackConfiguration#PLAYER_STATE_PAUSED}. */
    private static final int PLAYER_STATE_PAUSED = 3;

    /** Hidden accessors of {@link AudioPlaybackConfiguration}; cached once. */
    private static volatile Method sPlayerStateMethod;
    private static volatile Method sClientUidMethod;
    private static volatile Method sPlayerProxyMethod;
    private static volatile Method sPlayerInterfaceIdMethod;
    private static volatile Method sSessionIdMethod;
    private static volatile Method sPlayerVolumeMethod;
    private static volatile boolean sPlaybackAccessLogged;
    private static volatile boolean sPlayerAccessLogged;

    private final Context context;
    private final Prefs prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int myUid;
    private final AudioManager audioManager;

    /** Reports every start and stop of a player without waiting for a dump. */
    private AudioManager.AudioPlaybackCallback playbackCallback;
    private AudioManager.OnModeChangedListener modeListener;
    private volatile boolean playbackKnown;
    private volatile boolean playbackActive;

    private final Map<String, Integer> volumes = new HashMap<>();
    /** Sessions used by the effect fallback. */
    private final Map<Integer, String> sessions = new HashMap<>();
    /** Exact package of a piid, from the player-piid history of dumpsys. */
    private final Map<Integer, String> piidPackages = new HashMap<>();
    /** Players whose volume is controlled through the player proxy. */
    private final Set<Integer> proxySessions = new HashSet<>();
    /** Last gain written to each piid, so an unchanged player is not spammed. */
    private final Map<Integer, Applied> applied = new HashMap<>();
    private final Map<Integer, Entry> effects = new HashMap<>();
    private final Map<Integer, Long> failed = new HashMap<>();

    private ScheduledExecutorService scanner;
    private boolean scannerStarted;
    private boolean sessionsKnown;
    private final Runnable playbackScan = () -> {
        ScheduledExecutorService service = scanner;
        if (service != null) {
            service.execute(this::scanSessions);
        }
    };

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

    private static final class Applied {
        final String pkg;
        final int percent;
        final long at;

        Applied(String pkg, int percent, long at) {
            this.pkg = pkg;
            this.percent = percent;
            this.at = at;
        }
    }

    private EffectManager(Context context) {
        this.context = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        myUid = this.context.getApplicationInfo().uid;
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
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
        startPlaybackCallback();
    }

    /**
     * Subscribes to the audio service. The callback is delivered as soon as a
     * player starts or stops, so the app-volume button does not wait for the
     * next session dump, which can be up to one scan interval behind.
     */
    private void startPlaybackCallback() {
        if (audioManager == null) {
            XposedBridge.log(TAG + "no audio manager, the button follows the dump");
            return;
        }
        try {
            playbackCallback = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(
                        List<AudioPlaybackConfiguration> configs) {
                    applyPlaybackConfigs(configs);
                }
            };
            audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler);
            XposedBridge.log(TAG + "playback callback registered");
            // The callback reports changes only after it is registered; seed
            // the state from the players that are already running.
            mainHandler.post(this::reconcile);
        } catch (Throwable t) {
            playbackCallback = null;
            XposedBridge.log(TAG + "playback callback not registered: " + t);
        }

        try {
            // Call muting uses the same player volume multiplier as the app
            // volume. Reapply the module gain when a call ends so the app does
            // not stay muted or jump back to full volume.
            modeListener = mode -> mainHandler.post(() -> {
                synchronized (this) {
                    applied.clear();
                }
                reconcile();
            });
            audioManager.addOnModeChangedListener(
                    command -> mainHandler.post(command), modeListener);
            XposedBridge.log(TAG + "audio mode listener registered");
        } catch (Throwable t) {
            modeListener = null;
            XposedBridge.log(TAG + "audio mode listener not registered: " + t);
        }
    }

    /**
     * Turns a playback callback into the button state. Only applications are
     * counted: system players (uid below {@code 10000}) do not get an app
     * slider. The player state is checked instead of
     * {@code AudioPlaybackConfiguration#isActive()}, because on this ROM
     * {@code isActive()} turns false when the media stream is muted. The app
     * is still playing in that case, and the button has to stay reachable so
     * the per-app volumes can be adjusted while the main volume is at zero.
     */
    private void applyPlaybackConfigs(List<AudioPlaybackConfiguration> configs) {
        // The callback list can be partial, so the player volume is
        // reconciled from the complete list from the audio service.
        reconcile();

        boolean active = false;
        if (configs != null) {
            for (AudioPlaybackConfiguration config : configs) {
                if (config == null) {
                    continue;
                }
                Boolean started = isStarted(config);
                if (started == null) {
                    // The hidden accessors are not readable; keep the dump as
                    // the source of the button state instead of hiding it.
                    return;
                }
                if (!started) {
                    continue;
                }
                Integer uid = clientUid(config);
                if (uid == null) {
                    return;
                }
                if (uid >= 10000 && uid != myUid) {
                    active = true;
                    break;
                }
            }
        }

        boolean changed = !playbackKnown || playbackActive != active;
        playbackKnown = true;
        playbackActive = active;
        if (!changed) {
            return;
        }
        XposedBridge.log(TAG + "app playback " + (active ? "started" : "stopped"));
        SystemUiHook.onPlaybackStateChanged(active);
        // Refresh the session table behind the rows right away instead of
        // waiting for the next periodic scan.
        requestScan();
    }

    /**
     * {@code AudioPlaybackConfiguration#getPlayerState()}. Returns {@code -1}
     * if the hidden accessor is unavailable.
     */
    private static int playerState(AudioPlaybackConfiguration config) {
        try {
            Method method = sPlayerStateMethod;
            if (method == null) {
                method = AudioPlaybackConfiguration.class.getMethod("getPlayerState");
                method.setAccessible(true);
                sPlayerStateMethod = method;
            }
            Object value = method.invoke(config);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable t) {
            logPlaybackAccess(t);
            return -1;
        }
    }

    /**
     * {@code AudioPlaybackConfiguration#getPlayerState() == PLAYER_STATE_STARTED}.
     * Returns {@code null} if the hidden accessor is unavailable.
     */
    private static Boolean isStarted(AudioPlaybackConfiguration config) {
        int state = playerState(config);
        return state < 0 ? null : state == PLAYER_STATE_STARTED;
    }

    /** {@code AudioPlaybackConfiguration#getClientUid()}, or {@code null} if unavailable. */
    private static Integer clientUid(AudioPlaybackConfiguration config) {
        try {
            Method method = sClientUidMethod;
            if (method == null) {
                method = AudioPlaybackConfiguration.class.getMethod("getClientUid");
                method.setAccessible(true);
                sClientUidMethod = method;
            }
            Object value = method.invoke(config);
            return value instanceof Number ? ((Number) value).intValue() : null;
        } catch (Throwable t) {
            logPlaybackAccess(t);
            return null;
        }
    }

    /** {@code AudioPlaybackConfiguration#getPlayerInterfaceId()}, or {@code -1}. */
    private static int playerInterfaceId(AudioPlaybackConfiguration config) {
        try {
            Method method = sPlayerInterfaceIdMethod;
            if (method == null) {
                method = AudioPlaybackConfiguration.class.getMethod(
                        "getPlayerInterfaceId");
                method.setAccessible(true);
                sPlayerInterfaceIdMethod = method;
            }
            Object value = method.invoke(config);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable t) {
            logPlayerAccess(t);
            return -1;
        }
    }

    /** {@code AudioPlaybackConfiguration#getSessionId()}, or {@code -1}. */
    private static int sessionId(AudioPlaybackConfiguration config) {
        try {
            Method method = sSessionIdMethod;
            if (method == null) {
                method = AudioPlaybackConfiguration.class.getMethod("getSessionId");
                method.setAccessible(true);
                sSessionIdMethod = method;
            }
            Object value = method.invoke(config);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable t) {
            logPlayerAccess(t);
            return -1;
        }
    }

    /**
     * {@code AudioPlaybackConfiguration#getPlayerProxy()}. This returns
     * {@code null} for anonymized configurations; SystemUI has
     * {@code MODIFY_AUDIO_ROUTING}, so its configurations are not anonymized.
     */
    private static Object playerProxy(AudioPlaybackConfiguration config) {
        try {
            Method method = sPlayerProxyMethod;
            if (method == null) {
                method = AudioPlaybackConfiguration.class.getMethod("getPlayerProxy");
                method.setAccessible(true);
                sPlayerProxyMethod = method;
            }
            return method.invoke(config);
        } catch (Throwable t) {
            logPlayerAccess(t);
            return null;
        }
    }

    /**
     * Writes the volume multiplier of a player. The multiplier is applied by
     * the player's own process, so this also works for AAudio fast tracks
     * that have no session effect chain.
     */
    private static void setPlayerVolume(Object proxy, float gain) throws Exception {
        Method method = sPlayerVolumeMethod;
        if (method == null) {
            method = proxy.getClass().getMethod("setVolume", float.class);
            method.setAccessible(true);
            sPlayerVolumeMethod = method;
        }
        method.invoke(proxy, gain);
    }

    private static void logPlaybackAccess(Throwable t) {
        if (sPlaybackAccessLogged) {
            return;
        }
        sPlaybackAccessLogged = true;
        XposedBridge.log(TAG + "playback state unavailable: " + t);
    }

    private static void logPlayerAccess(Throwable t) {
        if (sPlayerAccessLogged) {
            return;
        }
        sPlayerAccessLogged = true;
        XposedBridge.log(TAG + "player access unavailable: " + t);
    }

    private List<AudioPlaybackConfiguration> activeConfigs() {
        if (audioManager == null) {
            return null;
        }
        try {
            return audioManager.getActivePlaybackConfigurations();
        } catch (Throwable t) {
            if (!sPlaybackAccessLogged) {
                sPlaybackAccessLogged = true;
                XposedBridge.log(TAG + "active playback configurations unavailable: " + t);
            }
            return null;
        }
    }

    private String packageForUid(int uid) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return null;
        }
        if (packages.length > 1) {
            // A shared UID can own several packages. The piid history from
            // the scan is exact; this is only the fallback for a player that
            // started between two scans.
            for (String pkg : packages) {
                if (getVolume(pkg) < Prefs.MAX) {
                    return pkg;
                }
            }
        }
        return packages[0];
    }

    /**
     * Applies the stored volume to every active player of an application.
     * This is the path that reaches fast/AAudio tracks. The complete
     * configuration list is used instead of a callback list, because a
     * callback only carries the players that changed.
     */
    private void applyPlayerVolumes(List<AudioPlaybackConfiguration> configs) {
        if (configs == null) {
            // A transient binder error. Keep the last proxy coverage instead
            // of reattaching session effects next to an active proxy.
            return;
        }

        boolean normalMode = audioManager == null
                || audioManager.getMode() == AudioManager.MODE_NORMAL;
        long now = System.currentTimeMillis();
        Set<Integer> seen = new HashSet<>();
        Set<Integer> covered = new HashSet<>();

        for (AudioPlaybackConfiguration config : configs) {
            if (config == null) {
                continue;
            }
            int state = playerState(config);
            if (state != PLAYER_STATE_STARTED && state != PLAYER_STATE_PAUSED) {
                continue;
            }
            Integer uidValue = clientUid(config);
            if (uidValue == null) {
                continue;
            }
            int uid = uidValue;
            if (uid < 10000 || uid == myUid) {
                continue;
            }
            int piid = playerInterfaceId(config);
            if (piid < 0) {
                continue;
            }
            String pkg = piidPackages.get(piid);
            if (pkg == null) {
                pkg = packageForUid(uid);
            }
            if (pkg == null || pkg.equals(context.getPackageName())) {
                continue;
            }
            Object proxy = playerProxy(config);
            if (proxy == null) {
                // No player proxy. The session effect is the only remaining
                // way to control this player.
                continue;
            }

            // The player volume is also the call-mute volume. Do not touch it
            // while a call (or another non-normal audio mode) is active; the
            // mode listener forces a reapply when the call ends.
            if (!normalMode) {
                continue;
            }

            int percent = getVolume(pkg);
            Applied previous = applied.get(piid);
            if (previous != null && previous.percent == percent
                    && previous.pkg.equals(pkg)
                    && now - previous.at < REAPPLY_MS) {
                seen.add(piid);
                int session = sessionId(config);
                if (session > 0) {
                    covered.add(session);
                }
                continue;
            }

            try {
                setPlayerVolume(proxy, percent / 100.0f);
                applied.put(piid, new Applied(pkg, percent, now));
                seen.add(piid);
                int session = sessionId(config);
                if (session > 0) {
                    covered.add(session);
                }
                if (previous == null || previous.percent != percent
                        || !previous.pkg.equals(pkg)) {
                    XposedBridge.log(TAG + "player " + piid + " (" + pkg
                            + ") volume=" + percent);
                }
            } catch (Throwable t) {
                applied.remove(piid);
                if (previous == null) {
                    XposedBridge.log(TAG + "cannot set player " + piid
                            + " (" + pkg + "): " + cause(t));
                }
            }
        }

        // A player that stopped no longer needs an entry. Its multiplier
        // dies with the player; a new player gets a new piid.
        applied.keySet().retainAll(seen);
        synchronized (this) {
            proxySessions.clear();
            proxySessions.addAll(covered);
        }
    }

    private static Throwable cause(Throwable t) {
        return t.getCause() != null ? t.getCause() : t;
    }

    private void requestScan() {
        mainHandler.removeCallbacks(playbackScan);
        mainHandler.postDelayed(playbackScan, PLAYBACK_SCAN_DELAY_MS);
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

    /** Replaces the active player and session tables with a fresh scan. */
    private void applySessions(List<PlayerScanner.Player> players) {
        Map<Integer, String> foundSessions = new HashMap<>();
        Map<Integer, String> foundPiids = new HashMap<>();
        if (players != null) {
            for (PlayerScanner.Player player : players) {
                if (player == null || player.pkg == null) {
                    continue;
                }
                for (Integer sessionId : player.sessions) {
                    if (sessionId != null && sessionId > 0) {
                        foundSessions.put(sessionId, player.pkg);
                    }
                }
                for (Integer piid : player.piids) {
                    if (piid != null && piid >= 0) {
                        foundPiids.put(piid, player.pkg);
                    }
                }
            }
        }

        boolean changed;
        synchronized (this) {
            changed = !foundSessions.equals(sessions)
                    || !foundPiids.equals(piidPackages);
            sessions.clear();
            sessions.putAll(foundSessions);
            piidPackages.clear();
            piidPackages.putAll(foundPiids);
        }

        // Reconcile even when the set did not change: an effect that failed
        // earlier is retried after RETRY_MS.
        reconcile();

        if (changed || !sessionsKnown) {
            sessionsKnown = true;
            if (playbackKnown) {
                // The live callback owns the button visibility; the dump only
                // refreshes the rows of a panel that is already open.
                SystemUiHook.onActiveAppsChanged();
            } else {
                SystemUiHook.onSessionsChanged(!foundSessions.isEmpty()
                        || !foundPiids.isEmpty());
            }
        }
    }

    // ------------------------------------------------------------------
    // Public API used by the volume panel that lives inside the dialog
    // ------------------------------------------------------------------

    /** Packages that currently produce audio. */
    public synchronized List<String> getActivePackages() {
        Set<String> packages = new HashSet<>(sessions.values());
        // AAudio players have no usable session, but they do have a piid, so
        // the rows of the panel must include the piid packages as well.
        packages.addAll(piidPackages.values());
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
        applyPlayerVolumes(activeConfigs());
        reconcileEffects();
    }

    private void reconcileEffects() {
        Map<Integer, Desired> wanted = new HashMap<>();
        synchronized (this) {
            for (Map.Entry<Integer, String> item : sessions.entrySet()) {
                int sessionId = item.getKey();
                if (proxySessions.contains(sessionId)) {
                    // The player proxy already multiplies this session's
                    // tracks by the app volume. An effect would attenuate the
                    // same audio a second time.
                    continue;
                }
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
