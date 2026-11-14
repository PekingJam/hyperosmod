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
    private static final java.util.concurrent.atomic.AtomicBoolean iconHooksInstalled =
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
        installNotifyIconHooks(param, packageName);
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

    /**
     * 通知图标同步。
     *
     * <p>不猜「哪个应用负责画图标」：只要进程被注入了（= 用户勾进了作用域）就装上图标来源
     * 改写，真正生效与否由目标包名名单在运行期判断。这样换 ROM、换 AOD 实现都不用再改代码。</p>
     *
     * <p>system_server 排除在外：那里的 Resources / PackageItemInfo 调用太密集，而且
     * 系统进程也不需要这个功能。</p>
     */
    private void installNotifyIconHooks(PackageReadyParam param, String packageName) {
        if ("android".equals(packageName) || "android".equals(processName)) {
            return;
        }
        if (!HookPrefs.getBoolean(com.hyperosmod.Keys.NOTIFY_ICON_ENABLED, false)) {
            return;
        }
        if (!iconHooksInstalled.compareAndSet(false, true)) {
            return;
        }
        Logs.i("notify-icon: 进程 " + processName + " 安装图标 hook");
        if (HookPrefs.getStringSet(com.hyperosmod.Keys.NOTIFY_ICON_TARGETS).contains(packageName)) {
            try {
                new NotifyIconHook(this, param.getClassLoader()).install();
            } catch (Throwable t) {
                Logs.e("Failed to install Notification.Builder hooks", t);
            }
        }
        try {
            new NotifyAppIconHook(this, param.getClassLoader()).install();
        } catch (Throwable t) {
            Logs.e("Failed to install app-icon hooks", t);
        }
        try {
            new AodIconHook(this, param.getClassLoader()).install();
        } catch (Throwable t) {
            Logs.e("Failed to install AOD view hooks", t);
        }
    }
}
