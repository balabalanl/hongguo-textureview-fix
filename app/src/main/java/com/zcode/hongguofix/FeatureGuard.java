package com.zcode.hongguofix;

/**
 * Isolates features from each other. One failing feature must never take down
 * the others or (worse) the target app.
 */
public final class FeatureGuard {

    public interface Task {
        /** @return true when the feature installed successfully. */
        boolean install() throws Throwable;
    }

    private FeatureGuard() {
    }

    public static boolean install(String feature, Task task) {
        try {
            boolean ok = task.install();
            if (ok) {
                LogUtils.hook(feature + " installed");
            } else {
                LogUtils.fail(feature + " target not found / rejected -> disabled (app left untouched)");
            }
            return ok;
        } catch (Throwable tr) {
            // Never rethrow into the target process.
            LogUtils.fail(feature + " install threw: " + tr.getClass().getName() + ": " + tr.getMessage(), tr);
            return false;
        }
    }
}
