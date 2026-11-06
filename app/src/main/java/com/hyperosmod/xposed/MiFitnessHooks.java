package com.hyperosmod.xposed;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.hyperosmod.Keys;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Restores Mi Fitness' band &lt;-&gt; phone DND (zen mode) sync on Android 15+.
 *
 * <p>Mi Fitness gates the whole feature behind
 * {@code ZenUtils.isSupportZenMode()}, which unconditionally returns {@code false} when
 * {@code SDK_INT >= 35}. The official chain also registers its {@code zen_mode} content
 * observer before that gate is lifted, so the observer never starts. We therefore:</p>
 *
 * <ol>
 *     <li>force {@code isSupportZenMode} to {@code true};</li>
 *     <li>force the "sync enabled" preference reads to {@code true};</li>
 *     <li>swallow writes that would turn the preference off;</li>
 *     <li>after {@code FitnessApp.onCreate}, re-register the zen listener and pull the
 *     device-side rules once so band -&gt; phone works as well.</li>
 * </ol>
 *
 * <p>DND access for Mi Fitness is enforced by the system and cannot be granted by the
 * module; the manager app links to the system settings page instead.</p>
 *
 * <p>Implementation reference: MiFitnessAdAway (AGPL-3.0).</p>
 */
final class MiFitnessHooks {
    private static final String ZEN_UTILS =
            "com.xiaomi.fitness.devicesettings.utils.ZenUtils";
    private static final String DEVICE_PREFS =
            "com.xiaomi.fitness.devicesettings.utils.DeviceSettingsPreference";
    private static final String SYNC_HELPER =
            "com.xiaomi.fitness.devicesettings.common.zenmode.ZenModeSyncHelper";
    private static final String WEARABLE_MODEL =
            "com.xiaomi.fitness.device.manager.export.WearableDeviceModel";
    private static final String FITNESS_APP = "com.xiaomi.fitness.FitnessApp";

    private static final long DND_REREGISTER_DELAY_MS = 10_000L;

    private final XposedInterface xposed;
    private final ClassLoader classLoader;
    private volatile Context appContext;

    MiFitnessHooks(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        try {
            hookZenSupport();
        } catch (Throwable t) {
            Logs.e("DND: failed to hook isSupportZenMode", t);
        }
        try {
            hookZenReadSwitch();
        } catch (Throwable t) {
            Logs.e("DND: failed to hook isZenModeOpen(String)", t);
        }
        try {
            hookZenWriteSwitch();
        } catch (Throwable t) {
            Logs.e("DND: failed to hook isZenModeOpen(String, boolean)", t);
        }
        try {
            hookFitnessOnCreate();
        } catch (Throwable t) {
            Logs.e("DND: failed to hook FitnessApp.onCreate", t);
        }
    }

