package com.zcode.hongguofix;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Hook 1：j65.g 工厂所有构造函数之后，强制内部纹理标志 c=true。 */
public final class HookFactoryCtor extends XC_MethodHook {
    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        try {
            XposedHelpers.setBooleanField(param.thisObject, "c", true);
        } catch (Throwable t) {
            XposedBridge.log("HongGuoTexFix set c failed: " + t);
        }
    }
}
