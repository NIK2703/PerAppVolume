package com.appvolume;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the apps that are currently playing audio and the AudioFlinger
 * session IDs that belong to them.
 *
 * <p>MediaPlayer/AudioTrack players expose their session id directly in
 * {@code dumpsys audio}. AAudio players usually report {@code sessionId:-1},
 * so their sessions are resolved from the global session table of
 * {@code dumpsys media.audio_flinger}. The player-piid history is used to get
 * the exact package name instead of guessing from the shared UID.
 */
public final class PlayerScanner {
    private static final String TAG = "AppVolume";

    private static final Pattern CURRENT_PLAYER = Pattern.compile(
            "piid:(\\d+).*?type:([^\\s]+) u/pid:(\\d+)/(\\d+) state:(\\w+)"
                    + ".*?sessionId:(-?\\d+)");
    private static final Pattern NEW_PLAYER = Pattern.compile(
            "new player piid:(\\d+) uid/pid:(\\d+)/(\\d+) package:([^\\s]+)");
    private static final Pattern FLINGER_ROW = Pattern.compile(
            "^\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)\\s*$");

    private static final Map<String, AppInfo> APP_CACHE = new HashMap<>();

    private PlayerScanner() {
    }

    public static final class Player {
        public final String pkg;
        public final String label;
        public final Drawable icon;
        public final int uid;
        public final List<Integer> sessions;

