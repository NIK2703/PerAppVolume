package de.robv.android.xposed;

/** Compile-time stub of the LSPosed/Xposed API. Not packaged in the APK. */
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args;

        public void setResult(Object result) {
        }

        public Object getResult() {
            return null;
        }
    }

    public class Unhook {
        public void unhook() {
        }
    }
}
