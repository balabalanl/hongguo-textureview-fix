package com.zcode.hongguofix.hooks;

import com.zcode.hongguofix.LogUtils;
import com.zcode.hongguofix.ReflectUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Feature 1 — stop the grey-screen by forcing the GPU-composited texture path.
 *
 * <p>Evidence (com.phoenix.read 7.3.9.32, classes13.dex):
 * <pre>
 * com.ss.ttm.player.TTPlayerViewHelper#isEnableSurfaceView(Context)
 *   = (isDeviceOppoRealmeBrand() &amp;&amp; isMediaTekChipset() &amp;&amp; ...) ? false : true
 * isMediaTekChipset() = Build.HARDWARE.matches("mt[0-9]*")
 * </pre>
 * A OnePlus Ace 2 Pro is Qualcomm, so the MediaTek branch is false and the
 * method returns {@code true} → the player picks a SurfaceView, which takes an
 * exclusive DPU video overlay plane. On ColorOS 16 that overlay state machine
 * intermittently produces a grey frame. Replacing the surface with a
 * TextureView makes the frames go through GPU composition (the same route
 * Douyin uses), which is exactly what "disable HW overlays" achieves manually.
 *
 * <p>The three layers below are ordered by stability. Each is independent, and
 * a failure in one never blocks the next.
 */
public final class RenderPathHook {

    private static final String CLS_HELPER = "com.ss.ttm.player.TTPlayerViewHelper";
    private static final String CLS_VIEW = "com.ss.ttm.player.TTPlayerView";
    private static final String CLS_CONFIG_IMPL =
            "com.dragon.read.component.shortvideo.impl.settings.ITTPlayerViewConfig$$Impl";
    private static final String CLS_CONFIG_BASE =
            "com.dragon.read.component.shortvideo.api.config.VideoTTPlayerViewConfig";

    private RenderPathHook() {
    }

    public static boolean install(XposedInterface xposed, ClassLoader cl) {
        boolean any = false;
        any |= installL0(xposed, cl);
        any |= installL1(xposed, cl);
        any |= installL2(xposed, cl);
        any |= installL3(xposed, cl);
        if (!any) {
            LogUtils.fail("Feature1: no render-path target resolved; feature disabled");
        }
        return any;
    }

    /**
     * L0 — 配置加载总入口（实机验证后新增）。
     *
     * <p>实机日志显示 L1/L2/L3 全部安装成功但<b>从未被调用</b>，说明渲染决策
     * 并不经由这些方法。追查源码后确认真正的决策链是：
     * <pre>
     * com.dragon.read.component.shortvideo.impl.v2.view.adapter.e#Y2(...)
     *     │  第 419 行：
     *     │  if (VideoTTPlayerViewConfig.a.a().enableTTPlayerView
     *     │      || !VideoTTPlayerViewConfig.a.a().onlySurfaceView) { z6 = false; }
     *     └ VideoTTPlayerViewConfig.a.a()
     *           └ Lazy 单例，仅在首次访问时解析一次
     *               └ fq4.a.a(defaultObj, "video_ttplayer_view", true)   ← ★ 配置解析总入口
     * </pre>
     *
     * <p>{@code fq4.a.a} 是所有 ssconfig 的统一解析函数；hook 它即可在配置对象
     * 生成的那一刻改写字段，比在 {@code getConfig()} 上打补丁更靠前、更稳。
     * 稳定性：MEDIUM（类名混淆，但签名 {@code (Object,String,boolean)} 明确，
     * 且只改目标配置对象的字段，其他配置原样返回）。
     */
    private static boolean installL0(XposedInterface xposed, ClassLoader cl) {
        Class<?> cls = ReflectUtils.findClass("fq4.a", cl);
        if (cls == null) {
            LogUtils.d("Feature1 L0 fq4.a 不存在");
            return false;
        }
        Method m = ReflectUtils.findMethod(cls, "a", Object.class, String.class, boolean.class);
        if (m == null) {
            LogUtils.d("Feature1 L0 fq4.a#a(Object,String,boolean) 未找到");
            return false;
        }
        Class<?> cfgBase = ReflectUtils.findClass(CLS_CONFIG_BASE, cl);
        try {
            xposed.hook(m).setId("f1-l0").intercept(chain -> {
                Object result = chain.proceed();
                if (result == null || cfgBase == null || !cfgBase.isInstance(result)) {
                    return result;
                }
                boolean a = ReflectUtils.setFieldValue(result, "enableTTPlayerView", Boolean.TRUE);
                boolean b = ReflectUtils.setFieldValue(result, "onlySurfaceView", Boolean.FALSE);
                if (a || b) {
                    LogUtils.call("L0 配置已改写 enableTTPlayerView=" + a + " onlySurfaceView=" + b
                            + " 类=" + result.getClass().getName());
                }
                return result;
            });
            LogUtils.resolve("Feature1 L0 fq4.a#a(Object,String,boolean) stability=MEDIUM");
            return true;
        } catch (Throwable tr) {
            LogUtils.fail("Feature1 L0 hook 失败", tr);
            return false;
        }
    }

