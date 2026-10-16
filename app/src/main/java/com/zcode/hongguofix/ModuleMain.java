package com.zcode.hongguofix;

import androidx.annotation.NonNull;

import com.zcode.hongguofix.hooks.DoubleTapCommentHook;
import com.zcode.hongguofix.hooks.PendantBlocker;
import com.zcode.hongguofix.hooks.RenderPathHook;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Entry point for the 红果短剧 (com.phoenix.read) fix module.
 *
 * <p>Three independent features:
 * <ol>
 *   <li>Render-path fix (grey screen) — force the GPU-composited texture path.</li>
 *   <li>Double-tap opens comments instead of liking.</li>
 *   <li>In-player red-packet floating window becomes dismissible.</li>
 * </ol>
 *
 * <p>Every feature is isolated by FeatureGuard: one failing feature never
 * prevents the others from installing, and never crashes the target app.
 */
public class ModuleMain extends XposedModule {

    private static final String TARGET = "com.phoenix.read";

    /** 模块版本，与 build.gradle.kts 保持一致，便于日志核对。 */
    public static final String MODULE_VERSION = "2.0.0";

    private volatile boolean mInstalled;
    private volatile String mProcessName = "unknown";

    public ModuleMain() {
        super();
    }

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        LogUtils.bind(this);
        try {
            mProcessName = param.getProcessName();
        } catch (Throwable ignored) {
        }
        LogUtils.init("HongguoTexFix v" + MODULE_VERSION + " loaded process=" + mProcessName
                + " systemServer=" + param.isSystemServer()
                + " framework=" + safeFrameworkName()
                + " api=" + safeApiVersion());
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        LogUtils.bind(this);
        try {
            handlePackageReady(param);
        } catch (Throwable tr) {
            LogUtils.fail("onPackageReady crashed: " + tr.getClass().getName() + ": " + tr.getMessage(), tr);
        }
    }

    private void handlePackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        LogUtils.process("package=" + pkg + " process=" + mProcessName);

        if (!ProcessRouter.isTargetPackage(pkg)) {
            LogUtils.d("[PROCESS] not the target package, skipping");
            return;
        }
        if (!ProcessRouter.isMainProcess(mProcessName, pkg)) {
            LogUtils.d("[PROCESS] satellite process, skipping hook install");
            return;
        }
        if (mInstalled) {
            LogUtils.d("[PROCESS] already installed, skipping duplicate");
            return;
        }

        ClassLoader cl = param.getClassLoader();
        if (cl == null) {
            LogUtils.fail("classloader is null; cannot resolve targets");
            return;
        }

        LogUtils.init("target versionName=" + safeVersionName(param)
                + " android=" + android.os.Build.VERSION.SDK_INT
                + " brand=" + android.os.Build.BRAND
                + " hardware=" + android.os.Build.HARDWARE
                + " abi=" + (android.os.Build.SUPPORTED_ABIS.length > 0
                        ? android.os.Build.SUPPORTED_ABIS[0] : "unknown"));

        // ---- Feature 1: render path (grey screen) ----
        FeatureGuard.install("Feature1.renderPath", () -> RenderPathHook.install(this, cl));

        // ---- Feature 2: double-tap -> comments ----
        FeatureGuard.install("Feature2.doubleTapComment", () -> DoubleTapCommentHook.install(this, cl));

        // ---- Feature 3: 屏蔽红包悬浮窗（移植自白果助手 HidePendant，五层冗余） ----
        FeatureGuard.install("Feature3.pendantBlocker", () -> PendantBlocker.install(this, cl));

        mInstalled = true;
        LogUtils.init("all features processed");
    }

    private String safeFrameworkName() {
        try {
            return getFrameworkName() + "/" + getFrameworkVersion();
        } catch (Throwable tr) {
            return "unknown";
        }
    }

    private int safeApiVersion() {
        try {
            return getApiVersion();
        } catch (Throwable tr) {
            return -1;
        }
    }

    /** versionName is a hidden field at compile time; read it reflectively. */
    private static String safeVersionName(XposedModuleInterface.PackageReadyParam param) {
        try {
            Object info = param.getApplicationInfo();
            java.lang.reflect.Field f = android.content.pm.ApplicationInfo.class.getField("versionName");
            Object v = f.get(info);
            return v == null ? "unknown" : String.valueOf(v);
        } catch (Throwable tr) {
            return "unknown";
        }
    }
}