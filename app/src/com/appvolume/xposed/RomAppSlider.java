package com.appvolume.xposed;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * One volume slider of an application, built from the volume slider of the
 * system volume panel.
 *
 * <p>The panel draws its slider with the compose function
 * {@code com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSlider},
 * which reads its state from a {@code VolumeDialogSliderViewModel}. For every
 * application a second view model is created through the dagger component of
 * the ROM, its state is replaced by the volume of the application and the
 * slider is bound with the very same code the ROM uses for its own sliders.
 * What is shown is therefore the slider of the ROM and not a copy of it.
 *
 * <p>The release build of the ROM is shrunk: the data class of the state has
 * no constructor and the fields of the view model are final. Both are handled
 * by {@link Reflect}.
 */
final class RomAppSlider {
    private static final String TAG = "AppVolumeRom: ";

    private static final String SLIDERS_BINDER =
            "com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSlidersViewBinder";
    private static final String SLIDER_TYPE_STREAM =
            "com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType$Stream";
    private static final String STATE_MODEL =
            "com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderStateModel";
    private static final String ICON_LOADED =
            "com.android.systemui.common.shared.model.Icon$Loaded";
    private static final String RANGES_KT = "kotlin.ranges.RangesKt";
    private static final String STATE_FLOW_KT = "kotlinx.coroutines.flow.StateFlowKt";
    private static final String FLOW_KT = "kotlinx.coroutines.flow.FlowKt";
    private static final String FLOW_KT_FILTER = "kotlinx.coroutines.flow.FlowKt__FilterKt";
    private static final String FLOW = "kotlinx.coroutines.flow.Flow";
    private static final String SLIDER_LAYOUT = "volume_dialog_slider";

    /** The audio stream the slider pretends to control. */
    private static final int STREAM_MUSIC = 3;

    /** Called when the user moves the slider. */
    interface Listener {
        void onVolumeChanged(String pkg, int percent);
    }

    /** The component of the ROM and the scope it was created with. */
    private static volatile Object sComponent;
    private static volatile Object sScope;

    /** The dialog of the ROM, it holds the factory of the component. */
    private static volatile Object sDialog;

    /**
     * Remembers the dialog of the ROM. The component of the ROM is made with
     * the factory of the dialog and the scope of the ROM.
     *
     * <p>Every dialog comes with a new scope that is cancelled when the dialog
     * is dismissed. A component made for a previous dialog would carry slider
     * view models whose coroutines never run, so it is dropped together with
     * the old dialog.
     */
    static void setDialog(Object dialog, Object scope) {
        sDialog = dialog;
        sScope = scope;
        sComponent = null;
    }

    private final String pkg;
    private final ClassLoader loader;
    private final Object icon;
    private final Object viewModel;
    /**
     * The mutable state flow behind the replaced state. The field of the
     * view model holds the filtered flow built from this one, so the value
     * has to be written here and not into the filtered wrapper.
     */
    private final Object stateFlow;
    private final Object interactor;
    private final View view;
    private final Listener listener;

    private int percent;

