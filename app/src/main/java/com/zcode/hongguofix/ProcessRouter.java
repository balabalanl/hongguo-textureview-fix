package com.zcode.hongguofix;

import android.app.Application;
import android.content.Context;

/**
 * Decides whether the current process is one we should touch at all.
 * The visual features only make sense in the main UI process.
 */
public final class ProcessRouter {

    public static final String TARGET_PACKAGE = "com.phoenix.read";

    private ProcessRouter() {
    }

    public static boolean isTargetPackage(String packageName) {
        return TARGET_PACKAGE.equals(packageName);
    }

    /**
     * The player / feed / floating window all live in the main process.
     * Satellite processes (:push, :minigame*, :sandboxed_process*) are skipped
     * so we never pay DexKit/UI cost there.
     */
    public static boolean isMainProcess(String processName, String packageName) {
        if (processName == null) {
            return false;
        }
        if (!isTargetPackage(packageName)) {
            return false;
        }
        return processName.equals(TARGET_PACKAGE);
    }

    /** True for a real, usable application context. */
    public static boolean isUsableContext(Context context) {
        return context != null && context.getApplicationContext() != null;
    }

    public static Application asApplication(Context context) {
        if (context == null) {
            return null;
        }
        Context app = context.getApplicationContext();
        return app instanceof Application ? (Application) app : null;
    }
}
