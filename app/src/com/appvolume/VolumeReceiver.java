package com.appvolume;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Receives the volume changes that the SystemUI hook applies.
 *
 * <p>The sliders live inside the volume dialog, so the change is applied by
 * the hook right away. The companion app only stores the new value, so that
 * it survives a restart of the audio session.
 */
public final class VolumeReceiver extends BroadcastReceiver {
    private static final String TAG = "AppVolume";
    private static final int SYSTEM_UID = 1000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Protocol.ACTION_SET_VOLUME.equals(intent.getAction())) {
            return;
        }
        if (!isFromSystem()) {
            return;
        }
        String pkg = intent.getStringExtra(Protocol.EXTRA_PACKAGE);
        if (pkg == null) {
            return;
        }
        int volume = intent.getIntExtra(Protocol.EXTRA_VOLUME, Prefs.MAX);
        Log.i(TAG, "stored app volume " + pkg + " = " + volume);
        new Prefs(context).setVolume(pkg, volume);
        VolumeService.ensureRunning(context);
        VolumeService.requestRefresh();
    }

    /**
     * Only the system may change the stored volumes, so that no other app can
     * silently take over the audio of a running application.
     */
    private boolean isFromSystem() {
        try {
            Method method = BroadcastReceiver.class.getMethod("getSentFromUid");
            Object value = method.invoke(this);
            if (value instanceof Integer) {
                int uid = (Integer) value;
                // getSentFromUid() returns -1 when the platform does not
                // expose the sender for this broadcast. The action is
                // package-targeted and the hook is the only sender, so an
                // unknown uid must not be treated as a foreign app.
                if (uid < 0) {
                    return true;
                }
                return uid == SYSTEM_UID || uid == android.os.Process.myUid();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }
}
