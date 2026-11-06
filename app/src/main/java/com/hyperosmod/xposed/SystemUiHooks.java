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
    }

    /**
     * HyperCeiler: NotificationShowOnKeyguard
     * {@code ExpandedNotification#canShowOnKeyguard()} returns whether a notification may
     * be shown on the lock screen. Force it to {@code true}.
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
                        if (HookPrefs.getBoolean(Keys.UNLOCK_NOTIFICATION_RESTRICT, false)) {
                            return Boolean.TRUE;
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
