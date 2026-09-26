package com.zcode.hongguofix;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** L3：ITTPlayerViewConfig$$Impl.getConfig() 返回的配置单例强制
 *  enableTTPlayerView=true / onlySurfaceView=false，让工厂永远走 TTPlayerView 分支。 */
public final class HookConfigForce extends XC_MethodHook {
    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        Object cfg = param.getResult();
        if (cfg != null) {
            try {
                XposedHelpers.setBooleanField(cfg, "enableTTPlayerView", true);
                XposedHelpers.setBooleanField(cfg, "onlySurfaceView", false);
            } catch (Throwable ignored) {
                // 字段名变化时静默跳过，由 L1/L2 兜底
            }
        }
    }
}
