package com.zcode.hongguofix;

import de.robv.android.xposed.XC_MethodHook;

/** Hook 2：j65.g.a(Context, Boolean) 工厂调用，入参强制 true（走纹理分支）。 */
public final class HookFactoryCall extends XC_MethodHook {
    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (!Boolean.TRUE.equals(param.args[1])) {
            param.args[1] = Boolean.TRUE;
        }
    }
}
