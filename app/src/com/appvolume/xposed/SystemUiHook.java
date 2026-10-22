package com.appvolume.xposed;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collection;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Adds crDroid's app-volume button to the SystemUI volume dialog.
 *
 * <p>The button is created like crDroid's {@code @id/app_volume_icon} from
 * {@code volume_dialog_bottom_section.xml}: 48dp, the crDroid
 * {@code ic_app_volume} vector, {@code ?attr/colorControlNormal} tint and no
 * background, so only the icon is drawn.
 *
 * <p>The volume dialog builds its touchable region from the views registered in
 * {@code VolumeDialogViewModel.touchableBoundsViews}
 * ({@code VolumeDialogViewBinder.bind$5} unions their window bounds into
 * {@code InternalInsetsInfo.touchableRegion}). A view that is not registered
 * there is invisible to the window manager, so the button has to be added to
 * that collection - exactly what crDroid's own binder does
 * ({@code VolumeDialogAppVolumeButtonViewBinder.bind$1}).
 */
public final class SystemUiHook implements IXposedHookLoadPackage {
    private static final String TAG = "AppVolumeHook: ";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String APP = "com.appvolume";
    private static final String SHOW_APP_VOLUME = "show_app_volume";
    private static final String BUTTON_TAG = "appvolume_dialog_button";
    private static final String DEBUG_SHOW = "com.appvolume.action.DEBUG_SHOW";
    private static final String DEBUG_ROM = "com.appvolume.action.DEBUG_ROM";

    private static final String VIEW_MODEL =
            "com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel";

    private static WeakReference<Dialog> sLastDialog = new WeakReference<>(null);
    private static boolean sDebugReceiverRegistered;

    /** Source drawable of the settings button's circular ripple. */
    private static final java.util.Map<View, Drawable> sButtonRipples =
            new java.util.WeakHashMap<>();

    /** The view model of the plugin that opens the volume panel. */
    private static volatile Object sPluginViewModel;

    /** The plugin that opens the volume panel. */
    private static volatile Object sVolumePlugin;

    /** {@code VolumeDialogViewModel} of the dialog that is currently open. */
    private static volatile Object sViewModel;

    /** Whether an app is currently playing audio (crDroid: active app volume). */
    private static volatile boolean sSessionsKnown;
    private static volatile boolean sHasActiveApp;

    /** Limits the diagnostics of the stream volume hook. */
    private static int sStreamVolumeLogs;

    /** Limits the diagnostics of the slider callback hook. */
    private static int sSliderValueLogs;

