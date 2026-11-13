package com.hyperosmod.xposed;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import com.hyperosmod.Keys;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * SystemUI 侧的通知图标同步（兜底）。
 *
 * <p>只在目标应用进程里替换 {@code setSmallIcon} 还不够：MIUI / HyperOS 的通知面板左侧
 * 显示的是「应用图标」，前台服务通知的小图标有时还会被系统换成应用图标，这两条路径都不经过
 * 应用自己的 Notification.Builder，拿到的是 {@code <application>} 上的原始图标。</p>
 *
 * <p>所以这里在 SystemUI 里做三件事，全部只作用于勾选过的包：</p>
 * <ol>
 *   <li>把 PackageManager / ApplicationInfo 返回的「应用图标」换成当前桌面别名图标；</li>
 *   <li>如果通知小图标在到达视图之前已经被系统换掉，则在状态栏图标视图上再改一次。</li>
 * </ol>
 */
final class NotifyAppIconHook {
    private static final String LOG_TAG = "notify-icon: ";

    /** 解析桌面图标时会用到 PackageManager 自身，这里是防递归的闸门。 */
    private static final ThreadLocal<Boolean> RESOLVING = new ThreadLocal<>();

    private final XposedInterface xposed;
    private final ClassLoader classLoader;

    NotifyAppIconHook(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        try {
            hookItemIcon();
        } catch (Throwable t) {
            Logs.e(LOG_TAG + "hook PackageItemInfo#loadIcon 失败", t);
        }
        try {
            hookUnbadgedIcon();
        } catch (Throwable t) {
            Logs.e(LOG_TAG + "hook ApplicationInfo#loadUnbadgedIcon 失败", t);
        }
        try {
            hookPackageManager();
        } catch (Throwable t) {
            Logs.e(LOG_TAG + "hook PackageManager#getApplicationIcon 失败", t);
        }
        try {
            hookStatusBarIconView();
        } catch (Throwable t) {
            Logs.e(LOG_TAG + "hook StatusBarIconView 失败", t);
        }
    }

    // ==================================================================================
    // 应用图标来源
    // ==================================================================================

    private void hookItemIcon() {
        Class<?> type = Reflect.findClass(classLoader, "android.content.pm.PackageItemInfo");
        Method method = Reflect.findMethod(type, "loadIcon", PackageManager.class);
        if (method == null) {
            Logs.w(LOG_TAG + "PackageItemInfo#loadIcon 不存在", null);
            return;
        }
        hookIconMethod(method);
        Logs.i(LOG_TAG + "已 hook PackageItemInfo#loadIcon");
    }

    private void hookUnbadgedIcon() {
        Class<?> type = Reflect.findClass(classLoader, "android.content.pm.ApplicationInfo");
        Method method = Reflect.findMethod(type, "loadUnbadgedIcon", PackageManager.class);
        if (method == null) {
            Logs.w(LOG_TAG + "ApplicationInfo#loadUnbadgedIcon 不存在，跳过", null);
            return;
        }
        hookIconMethod(method);
        Logs.i(LOG_TAG + "已 hook ApplicationInfo#loadUnbadgedIcon");
    }

