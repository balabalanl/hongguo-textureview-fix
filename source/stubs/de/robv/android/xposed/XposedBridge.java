package de.robv.android.xposed;

/** 编译期桩：运行时由 LSPosed 提供。 */
public final class XposedBridge {
    public static Object[] hookMethod(java.lang.reflect.Member hookMethod, XC_MethodHook callback) {
        return null;
    }
    public static void log(String text) {}
    public static void log(Throwable t) {}
}
