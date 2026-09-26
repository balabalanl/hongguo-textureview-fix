package de.robv.android.xposed;

/** 编译期桩：运行时由 LSPosed 提供。 */
public final class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader classLoader) { return null; }
    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) { return null; }
    public static void findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {}
    public static void findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {}
    public static void findAndHookConstructor(String className, ClassLoader classLoader, Object... parameterTypesAndCallback) {}
    public static void setBooleanField(Object obj, String fieldName, boolean value) {}
}
