package com.zcode.hongguofix.hooks;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.zcode.hongguofix.LogUtils;
import com.zcode.hongguofix.ReflectUtils;
import com.zcode.hongguofix.HookHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * 功能三 —— 屏蔽播放页的红包悬浮窗与「赚钱」相关挂件。
 *
 * <p>实现直接移植自「白果助手」com.hgdj.mod.HidePendant（对红果 7.3.9.32 实测有效的方案），
 * 并保留我们 v1.6 的 Lottie 资源拦截作为最后防线。
 *
 * <h3>五层屏蔽策略</h3>
 * <ol>
 *   <li>拦截挂件分发：hook 混淆类 gb6.m / yf6.m 的 l()，before 阶段 skip，
 *       挂件根本不会被创建。</li>
 *   <li>拦截金币盒：hook 未混淆类
 *       com.dragon.read.goldcoinbox.pendant.video.f1 的 d()。</li>
 *   <li>拦截福袋服务：hook 字节 SDK
 *       com.bytedance.ug.sdk.novel.pendant.PlanPendantServiceImpl#triggerEvent，
 *       仅事件名含 "pendant" 时拦截。</li>
 *   <li>业务关键词：hook com.dragon.read.pages.main.m 的 b()/a()/c()，
 *       参数含 LuckyBenefit / ShopMall 时反射扫出 View 并隐藏。</li>
 *   <li>Lottie 资源兜底：hook LottieAnimationView#setAnimation(String)，
 *       命中 video_task_red_pack_lottie 即隐藏。</li>
 * </ol>
 *
 * <p>另含看护线程（移植自白果助手）：已隐藏 View 每 40ms 复查，
 * 被 App 重新显示就再关；空闲则自动停，WeakReference 防泄漏。
 */
public final class PendantBlocker {

    private static final String CLS_GB6 = "gb6.m";
    private static final String CLS_YF6 = "yf6.m";
    private static final String CLS_F1 = "com.dragon.read.goldcoinbox.pendant.video.f1";
    private static final String CLS_PPS =
            "com.bytedance.ug.sdk.novel.pendant.PlanPendantServiceImpl";
    private static final String CLS_M = "com.dragon.read.pages.main.m";
    private static final String CLS_LOTTIE = "com.airbnb.lottie.LottieAnimationView";

    /** 业务关键词（英文 key，不走混淆，比中文文案精准）。 */
    private static final String KW_LUCKY = "LuckyBenefit";
    private static final String KW_MALL = "ShopMall";
    private static final String ANIM_RED_PACK = "video_task_red_pack_lottie";

    private static final int HIDE_TAG_KEY = 2112883388;
    private static final int HIDDEN_MAX = 64;
    private static final long WATCH_MS = 40L;
    private static final long[] RETRY_DELAYS = {2000L, 5000L, 10000L, 20000L, 40000L};

    private static ClassLoader classLoader;
    private static volatile boolean watching;
    private static final Handler WATCH_H = new Handler(Looper.getMainLooper());
    private static final Handler RETRY_H = new Handler(Looper.getMainLooper());
    private static final List<WeakReference<View>> HIDDEN_REFS = new ArrayList<>();
    private static final Set<String> BOUND = new HashSet<>();
    private static final WeakHashMap<Class<?>, List<Field>> SWEEP_CACHE = new WeakHashMap<>();

    /** 总开关。 */
    public static volatile boolean ENABLED = true;

    private PendantBlocker() {
    }

    public static boolean install(XposedInterface xp, ClassLoader cl) {
        classLoader = cl;
        bindAll(xp);
        // 主 App 挂件懒加载，按退避间隔重试绑定
        for (long delay : RETRY_DELAYS) {
            RETRY_H.postDelayed(() -> bindAll(xp), delay);
        }
        return true;
    }

    private static synchronized void bindAll(XposedInterface xp) {
        bindDispatch(xp, CLS_GB6, "gb6");
        bindDispatch(xp, CLS_YF6, "yf6");
        bindF1(xp);
        bindPPS(xp);
        bindM(xp);
        bindLottie(xp);
        reportPending();
    }

