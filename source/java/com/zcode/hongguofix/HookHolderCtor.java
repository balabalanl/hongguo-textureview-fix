package com.zcode.hongguofix;

import de.robv.android.xposed.XC_MethodHook;

/** Hook 3：j65.h(Context, int) 渲染类型强制 2 = TTTextureView。 */
public final class HookHolderCtor extends XC_MethodHook {
    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        Integer type = (Integer) param.args[1];
        if (type == null || type.intValue() != 2) {
            param.args[1] = Integer.valueOf(2);
        }
    }
}