        Player(String pkg, String label, Drawable icon, int uid, List<Integer> sessions) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.uid = uid;
            this.sessions = sessions;
        }
    }

    private static final class AppInfo {
        final String label;
        final Drawable icon;
        final boolean systemApp;

        AppInfo(String label, Drawable icon, boolean systemApp) {
            this.label = label;
            this.icon = icon;
            this.systemApp = systemApp;
        }
    }

    private static final class RawPlayer {
        final int piid;
        final String type;
        final int uid;
        final int pid;
        final boolean active;
        final int sessionId;

        RawPlayer(int piid, String type, int uid, int pid, boolean active, int sessionId) {
            this.piid = piid;
            this.type = type;
            this.uid = uid;
            this.pid = pid;
            this.active = active;
            this.sessionId = sessionId;
        }
    }

    private static final class Builder {
        final String pkg;
        final int uid;
        final AppInfo info;
        final Set<Integer> sessions = new HashSet<>();

        Builder(String pkg, int uid, AppInfo info) {
            this.pkg = pkg;
            this.uid = uid;
            this.info = info;
        }
    }

    public static List<Player> scan(Context context) {
        String audio = runDumpsys("audio");
        if (audio == null || audio.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Integer, String> piidPackages = parseNewPlayers(audio);
        Map<Integer, RawPlayer> players = parseCurrentPlayers(audio);
        if (players.isEmpty()) {
            return Collections.emptyList();
        }

        PackageManager pm = context.getPackageManager();
        String myPackage = context.getPackageName();
        Map<String, Builder> grouped = new LinkedHashMap<>();
        Map<Integer, List<Integer>> flingerSessions = null;

        for (RawPlayer player : players.values()) {
            if (!player.active) {
                continue;
            }
            if (player.uid < 10000) {
                continue;
            }
            if (player.type != null && player.type.contains("SoundPool")) {
                // SoundPool sessions are short sound effects; session 0 cannot be
                // isolated with a per-session effect.
                continue;
            }

            String pkg = piidPackages.get(player.piid);
            if (pkg == null || pkg.isEmpty()) {
                pkg = resolvePackage(pm, player.uid);
            }
            if (pkg == null || pkg.equals(myPackage)) {
                continue;
            }

            AppInfo info = appInfo(pm, pkg);
            if (info == null) {
                String fallback = resolvePackage(pm, player.uid);
                if (fallback != null && !fallback.equals(pkg)) {
                    pkg = fallback;
                    info = appInfo(pm, pkg);
                }
            }
            if (info == null) {
                continue;
            }

            Builder builder = grouped.get(pkg);
            if (builder == null) {
                builder = new Builder(pkg, player.uid, info);
                grouped.put(pkg, builder);
            }

            if (player.sessionId > 0) {
                builder.sessions.add(player.sessionId);
            } else {
                if (flingerSessions == null) {
                    flingerSessions = parseFlingerSessions();
                }
                List<Integer> fallback = flingerSessions.get(player.uid);
                if (fallback != null) {
                    builder.sessions.addAll(fallback);
                }
            }
        }

        List<Player> result = new ArrayList<>();
        for (Builder builder : grouped.values()) {
            if (builder.sessions.isEmpty()) {
                continue;
            }
            List<Integer> sessions = new ArrayList<>(builder.sessions);
            Collections.sort(sessions);
            result.add(new Player(builder.pkg, builder.info.label, builder.info.icon,
                    builder.uid, Collections.unmodifiableList(sessions)));
        }
        Collections.sort(result, (a, b) -> {
            int byLabel = a.label.compareToIgnoreCase(b.label);
            return byLabel != 0 ? byLabel : a.pkg.compareTo(b.pkg);
        });
        return result;
    }

    private static Map<Integer, String> parseNewPlayers(String dump) {
        Map<Integer, String> result = new HashMap<>();
        for (String line : dump.split("\n")) {
            if (!line.contains("new player piid:")) {
                continue;
            }
            Matcher m = NEW_PLAYER.matcher(line);
            if (m.find()) {
                try {
                    result.put(Integer.parseInt(m.group(1)), m.group(4));
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    private static Map<Integer, RawPlayer> parseCurrentPlayers(String dump) {
        Map<Integer, RawPlayer> result = new LinkedHashMap<>();
        for (String line : dump.split("\n")) {
            if (!line.contains("AudioPlaybackConfiguration piid:")) {
                continue;
            }
            Matcher m = CURRENT_PLAYER.matcher(line);
            if (!m.find()) {
                continue;
            }
            try {
                int piid = Integer.parseInt(m.group(1));
                String type = m.group(2);
                int uid = Integer.parseInt(m.group(3));
                int pid = Integer.parseInt(m.group(4));
                String state = m.group(5);
                int sessionId = Integer.parseInt(m.group(6));
                boolean active = "started".equals(state) || "paused".equals(state);
                result.put(piid, new RawPlayer(piid, type, uid, pid, active, sessionId));
            } catch (Throwable t) {
                Log.w(TAG, "cannot parse player line: " + line, t);
            }
        }
        return result;
    }

    private static Map<Integer, List<Integer>> parseFlingerSessions() {
        Map<Integer, List<Integer>> result = new HashMap<>();
        String dump = runDumpsys("media.audio_flinger");
        if (dump == null) {
            return result;
        }
        boolean inSection = false;
        int parsed = 0;
        for (String line : dump.split("\n")) {
            String trimmed = line.trim();
            if (!inSection) {
                if (trimmed.startsWith("Global session refs:")
                        || trimmed.startsWith("Session refs:")) {
                    inSection = true;
                }
                continue;
            }
            if (trimmed.isEmpty()) {
                if (parsed > 0) {
                    break;
                }
                continue;
            }
            Matcher m = FLINGER_ROW.matcher(line);
            if (!m.matches()) {
                if (parsed > 0) {
                    break;
                }
                continue;
            }
            parsed++;
            try {
                int session = Integer.parseInt(m.group(1));
                int count = Integer.parseInt(m.group(2));
                int uid = Integer.parseInt(m.group(4));
                if (session <= 0 || count <= 0 || uid < 10000) {
                    continue;
                }
                List<Integer> list = result.get(uid);
                if (list == null) {
                    list = new ArrayList<>();
                    result.put(uid, list);
                }
                if (!list.contains(session)) {
                    list.add(session);
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static String runDumpsys(String service) {
        StringBuilder out = new StringBuilder(128 * 1024);
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", service)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                    if (out.length() > 4_000_000) {
                        break;
                    }
                }
            }
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroy();
            }
        } catch (Throwable t) {
            Log.w(TAG, "dumpsys " + service + " failed", t);
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
        return out.toString();
    }

    private static String resolvePackage(PackageManager pm, int uid) {
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return null;
        }
        return packages[0];
    }

    private static AppInfo appInfo(PackageManager pm, String pkg) {
        synchronized (APP_CACHE) {
            AppInfo cached = APP_CACHE.get(pkg);
            if (cached != null) {
                return cached;
            }
        }
        AppInfo info;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String label;
            try {
                label = pm.getApplicationLabel(ai).toString();
            } catch (Throwable t) {
                label = pkg;
            }
            Drawable icon;
            try {
                icon = pm.getApplicationIcon(ai);
            } catch (Throwable t) {
                icon = pm.getDefaultActivityIcon();
            }
            boolean system = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            info = new AppInfo(label, icon, system);
        } catch (Throwable t) {
            synchronized (APP_CACHE) {
                APP_CACHE.put(pkg, null);
            }
            return null;
        }
        synchronized (APP_CACHE) {
            APP_CACHE.put(pkg, info);
        }
        return info;
    }
}