    private void hookPackageManager() {
        Class<?> type = Reflect.findClass(classLoader, "android.app.ApplicationPackageManager");
        if (type == null) {
            Logs.w(LOG_TAG + "ApplicationPackageManager 不存在，跳过", null);
            return;
        }
        int hooked = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!"getApplicationIcon".equals(method.getName())) {
                continue;
            }
            if (method.getParameterCount() != 1) {
                continue;
            }
            Class<?> param = method.getParameterTypes()[0];
            if (param != String.class && !param.getName().equals("android.content.pm.ApplicationInfo")) {
                continue;
            }
            method.setAccessible(true);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Drawable replacement = replacementFor(packageOf(chain.getArg(0)));
                        if (replacement != null) {
                            return replacement;
                        }
                        return chain.proceed();
                    });
            hooked++;
        }
        Logs.i(LOG_TAG + "已 hook " + hooked + " 个 getApplicationIcon");
    }

    private void hookIconMethod(Method method) {
        method.setAccessible(true);
        xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Drawable replacement = replacementFor(packageOf(chain.getThisObject()));
                    if (replacement != null) {
                        return replacement;
                    }
                    return chain.proceed();
                });
    }

    private static String packageOf(Object item) {
        if (item instanceof String) {
            return (String) item;
        }
        if (item == null) {
            return null;
        }
        Object value = Reflect.getField(item, "packageName");
        if (value == null) {
            // StatusBarIcon 里这个字段叫 pkg。
            value = Reflect.getField(item, "pkg");
        }
        return value instanceof String ? (String) value : null;
    }

    /** 目标包返回当前桌面图标，其余一律返回 null（走原逻辑）。 */
    private static Drawable replacementFor(String packageName) {
        if (packageName == null || Boolean.TRUE.equals(RESOLVING.get())) {
            return null;
        }
        if (!HookPrefs.getBoolean(Keys.NOTIFY_ICON_ENABLED, false)
                || !HookPrefs.getStringSet(Keys.NOTIFY_ICON_TARGETS).contains(packageName)) {
            return null;
        }
        RESOLVING.set(Boolean.TRUE);
        try {
            PackageManager pm = LauncherIconProvider.packageManager();
            return LauncherIconProvider.launcherDrawable(pm, packageName);
        } finally {
            RESOLVING.remove();
        }
    }

    // ==================================================================================
    // 通知小图标兜底
    // ==================================================================================

    /**
     * 状态栏通知图标最终都经由 {@code StatusBarIconView#set(StatusBarIcon)} 之类的方法落地，
     * 不依赖具体方法名：扫描整个类里「单参数、参数类型名含 StatusBarIcon」的方法并在调用前
     * 改写 {@code pkg} 属于目标包的那份图标的 {@code icon} 字段。
     */
    private void hookStatusBarIconView() {
        Class<?> type = Reflect.findClass(classLoader,
                "com.android.systemui.statusbar.StatusBarIconView");
        if (type == null) {
            type = Reflect.findClass(classLoader,
                    "com.android.systemui.statusbar.views.MiuiStatusBarIconView");
        }
        if (type == null) {
            Logs.w(LOG_TAG + "找不到 StatusBarIconView，跳过兜底", null);
            return;
        }
        int hooked = 0;
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 1 || !parameters[0].getName().endsWith("StatusBarIcon")) {
                continue;
            }
            try {
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                rewriteStatusBarIcon(chain.getArg(0));
                            } catch (Throwable t) {
                                Logs.w(LOG_TAG + "改写状态栏通知图标失败", t);
                            }
                            return chain.proceed();
                        });
                hooked++;
            } catch (Throwable t) {
                Logs.w(LOG_TAG + "hook " + method.getName() + " 失败", t);
            }
        }
        Logs.i(LOG_TAG + "状态栏图标兜底 hook 数=" + hooked + " 类=" + type.getName());
    }

    private static void rewriteStatusBarIcon(Object statusBarIcon) {
        if (statusBarIcon == null) {
            return;
        }
        String packageName = packageOf(statusBarIcon);
        if (!HookPrefs.getBoolean(Keys.NOTIFY_ICON_ENABLED, false)
                || packageName == null
                || !HookPrefs.getStringSet(Keys.NOTIFY_ICON_TARGETS).contains(packageName)) {
            return;
        }
        PackageManager pm = LauncherIconProvider.packageManager();
        android.graphics.drawable.Icon icon = LauncherIconProvider.notificationIcon(pm, packageName);
        if (icon == null) {
            return;
        }
        if (!Reflect.setField(statusBarIcon, "icon", icon)) {
            Logs.w(LOG_TAG + "写入 StatusBarIcon.icon 失败", null);
        }
    }
}
