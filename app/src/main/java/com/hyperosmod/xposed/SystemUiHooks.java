package com.hyperosmod.xposed;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.hyperosmod.Keys;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;

/**
 * SystemUI hooks.
 *
 * <p>Hooks are installed once and consult the preferences on every call, so toggling a
 * switch takes effect without restarting SystemUI. The theme overlay is refreshed by
 * asking the captured controller to re-evaluate the system theme when the color changes.</p>
 */
final class SystemUiHooks {
    private final XposedInterface xposed;
    private final ClassLoader classLoader;
    private final AtomicReference<Object> themeController = new AtomicReference<>();
    private SharedPreferences.OnSharedPreferenceChangeListener themeListener;

    SystemUiHooks(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        try {
            hookCanShowOnKeyguard();
        } catch (Throwable t) {
            Logs.e("Failed to hook lock-screen notification visibility", t);
        }
        try {
            hookKeepNotification();
        } catch (Throwable t) {
            Logs.e("Failed to hook keep-notification behaviour", t);
        }
        try {
            hookThemeOverlay();
        } catch (Throwable t) {
            Logs.e("Failed to hook theme overlays", t);
        }
        try {
            new DualSignalHook(xposed, classLoader).install();
        } catch (Throwable t) {
            Logs.e("Failed to install dual-row signal hooks", t);
        }
        try {
            new ClockSecondsHook(xposed, classLoader).install();
        } catch (Throwable t) {
            Logs.e("Failed to install clock-seconds hooks", t);
        }
        // 通知图标的应用图标改写由 ModuleEntry 统一装（它对每个注入进程都装一次）。
    }