    /** The material icon overload that draws an icon without a tint. */
    private static Method sUntintedIconMethod;
    private static long sUnspecifiedIconColor;
    private static boolean sIconHookLogged;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !SYSTEMUI.equals(lpparam.packageName)) {
            return;
        }
        try {
            // The application class of the system ui changed its name and it
            // does not call the constructor of the framework, so the class of
            // the ROM is hooked and the framework class is the fallback.
            startApplication(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "application hook skipped: " + t);
        }

        try {
            hookIconTint(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "icon tint hook skipped: " + t);
        }

        try {
            Class<?> viewModelClass = XposedHelpers.findClass(
                    VIEW_MODEL, lpparam.classLoader);
            XposedBridge.hookAllMethods(viewModelClass, "addTouchableBounds",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sViewModel = param.thisObject;
                            View button = findButton();
                            if (button != null) {
                                registerTouchable(button);
                                requestInsets(button);
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked " + VIEW_MODEL + "#addTouchableBounds");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "view model hook skipped: " + t);
        }

        try {
            // The plugin of the volume panel holds the view model that opens
            // the panel. It is used by the debug helper to show the panel
            // without touching the volume keys.
            Class<?> componentClass = XposedHelpers.findClass(
                    "com.android.systemui.dagger.DaggerReferenceGlobalRootComponent$"
                            + "VolumeDialogPluginComponentImpl", lpparam.classLoader);
            XposedBridge.hookAllMethods(componentClass, "viewModel", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object viewModel = param.getResult();
                    if (viewModel != null) {
                        sPluginViewModel = viewModel;
                        XposedBridge.log(TAG + "view model of the plugin captured");
                    }
                }
            });
            XposedBridge.log(TAG + "hooked VolumeDialogPluginComponentImpl#viewModel");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "plugin hook skipped: " + t);
        }

        try {
            Class<?> pluginFactory = XposedHelpers.findClass(
                    "com.android.systemui.dagger.DaggerReferenceGlobalRootComponent$"
                            + "VolumeDialogPluginComponentFactory", lpparam.classLoader);
            XposedBridge.hookAllMethods(pluginFactory, "create", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    XposedBridge.log(TAG + "plugin component created: " + param.getResult());
                }
            });
            XposedBridge.log(TAG + "hooked VolumeDialogPluginComponentFactory#create");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "plugin factory hook skipped: " + t);
        }

        try {
            // The plugin that shows the volume panel is created together with
            // the component of the panel, it is used by the debug helper to
            // open the panel.
            Class<?> panelClass = XposedHelpers.findClass(
                    "com.android.systemui.volume.VolumeDialogComponent", lpparam.classLoader);
            XposedBridge.log(TAG + "panel component found: " + panelClass);
            XposedBridge.hookAllConstructors(panelClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object plugin = findPlugin(param.args);
                        if (plugin != null) {
                            sVolumePlugin = plugin;
                            XposedBridge.log(TAG
                                    + "plugin of the volume panel captured");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked VolumeDialogComponent");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "panel hook skipped: " + t);
        }

        try {
            // The component of the ROM is the only way to get the slider
            // components of the ROM, they are only reachable through the
            // dagger graph of the system ui. It is created when the volume
            // dialog is opened.
            Class<?> factoryClass = XposedHelpers.findClass(
                    "com.android.systemui.dagger.DaggerReferenceGlobalRootComponent$"
                            + "VolumeDialogComponentFactory", lpparam.classLoader);
            XposedBridge.hookAllMethods(factoryClass, "create", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object component = param.getResult();
                        Object scope = param.args != null && param.args.length > 0
                                ? param.args[0] : null;
                        if (component != null && scope != null) {
                            RomAppSlider.setComponent(component, scope);
                            XposedBridge.log(TAG + "component of the ROM captured");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked VolumeDialogComponentFactory#create");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "component hook skipped: " + t);
        }

        try {
            // The shrunk ROM calls the factory of the component directly, so
            // the constructor of the component is the place where the
            // component and the scope of the ROM show up.
            Class<?> componentClass = XposedHelpers.findClass(
                    "com.android.systemui.dagger.DaggerReferenceGlobalRootComponent$"
                            + "VolumeDialogComponentImpl", lpparam.classLoader);
            XposedBridge.hookAllConstructors(componentClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object scope = null;
                        if (param.args != null && param.args.length > 0) {
                            scope = param.args[param.args.length - 1];
                        }
                        if (scope != null) {
                            RomAppSlider.setComponent(param.thisObject, scope);
                            XposedBridge.log(TAG + "component of the ROM captured");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked VolumeDialogComponentImpl");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "component constructor hook skipped: " + t);
        }

        try {
            // The binder of the panel gets the scope of the ROM and the
            // dialog, with them the component of the ROM can be made.
            Class<?> binderClass = XposedHelpers.findClass(
                    "com.android.systemui.volume.dialog.ui.binder.VolumeDialogViewBinder",
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(binderClass, "bind", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2) {
                            return;
                        }
                        Object scope = param.args[0];
                        Object dialog = param.args[1];
                        if (scope != null && dialog != null) {
                            RomAppSlider.setDialog(dialog, scope);
                            XposedBridge.log(TAG + "dialog and scope of the ROM captured");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked VolumeDialogViewBinder#bind");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "view binder hook skipped: " + t);
        }

        try {
            // The shrunk ROM has no method in the view model of the slider any
            // more, the volume of the system is set through the interactor of
            // the slider. Only the sliders of the applications are taken, so
            // the sliders of the ROM keep working as they are.
            Class<?> interactorClass = XposedHelpers.findClass(
                    "com.android.systemui.volume.dialog.sliders.domain.interactor."
                            + "VolumeDialogSliderInteractor", lpparam.classLoader);
            XposedBridge.hookAllMethods(interactorClass, "setStreamVolume",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                RomAppSlider slider = findSlider(param.thisObject);
                                Object value = param.args != null && param.args.length > 0
                                        ? param.args[0] : null;
                                if (sStreamVolumeLogs < 200) {
                                    sStreamVolumeLogs++;
                                    XposedBridge.log(TAG + "setStreamVolume " + value
                                            + " match=" + (slider != null));
                                }
                                if (slider == null) {
                                    return;
                                }
                                if (value instanceof Number) {
                                    slider.onUserValue(((Number) value).intValue());
                                }
                                // The volume of the system must not be touched.
                                param.setResult(unit());
                            } catch (Throwable t) {
                                XposedBridge.log(t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked VolumeDialogSliderInteractor#setStreamVolume");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "slider interactor hook skipped: " + t);
        }

        try {
            // The compose slider reports every move through the onValueChange
            // lambda of the ROM. The path through userVolumeUpdates is a
            // coroutine whose scope belongs to the dialog, so it is not
            // reliable for the extra sliders. The callback is intercepted
            // directly instead: for a slider of an application it applies the
            // volume of the application and updates the slider state, the
            // stream volume of the system is never written. The sliders of
            // the ROM are left alone.
            Class<?> valueLambda = XposedHelpers.findClass(
                    "com.android.systemui.volume.dialog.sliders.ui."
                            + "VolumeDialogSliderViewBinderKt$$ExternalSyntheticLambda1",
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(valueLambda, "invoke", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object value = param.args != null && param.args.length > 0
                                ? param.args[0] : null;
                        Object viewModel = XposedHelpers.getObjectField(
                                param.thisObject, "f$2");
                        RomAppSlider slider = sliderForViewModel(viewModel);
                        if (sSliderValueLogs < 200) {
                            sSliderValueLogs++;
                            XposedBridge.log(TAG + "slider value " + value
                                    + " match=" + (slider != null));
                        }
                        if (slider == null) {
                            return;
                        }
                        if (value instanceof Number) {
                            slider.onUserValue(Math.round(
                                    ((Number) value).floatValue()));
                        }
                        resetDismissTimeout(viewModel);
                        // userVolumeUpdates would set the system stream volume.
                        param.setResult(unit());
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "slider value failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked VolumeDialogSlider onValueChange");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "slider value hook skipped: " + t);
        }

        try {
            Class<?> dialogClass = XposedHelpers.findClass(
                    "com.android.systemui.volume.dialog.VolumeDialog", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(dialogClass, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                install((Dialog) param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log(t);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(dialogClass, "onStart", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Dialog dialog = (Dialog) param.thisObject;
                        // The ROM sets the window to WRAP_CONTENT in onStart.
                        // Widen it here, before the window is shown, so that
                        // toggling the app rows never changes the window size.
                        AppVolumePanel.prepareWindow(dialog);
                        updateVisibility(dialog);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(dialogClass, "onStop", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        AppVolumePanel.onDialogStopped((Dialog) param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            });

            XposedBridge.log(TAG + "hooked VolumeDialog");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            // The visibility binder starts its own close animation when the
            // dialog becomes Dismissed. This is early enough to run our
            // reverse animation in parallel with the ROM panel.
            Class<?> visibilityClass = XposedHelpers.findClass(
                    "com.android.systemui.volume.dialog.ui.binder."
                            + "VolumeDialogViewBinder$animateVisibility$1",
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(visibilityClass, "invoke", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object state = param.args != null && param.args.length > 0
                                ? param.args[0] : null;
                        if (state == null
                                || !state.getClass().getName().endsWith("$Dismissed")) {
                            return;
                        }
                        Object dialog = Reflect.get(param.thisObject, "$dialog");
                        if (dialog instanceof Dialog) {
                            AppVolumePanel.onDialogClosing((Dialog) dialog);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "visibility close failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked volume dialog close animation");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "volume dialog close hook skipped: " + t);
        }

        try {
            // The ROM dismisses the window after its own close animation. If
            // our rows are still collapsing, hold the dismiss until they are
            // done; otherwise the window would disappear under them.
            XposedHelpers.findAndHookMethod(Dialog.class, "dismiss", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    final Dialog dialog = (Dialog) param.thisObject;
                    if (!AppVolumePanel.shouldHoldDismiss(dialog)) {
                        return;
                    }
                    if (!sHeldDismiss.add(dialog)) {
                        param.setResult(null);
                        return;
                    }
                    final Method method = (Method) param.method;
                    final Object[] args = param.args;
                    AppVolumePanel.onDialogClosing(dialog);
                    AppVolumePanel.afterClose(() -> {
                        sHeldDismiss.remove(dialog);
                        try {
                            XposedBridge.invokeOriginalMethod(method, dialog, args);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "delayed dismiss failed: " + t);
                        }
                    });
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + "hooked Dialog.dismiss for the close animation");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "dialog dismiss hook skipped: " + t);
        }
    }

    /** Refreshes the app-volume button after its rows were opened or closed. */
    static void onPanelVisibilityChanged() {
        Dialog dialog = sLastDialog.get();
        if (dialog != null) {
            updateVisibility(dialog);
        }
    }

    /**
     * Called by {@link EffectManager} whenever the companion app reports the
     * active players. crDroid shows the app-volume button only while an app
     * volume is active, so the button follows the playback state.
     */
    static void onSessionsChanged(boolean hasActiveApp) {
        sSessionsKnown = true;
        sHasActiveApp = hasActiveApp;
        AppVolumePanel.onSessionsChanged();
        Dialog dialog = sLastDialog.get();
        if (dialog != null) {
            updateVisibility(dialog);
        }
    }

    /** Called by {@link EffectManager} when a volume was changed. */
    static void onVolumeChanged(String pkg, int volume) {
        AppVolumePanel.onVolumeChanged(pkg, volume);
    }

    /** The view model of the volume dialog that is currently open. */
    static Object getViewModel() {
        return sViewModel;
    }

    /** All app sliders that were built from the slider of the volume panel. */
    private static final java.util.List<RomAppSlider> sSliders =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Dialogs whose dismiss is waiting for the rows to collapse. */
    private static final java.util.Set<Dialog> sHeldDismiss =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    static void registerSlider(RomAppSlider slider) {
        if (slider != null) {
            sSliders.add(slider);
        }
    }

    static void unregisterSlider(RomAppSlider slider) {
        sSliders.remove(slider);
    }

    /**
     * The sliders of the ROM write the value the user picked into the
     * interactor of the slider. The sliders of the applications have their own
     * interactor, so the value can be applied to the application instead of
     * the system volume.
     */
    static RomAppSlider sliderFor(Object interactor) {
        return findSlider(interactor);
    }

    private static RomAppSlider findSlider(Object interactor) {
        if (interactor == null) {
            return null;
        }
        for (RomAppSlider slider : sSliders) {
            if (slider.getInteractor() == interactor) {
                return slider;
            }
        }
        return null;
    }

    /** The application slider that uses the given view model of the ROM. */
    private static RomAppSlider sliderForViewModel(Object viewModel) {
        if (viewModel == null) {
            return null;
        }
        for (RomAppSlider slider : sSliders) {
            if (slider.getViewModel() == viewModel) {
                return slider;
            }
        }
        return null;
    }

    /** Keeps the volume dialog on the screen while the slider is dragged. */
    private static void resetDismissTimeout(Object viewModel) {
        try {
            Object interactor = Reflect.get(viewModel, "visibilityInteractor");
            if (interactor != null) {
                XposedHelpers.callMethod(interactor, "resetDismissTimeout");
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * The ROM draws the icon of a slider through the material icon
     * composable, which tints the drawable with the content color. The app
     * icons are wrapped in {@link AppIconDrawable}; for those, the tint is
     * skipped by calling the untinted overload of the same composable.
     */
    private static void hookIconTint(ClassLoader loader) throws Exception {
        Class<?> iconKt = XposedHelpers.findClass("androidx.compose.material3.IconKt", loader);
        Method producerMethod = null;
        for (Method method : iconKt.getDeclaredMethods()) {
            if (!"Icon".equals(method.getName())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 6
                    && "androidx.compose.ui.graphics.painter.Painter".equals(types[0].getName())
                    && types[1].getName().contains("ColorProducer")
                    && types[2] == String.class
                    && "androidx.compose.ui.Modifier".equals(types[3].getName())
                    && "androidx.compose.runtime.Composer".equals(types[4].getName())) {
                producerMethod = method;
                break;
            }
        }
        if (producerMethod == null) {
            XposedBridge.log(TAG + "tinted icon method not found");
            return;
        }

        for (Method method : iconKt.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 7
                    && "androidx.compose.ui.graphics.painter.Painter".equals(types[0].getName())
                    && types[1] == String.class
                    && "androidx.compose.ui.Modifier".equals(types[2].getName())
                    && types[3] == long.class
                    && "androidx.compose.runtime.Composer".equals(types[4].getName())) {
                sUntintedIconMethod = method;
                break;
            }
        }
        if (sUntintedIconMethod == null) {
            XposedBridge.log(TAG + "untinted icon method not found");
            return;
        }
        sUntintedIconMethod.setAccessible(true);

        java.lang.reflect.Field unspecified =
                XposedHelpers.findClass("androidx.compose.ui.graphics.Color", loader)
                        .getDeclaredField("Unspecified");
        unspecified.setAccessible(true);
        sUnspecifiedIconColor = unspecified.getLong(null);

        final Method untinted = sUntintedIconMethod;
        XposedBridge.hookMethod(producerMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object painter = param.args[0];
                    Object drawable = Reflect.get(painter, "drawable");
                    if (!(drawable instanceof AppIconDrawable)) {
                        return;
                    }
                    untinted.invoke(null, painter, param.args[2], param.args[3],
                            sUnspecifiedIconColor, param.args[4], 0, 0);
                    param.setResult(null);
                    if (!sIconHookLogged) {
                        sIconHookLogged = true;
                        XposedBridge.log(TAG + "app icon drawn without tint");
                    }
                } catch (Throwable t) {
                    if (!sIconHookLogged) {
                        sIconHookLogged = true;
                        XposedBridge.log(TAG + "app icon tint bypass failed: " + t);
                    }
                }
            }
        });
        XposedBridge.log(TAG + "hooked material3 icon tint for app icons");
    }

    /**
     * Starts the helper as soon as the application of the system ui is up.
     * The class of the ROM is {@code SystemUIApplicationImpl}, it does not
     * call the constructor of the framework, so the framework class is only
     * a fallback.
     */
    private static void startApplication(ClassLoader loader) {
        for (String name : new String[]{
                "com.android.systemui.application.impl.SystemUIApplicationImpl",
                "android.app.Application"}) {
            try {
                Class<?> applicationClass = XposedHelpers.findClass(name, loader);
                XposedHelpers.findAndHookMethod(applicationClass, "onCreate",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Context context = (Context) param.thisObject;
                                    EffectManager.ensureStarted(context);
                                    registerDebugReceiver(context);
                                } catch (Throwable t) {
                                    XposedBridge.log(t);
                                }
                            }
                        });
                XposedBridge.log(TAG + "hooked " + name + "#onCreate");
            } catch (Throwable t) {
                XposedBridge.log(TAG + "application hook " + name + " skipped: " + t);
            }
        }
    }

    /** {@code kotlin.Unit}, the result of a suspend function without a value. */
    private static Object unit() {
        try {
            return Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The plugin of the volume panel, it is the argument of that type. */
    private static Object findPlugin(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            for (Class<?> type : arg.getClass().getInterfaces()) {
                if (type.getName().equals("com.android.systemui.plugins.VolumeDialog")) {
                    return arg;
                }
            }
        }
        return null;
    }

    /**
     * Starts the plugin of the volume panel. The plugin opens the panel, the
     * volume of the system is not touched by it.
     */
    private static void initVolumePlugin(Object plugin) {
        try {
            Class<?> callbackType = XposedHelpers.findClass(
                    "com.android.systemui.plugins.VolumeDialog$Callback",
                    plugin.getClass().getClassLoader());
            Object callback = java.lang.reflect.Proxy.newProxyInstance(
                    plugin.getClass().getClassLoader(), new Class<?>[]{callbackType},
                    (proxy, method, args) -> null);
            Method init = plugin.getClass().getMethod("init", int.class, callbackType);
            init.setAccessible(true);
            init.invoke(plugin, android.os.Process.myUid() / 100000, callback);
            XposedBridge.log(TAG + "plugin of the volume panel started");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "plugin could not be started: " + t);
            XposedBridge.log(t);
        }
    }

    /**
     * Shows the volume panel the same way the ROM does: the dialog is made by
     * the factory of the plugin and shown. The volume of the system is not
     * touched by it.
     */
    private static void showVolumeDialog(Object pluginViewModel) {
        try {
            Object factory = Reflect.get(pluginViewModel, "volumeDialogFactory");
            if (factory == null) {
                XposedBridge.log(TAG + "no factory for the volume dialog");
                return;
            }
            Method create = null;
            for (Method method : factory.getClass().getMethods()) {
                if (method.getName().equals("create") && method.getParameterCount() == 1) {
                    create = method;
                    break;
                }
            }
            if (create == null) {
                XposedBridge.log(TAG + "no create method for the volume dialog");
                return;
            }
            create.setAccessible(true);
            // The flag of the factory tells the ROM if the panel stands
            // vertical, the sliders of the ROM are only in that layout.
            Object dialog = create.invoke(factory, Boolean.TRUE);
            if (dialog instanceof Dialog) {
                ((Dialog) dialog).show();
                XposedBridge.log(TAG + "volume dialog shown");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "the volume dialog could not be shown: " + t);
            XposedBridge.log(t);
        }
    }

    /**
     * Opens the volume panel through the plugin of the panel: the plugin
     * holds the factory of the component of the panel, the component holds
     * the view model and the view model holds the factory of the dialog.
     * The volume of the system is not touched by it.
     */
    private static void openVolumePanel(Object plugin) {
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Object factory = Reflect.get(plugin, "volumeDialogPluginComponentFactory");
            if (factory == null) {
                XposedBridge.log(TAG + "no factory for the plugin component");
                return;
            }
            Object scope = RomAppSlider.newScope(loader);
            if (scope == null) {
                XposedBridge.log(TAG + "no scope for the plugin component");
                return;
            }
            Object component = RomAppSlider.callMethod(factory, "create", scope);
            if (component == null) {
                XposedBridge.log(TAG + "no plugin component");
                return;
            }
            Object viewModel = RomAppSlider.callMethod(component, "viewModel");
            if (viewModel == null) {
                XposedBridge.log(TAG + "no view model of the plugin");
                return;
            }
            sPluginViewModel = viewModel;
            showVolumeDialog(viewModel);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "the volume panel could not be opened: " + t);
            XposedBridge.log(t);
        }
    }

    private static void install(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) {
            return;
        }
        View decor = dialog.getWindow().getDecorView();
        EffectManager.ensureStarted(decor.getContext());
        sLastDialog = new WeakReference<>(dialog);
        registerDebugReceiver(decor.getContext());

        Context context = decor.getContext();
        ViewGroup container = findBottomSection(decor);
        if (container == null) {
            XposedBridge.log(TAG + "bottom section not found");
            return;
        }
        if (container.findViewWithTag(BUTTON_TAG) != null) {
            return;
        }

        ImageButton settings = findSettingsButton(container);
        ImageButton button = createButton(context, settings);
        if (button == null) {
            return;
        }

        int index = settings != null ? container.indexOfChild(settings) : container.getChildCount();
        container.addView(button, index, layoutParams(context, settings));

        button.setOnClickListener(v -> {
            XposedBridge.log(TAG + "app-volume button clicked");
            try {
                AppVolumePanel.toggle(dialog);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        });

        registerTouchable(button);
        update(button, context);

        XposedBridge.log(TAG + "app-volume button added");
        logLayout(container, "  ");
        button.post(() -> {
            registerTouchable(button);
            requestInsets(button);
            XposedBridge.log(TAG + "button rect " + rectOf(button)
                    + " parent rect " + rectOf(container)
                    + " clickable=" + button.isClickable());
        });
    }

    /**
     * Builds the button with the size and icon tint of crDroid's app-volume
     * button. The settings button's circular ripple is used as the foreground;
     * a matching round background is shown only while the rows are open.
     */
    private static ImageButton createButton(Context context, ImageButton settings) {
        ImageButton button = new ImageButton(context);
        button.setTag(BUTTON_TAG);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setSoundEffectsEnabled(false);

        int padding = dp(context, 4);
        if (settings != null) {
            button.setScaleType(settings.getScaleType());
            button.setPadding(settings.getPaddingLeft(), settings.getPaddingTop(),
                    settings.getPaddingRight(), settings.getPaddingBottom());
        } else {
            button.setPadding(padding, padding, padding, padding);
        }

        Drawable icon = loadAppIcon(context);
        if (icon != null) {
            button.setImageDrawable(icon);
        }

        ColorStateList tint = null;
        if (settings != null) {
            tint = settings.getImageTintList();
        }
        if (tint == null) {
            tint = resolveColorControlNormal(context);
        }
        if (tint != null) {
            button.setImageTintList(tint);
        }

        // The settings button uses ripple_drawable_20dp: a centered circular
        // ripple with a 20dp radius. Keep its source and clone it for every
        // press, so a reused drawable can never lose its ripple.
        Drawable ripple = settings != null ? settings.getBackground() : null;
        if (ripple == null) {
            ripple = loadSystemUiDrawable(context, "ripple_drawable_20dp");
        }
        if (ripple != null) {
            sButtonRipples.put(button, ripple);
        }
        button.setBackground(null);
        button.setForeground(null);
        button.setContentDescription(loadAppLabel(context));
        return button;
    }

    private static Drawable newRipple(View button, Context context) {
        Drawable source = sButtonRipples.get(button);
        if (source == null) {
            return null;
        }
        Drawable.ConstantState state = source.getConstantState();
        if (state == null) {
            return null;
        }
        return state.newDrawable(context.getResources(), context.getTheme()).mutate();
    }

    /**
     * The old crDroid background is a 40dp rounded square (48dp button with a
     * 4dp inset). Make the same 40dp area round, matching the 20dp radius of
     * the settings button's ripple.
     */
    private static Drawable createRoundBackground(Context context) {
        int inset = dp(context, 4);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        int color = resolveSystemUiColor(context, "materialColorSurfaceContainerHighest");
        if (color == 0) {
            color = 0x33FFFFFF;
        }
        circle.setColor(color);
        circle.setSize(dp(context, 40), dp(context, 40));
        return new InsetDrawable(circle, inset, inset, inset, inset);
    }

    /**
     * crDroid places the button with {@code layout_gravity=center} and the size
     * of {@code volume_dialog_button_size}.
     */
    private static ViewGroup.LayoutParams layoutParams(Context context, ImageButton settings) {
        int size = dp(context, 48);
        int dimen = systemUiIdentifier(context, "volume_dialog_button_size", "dimen");
        if (dimen != 0) {
            try {
                size = context.getResources().getDimensionPixelSize(dimen);
            } catch (Throwable ignored) {
            }
        }
        if (settings != null
                && settings.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams source =
                    (LinearLayout.LayoutParams) settings.getLayoutParams();
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(source.width, source.height);
            params.gravity = source.gravity;
            params.weight = source.weight;
            params.setMargins(source.leftMargin, source.topMargin,
                    source.rightMargin, source.bottomMargin);
            return params;
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.gravity = Gravity.CENTER;
        return params;
    }

    /**
     * Adds the button to the dialog's touchable bounds so that the window
     * manager accepts touches on it. Without this the view is laid out and
     * drawn, but the panel is dismissed before the click is delivered.
     */
    private static void registerTouchable(View button) {
        Object viewModel = sViewModel;
        if (viewModel == null || button == null) {
            return;
        }
        try {
            Object collection = XposedHelpers.getObjectField(
                    viewModel, "touchableBoundsViews");
            if (collection instanceof Collection) {
                @SuppressWarnings("unchecked")
                Collection<Object> views = (Collection<Object>) collection;
                if (!views.contains(button)) {
                    views.add(button);
                    XposedBridge.log(TAG + "button registered as touchable bound");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "touchable registration failed: " + t);
        }
    }

    /** Forces the dialog to recompute its touchable region. */
    private static void requestInsets(View view) {
        try {
            View root = view.getRootView();
            if (root != null) {
                root.requestApplyInsets();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "requestApplyInsets failed: " + t);
        }
    }

    private static View findButton() {
        Dialog dialog = sLastDialog.get();
        if (dialog == null || dialog.getWindow() == null) {
            return null;
        }
        return dialog.getWindow().getDecorView().findViewWithTag(BUTTON_TAG);
    }

    private static ViewGroup findBottomSection(View decor) {
        int id = systemUiIdentifier(decor.getContext(),
                "volume_dialog_bottom_section_container", "id");
        if (id != 0) {
            View view = decor.findViewById(id);
            if (view instanceof ViewGroup) {
                return (ViewGroup) view;
            }
        }
        ImageButton settings = findSettingsButton(decor);
        if (settings != null && settings.getParent() instanceof ViewGroup) {
            return (ViewGroup) settings.getParent();
        }
        return null;
    }

    /**
     * Debug helper: shows the volume dialog on demand so that the injected
     * button can be inspected without touching the volume keys.
     */
    private static void registerDebugReceiver(Context context) {
        if (sDebugReceiverRegistered) {
            return;
        }
        sDebugReceiverRegistered = true;
        try {
            android.content.BroadcastReceiver receiver =
                    new android.content.BroadcastReceiver() {
                        @Override
                        public void onReceive(Context ctx, Intent intent) {
                            String action = intent.getAction();
                            if (DEBUG_ROM.equals(action)) {
                                // Debug helper: build one slider of the ROM to
                                // check that the chain of the ROM works.
                                try {
                                    RomAppSlider slider = RomAppSlider.create(
                                            ctx, "com.android.chrome", 60,
                                            (pkg, percent) -> XposedBridge.log(
                                                    TAG + "debug slider " + pkg
                                                            + " -> " + percent));
                                    XposedBridge.log(TAG + "debug slider: " + slider);
                                } catch (Throwable t) {
                                    XposedBridge.log(t);
                                }
                                return;
                            }
                            Dialog dialog = sLastDialog.get();
                            Object plugin = sPluginViewModel;
                            if (plugin != null) {
                                XposedBridge.log(TAG + "debug show: opening the panel");
                                showVolumeDialog(plugin);
                                return;
                            }
                            Object volumePlugin = sVolumePlugin;
                            if (volumePlugin != null) {
                                XposedBridge.log(TAG + "debug show: opening the panel");
                                openVolumePanel(volumePlugin);
                                return;
                            }
                            if (dialog == null) {
                                XposedBridge.log(TAG + "debug show: no dialog");
                                return;
                            }
                            XposedBridge.log(TAG + "debug show: showing dialog");
                            dialog.getWindow().getDecorView().post(dialog::show);
                        }
                    };
            android.content.IntentFilter filter =
                    new android.content.IntentFilter(DEBUG_SHOW);
            filter.addAction(DEBUG_ROM);
            if (android.os.Build.VERSION.SDK_INT
                    >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.getApplicationContext().registerReceiver(
                        receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.getApplicationContext().registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void logLayout(ViewGroup group, String indent) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            XposedBridge.log(TAG + indent + i + ": " + describe(child));
        }
    }

    private static String describe(View view) {
        String id = "no-id";
        if (view.getId() != View.NO_ID) {
            try {
                id = view.getResources().getResourceEntryName(view.getId());
            } catch (Throwable ignored) {
            }
        }
        CharSequence description = view.getContentDescription();
        return view.getClass().getSimpleName() + " id=" + id
                + " desc=" + description
                + " vis=" + view.getVisibility()
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " rect=" + rectOf(view);
    }

    private static String rectOf(View view) {
        android.graphics.Rect rect = new android.graphics.Rect();
        view.getGlobalVisibleRect(rect);
        return rect.toShortString();
    }

    private static void updateVisibility(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) {
            return;
        }
        View decor = dialog.getWindow().getDecorView();
        View button = decor.findViewWithTag(BUTTON_TAG);
        if (button != null) {
            update(button, decor.getContext());
        }
    }

    private static void update(View button, Context context) {
        button.setVisibility(shouldShow(context) ? View.VISIBLE : View.GONE);

        // The foreground ripple stays attached for the entire lifetime of the
        // button. Replacing or removing it would cancel the touch animation;
        // keeping it also makes the ripple play on every press.
        if (button.getForeground() == null) {
            Drawable idle = newRipple(button, context);
            if (idle != null) {
                button.setForeground(idle);
            }
        }

        // The round background is an enabled-state indicator, not the ripple.
        // Remove it as soon as the collapse starts, without waiting for the
        // rows to finish animating.
        boolean enabled = AppVolumePanel.isOpen() && !AppVolumePanel.isClosing();
        button.setBackground(enabled ? createRoundBackground(context) : null);
    }

    /**
     * crDroid shows the app-volume button while the feature is enabled and at
     * least one app volume is active, i.e. while something is playing.
     */
    private static boolean shouldShow(Context context) {
        try {
            int enabled = Settings.System.getInt(
                    context.getContentResolver(), SHOW_APP_VOLUME, 0);
            if (enabled != 1) {
                return false;
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
        return AppVolumePanel.isOpen() || !sSessionsKnown || sHasActiveApp;
    }

    private static ImageButton findSettingsButton(View view) {
        if (view instanceof ImageButton) {
            int id = view.getId();
            if (id != View.NO_ID) {
                try {
                    String name = view.getResources().getResourceEntryName(id);
                    if ("volume_dialog_settings".equals(name)) {
                        return (ImageButton) view;
                    }
                } catch (Throwable ignored) {
                }
            }
            CharSequence description = view.getContentDescription();
            if (description != null) {
                String text = description.toString().toLowerCase(java.util.Locale.ROOT);
                if (text.equals("settings") || text.contains("настрой")
                        || text.contains("sound settings")) {
                    return (ImageButton) view;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ImageButton found = findSettingsButton(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int systemUiIdentifier(Context context, String name, String type) {
        try {
            return context.getResources().getIdentifier(name, type, SYSTEMUI);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Drawable loadSystemUiDrawable(Context context, String name) {
        int id = systemUiIdentifier(context, name, "drawable");
        if (id == 0) {
            return null;
        }
        try {
            return context.getResources().getDrawable(id, context.getTheme());
        } catch (Throwable t) {
            return null;
        }
    }

    private static int resolveSystemUiColor(Context context, String name) {
        int id = systemUiIdentifier(context, name, "color");
        if (id == 0) {
            return 0;
        }
        try {
            return context.getColor(id);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static ColorStateList resolveColorControlNormal(Context context) {
        try {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(
                    android.R.attr.colorControlNormal, value, true)) {
                if (value.resourceId != 0) {
                    return ColorStateList.valueOf(context.getResources()
                            .getColor(value.resourceId, context.getTheme()));
                }
                return ColorStateList.valueOf(value.data);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Drawable loadAppIcon(Context context) {
        try {
            Resources resources = context.getPackageManager()
                    .getResourcesForApplication(APP);
            int id = resources.getIdentifier("ic_app_volume", "drawable", APP);
            if (id != 0) {
                return resources.getDrawable(id, null);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        return null;
    }

    private static CharSequence loadAppLabel(Context context) {
        try {
            Resources resources = context.getPackageManager()
                    .getResourcesForApplication(APP);
            int id = resources.getIdentifier("app_volume", "string", APP);
            if (id != 0) {
                return resources.getString(id);
            }
        } catch (Throwable ignored) {
        }
        return "App volume";
    }

    private static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
