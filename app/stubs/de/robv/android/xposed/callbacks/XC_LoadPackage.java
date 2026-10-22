package de.robv.android.xposed.callbacks;

/** Compile-time stub of the LSPosed/Xposed API. Not packaged in the APK. */
public final class XC_LoadPackage {
    private XC_LoadPackage() {
    }

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
