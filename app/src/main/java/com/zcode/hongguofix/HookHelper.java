package com.zcode.hongguofix;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 轻量 hook 工具集。
 *
 * <p>设计移植自「白果助手」com.hgdj.mod.HookKit（同样基于 libxposed Modern API），
 * 保留其最有价值的两个能力：
 * <ul>
 *   <li><b>按名批量 hook</b>：一次拦截某类的所有同名重载，避免遗漏 overload；</li>
 *   <li><b>before / after 对称回调</b>：before 可 skip（拦截），after 拿返回值。</li>
 * </ul>
 * 这样上层功能代码只需写业务判断，不必反复拼 Chain。
 */
public final class HookHelper {

    public interface Before {
        void call(Chain chain);
    }

    public interface After {
        void call(Object thisObject, Object result, List<Object> args);
    }

    public interface Chain {
        List<Object> args();

        Object thisObject();

        /** before 阶段无返回值，恒为 null；仅供占位。 */
        Object result();

        /** 拦截并返回该方法的类型默认值（void→null）。 */
        void skip();

        /** 拦截并返回指定值。 */
        void skipWith(Object value);
    }

    private HookHelper() {
    }

    private static final class Ctx implements Chain {
        private final XposedInterface.Chain base;
        private boolean skipped;
        private boolean hasResult;
        private Object result;

        Ctx(XposedInterface.Chain base) {
            this.base = base;
        }

        @Override
        public List<Object> args() {
            return base.getArgs();
        }

        @Override
        public Object thisObject() {
            return base.getThisObject();
        }

        @Override
        public Object result() {
            return null;
        }

        @Override
        public void skip() {
            skipped = true;
        }

        @Override
        public void skipWith(Object value) {
            skipped = true;
            hasResult = true;
            result = value;
        }
    }

    /**
     * 批量 hook 某类的所有同名方法（含父类继承来的）。
     *
     * @param returnDefault true 表示拦截时返回类型默认值（false/0/null），
     *                      false 表示返回 null（引用类型）
     * @return 实际 hook 成功的方法数量
     */
    public static int hookAllByName(XposedInterface xp, Class<?> cls, String name,
                                    boolean returnDefault, Before cb) {
        if (xp == null || cls == null || name == null) {
            return 0;
        }
        int count = 0;
        for (Method mm : declaredHierarchy(cls)) {
            if (!mm.getName().equals(name)) {
                continue;
            }
            final Method m = mm;
            final Class<?> ret = m.getReturnType();
            try {
                m.setAccessible(true);
                xp.hook(m).setId("hh-" + name + "-" + count).intercept(chain -> {
                    Ctx ctx = new Ctx(chain);
                    try {
                        cb.call(ctx);
                    } catch (Throwable tr) {
                        LogUtils.d("HookHelper: before 回调异常 " + tr.getClass().getSimpleName());
                    }
                    if (ctx.skipped) {
                        if (ctx.hasResult) {
                            return ctx.result;
                        }
                        return returnDefault ? defaultValue(ret) : null;
                    }
                    return chain.proceed();
                });
                count++;
            } catch (Throwable tr) {
                LogUtils.d("HookHelper: hook " + cls.getName() + "#" + name
                        + " 失败 " + tr.getClass().getSimpleName());
            }
        }
        return count;
    }

    /** 批量 hook 某类的所有同名方法，after 回调。 */
    public static int hookAllAfterByName(XposedInterface xp, Class<?> cls, String name, After cb) {
        if (xp == null || cls == null || name == null) {
            return 0;
        }
        int count = 0;
        for (Method mm : declaredHierarchy(cls)) {
            if (!mm.getName().equals(name)) {
                continue;
            }
            final Method m = mm;
            try {
                m.setAccessible(true);
                xp.hook(m).setId("hha-" + name + "-" + count).intercept(chain -> {
                    Object r = chain.proceed();
                    try {
                        cb.call(chain.getThisObject(), r, chain.getArgs());
                    } catch (Throwable tr) {
                        LogUtils.d("HookHelper: after 回调异常 " + tr.getClass().getSimpleName());
                    }
                    return r;
                });
                count++;
            } catch (Throwable tr) {
                LogUtils.d("HookHelper: after-hook " + cls.getName() + "#" + name
                        + " 失败 " + tr.getClass().getSimpleName());
            }
        }
        return count;
    }

    /** 收集类及其全部父类声明的方法（含 private）。 */
    private static List<Method> declaredHierarchy(Class<?> cls) {
        List<Method> out = new ArrayList<>();
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            try {
                for (Method m : cur.getDeclaredMethods()) {
                    out.add(m);
                }
            } catch (Throwable ignored) {
                // 个别类不可反射时跳过
            }
            cur = cur.getSuperclass();
        }
        return out;
    }

    /** 返回类型的默认值。 */
    public static Object defaultValue(Class<?> c) {
        if (c == null || c == Void.TYPE) {
            return null;
        }
        if (c == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (c == Integer.TYPE) {
            return 0;
        }
        if (c == Long.TYPE) {
            return 0L;
        }
        if (c == Short.TYPE) {
            return (short) 0;
        }
        if (c == Byte.TYPE) {
            return (byte) 0;
        }
        if (c == Character.TYPE) {
            return (char) 0;
        }
        if (c == Float.TYPE) {
            return Float.valueOf(0.0f);
        }
        if (c == Double.TYPE) {
            return Double.valueOf(0.0d);
        }
        return null;
    }
}
