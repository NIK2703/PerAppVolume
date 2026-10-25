package com.appvolume.xposed;

import android.app.Dialog;
import android.content.Context;
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
import java.lang.reflect.Field;
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

    private static final String VIEW_MODEL =
            "com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel";

    private static WeakReference<Dialog> sLastDialog = new WeakReference<>(null);

    /**
     * AOSP 16.0 uses {@code VolumeDialogImpl} with classic views, while
     * AOSP 16 QPR1 and newer use the compose dialog above. The classic
     * dialog keeps its instance for the whole lifetime of the process, so
     * only weak references are stored here.
     */
    private static WeakReference<Dialog> sClassicDialog = new WeakReference<>(null);
    private static WeakReference<Object> sClassicImpl = new WeakReference<>(null);
    private static WeakReference<View> sClassicButton = new WeakReference<>(null);

    /** Source drawable of the settings button's circular ripple. */
    private static final java.util.Map<View, Drawable> sButtonRipples =
            new java.util.WeakHashMap<>();

    /** {@code VolumeDialogViewModel} of the dialog that is currently open. */
    private static volatile Object sViewModel;

    /** Whether an app is currently playing audio (crDroid: active app volume). */
    private static volatile boolean sSessionsKnown;
    private static volatile boolean sHasActiveApp;

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

        // Android 16.0 still has the classic volume dialog. QPR1 and newer
        // have the compose dialog; on those builds the class is absent or
        // unused, so this hook is simply skipped.
        try {
            hookClassicVolumeDialog(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "classic volume dialog hook skipped: " + t);
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
            // Android 16 QPR1 has a public setStreamVolume on the slider view
            // model. The release build of Android 17 inlines it, so the hook
            // only exists when the method is really there.
            Class<?> sliderViewModelClass = XposedHelpers.findClass(
                    "com.android.systemui.volume.dialog.sliders.ui.viewmodel."
                            + "VolumeDialogSliderViewModel", lpparam.classLoader);
            XposedBridge.hookAllMethods(sliderViewModelClass, "setStreamVolume",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                RomAppSlider slider = sliderForViewModel(param.thisObject);
                                if (slider == null) {
                                    return;
                                }
                                Object value = param.args != null && param.args.length > 0
                                        ? param.args[0] : null;
                                if (value instanceof Number) {
                                    slider.onUserValue(Math.round(
                                            ((Number) value).floatValue()));
                                }
                                resetDismissTimeout(param.thisObject);
                                param.setResult(null);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "slider view model value failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "hooked VolumeDialogSliderViewModel#setStreamVolume");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "slider view model hook skipped: " + t);
        }

        // The compose slider reports every move through the onValueChange
        // lambda of the ROM. The lambda is synthetic and its class number is
        // not stable across AOSP versions, so all lambdas of the slider binder
        // are inspected. The one that captures both the overscroll view model
        // and the slider view model is the value callback.
        hookSliderValueLambdas(lpparam.classLoader);

        try {
            Class<?> dialogClass = XposedHelpers.findClass(
                    "com.android.systemui.volume.dialog.VolumeDialog", lpparam.classLoader);

            // Each hook is optional. AOSP 16 QPR1 does not override
            // onStart()/onStop() in VolumeDialog, while Android 17 does; the
            // base AOSP 16 dialog does not exist at all.
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
            XposedBridge.log(TAG + "hooked VolumeDialog#onCreate");

            try {
                XposedHelpers.findAndHookMethod(dialogClass, "onStart", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Dialog dialog = (Dialog) param.thisObject;
                            // The ROM sets the window to WRAP_CONTENT in
                            // onStart. Widen it here, before the window is
                            // shown, so that toggling the app rows never
                            // changes the window size.
                            AppVolumePanel.prepareWindow(dialog);
                            updateVisibility(dialog);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
                XposedBridge.log(TAG + "hooked VolumeDialog#onStart");
            } catch (Throwable t) {
                // Not declared on AOSP 16 QPR1. install() widens the window
                // early enough, so this is not an error.
                XposedBridge.log(TAG + "VolumeDialog#onStart not present: " + t);
            }

            try {
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
                XposedBridge.log(TAG + "hooked VolumeDialog#onStop");
            } catch (Throwable t) {
                XposedBridge.log(TAG + "VolumeDialog#onStop not present: " + t);
            }
        } catch (Throwable t) {
            // Expected on Android 16.0, where the compose volume dialog is
            // not part of SystemUI; the classic hook below handles it.
            XposedBridge.log(TAG + "compose volume dialog not present: " + t);
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
                    if (ClassicAppVolumePanel.isPanel(dialog)) {
                        return;
                    }
                    if (sClassicDialog.get() == dialog) {
                        if (ClassicAppVolumePanel.shouldHoldDismiss(dialog)) {
                            if (!sHeldDismiss.add(dialog)) {
                                param.setResult(null);
                                return;
                            }
                            final Method method = (Method) param.method;
                            final Object[] args = param.args;
                            ClassicAppVolumePanel.afterClose(() -> {
                                sHeldDismiss.remove(dialog);
                                try {
                                    XposedBridge.invokeOriginalMethod(method, dialog, args);
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + "delayed dismiss failed: " + t);
                                }
                            });
                            param.setResult(null);
                            return;
                        }
                        ClassicAppVolumePanel.onVolumeDialogDismissed(dialog);
                    }
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

    /**
     * The compose slider's value lambda is synthetic. R8 numbers these
     * classes differently in different releases, and AOSP 16 QPR1 has a
     * slightly different lambda body than Android 17. Find the lambda by the
     * view models it captures instead of by its class number.
     */
    private static void hookSliderValueLambdas(ClassLoader loader) {
        int hooked = 0;
        for (int i = 0; i < 32; i++) {
            final Class<?> lambdaClass = findClassIfExists(
                    "com.android.systemui.volume.dialog.sliders.ui."
                            + "VolumeDialogSliderViewBinderKt$$ExternalSyntheticLambda" + i,
                    loader);
            if (lambdaClass == null) {
                continue;
            }

            Field viewModelField = null;
            boolean hasOverscroll = false;
            boolean hasStateModel = false;
            boolean hasModifier = false;
            for (Field field : lambdaClass.getDeclaredFields()) {
                String type = field.getType().getName();
                if (type.endsWith("VolumeDialogSliderViewModel")) {
                    viewModelField = field;
                } else if (type.endsWith("VolumeDialogOverscrollViewModel")) {
                    hasOverscroll = true;
                } else if (type.endsWith("VolumeDialogSliderStateModel")) {
                    hasStateModel = true;
                } else if (type.endsWith("androidx.compose.ui.Modifier")) {
                    hasModifier = true;
                }
            }
            // The onValueChanged lambda captures the view model, the
            // overscroll model and the slider state. The composable lambda of
            // the same binder also captures the overscroll and view model,
            // but has a Modifier and no state model; suppressing that one
            // would break the compose content completely.
            if (viewModelField == null || !hasOverscroll
                    || !hasStateModel || hasModifier) {
                continue;
            }

            final Field field = viewModelField;
            field.setAccessible(true);
            XposedBridge.hookAllMethods(lambdaClass, "invoke", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object value = param.args != null && param.args.length > 0
                                ? param.args[0] : null;
                        Object viewModel = field.get(param.thisObject);
                        RomAppSlider slider = sliderForViewModel(viewModel);
                        if (slider == null) {
                            return;
                        }
                        if (value instanceof Number) {
                            slider.onUserValue(Math.round(
                                    ((Number) value).floatValue()));
                        }
                        resetDismissTimeout(viewModel);
                        // The lambda would update userVolumeUpdates, which
                        // sets the system stream volume.
                        param.setResult(unit());
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "slider value failed: " + t);
                    }
                }
            });
            hooked++;
            XposedBridge.log(TAG + "hooked slider value lambda "
                    + lambdaClass.getSimpleName());
        }
        XposedBridge.log(TAG + "hooked " + hooked + " compose slider value lambdas");
    }

    /**
     * Android 16.0 still uses {@code VolumeDialogImpl}. It is a classic view
     * hierarchy without the compose slider components, so the compose panel
     * cannot be reused. A transparent overlay with native replicas of the
     * Material 3 Expressive sliders is opened from an extra button in the
     * bottom settings row.
     */
    private static void hookClassicVolumeDialog(ClassLoader loader) {
        Class<?> implClass = findClassIfExists(
                "com.android.systemui.volume.VolumeDialogImpl", loader);
        if (implClass == null) {
            return;
        }
        XposedBridge.hookAllMethods(implClass, "initDialog", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    sClassicImpl = new WeakReference<>(param.thisObject);
                    Object dialog = XposedHelpers.getObjectField(
                            param.thisObject, "mDialog");
                    if (dialog instanceof Dialog) {
                        installClassic((Dialog) dialog);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "classic dialog install failed: " + t);
                }
            }
        });

        try {
            // The app sliders live in a transparent window on top of the
            // classic volume dialog. Without this hook the ROM's
            // CustomDialog.onTouchEvent() would treat a slider drag as an
            // outside touch and dismiss the whole volume panel.
            int touchOutsideReason = 1; // Events.DISMISS_REASON_TOUCH_OUTSIDE
            Class<?> eventsClass = findClassIfExists(
                    "com.android.systemui.volume.Events", loader);
            if (eventsClass != null) {
                Field field = eventsClass.getField("DISMISS_REASON_TOUCH_OUTSIDE");
                touchOutsideReason = field.getInt(null);
            }
            final int reason = touchOutsideReason;
            XposedBridge.hookAllMethods(implClass, "dismissH", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ClassicAppVolumePanel.isOpen()
                            || param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof Number)) {
                        return;
                    }
                    if (((Number) param.args[0]).intValue() == reason) {
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked classic outside-touch dismissal");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "classic outside-touch hook skipped: " + t);
        }
        XposedBridge.log(TAG + "hooked VolumeDialogImpl#initDialog");
    }

    private static void installClassic(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) {
            return;
        }
        View decor = dialog.getWindow().getDecorView();
        Context context = decor.getContext();
        EffectManager.ensureStarted(context);

        sClassicDialog = new WeakReference<>(dialog);
        ClassicAppVolumePanel.onVolumeDialogChanged(dialog);

        int containerId = systemUiIdentifier(context, "settings_container", "id");
        View containerView = containerId != 0 ? decor.findViewById(containerId) : null;
        if (!(containerView instanceof ViewGroup)) {
            XposedBridge.log(TAG + "classic settings container not found");
            return;
        }
        ViewGroup container = (ViewGroup) containerView;
        if (container.findViewWithTag(BUTTON_TAG) != null) {
            updateClassicVisibility();
            return;
        }

        ImageButton settings = findClassicSettings(decor);
        if (settings == null) {
            XposedBridge.log(TAG + "classic settings button not found");
            return;
        }
        ImageButton button = createButton(context, settings);
        if (button == null) {
            return;
        }

        ViewGroup parent = (ViewGroup) settings.getParent();
        if (parent == null) {
            return;
        }
        int size = dp(context, 42);
        int dimension = systemUiIdentifier(context, "volume_dialog_tap_target_size", "dimen");
        if (dimension != 0) {
            try {
                size = context.getResources().getDimensionPixelSize(dimension);
            } catch (Throwable ignored) {
            }
        } else if (settings.getLayoutParams() != null
                && settings.getLayoutParams().width > 0) {
            size = settings.getLayoutParams().width;
        }

        parent.removeView(settings);
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(button, new LinearLayout.LayoutParams(size, size));
        buttons.addView(settings, new LinearLayout.LayoutParams(size, size));
        container.addView(buttons, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        final Dialog dialogRef = dialog;
        button.setOnClickListener(v -> {
            XposedBridge.log(TAG + "classic app-volume button clicked");
            try {
                ClassicAppVolumePanel.toggle(context, dialogRef);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        });

        sClassicButton = new WeakReference<>(button);
        updateClassicVisibility();
        try {
            decor.requestApplyInsets();
        } catch (Throwable ignored) {
        }
        XposedBridge.log(TAG + "classic app-volume button added");
    }

    private static ImageButton findClassicSettings(View decor) {
        int id = systemUiIdentifier(decor.getContext(), "settings", "id");
        if (id != 0) {
            View view = decor.findViewById(id);
            if (view instanceof ImageButton) {
                return (ImageButton) view;
            }
        }
        return findSettingsButton(decor);
    }

    private static void updateClassicVisibility() {
        View button = sClassicButton.get();
        Dialog dialog = sClassicDialog.get();
        if (button == null || dialog == null || dialog.getWindow() == null) {
            return;
        }
        update(button, button.getContext());
    }

    /** Refreshes the app-volume button after its rows were opened or closed. */
    static void onPanelVisibilityChanged() {
        Dialog dialog = sLastDialog.get();
        if (dialog != null) {
            updateVisibility(dialog);
        }
        updateClassicVisibility();
    }

    /** The classic panel was opened or closed. */
    static void onClassicPanelVisibilityChanged() {
        updateClassicVisibility();
    }

    /**
     * Keeps the classic volume dialog alive while the user drags an app
     * slider, matching the resetDismissTimeout() call of the compose slider.
     */
    static void resetClassicTimeout() {
        Object impl = sClassicImpl.get();
        if (impl == null || !ClassicAppVolumePanel.isOpen()) {
            return;
        }
        try {
            XposedHelpers.callMethod(impl, "rescheduleTimeoutH");
        } catch (Throwable ignored) {
        }
    }

    /**
     * Called by {@link EffectManager} whenever the in-process scanner finds a
     * different set of players. crDroid shows the app-volume button only while
     * an app volume is active, so the button follows the playback state.
     */
    static void onSessionsChanged(boolean hasActiveApp) {
        sSessionsKnown = true;
        sHasActiveApp = hasActiveApp;
        AppVolumePanel.onSessionsChanged();
        ClassicAppVolumePanel.onSessionsChanged();
        Dialog dialog = sLastDialog.get();
        if (dialog != null) {
            updateVisibility(dialog);
        }
        updateClassicVisibility();
    }

    /**
     * Called by {@link EffectManager} as soon as the audio service reports
     * that an application started or stopped playing. Unlike the periodic
     * session dump, this arrives immediately, so the button is shown on the
     * first sound after a reboot and hidden as soon as every app is silent.
     */
    static void onPlaybackStateChanged(boolean hasActiveApp) {
        sSessionsKnown = true;
        sHasActiveApp = hasActiveApp;
        AppVolumePanel.onSessionsChanged();
        ClassicAppVolumePanel.onSessionsChanged();
        Dialog dialog = sLastDialog.get();
        if (dialog != null) {
            updateVisibility(dialog);
        }
        updateClassicVisibility();
    }

    /** The scanned set of players changed; refresh an open app-volume panel. */
    static void onActiveAppsChanged() {
        AppVolumePanel.onSessionsChanged();
        ClassicAppVolumePanel.onSessionsChanged();
    }

    /** Called by {@link EffectManager} when a volume was changed. */
    static void onVolumeChanged(String pkg, int volume) {
        AppVolumePanel.onVolumeChanged(pkg, volume);
        ClassicAppVolumePanel.onVolumeChanged(pkg, volume);
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

    private static void install(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) {
            return;
        }
        View decor = dialog.getWindow().getDecorView();
        EffectManager.ensureStarted(decor.getContext());
        sLastDialog = new WeakReference<>(dialog);

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

        // Android 16 QPR1 does not override onStart(), so the window has to
        // be widened here, before the dialog is shown. On Android 17 the same
        // call is repeated in onStart(), which is harmless.
        AppVolumePanel.prepareWindow(dialog);

        XposedBridge.log(TAG + "app-volume button added");
        button.post(() -> {
            registerTouchable(button);
            requestInsets(button);
        });
    }

    /**
     * Builds the button with the size and icon tint of crDroid's app-volume
     * button. The settings button's circular ripple is used as the foreground;
     * a matching round background is shown only while the rows are open.
     */
    static ImageButton createButton(Context context, ImageButton settings) {
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
        boolean enabled = (AppVolumePanel.isOpen() && !AppVolumePanel.isClosing())
                || ClassicAppVolumePanel.isOpen();
        button.setBackground(enabled ? createRoundBackground(context) : null);
    }

    /**
     * crDroid shows the app-volume button while the feature is enabled and at
     * least one app volume is active, i.e. while something is playing.
     */
    private static boolean shouldShow(Context context) {
        try {
            int enabled = Settings.System.getInt(
                    context.getContentResolver(), SHOW_APP_VOLUME, 1);
            if (enabled != 1) {
                return false;
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
            return false;
        }
        return AppVolumePanel.isOpen() || ClassicAppVolumePanel.isOpen()
                || !sSessionsKnown || sHasActiveApp;
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

    /** Like {@code XposedHelpers.findClassIfExists}, which the stubs lack. */
    private static Class<?> findClassIfExists(String name, ClassLoader loader) {
        try {
            return XposedHelpers.findClass(name, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static int systemUiIdentifier(Context context, String name, String type) {
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

    static int resolveSystemUiColor(Context context, String name) {
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

    static CharSequence loadAppLabel(Context context) {
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

    static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
