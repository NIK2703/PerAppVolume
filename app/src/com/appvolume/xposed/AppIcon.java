package com.appvolume.xposed;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

/** Loads the icon and label shown by an app volume slider. */
final class AppIcon {
    private AppIcon() {
    }

    /**
     * The launcher icon of the package, wrapped in {@link AppIconDrawable} so
     * the compose icon does not tint it with the volume-panel content color.
     */
    static Drawable load(Context context, String pkg) {
        try {
            Drawable icon = context.getPackageManager().getApplicationIcon(pkg);
            return icon != null ? new AppIconDrawable(icon) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static CharSequence label(Context context, String pkg) {
        try {
            PackageManager manager = context.getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(pkg, 0);
            CharSequence label = manager.getApplicationLabel(info);
            if (label != null && label.length() > 0) {
                return label;
            }
        } catch (Throwable ignored) {
        }
        return pkg;
    }
}
