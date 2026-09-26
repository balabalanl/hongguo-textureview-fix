package de.robv.android.xposed.callbacks;

import de.robv.android.xposed.XC_MethodHook;

/** 编译期桩：运行时由 LSPosed 提供。 */
public abstract class XC_LoadPackage extends XC_MethodHook {
    public static class LoadPackageParam extends XC_MethodHook.MethodHookParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public android.content.pm.ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
