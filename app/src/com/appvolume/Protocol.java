package com.appvolume;

/** Broadcast protocol between the panel app and the SystemUI effect hook. */
public final class Protocol {
    public static final String SYSTEMUI = "com.android.systemui";

    public static final String ACTION_SET_VOLUME = "com.appvolume.action.SET_VOLUME";
    public static final String ACTION_SYNC_VOLUMES = "com.appvolume.action.SYNC_VOLUMES";
    public static final String ACTION_SESSIONS = "com.appvolume.action.SESSIONS";
    public static final String ACTION_CLEAR = "com.appvolume.action.CLEAR";

    public static final String EXTRA_PACKAGE = "pkg";
    public static final String EXTRA_VOLUME = "volume";
    public static final String EXTRA_VOLUMES = "volumes";
    public static final String EXTRA_SESSIONS = "sessions";

    private Protocol() {
    }
}
