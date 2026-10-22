package com.appvolume.xposed;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;

/**
 * Marks the application icon that belongs to an app-volume slider.
 *
 * <p>The compose icon of the ROM would normally tint every drawable with the
 * content color of the volume panel. That is correct for the monochrome
 * system icons, but not for an application icon. The marker lets the icon
 * hook recognize these drawables and draw them without the tint, so the
 * launcher-style colors are kept.
 */
final class AppIconDrawable extends DrawableWrapper {
    AppIconDrawable(Drawable icon) {
        super(icon);
    }
}
