package com.zcode.hongguofix;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * Unified logging with a single tag so users can filter with:
 *   adb logcat | findstr HongguoTexFix
 */
public final class LogUtils {

    public static final String TAG = "HongguoTexFix";

    /** DEBUG=false keeps release logs to the minimum. */
    public static volatile boolean DEBUG = true;

    private static XposedInterface sXposed;

    private LogUtils() {
    }

    public static void bind(XposedInterface xposed) {
        sXposed = xposed;
    }

    private static void emit(int priority, String msg, Throwable tr) {
        XposedInterface x = sXposed;
        if (x != null) {
            try {
                x.log(priority, TAG, msg, tr);
                return;
            } catch (Throwable ignored) {
                // fall through to android.util.Log
            }
        }
        try {
            if (tr != null) {
                Log.println(priority, TAG, msg + "\n" + Log.getStackTraceString(tr));
            } else {
                Log.println(priority, TAG, msg);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void i(String msg) {
        emit(Log.INFO, msg, null);
    }

    public static void d(String msg) {
        if (DEBUG) {
            emit(Log.DEBUG, msg, null);
        }
    }

    public static void w(String msg) {
        emit(Log.WARN, msg, null);
    }

    public static void e(String msg) {
        emit(Log.ERROR, msg, null);
    }

    public static void e(String msg, Throwable tr) {
        emit(Log.ERROR, msg, tr);
    }

    /** Standard prefixes required by the module spec. */
    public static void init(String msg) {
        i("[INIT] " + msg);
    }

    public static void process(String msg) {
        i("[PROCESS] " + msg);
    }

    public static void resolve(String msg) {
        i("[RESOLVE] " + msg);
    }

    public static void hook(String msg) {
        i("[HOOK] " + msg);
    }

    public static void call(String msg) {
        d("[CALL] " + msg);
    }

    public static void cache(String msg) {
        d("[CACHE] " + msg);
    }

    public static void warn(String msg) {
        w("[WARN] " + msg);
    }

    public static void fail(String msg) {
        e("[FAIL] " + msg);
    }

    public static void fail(String msg, Throwable tr) {
        e("[FAIL] " + msg, tr);
    }
}
