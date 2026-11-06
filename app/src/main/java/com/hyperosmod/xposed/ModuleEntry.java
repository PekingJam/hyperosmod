package com.hyperosmod.xposed;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;

/**
 * libxposed API 102 entry point.
 *
 * <p>The module declares only static scopes ({@code system} and
 * {@code com.android.systemui}), so this entry is loaded in exactly those processes.</p>
 */
public final class ModuleEntry extends XposedModule {
    private String processName = "";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        Logs.attach(this);
        HookPrefs.init(this);
        processName = param.getProcessName();
        Logs.i("Loaded in " + processName + " (systemServer=" + param.isSystemServer() + ")");
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        try {
            new UpdateGuard(this, param.getClassLoader()).install();
        } catch (Throwable t) {
            Logs.e("Failed to install update guard", t);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) {
            return;
        }
        if (!"com.android.systemui".equals(processName)
                || !"com.android.systemui".equals(param.getPackageName())) {
            return;
        }
        try {
            new SystemUiHooks(this, param.getClassLoader()).install();
        } catch (Throwable t) {
            Logs.e("Failed to install SystemUI hooks", t);
        }
    }
}
