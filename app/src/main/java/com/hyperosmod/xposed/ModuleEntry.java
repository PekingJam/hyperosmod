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
    /** 一个进程里只装一次通知图标 hook（同一个进程可能对应多个包）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean notifyIconInstalled =
            new java.util.concurrent.atomic.AtomicBoolean();

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
        try {
            new FcmSystemHooks(this, param.getClassLoader()).install();
        } catch (Throwable t) {
            Logs.e("Failed to install FCM system hooks", t);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String packageName = param.getPackageName();
        // 通知图标同步要跑到每个目标应用自己的进程里，所以放在 firstPackage 判断之前。
        if (notifyIconInstalled.compareAndSet(false, true)
                && HookPrefs.getBoolean(com.hyperosmod.Keys.NOTIFY_ICON_ENABLED, false)
                && HookPrefs.getStringSet(com.hyperosmod.Keys.NOTIFY_ICON_TARGETS)
                .contains(packageName)) {
            try {
                new NotifyIconHook(this, param.getClassLoader()).install();
            } catch (Throwable t) {
                Logs.e("Failed to install notification-icon hooks", t);
            }
        }
        if (!param.isFirstPackage()) {
            return;
        }
        if ("com.android.systemui".equals(processName)
                && "com.android.systemui".equals(packageName)) {
            try {
                new SystemUiHooks(this, param.getClassLoader()).install();
            } catch (Throwable t) {
                Logs.e("Failed to install SystemUI hooks", t);
            }
            return;
        }
        if ("com.miui.home".equals(packageName)) {
            try {
                new GestureLineHook(this, param.getClassLoader()).install();
            } catch (Throwable t) {
                Logs.e("Failed to install gesture-line hooks", t);
            }
            return;
        }
        if (("com.mi.health".equals(packageName) || "com.xiaomi.wearable".equals(packageName))) {
            try {
                new MiFitnessHooks(this, param.getClassLoader()).install();
            } catch (Throwable t) {
                Logs.e("Failed to install Mi Fitness DND hooks", t);
            }
        }
        if (HookPrefs.getBoolean(com.hyperosmod.Keys.TELEPHONY_ENABLED, false)
                && HookPrefs.getStringSet(com.hyperosmod.Keys.TELEPHONY_TARGETS)
                .contains(packageName)) {
            try {
                new TelephonyHooks(this, param.getClassLoader()).install();
            } catch (Throwable t) {
                Logs.e("Failed to install telephony hooks", t);
            }
        }
        if ("com.miui.powerkeeper".equals(packageName)) {
            try {
                new FcmPowerkeeperHooks(this, param.getClassLoader()).install();
            } catch (Throwable t) {
                Logs.e("Failed to install FCM powerkeeper hooks", t);
            }
        }
    }
}
