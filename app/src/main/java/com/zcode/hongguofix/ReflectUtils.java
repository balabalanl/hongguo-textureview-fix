package com.zcode.hongguofix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Small reflection helper. Every failure logs class/member/signature so a
 * broken hook is diagnosable from logcat alone.
 */
public final class ReflectUtils {

    private ReflectUtils() {
    }

    public static Class<?> findClass(String name, ClassLoader cl) {
        if (name == null) {
            return null;
        }
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable tr) {
            LogUtils.d("[RESOLVE] class not found: " + name + " (" + tr.getClass().getSimpleName() + ")");
            return null;
        }
    }

    public static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        if (cls == null || name == null) {
            return null;
        }
        Class<?> cur = cls;
        while (cur != null) {
            try {
                Method m = cur.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                cur = cur.getSuperclass();
            } catch (Throwable tr) {
                LogUtils.d("[RESOLVE] getDeclaredMethod failed " + cls.getName() + "#" + name
                        + " (" + tr.getClass().getSimpleName() + ")");
                return null;
            }
        }
        LogUtils.d("[RESOLVE] method missing: " + cls.getName() + "#" + name + signature(params));
        return null;
    }

    public static Method findMethodLoose(Class<?> cls, String name, int paramCount) {
        if (cls == null) {
            return null;
        }
        Class<?> cur = cls;
        while (cur != null) {
            Method[] all;
            try {
                all = cur.getDeclaredMethods();
            } catch (Throwable tr) {
                return null;
            }
            for (Method m : all) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** Finds the single-parameter method whose parameter is java.lang.Class. */
    public static Method findMethodClassParam(Class<?> cls, String name) {
        if (cls == null) {
            return null;
        }
        Class<?> cur = cls;
        while (cur != null) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Class.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    public static Field findField(Class<?> cls, String name) {
        if (cls == null || name == null) {
            return null;
        }
        Class<?> cur = cls;
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            } catch (Throwable tr) {
                LogUtils.d("[RESOLVE] getDeclaredField failed " + cls.getName() + "." + name
                        + " (" + tr.getClass().getSimpleName() + ")");
                return null;
            }
        }
        LogUtils.d("[RESOLVE] field missing: " + cls.getName() + "." + name);
        return null;
    }

    public static Object getFieldValue(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field f = findField(target.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable tr) {
            LogUtils.d("[RESOLVE] read field failed " + name + " (" + tr.getClass().getSimpleName() + ")");
            return null;
        }
    }

    /**
     * Best-effort write of a field. Works for instance and static fields, and
     * also reaches through to a superclass declaration.
     */
    public static boolean setFieldValue(Object target, String name, Object value) {
        if (target == null) {
            return false;
        }
        Field f = findField(target.getClass(), name);
        if (f == null) {
            return false;
        }
        try {
            f.set(target, value);
            return true;
        } catch (Throwable tr) {
            LogUtils.d("[RESOLVE] write field failed " + name + " (" + tr.getClass().getSimpleName() + ")");
            return false;
        }
    }

    public static Object callMethod(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        Method m = findMethod(target.getClass(), name, types);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target, args);
        } catch (Throwable tr) {
            LogUtils.d("[RESOLVE] invoke failed " + name + " (" + tr.getClass().getSimpleName() + ")");
            return null;
        }
    }

    public static String signature(Class<?>[] params) {
        if (params == null || params.length == 0) {
            return "()";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i] == null ? "?" : params[i].getName());
        }
        return sb.append(')').toString();
    }
}