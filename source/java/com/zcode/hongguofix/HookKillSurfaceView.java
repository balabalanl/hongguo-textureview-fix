package com.zcode.hongguofix;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;

/** L1【主防线】：TTPlayerViewHelper.isEnableSurfaceView 永远返回 false，
 *  触发字节 SDK 内置的"设备不支持 SurfaceView"降级 → 自动改用 TTTextureView。 */
public final class HookKillSurfaceView extends XC_MethodHook {
    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        param.setResult(Boolean.FALSE);
    }
}