    private RomAppSlider(String pkg, ClassLoader loader, Object icon, Object viewModel,
            Object stateFlow, Object interactor, View view, int percent, Listener listener) {
        this.pkg = pkg;
        this.loader = loader;
        this.icon = icon;
        this.viewModel = viewModel;
        this.stateFlow = stateFlow;
        this.interactor = interactor;
        this.view = view;
        this.percent = percent;
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // The component of the ROM
    // ------------------------------------------------------------------

    /**
     * Remembers the component the ROM created for the volume dialog. It is
     * the only way to get the slider components of the ROM, because they are
     * only reachable through the dagger graph of the system ui.
     */
    static void setComponent(Object component, Object scope) {
        sComponent = component;
        sScope = scope;
    }

    // ------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------

    static RomAppSlider create(Context context, String pkg, int percent, Listener listener) {
        try {
            Object component = sComponent;
            Object scope = sScope;
            if (component == null) {
                // The component of the ROM is made with the factory of the
                // dialog and the scope of the ROM.
                Object dialog = sDialog;
                if (dialog == null || scope == null) {
                    log("no component of the ROM");
                    return null;
                }
                Object factory = Reflect.get(dialog, "componentFactory");
                if (factory == null) {
                    log("no factory of the ROM");
                    return null;
                }
                component = call(factory, "create", scope);
                if (component == null) {
                    log("the component of the ROM could not be made");
                    return null;
                }
                sComponent = component;
            }
            if (scope == null) {
                log("no scope of the ROM");
                return null;
            }
            ClassLoader loader = context.getClassLoader();

            Object factory = call(component, "sliderComponentFactory");
            if (factory == null) {
                log("no slider component factory");
                return null;
            }
            Object sliderType = streamType(loader, STREAM_MUSIC);
            if (sliderType == null) {
                log("no slider type");
                return null;
            }
            Object sliderComponent = call(factory, "create", sliderType);
            if (sliderComponent == null) {
                log("no slider component");
                return null;
            }
            Object binder = call(sliderComponent, "sliderViewBinder");
            if (binder == null) {
                log("no slider view binder");
                return null;
            }
            Object viewModel = Reflect.get(binder, "viewModel");
            if (viewModel == null) {
                log("no slider view model");
                return null;
            }
            Object interactor = Reflect.get(viewModel, "interactor");

            Drawable icon = appIcon(context, pkg);
            Object iconLoaded = iconLoaded(loader, icon);
            Object model = stateModel(loader, iconLoaded, pkg, percent);
            if (model == null) {
                log("no state model");
                return null;
            }
            Object flow = flows(loader, model);
            if (flow == null) {
                log("no state flow");
                return null;
            }
            if (!Reflect.set(viewModel, "state", filteredOf(flow))) {
                log("the state of the view model could not be replaced");
                return null;
            }

            View container = inflateSlider(context);
            if (container == null) {
                log("no slider layout");
                return null;
            }
            if (!bind(loader, scope, sliderComponent, component, container)) {
                log("the slider could not be bound");
                return null;
            }

            return new RomAppSlider(pkg, loader, iconLoaded, viewModel, mutableOf(flow), interactor,
                    container, percent, listener);
        } catch (Throwable t) {
            log("create failed: " + t);
            XposedBridge.log(t);
            return null;
        }
    }

    private static Object streamType(ClassLoader loader, int audioStream) {
        try {
            Class<?> type = XposedHelpers.findClass(SLIDER_TYPE_STREAM, loader);
            Object instance = Reflect.allocate(type);
            if (instance == null) {
                return null;
            }
            Reflect.set(instance, "audioStream", audioStream);
            return instance;
        } catch (Throwable t) {
            log("stream type failed: " + t);
            return null;
        }
    }

    private static Object iconLoaded(ClassLoader loader, Drawable icon) {
        try {
            Class<?> type = XposedHelpers.findClass(ICON_LOADED, loader);
            Constructor<?> constructor = type.getDeclaredConstructor(Drawable.class);
            constructor.setAccessible(true);
            return constructor.newInstance(icon);
        } catch (Throwable t) {
            log("icon failed: " + t);
            return null;
        }
    }

    private static Object stateModel(ClassLoader loader, Object icon, String label, float value) {
        try {
            Class<?> type = XposedHelpers.findClass(STATE_MODEL, loader);
            Object model = Reflect.allocate(type);
            if (model == null) {
                return null;
            }
            Reflect.set(model, "icon", icon);
            Reflect.set(model, "isDisabled", Boolean.FALSE);
            Reflect.set(model, "label", label);
            Reflect.set(model, "value", value);
            Reflect.set(model, "valueRange", valueRange(loader));
            return model;
        } catch (Throwable t) {
            log("state model failed: " + t);
            return null;
        }
    }

    private static Object valueRange(ClassLoader loader) {
        try {
            Class<?> ranges = XposedHelpers.findClass(RANGES_KT, loader);
            Method rangeTo = ranges.getMethod("rangeTo", float.class, float.class);
            return rangeTo.invoke(null, 0f, 100f);
        } catch (Throwable t) {
            log("value range failed: " + t);
            return null;
        }
    }

    /** The mutable flow and the filtered wrapper the view model holds. */
    private static final class StateFlows {
        final Object mutable;
        final Object filtered;

        StateFlows(Object mutable, Object filtered) {
            this.mutable = mutable;
            this.filtered = filtered;
        }
    }

    private static Object mutableOf(Object flows) {
        return ((StateFlows) flows).mutable;
    }

    private static Object filteredOf(Object flows) {
        return ((StateFlows) flows).filtered;
    }

    private static StateFlows flows(ClassLoader loader, Object model) {
        try {
            Class<?> stateFlowKt = XposedHelpers.findClass(STATE_FLOW_KT, loader);
            Object mutable = stateFlowKt.getMethod("MutableStateFlow", Object.class)
                    .invoke(null, model);
            Class<?> flowType = XposedHelpers.findClass(FLOW, loader);
            Method filterNotNull = null;
            for (String name : new String[]{FLOW_KT, FLOW_KT_FILTER}) {
                try {
                    filterNotNull = XposedHelpers.findClass(name, loader)
                            .getMethod("filterNotNull", flowType);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (filterNotNull == null) {
                log("filterNotNull not found");
                return null;
            }
            Object filtered = filterNotNull.invoke(null, mutable);
            if (filtered == null) {
                return null;
            }
            return new StateFlows(mutable, filtered);
        } catch (Throwable t) {
            log("state flow failed: " + t);
            return null;
        }
    }

    private static View inflateSlider(Context context) {
        try {
            int layout = context.getResources().getIdentifier(SLIDER_LAYOUT, "layout",
                    context.getPackageName());
            if (layout == 0) {
                return null;
            }
            return LayoutInflater.from(context).inflate(layout, null);
        } catch (Throwable t) {
            log("layout failed: " + t);
            return null;
        }
    }

    /**
     * Binds the slider with the code of the ROM. The shrunk ROM moved the
     * binding into the synthetic accessor of the sliders view binder, which
     * sets the compose content and starts the animation of the overscroll.
     */
    private static boolean bind(ClassLoader loader, Object scope, Object sliderComponent,
            Object component, View container) {
        try {
            Class<?> binderClass = XposedHelpers.findClass(SLIDERS_BINDER, loader);
            Object slidersBinder = slidersBinder(component, binderClass);
            if (slidersBinder == null) {
                log("no sliders view binder");
                return false;
            }

            Method method = null;
            // Android 16 QPR1/17 release: static synthetic accessor. It takes
            // the binder, the coroutine scope, the slider component, the
            // container and the list of views to animate.
            for (Method candidate : binderClass.getDeclaredMethods()) {
                if (candidate.getParameterCount() == 5
                        && candidate.getName().startsWith("access$bindSlider")
                        && java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
                    method = candidate;
                    break;
                }
            }
            if (method == null) {
                for (Method candidate : binderClass.getDeclaredMethods()) {
                    if (candidate.getParameterCount() == 5
                            && candidate.getName().equals("bindSlider")
                            && java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
                        method = candidate;
                        break;
                    }
                }
            }
            if (method == null) {
                // Non-shrunk AOSP 16 QPR1: a member extension with four
                // parameters (scope, component, container, viewsToAnimate).
                for (Method candidate : binderClass.getDeclaredMethods()) {
                    if (candidate.getParameterCount() == 4
                            && candidate.getName().equals("bindSlider")
                            && !java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
                        method = candidate;
                        break;
                    }
                }
            }
            if (method == null) {
                // Android 17 master: bindSlider(scope, component, container).
                for (Method candidate : binderClass.getDeclaredMethods()) {
                    if (candidate.getParameterCount() == 3
                            && candidate.getName().equals("bindSlider")
                            && !java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) {
                        method = candidate;
                        break;
                    }
                }
            }
            if (method == null) {
                log("no bind method");
                return false;
            }
            method.setAccessible(true);
            boolean isStatic = java.lang.reflect.Modifier.isStatic(method.getModifiers());
            if (isStatic) {
                method.invoke(null, slidersBinder, scope, sliderComponent,
                        container, new View[]{container});
            } else if (method.getParameterCount() == 4) {
                method.invoke(slidersBinder, scope, sliderComponent,
                        container, new View[]{container});
            } else {
                method.invoke(slidersBinder, scope, sliderComponent, container);
            }
            return true;
        } catch (Throwable t) {
            log("bind failed: " + t);
            XposedBridge.log(t);
            return false;
        }
    }

    /**
     * The binder of the sliders of the ROM. Android 17's component exposes a
     * method that returns all view binders. AOSP 16 QPR1 puts the same list
     * into a field of the panel binder instead.
     */
    private static Object slidersBinder(Object component, Class<?> binderClass) {
        try {
            Object list = call(component, "volumeDialogListOfViewBinder");
            Object found = findBinder(list, binderClass);
            if (found != null) {
                return found;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object panelBinder = call(component, "volumeDialogViewBinder");
            Object list = Reflect.get(panelBinder, "viewBinders");
            return findBinder(list, binderClass);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findBinder(Object list, Class<?> binderClass) {
        if (list instanceof Iterable) {
            for (Object item : (Iterable<?>) list) {
                if (item != null && binderClass.isInstance(item)) {
                    return item;
                }
            }
        }
        return null;
    }

    private static Drawable appIcon(Context context, String pkg) {
        return AppIcon.load(context, pkg);
    }

    private static Object call(Object target, String name, Object... args) {
        try {
            for (Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(target, args);
            }
        } catch (Throwable t) {
            log("call " + name + " failed: " + t);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The value of the slider
    // ------------------------------------------------------------------

    View getView() {
        return view;
    }

    Object getInteractor() {
        return interactor;
    }

    Object getViewModel() {
        return viewModel;
    }

    /** Shows the given volume in the slider of the ROM. */
    void setPercent(int value) {
        int snapped = SliderPanel.snap(value);
        if (snapped == percent) {
            return;
        }
        percent = snapped;
        publish();
    }

    /**
     * The user moved the slider. The volume of the application is changed
     * right away, the volume of the system is not touched at all.
     */
    void onUserValue(int value) {
        int snapped = SliderPanel.snap(value);
        if (snapped == percent) {
            return;
        }
        percent = snapped;
        if (listener != null) {
            listener.onVolumeChanged(pkg, snapped);
        }
        publish();
    }

    private void publish() {
        try {
            Object model = stateModel(loader, icon, pkg, percent);
            if (model == null) {
                return;
            }
            if (!setFlowValue(model)) {
                log("the value of the slider could not be written");
            }
        } catch (Throwable t) {
            log("publish failed: " + t);
        }
    }

    /**
     * Writes the new state into the mutable flow. The filtered wrapper that
     * the view model holds has no setter, only the flow behind it.
     */
    private boolean setFlowValue(Object model) {
        for (Method method : stateFlow.getClass().getMethods()) {
            if (!method.getName().equals("setValue") || method.getParameterCount() != 1) {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(stateFlow, model);
                return true;
            } catch (Throwable t) {
                log("setValue failed: " + t);
            }
        }
        for (Method method : stateFlow.getClass().getMethods()) {
            if (!method.getName().equals("updateState") || method.getParameterCount() != 2) {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(stateFlow, null, model);
                return true;
            } catch (Throwable t) {
                log("updateState failed: " + t);
            }
        }
        return false;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }
}
