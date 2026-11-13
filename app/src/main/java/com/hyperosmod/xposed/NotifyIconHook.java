package com.hyperosmod.xposed;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;

import com.hyperosmod.Keys;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 目标应用进程内的通知图标同步。
 *
 * <p>应用换桌面图标时只切了 activity-alias，通知里 {@code setSmallIcon()} 用的还是清单里
 * 那个固定的 {@code R.drawable.notification}。这里在 {@link Notification.Builder} 的每个
 * {@code setSmallIcon} 重载之后把它改成本应用当前桌面图标的位图，于是状态栏、锁屏、通知
 * 面板三处的通知图标都跟着桌面图标走。</p>
 */
final class NotifyIconHook {
    private static final String LOG_TAG = "notify-icon: ";

    /** 防止在 hook 里再调 setSmallIcon 时无限递归。 */
    private static final ThreadLocal<Boolean> REPLACING = new ThreadLocal<>();

    private final XposedInterface xposed;
    private final ClassLoader classLoader;

    NotifyIconHook(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        Class<?> builder = Reflect.findClass(classLoader, "android.app.Notification$Builder");
        if (builder == null) {
            Logs.w(LOG_TAG + "找不到 Notification$Builder", null);
            return;
        }
        int hooked = 0;
        for (Method method : builder.getDeclaredMethods()) {
            if (!"setSmallIcon".equals(method.getName())) {
                continue;
            }
            try {
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                apply(chain.getThisObject());
                            } catch (Throwable t) {
                                Logs.w(LOG_TAG + "替换通知小图标失败", t);
                            }
                            return result;
                        });
                hooked++;
            } catch (Throwable t) {
                Logs.w(LOG_TAG + "hook " + method.getName() + " 失败", t);
            }
        }
        if (hooked == 0) {
            Logs.w(LOG_TAG + "没有 hook 到任何 setSmallIcon 重载", null);
        } else {
            Logs.i(LOG_TAG + "已 hook " + hooked + " 个 setSmallIcon 重载");
        }
    }

    @SuppressWarnings("unchecked")
    private void apply(Object builder) {
        if (builder == null || Boolean.TRUE.equals(REPLACING.get())) {
            return;
        }
        Context context = contextOf(builder);
        if (context == null) {
            return;
        }
        String packageName = context.getPackageName();
        if (packageName == null || !HookPrefs.getStringSet(Keys.NOTIFY_ICON_TARGETS).contains(packageName)) {
            return;
        }
        PackageManager pm = context.getPackageManager();
        Icon icon = LauncherIconProvider.notificationIcon(pm, packageName);
        if (icon == null) {
            return;
        }
        REPLACING.set(Boolean.TRUE);
        try {
            ((Notification.Builder) builder).setSmallIcon(icon);
        } finally {
            REPLACING.remove();
        }
    }

    private static Context contextOf(Object builder) {
        Object context = Reflect.getField(builder, "mContext");
        if (context instanceof Context) {
            return (Context) context;
        }
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
            Object application = threadClass.getMethod("getApplication").invoke(thread);
            if (application instanceof Context) {
                return (Context) application;
            }
        } catch (Throwable ignored) {
            // 拿不到 Context 就放弃这一条通知。
        }
        return null;
    }
}
