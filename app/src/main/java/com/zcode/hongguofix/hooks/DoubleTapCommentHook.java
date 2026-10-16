package com.zcode.hongguofix.hooks;

import android.view.MotionEvent;

import com.zcode.hongguofix.LogUtils;
import com.zcode.hongguofix.ReflectUtils;
import com.zcode.hongguofix.HookHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * 功能二 —— 双击点赞 改为 双击打开评论区（挂件屏蔽见 {@link PendantBlocker}）。
 *
 * <h3>定位链路（七轮实机迭代结论）</h3>
 * <ol>
 *   <li>点赞触发点已确认为 {@code wp4.e#onDoubleTap(MotionEvent)} ——
 *       {@code es4.a} 的唯一实现类，指纹评分 10。</li>
 *   <li>但要打开评论区需要 ICommentDialogHelperApi。追查发现从 wp4.e 出发
 *       <b>拿不到 docker</b>：wp4.e.a → t65.v$d.a → t65.l，其 f 字段运行时是
 *       c95.n（无 b(Class) 方法），且 po4.c 不在继承链上。</li>
 *   <li>「白果助手」的做法给了出路：它 hook {@code e55.h} 与
 *       {@code com.dragon.read...VideoGestureDetectLayout}，并用 dex 扫描
 *       枚举 GestureDetector$OnDoubleTapListener 的所有实现类。</li>
 * </ol>
 *
 * <h3>本版策略（三层）</h3>
 * <ol>
 *   <li><b>dex 扫描 OnDoubleTapListener 实现类</b>（移植自白果助手
 *       CleanScreen.findImplementations）：全部 hook，命中含点赞语义的即拦。
 *       这一层不依赖任何混淆类名，最稳。</li>
 *   <li><b>直接 hook wp4.e / e55.h</b>：已知目标，优先命中，省去 dex 扫描开销。</li>
 *   <li><b>评论区打开</b>：通过 {@code ko4.a#bizContext}（类型 lo4.b，
 *       调用 findSeriesBiz / b(Class)）拿 ICommentDialogHelperApi。</li>
 * </ol>
 */
public final class DoubleTapCommentHook {

    private static final String CLS_DOUBLE_TAP_IFACE =
            "android.view.GestureDetector$OnDoubleTapListener";
    /** 点赞动画本体（es4.a 唯一实现）。 */
    private static final String CLS_LIKE_VIEW = "wp4.e";
    /** 双击分发器（白果助手验证过）。 */
    private static final String CLS_GESTURE_H = "e55.h";
    private static final String IFACE_LIKE = "es4.a";

    private static final String CLS_COMMENT_API =
            "com.dragon.read.component.shortvideo.impl.rightview.comment.ICommentDialogHelperApi";
    private static final String CLS_BIZ_BASE = "ko4.a";
    private static final String CLS_DOCKER_BASE = "lo4.b";

    /** 缓存 App 自己打开评论区时的参数，实现"学习后复现"。 */
    private static volatile Object sCommentHelper;
    private static volatile Object sLaunchArgs;

    private static ClassLoader sClassLoader;

    private DoubleTapCommentHook() {
    }

    public static boolean install(XposedInterface xp, ClassLoader cl) {
        sClassLoader = cl;
        int total = 0;

        // 1) 直接 hook 已知目标（快）
        total += hookDoubleTapClass(xp, cl, CLS_GESTURE_H, "e55.h");
        total += hookDoubleTapClass(xp, cl, CLS_LIKE_VIEW, "wp4.e");

        // 2) dex 扫描兜底（不依赖类名）
        total += scanAndHookDoubleTap(xp, cl);

        if (total == 0) {
            LogUtils.fail("Feature2: 未 hook 到任何双击入口");
            return false;
        }

        // 3) 评论区打开所需的实例捕获
        captureCommentContext(xp, cl);
        LogUtils.resolve("Feature2 双击入口合计 hook " + total + " 处");
        return true;
    }

    // ------------------------------------------------------------ 双击拦截

    /** hook 指定类的 onDoubleTap 方法。 */
    private static int hookDoubleTapClass(XposedInterface xp, ClassLoader cl,
                                          String clsName, String tag) {
        try {
            Class<?> cls = ReflectUtils.findClass(clsName, cl);
            if (cls == null) {
                LogUtils.d("Feature2: 未找到 " + clsName);
                return 0;
            }
            Method m = ReflectUtils.findMethod(cls, "onDoubleTap", MotionEvent.class);
            if (m == null) {
                return 0;
            }
            xp.hook(m).setId("f2-" + tag).intercept(chain -> {
                if (openComments(chain.getThisObject())) {
                    return Boolean.TRUE;
                }
                LogUtils.warn("Feature2: 评论区打开失败，回退到原始双击逻辑");
                return chain.proceed();
            });
            LogUtils.resolve("Feature2 已 hook " + clsName + "#onDoubleTap");
            return 1;
        } catch (Throwable tr) {
            LogUtils.d("Feature2: hook " + clsName + " 失败 " + tr.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * 通过 dex 扫描找出 GestureDetector$OnDoubleTapListener 的所有实现类并 hook。
     *
     * <p>移植自「白果助手」CleanScreen#findImplementations。反射遍历
     * BaseDexClassLoader 的 pathList → dexElements → dexFile 拿 dex 路径，
     * 再逐包枚举，命中实现接口的类名即 Class.forName 验证。
     *
     * <p>优势：完全不依赖混淆类名，App 改名也能命中。
     */
    private static int scanAndHookDoubleTap(XposedInterface xp, ClassLoader cl) {
        try {
            Class<?> iface = ReflectUtils.findClass(CLS_DOUBLE_TAP_IFACE, cl);
            if (iface == null) {
                return 0;
            }
            List<Class<?>> impls = findImplementations(cl, iface);
            LogUtils.resolve("Feature2 dex 扫描到 OnDoubleTapListener 实现 " + impls.size() + " 个");
            int n = 0;
            Set<String> done = new HashSet<>();
            for (Class<?> c : impls) {
                Method m = ReflectUtils.findMethod(c, "onDoubleTap", MotionEvent.class);
                if (m == null || !done.add(c.getName())) {
                    continue;
                }
                try {
                    xp.hook(m).setId("f2-scan-" + n).intercept(chain -> {
                        if (openComments(chain.getThisObject())) {
                            return Boolean.TRUE;
                        }
                        return chain.proceed();
                    });
                    n++;
                    LogUtils.d("Feature2 扫描命中并 hook: " + c.getName());
                } catch (Throwable tr) {
                    LogUtils.d("Feature2: hook 扫描结果 " + c.getName() + " 失败");
                }
            }
            return n;
        } catch (Throwable tr) {
            LogUtils.d("Feature2: dex 扫描失败 " + tr.getClass().getSimpleName());
            return 0;
        }
    }

    /** 枚举 ClassLoader 下所有 dex，找出实现指定接口的类。 */
    private static List<Class<?>> findImplementations(ClassLoader cl, Class<?> iface) {
        List<Class<?>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String dexPath : dexPaths(cl)) {
            try {
                dalvik.system.DexFile dx = new dalvik.system.DexFile(dexPath);
                java.util.Enumeration<String> en = dx.entries();
                while (en.hasMoreElements()) {
                    String name = en.nextElement();
                    if (name == null || !seen.add(name)) {
                        continue;
                    }
                    // 只在可能相关的包内深挖，控制开销
                    if (!name.startsWith("com.dragon.read") && !name.startsWith("com.bytedance")) {
                        continue;
                    }
                    try {
                        Class<?> c = Class.forName(name, false, cl);
                        if (iface.isAssignableFrom(c) && !c.isInterface()) {
                            out.add(c);
                        }
                    } catch (Throwable ignored) {
                        // 类不可加载时跳过
                    }
                }
                dx.close();
            } catch (Throwable tr) {
                LogUtils.d("Feature2: 读取 dex " + dexPath + " 失败 "
                        + tr.getClass().getSimpleName());
            }
        }
        return out;
    }

    /** 从 BaseDexClassLoader 反射取出所有 dex 文件路径。 */
    private static List<String> dexPaths(ClassLoader cl) {
        List<String> out = new ArrayList<>();
        Object pathList = getFieldDeep(cl, "pathList");
        if (pathList == null) {
            return out;
        }
        Object dexElements = getFieldDeep(pathList, "dexElements");
        if (!(dexElements instanceof Object[])) {
            return out;
        }
        for (Object el : (Object[]) dexElements) {
            if (el == null) {
                continue;
            }
            Object dexFile = getFieldDeep(el, "dexFile");
            if (dexFile == null) {
                dexFile = getFieldDeep(el, "mDexFile");
            }
            if (dexFile != null) {
                Object p = getFieldDeep(dexFile, "mFileName");
                if (p instanceof String) {
                    out.add((String) p);
                }
            }
        }
        return out;
    }

    private static Object getFieldDeep(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable tr) {
            return null;
        }
    }

    // ------------------------------------------------------------ 评论区入口

    /**
     * 捕获评论区实现实例。
     *
     * <p>{@code CommentDialogHelper extends ko4.a}，而 ko4.a 的
     * {@code bizContext} 字段本身就是 lo4.b —— 有了实例就等于有了 docker。
     * 在 App 自己播放时 hook onPlay 拿到它，双击时直接复用。
     */
    private static void captureCommentContext(XposedInterface xp, ClassLoader cl) {
        Class<?> impl = ReflectUtils.findClass(
                "com.dragon.read.component.shortvideo.impl.rightview.comment.CommentDialogHelper", cl);
        if (impl == null) {
            LogUtils.d("Feature2: 未找到 CommentDialogHelper 实现类");
            return;
        }
        // 1) onPlay(String,String)：稳定高频回调，用来捕获实例
        Method onPlay = ReflectUtils.findMethod(impl, "onPlay", String.class, String.class);
        if (onPlay != null) {
            try {
                xp.hook(onPlay).setId("f2-cap-inst").intercept(chain -> {
                    if (chain.getThisObject() != null) {
                        sCommentHelper = chain.getThisObject();
                    }
                    return chain.proceed();
                });
                LogUtils.resolve("Feature2 实例捕获点 onPlay(String,String) stability=HIGH");
            } catch (Throwable tr) {
                LogUtils.d("Feature2: onPlay 捕获失败 " + tr.getClass().getSimpleName());
            }
        }
        // 2) onShowCommentDialog(v35.l,b,boolean)：同时缓存参数体
        Method show = findByParamCount(impl, "onShowCommentDialog", 3);
        if (show != null) {
            try {
                xp.hook(show).setId("f2-cap-args").intercept(chain -> {
                    Object th = chain.getThisObject();
                    if (th != null) {
                        sCommentHelper = th;
                    }
                    List<Object> args = chain.getArgs();
                    if (args != null && !args.isEmpty() && args.get(0) != null) {
                        sLaunchArgs = args.get(0);
                        LogUtils.d("Feature2: 已缓存评论参数 "
                                + args.get(0).getClass().getName());
                    }
                    return chain.proceed();
                });
                LogUtils.resolve("Feature2 参数捕获点 onShowCommentDialog stability=HIGH");
            } catch (Throwable tr) {
                LogUtils.d("Feature2: 参数捕获失败 " + tr.getClass().getSimpleName());
            }
        }
    }

    private static Method findByParamCount(Class<?> cls, String name, int count) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == count) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /** 打开当前视频的评论区。 */
    private static boolean openComments(Object from) {
        try {
            if (from == null || sClassLoader == null) {
                return false;
            }
            Object helper = sCommentHelper;
            if (helper == null) {
                LogUtils.d("Feature2: 尚未捕获到评论区实例（请先点一次评论按钮）");
                return false;
            }
            Class<?> api = ReflectUtils.findClass(CLS_COMMENT_API, sClassLoader);
            if (api == null || !api.isInstance(helper)) {
                LogUtils.d("Feature2: 缓存实例不是 ICommentDialogHelperApi");
                return false;
            }
            Method show = findByParamCount(helper.getClass(), "onShowCommentDialog", 3);
            if (show == null) {
                LogUtils.d("Feature2: 未找到 onShowCommentDialog");
                return false;
            }
            Object launchArgs = sLaunchArgs;
            if (launchArgs == null) {
                LogUtils.d("Feature2: 尚无缓存参数（请先点一次评论按钮让模块学习）");
                return false;
            }
            // 复用 App 自己构造的参数体，强制刷新
            Object r = show.invoke(helper, launchArgs, null, Boolean.TRUE);
            LogUtils.call("Feature2: 已调用 onShowCommentDialog -> " + r);
            return true;
        } catch (Throwable tr) {
            LogUtils.fail("Feature2: openComments 失败 "
                    + tr.getClass().getSimpleName() + ": " + tr.getMessage(), tr);
            return false;
        }
    }
}
