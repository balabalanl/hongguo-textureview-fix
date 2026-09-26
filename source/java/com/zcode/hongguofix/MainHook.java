package com.zcode.hongguofix;

import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 红果免费短剧 (com.phoenix.read) 灰屏修复 v1.1 — 四层防御，追求跨版本生效
 *
 * 原理：ColorOS 16 (Ace 2 Pro) 的 DPU 在"视频独占硬件叠加层"场景下有状态机 bug，
 * 整屏偶发发灰。本模块强制播放器全部渲染视图走 TextureView（抖音同款路径），
 * 视频帧经 GPU 合成，不再独占 DPU 视频叠加管线。
 *
 * 分层（由稳定到易变）：
 *  L1 com.ss.ttm.player.TTPlayerViewHelper.isEnableSurfaceView → false
 *     字节 SDK 自带的降级开关（原本为 OPlus+MTK 设备规避同类 DPU/OSIE 问题而设，
 *     触发后 TTPlayerView 内部自动把 SurfaceView 换成 TTTextureView）。
 *     SDK 类名不混淆，跨版本/跨 App 存活率最高。【主防线】
 *  L2 com.ss.ttm.player.TTPlayerView.<init>(Context,int,float,int)
 *     渲染类型参数强制 2=TTTextureView。SDK 类名稳定。【二防线】
 *  L3 ITTPlayerViewConfig$$Impl.getConfig() 返回对象强制
 *     enableTTPlayerView=true / onlySurfaceView=false，
 *     保证工厂永远走 TTPlayerView 分支（生成类名跟随稳定接口名）。【三防线】
 *  L4 旧版混淆名钩子（j65.g / j65.h，仅对 7.3.9.32 有效），
 *     App 更新后自动失配，仅作当前版本冗余。【四防线】
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "HongGuoTexFix";
    private static final String TARGET_PKG = "com.phoenix.read";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!TARGET_PKG.equals(lpp.packageName)) {
            return;
        }
        ClassLoader cl = lpp.classLoader;
        StringBuilder report = new StringBuilder(TAG + ": ");

        // L1【主防线】SDK 自带的 SurfaceView 降级开关 → 永远返回 false
        try {
            XposedHelpers.findAndHookMethod("com.ss.ttm.player.TTPlayerViewHelper",
                    cl, "isEnableSurfaceView", Context.class, new HookKillSurfaceView());
            report.append("L1(ok) ");
        } catch (Throwable t) {
            report.append("L1(FAIL ").append(t.getClass().getSimpleName()).append(") ");
        }

        // L2 TTPlayerView 渲染类型参数强制 2
        try {
            XposedHelpers.findAndHookConstructor("com.ss.ttm.player.TTPlayerView", cl,
                    Context.class, int.class, float.class, int.class, new HookForceViewType());
            report.append("L2(ok) ");
        } catch (Throwable t) {
            report.append("L2(FAIL ").append(t.getClass().getSimpleName()).append(") ");
        }

        // L3 配置单例强制 enableTTPlayerView=true / onlySurfaceView=false
        try {
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.shortvideo.impl.settings.ITTPlayerViewConfig$$Impl",
                    cl, "getConfig", new HookConfigForce());
            report.append("L3(ok) ");
        } catch (Throwable t) {
            report.append("L3(FAIL ").append(t.getClass().getSimpleName()).append(") ");
        }

        // L4 旧版混淆名钩子（7.3.9.32 专用，未来版本自动失配）
        try {
            Class<?> gClass = XposedHelpers.findClass("j65.g", cl);
            java.lang.reflect.Constructor<?>[] ctors = gClass.getDeclaredConstructors();
            for (int i = 0; i < ctors.length; i++) {
                XposedBridge.hookMethod(ctors[i], new HookFactoryCtor());
            }
            XposedHelpers.findAndHookMethod(gClass, "a",
                    Context.class, Boolean.class, new HookFactoryCall());
            report.append("L4a(ok) ");
        } catch (Throwable t) {
            report.append("L4a(miss) ");
        }
        try {
            XposedHelpers.findAndHookConstructor("j65.h", cl,
                    Context.class, int.class, new HookHolderCtor());
            report.append("L4b(ok)");
        } catch (Throwable t) {
            report.append("L4b(miss)");
        }

        XposedBridge.log(report.toString());
    }
}
