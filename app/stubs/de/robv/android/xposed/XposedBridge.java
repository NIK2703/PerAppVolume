package de.robv.android.xposed;

/** Compile-time stub of the LSPosed/Xposed API. Not packaged in the APK. */
public final class XposedBridge {
    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable throwable) {
    }

    public static java.util.Set<Object> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return null;
    }

    public static java.util.Set<Object> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        return null;
    }

    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member method,
            XC_MethodHook callback) {
        return null;
    }

    public static Object invokeOriginalMethod(java.lang.reflect.Member method,
            Object thisObject, Object[] args) throws Throwable {
        return null;
    }
}