    private void hookZenSupport() {
        Class<?> zen = Reflect.findClass(classLoader, ZEN_UTILS);
        Method method = findMethod(zen, "isSupportZenMode", WEARABLE_MODEL);
        if (method == null) {
            Logs.w("DND: ZenUtils.isSupportZenMode not found", null);
            return;
        }
        xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (enabled()) {
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });
        Logs.i("DND: hooked ZenUtils.isSupportZenMode");
    }

    private void hookZenReadSwitch() {
        Class<?> pref = Reflect.findClass(classLoader, DEVICE_PREFS);
        Method method = findMethod(pref, "isZenModeOpen", String.class.getName());
        if (method == null) {
            Logs.w("DND: DeviceSettingsPreference.isZenModeOpen(String) not found", null);
            return;
        }
        xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (enabled()) {
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });
        Logs.i("DND: hooked DeviceSettingsPreference.isZenModeOpen(String)");
    }

    private void hookZenWriteSwitch() {
        Class<?> pref = Reflect.findClass(classLoader, DEVICE_PREFS);
        Method method = findMethod(pref, "isZenModeOpen",
                String.class.getName(), boolean.class.getName());
        if (method == null) {
            Logs.w("DND: DeviceSettingsPreference.isZenModeOpen(String, boolean) not found", null);
            return;
        }
        final Class<?> returnType = method.getReturnType();
        xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (enabled() && Boolean.FALSE.equals(chain.getArg(1))) {
                        // Swallow writes that try to turn the sync switch off. If this
                        // overload returns a value, report "still on" instead of null.
                        if (returnType == boolean.class) {
                            return Boolean.TRUE;
                        }
                        if (returnType == void.class) {
                            return null;
                        }
                    }
                    return chain.proceed();
                });
        Logs.i("DND: hooked DeviceSettingsPreference.isZenModeOpen(String, boolean)");
    }

    private void hookFitnessOnCreate() {
        Class<?> app = Reflect.findClass(classLoader, FITNESS_APP);
        Method onCreate = findMethod(app, "onCreate");
        if (onCreate == null) {
            Logs.w("DND: FitnessApp.onCreate not found", null);
            return;
        }
        xposed.hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof Context) {
                        appContext = (Context) self;
                    }
                    if (enabled()) {
                        scheduleDndReregister();
                    }
                    return result;
                });
        Logs.i("DND: hooked FitnessApp.onCreate");
    }

    private void scheduleDndReregister() {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!enabled()) {
                    return;
                }
                Context context = appContext;
                if (context == null) {
                    Logs.w("DND: application context unavailable", null);
                    return;
                }
                reregisterZenListener();
                pullDeviceZenRules(context);
                logDndPermission(context);
            }, DND_REREGISTER_DELAY_MS);
        } catch (Throwable t) {
            Logs.e("DND: unable to schedule listener re-registration", t);
        }
    }

    private void reregisterZenListener() {
        try {
            Class<?> zen = Class.forName(ZEN_UTILS, false, classLoader);
            Object instance = staticInstance(zen);
            if (instance == null) {
                return;
            }
            try {
                invoke(instance, "unRegisterZenListener");
            } catch (Throwable t) {
                // It is normal for the first registration to be missing/partial.
                Logs.i("DND: unregister skipped (" + t.getClass().getSimpleName() + ")");
            }
            invoke(instance, "registerZenListener");
            Logs.i("DND: zen listener re-registered");
        } catch (Throwable t) {
            Logs.e("DND: listener re-register failed", t);
        }
    }

    private void pullDeviceZenRules(Context context) {
        try {
            Class<?> helper = Class.forName(SYNC_HELPER, false, classLoader);
            Object instance = staticInstance(helper);
            if (instance == null) {
                return;
            }
            Method pull = findMethod(helper, "getDeviceZenRules", Context.class.getName());
            if (pull == null) {
                return;
            }
            pull.setAccessible(true);
            pull.invoke(instance, context);
            Logs.i("DND: pulled device zen rules");
        } catch (Throwable t) {
            Logs.e("DND: pull device zen rules failed", t);
        }
    }

    private void logDndPermission(Context context) {
        try {
            NotificationManager nm = (NotificationManager) context
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            boolean granted = nm != null && nm.isNotificationPolicyAccessGranted();
            Logs.i("DND: notification policy access granted=" + granted);
            if (!granted) {
                Logs.w("DND: grant DND access to Mi Fitness in system settings", null);
            }
        } catch (Throwable t) {
            Logs.w("DND: permission check failed", t);
        }
    }

    private boolean enabled() {
        return HookPrefs.getBoolean(Keys.RESTORE_DND_SYNC, false);
    }

    private static Object staticInstance(Class<?> type) throws Exception {
        Field field;
        try {
            field = type.getField("INSTANCE");
        } catch (NoSuchFieldException e) {
            field = type.getDeclaredField("INSTANCE");
        }
        field.setAccessible(true);
        return field.get(null);
    }

    private static Object invoke(Object target, String name) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0) {
                    continue;
                }
                method.setAccessible(true);
                return method.invoke(target);
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    /**
     * Finds a method by parameter type names. This avoids initializing the target class
     * (Mi Fitness' preference classes touch Application context in their static init).
     */
    private static Method findMethod(Class<?> type, String name, String... parameterTypeNames) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() != parameterTypeNames.length) {
                    continue;
                }
                Class<?>[] actual = method.getParameterTypes();
                boolean matches = true;
                for (int i = 0; i < actual.length; i++) {
                    if (!actual[i].getName().equals(parameterTypeNames[i])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