    /**
     * L1 — the primary defence: force isEnableSurfaceView() to return false.
     * Stability: HIGH (small static method with a stable name and signature).
     */
    private static boolean installL1(XposedInterface xposed, ClassLoader cl) {
        Class<?> helper = ReflectUtils.findClass(CLS_HELPER, cl);
        if (helper == null) {
            return false;
        }
        Method target = ReflectUtils.findMethod(helper, "isEnableSurfaceView", android.content.Context.class);
        if (target == null) {
            // Signature changed; do not guess. Fall through to L2/L3.
            return false;
        }
        try {
            xposed.hook(target).setId("f1-l1").intercept(chain -> {
                LogUtils.call("L1 isEnableSurfaceView -> false (force texture path)");
                return Boolean.FALSE;
            });
            LogUtils.resolve("Feature1 L1 " + CLS_HELPER + "#isEnableSurfaceView(Context) stability=HIGH");
            return true;
        } catch (Throwable tr) {
            LogUtils.fail("Feature1 L1 hook failed", tr);
            return false;
        }
    }

    /**
     * L2 — second defence: the render type argument.
     * {@code TTPlayerView(Context,int,float,int)} → init() reads i:
     * 1 = SurfaceView, 2 = TTTextureView. Force 2.
     * Stability: HIGH (public constructor).
     */
    private static boolean installL2(XposedInterface xposed, ClassLoader cl) {
        Class<?> view = ReflectUtils.findClass(CLS_VIEW, cl);
        if (view == null) {
            return false;
        }
        Constructor<?> ctor = null;
        try {
            ctor = view.getDeclaredConstructor(
                    android.content.Context.class, int.class, float.class, int.class);
        } catch (Throwable tr) {
            LogUtils.d("Feature1 L2 ctor (Context,int,float,int) not present");
        }
        if (ctor == null) {
            // Try the LifeCycleCallback overload as a fallback.
            try {
                Class<?> cb = ReflectUtils.findClass("com.ss.ttm.player.TTAVPlayerView$LifeCycleCallback", cl);
                if (cb != null) {
                    for (Constructor<?> c : view.getDeclaredConstructors()) {
                        Class<?>[] ps = c.getParameterTypes();
                        if (ps.length == 5 && ps[1] == cb && ps[2] == int.class) {
                            ctor = c;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (ctor == null) {
            return false;
        }
        final Constructor<?> target = ctor;
        try {
            xposed.hook(target).setId("f1-l2").intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                // the render-type argument is the first int parameter
                Class<?>[] ps = target.getParameterTypes();
                for (int i = 0; i < ps.length && i < args.length; i++) {
                    if (ps[i] == int.class && ((Integer) args[i]) == 1) {
                        args[i] = 2; // TTTextureView
                        LogUtils.call("L2 render type 1 -> 2 (TTTextureView)");
                        break;
                    }
                }
                return chain.proceed(args);
            });
            LogUtils.resolve("Feature1 L2 " + CLS_VIEW + "#<init> stability=HIGH");
            return true;
        } catch (Throwable tr) {
            LogUtils.fail("Feature1 L2 hook failed", tr);
            return false;
        }
    }

    /**
     * L3 — the server-driven config: enable TTPlayerView and stop forcing
     * SurfaceView-only. Fields are final, so we rewrite the instance via
     * reflection right after getConfig() returns.
     * Stability: MEDIUM (interface + impl pair).
     */
    private static boolean installL3(XposedInterface xposed, ClassLoader cl) {
        Class<?> impl = ReflectUtils.findClass(CLS_CONFIG_IMPL, cl);
        if (impl == null) {
            LogUtils.d("Feature1 L3 impl class absent");
            return false;
        }
        Method getConfig = ReflectUtils.findMethod(impl, "getConfig");
        if (getConfig == null) {
            return false;
        }
        Class<?> base = ReflectUtils.findClass(CLS_CONFIG_BASE, cl);
        if (base == null) {
            base = getConfig.getReturnType();
        }
        final Class<?> baseCls = base;
        try {
            xposed.hook(getConfig).setId("f1-l3").intercept(chain -> {
                Object cfg = chain.proceed();
                if (cfg != null) {
                    // enableTTPlayerView = true, onlySurfaceView = false
                    boolean a = ReflectUtils.setFieldValue(cfg, "enableTTPlayerView", Boolean.TRUE);
                    boolean b = ReflectUtils.setFieldValue(cfg, "onlySurfaceView", Boolean.FALSE);
                    if (a || b) {
                        LogUtils.call("L3 config patched enableTTPlayerView=" + a + " onlySurfaceView=" + b);
                    }
                }
                return cfg;
            });
            LogUtils.resolve("Feature1 L3 " + CLS_CONFIG_IMPL + "#getConfig stability=MEDIUM");
            return true;
        } catch (Throwable tr) {
            LogUtils.fail("Feature1 L3 hook failed", tr);
            return false;
        }
    }
}
