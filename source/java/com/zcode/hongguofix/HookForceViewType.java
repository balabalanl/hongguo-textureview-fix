package com.zcode.hongguofix;

import de.robv.android.xposed.XC_MethodHook;

/** L2：TTPlayerView(Context, int, float, int) 渲染类型强制 2 = TTTextureView。 */
public final class HookForceViewType extends XC_MethodHook {
    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        Integer type = (Integer) param.args[1];
        if (type == null || type.intValue() != 2) {
            param.args[1] = Integer.valueOf(2);
        }
    }
}