    private static void reportPending() {
        StringBuilder sb = new StringBuilder();
        if (!BOUND.contains("gb6")) sb.append("gb6 ");
        if (!BOUND.contains("yf6")) sb.append("yf6 ");
        if (!BOUND.contains("f1")) sb.append("f1 ");
        if (!BOUND.contains("pps")) sb.append("pps ");
        if (!BOUND.contains("m")) sb.append("m ");
        if (sb.length() > 0) {
            LogUtils.d("PendantBlocker: 待绑定 -> " + sb);
        } else {
            LogUtils.call("PendantBlocker: 全部层级已绑定");
        }
    }

    // ------------------------------------------------------- 层 1：挂件分发

    private static void bindDispatch(XposedInterface xp, String clsName, String tag) {
        if (BOUND.contains(tag)) {
            return;
        }
        try {
            Class<?> cls = loadClass(clsName);
            if (cls == null) {
                return;
            }
            int n = HookHelper.hookAllByName(xp, cls, "l", false, chain -> chain.skip());
            BOUND.add(tag);
            LogUtils.resolve("层1 挂件分发 " + clsName + ".l x" + n + " stability=MEDIUM");
        } catch (Throwable tr) {
            LogUtils.d("层1 " + clsName + " 绑定失败: " + tr.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------- 层 2：金币盒

    private static void bindF1(XposedInterface xp) {
        if (BOUND.contains("f1")) {
            return;
        }
        try {
            Class<?> cls = loadClass(CLS_F1);
            if (cls == null) {
                return;
            }
            int n = HookHelper.hookAllByName(xp, cls, "d", false, chain -> chain.skip());
            BOUND.add("f1");
            LogUtils.resolve("层2 金币盒 .d x" + n + " stability=HIGH(未混淆)");
        } catch (Throwable tr) {
            LogUtils.d("层2 f1 绑定失败: " + tr.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------- 层 3：福袋服务

    private static void bindPPS(XposedInterface xp) {
        if (BOUND.contains("pps")) {
            return;
        }
        try {
            Class<?> cls = loadClass(CLS_PPS);
            if (cls == null) {
                return;
            }
            int n = HookHelper.hookAllByName(xp, cls, "triggerEvent", false, chain -> {
                List<Object> args = chain.args();
                if (args == null || args.isEmpty()) {
                    return;
                }
                Object a0 = args.get(0);
                if (a0 != null && String.valueOf(a0).contains("pendant")) {
                    chain.skip();
                }
            });
            BOUND.add("pps");
            LogUtils.resolve("层3 福袋服务 triggerEvent x" + n + " stability=HIGH");
        } catch (Throwable tr) {
            LogUtils.d("层3 pps 绑定失败: " + tr.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------- 层 4：业务关键词

    private static void bindM(XposedInterface xp) {
        if (BOUND.contains("m")) {
            return;
        }
        try {
            Class<?> cls = loadClass(CLS_M);
            if (cls == null) {
                return;
            }
            // b(...) / a(...)：before 阶段判参数语义
            HookHelper.hookAllByName(xp, cls, "b", false, chain -> {
                List<Object> args = chain.args();
                if (args == null || args.isEmpty() || args.get(0) == null) {
                    return;
                }
                Object label = callNoArg(args.get(0), "a");
                if (label != null && String.valueOf(label).contains(KW_LUCKY)) {
                    sweepViews(args.get(0));
                }
            });
            HookHelper.hookAllByName(xp, cls, "a", false, chain -> {
                List<Object> args = chain.args();
                if (args == null || args.size() < 2) {
                    return;
                }
                Object a0 = args.get(0);
                Object a1 = args.get(1);
                if (a0 != null && a1 != null && String.valueOf(a0).contains(KW_LUCKY)) {
                    sweepViews(a1);
                }
            });
            // c(...)：after 阶段拿返回值
            HookHelper.hookAllAfterByName(xp, cls, "c", (thisObj, result, args) -> {
                if (args == null || args.size() < 2 || thisObj == null) {
                    return;
                }
                String k1 = args.get(1) == null ? "" : String.valueOf(args.get(1));
                if (!k1.contains(KW_LUCKY) && !k1.contains(KW_MALL)) {
                    return;
                }
                Object view = callNoArg(thisObj, "getView");
                if (view instanceof View) {
                    hideView((View) view);
                }
                sweepViews(thisObj);
            });
            BOUND.add("m");
            LogUtils.resolve("层4 赚钱Tab 关键词=" + KW_LUCKY + "/" + KW_MALL + " stability=HIGH");
        } catch (Throwable tr) {
            LogUtils.d("层4 m 绑定失败: " + tr.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------- 层 5：Lottie 兜底

    private static void bindLottie(XposedInterface xp) {
        if (BOUND.contains("lottie")) {
            return;
        }
        try {
            Class<?> cls = loadClass(CLS_LOTTIE);
            if (cls == null) {
                return;
            }
            Method setAnim = ReflectUtils.findMethod(cls, "setAnimation", String.class);
            if (setAnim == null) {
                return;
            }
            xp.hook(setAnim).setId("pb-lottie").intercept(chain -> {
                List<Object> args = chain.getArgs();
                if (ENABLED && !args.isEmpty() && args.get(0) instanceof String) {
                    String anim = (String) args.get(0);
                    if (anim != null && anim.contains(ANIM_RED_PACK)) {
                        LogUtils.call("层5 拦截红包动画: " + anim);
                        Object host = chain.getThisObject();
                        if (host instanceof View) {
                            hideView((View) host);
                        }
                        return null;
                    }
                }
                return chain.proceed();
            });
            BOUND.add("lottie");
            LogUtils.resolve("层5 Lottie资源兜底 资源名=" + ANIM_RED_PACK + " stability=HIGH");
        } catch (Throwable tr) {
            LogUtils.d("层5 lottie 绑定失败: " + tr.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------- 隐藏与看护

    public static void hideView(View view) {
        if (view == null) {
            return;
        }
        try {
            if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
            }
            if (view.getTag(HIDE_TAG_KEY) == null) {
                view.setTag(HIDE_TAG_KEY, Boolean.TRUE);
                if (HIDDEN_REFS.size() < HIDDEN_MAX) {
                    HIDDEN_REFS.add(new WeakReference<>(view));
                }
            }
            startWatch();
        } catch (Throwable tr) {
            LogUtils.d("PendantBlocker: hideView 异常 " + tr.getClass().getSimpleName());
        }
    }

    private static void sweepViews(Object obj) {
        if (obj == null) {
            return;
        }
        try {
            Class<?> cls = obj.getClass();
            List<Field> fields = SWEEP_CACHE.get(cls);
            if (fields == null) {
                fields = new ArrayList<>();
                for (Field f : cls.getDeclaredFields()) {
                    if (View.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        fields.add(f);
                    }
                }
                SWEEP_CACHE.put(cls, fields);
            }
            for (Field f : fields) {
                try {
                    Object v = f.get(obj);
                    if (v instanceof View) {
                        hideView((View) v);
                    }
                } catch (Throwable ignored) {
                    // 个别字段读取失败跳过
                }
            }
        } catch (Throwable tr) {
            LogUtils.d("PendantBlocker: sweepViews 异常 " + tr.getClass().getSimpleName());
        }
    }

    private static void startWatch() {
        if (watching) {
            return;
        }
        watching = true;
        WATCH_H.postDelayed(PendantBlocker::watchStep, WATCH_MS);
    }

    private static void watchStep() {
        int alive = 0;
        try {
            int size = HIDDEN_REFS.size();
            for (int i = 0; i < size; ) {
                WeakReference<View> ref = HIDDEN_REFS.get(i);
                View v = ref == null ? null : ref.get();
                if (v == null) {
                    HIDDEN_REFS.remove(i);
                    size--;
                } else {
                    try {
                        if (v.getVisibility() != View.GONE) {
                            v.setVisibility(View.GONE);
                        }
                    } catch (Throwable ignored) {
                        // View 已 detach 等情况忽略
                    }
                    alive++;
                    i++;
                }
            }
        } catch (Throwable tr) {
            LogUtils.d("PendantBlocker: watchStep 异常 " + tr.getClass().getSimpleName());
        }
        if (alive > 0) {
            WATCH_H.postDelayed(PendantBlocker::watchStep, WATCH_MS);
        } else {
            watching = false;
        }
    }

    // ------------------------------------------------------- 反射小工具

    private static Class<?> loadClass(String name) {
        return ReflectUtils.findClass(name, classLoader);
    }

    private static Object callNoArg(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        try {
            Method m = ReflectUtils.findMethod(obj.getClass(), name);
            return m == null ? null : m.invoke(obj);
        } catch (Throwable tr) {
            return null;
        }
    }
}