    /**
     * HyperCeiler: NotificationShowOnKeyguard
     * {@code ExpandedNotification#canShowOnKeyguard()} returns whether a notification may
     * be shown on the lock screen. Only the packages picked in the whitelist are forced to
     * {@code true}; everything else keeps the system / app decision.
     */
    private void hookCanShowOnKeyguard() {
        Class<?> type = Reflect.findClass(classLoader,
                "com.android.systemui.statusbar.notification.ExpandedNotification");
        if (type == null) {
            Logs.w("ExpandedNotification not found; skip notification hook", null);
            return;
        }
        int hooked = 0;
        for (Method candidate : type.getDeclaredMethods()) {
            if (!"canShowOnKeyguard".equals(candidate.getName())
                    || candidate.getReturnType() != boolean.class) {
                continue;
            }
            candidate.setAccessible(true);
            xposed.hook(candidate)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (HookPrefs.getBoolean(Keys.KEYGUARD_NOTIFY_TARGETS_ENABLED, false)) {
                            java.util.Set<String> targets =
                                    HookPrefs.getStringSet(Keys.KEYGUARD_NOTIFY_TARGETS);
                            if (!targets.isEmpty()) {
                                String packageName = packageNameOf(chain.getThisObject());
                                if (packageName != null && targets.contains(packageName)) {
                                    logWhitelisted(packageName);
                                    return Boolean.TRUE;
                                }
                            }
                        }
                        return chain.proceed();
                    });
            hooked++;
        }
        if (hooked == 0) {
            Logs.w("ExpandedNotification#canShowOnKeyguard not found; skip", null);
        } else {
            Logs.i("Hooked ExpandedNotification#canShowOnKeyguard");
        }
    }

    /** 通知归属的包名；ExpandedNotification 继承 StatusBarNotification，直接转型最快。 */
    private static String packageNameOf(Object notification) {
        if (notification == null) {
            return null;
        }
        try {
            if (notification instanceof android.service.notification.StatusBarNotification) {
                return ((android.service.notification.StatusBarNotification) notification)
                        .getPackageName();
            }
        } catch (Throwable ignored) {
            // 走下面的反射兜底。
        }
        Object value = Reflect.call(notification, "getPackageName");
        return value instanceof String ? (String) value : null;
    }

    /** 同一包只打一行：锁屏每次都问一遍，热路径不要刷屏。 */
    private final java.util.Set<String> whitelistLogged =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void logWhitelisted(String packageName) {
        if (whitelistLogged.add(packageName)) {
            Logs.i("锁屏通知白名单命中：" + packageName);
        }
    }

    /**
     * HyperCeiler: KeepNotification
     * Clearing {@code mHasShownAfterUnlock} before the visibility decision makes SystemUI
     * treat the notification as never shown after unlock, so it survives the unlock.
     */
    private void hookKeepNotification() {
        Class<?> type = Reflect.findClass(classLoader,
                "com.android.systemui.statusbar.notification.interruption"
                        + ".KeyguardNotificationVisibilityProviderImpl");
        Method method = null;
        if (type != null) {
            for (Method candidate : type.getDeclaredMethods()) {
                if ("shouldHideNotification".equals(candidate.getName())
                        && candidate.getReturnType() == boolean.class
                        && candidate.getParameterCount() > 0) {
                    candidate.setAccessible(true);
                    method = candidate;
                    break;
                }
            }
        }
        if (method == null) {
            Logs.w("KeyguardNotificationVisibilityProviderImpl#shouldHideNotification not found; skip", null);
            return;
        }
        xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (HookPrefs.getBoolean(Keys.KEEP_NOTIFICATION, false)) {
                        Object entry = chain.getArg(0);
                        Object sbn = Reflect.getField(entry, "mSbn");
                        if (sbn != null) {
                            Reflect.setField(sbn, "mHasShownAfterUnlock", Boolean.FALSE);
                        }
                    }
                    return chain.proceed();
                });
        Logs.i("Hooked KeyguardNotificationVisibilityProviderImpl#shouldHideNotification");
    }

    /**
     * HyperCeiler: MonetThemeOverlay
     * {@code ThemeOverlayController#createOverlays(int color)} builds the fabricated
     * Monet overlays from the wallpaper seed color. Replace the seed with the user color.
     */
    private void hookThemeOverlay() {
        Class<?> type = Reflect.findClass(classLoader,
                "com.android.systemui.theme.ThemeOverlayController");
        if (type == null) {
            Logs.w("ThemeOverlayController not found; skip theme hook", null);
            return;
        }

        // Capture the live controller so color changes can be applied immediately.
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            try {
                constructor.setAccessible(true);
                xposed.hook(constructor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object self = chain.getThisObject();
                            if (self != null) {
                                themeController.set(self);
                            }
                            return result;
                        });
            } catch (Throwable t) {
                Logs.w("Failed to hook a ThemeOverlayController constructor", t);
            }
        }

        Method method = Reflect.findMethod(type, "createOverlays", int.class);
        if (method == null) {
            for (Method candidate : type.getDeclaredMethods()) {
                if ("createOverlays".equals(candidate.getName())
                        && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0] == int.class) {
                    candidate.setAccessible(true);
                    method = candidate;
                    break;
                }
            }
        }
        if (method == null) {
            Logs.w("ThemeOverlayController#createOverlays(int) not found; skip", null);
            return;
        }
        xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (HookPrefs.getBoolean(Keys.CUSTOM_THEME_ENABLED, false)) {
                        int color = HookPrefs.getInt(Keys.CUSTOM_THEME_COLOR, Keys.DEFAULT_THEME_COLOR);
                        return chain.proceed(new Object[]{color});
                    }
                    return chain.proceed();
                });
        Logs.i("Hooked ThemeOverlayController#createOverlays(int)");

        registerThemeListener();
    }

    private void registerThemeListener() {
        SharedPreferences prefs = HookPrefs.raw();
        if (prefs == null) {
            Logs.w("Remote preferences unavailable; theme changes need a SystemUI restart", null);
            return;
        }
        themeListener = (sharedPreferences, key) -> {
            if (Keys.CUSTOM_THEME_COLOR.equals(key) || Keys.CUSTOM_THEME_ENABLED.equals(key)) {
                refreshTheme();
            }
        };
        try {
            prefs.registerOnSharedPreferenceChangeListener(themeListener);
        } catch (Throwable t) {
            themeListener = null;
            Logs.w("Unable to observe theme changes", t);
        }
    }

    private void refreshTheme() {
        Object controller = themeController.get();
        if (controller == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Reflect.call(controller, "reevaluateSystemTheme", Boolean.TRUE);
            } catch (Throwable t) {
                Logs.w("Failed to refresh theme overlays", t);
            }
        });
    }
}
