package com.hyperosmod.xposed;

import com.hyperosmod.Keys;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Keeps GMS out of HyperOS power-keeper's restricted lists so the FCM connection is not
 * killed in the background.
 *
 * <p>Adapted from fcmfix's PowerkeeperFix (GPL-3.0).</p>
 */
final class FcmPowerkeeperHooks {
    private static final String GMS = "com.google.android.gms";
    private static final String GMS_EXT = "com.google.android.ext.services";

    private final XposedInterface xposed;
    private final ClassLoader classLoader;

    FcmPowerkeeperHooks(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        try {
            setGlobalFlag();
        } catch (Throwable t) {
            Logs.e("FCM: failed to set MilletConfig.isGlobal", t);
        }
        try {
            hookGmsControl();
        } catch (Throwable t) {
            Logs.e("FCM: failed to hook powerkeeper gms_control", t);
        }
        try {
            hookPolicyConstructors();
        } catch (Throwable t) {
            Logs.e("FCM: failed to hook MilletPolicy", t);
        }
    }

    private void setGlobalFlag() {
        if (!enabled()) {
            return;
        }
        Class<?> config = Reflect.findClass(classLoader,
                "com.miui.powerkeeper.millet.MilletConfig");
        if (config == null) {
            return;
        }
        Field field = Reflect.findField(config, "isGlobal");
        if (field != null) {
            try {
                field.setAccessible(true);
                field.setBoolean(null, true);
                Logs.i("FCM: set MilletConfig.isGlobal=true");
            } catch (Throwable t) {
                Logs.w("FCM: unable to set MilletConfig.isGlobal", t);
            }
        }
    }

    private void hookGmsControl() {
        Class<?> misc = Reflect.findClass(classLoader,
                "com.miui.powerkeeper.provider.SimpleSettings$Misc");
        if (misc == null) {
            return;
        }
        for (Method method : misc.getDeclaredMethods()) {
            if (!"getBoolean".equals(method.getName())
                    || method.getParameterCount() != 3) {
                continue;
            }
            method.setAccessible(true);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (enabled()
                                && chain.getArg(1) instanceof String
                                && "gms_control".equals(chain.getArg(1))) {
                            Logs.i("FCM: disable powerkeeper gms_control");
                            return Boolean.FALSE;
                        }
                        return chain.proceed();
                    });
            Logs.i("FCM: hooked SimpleSettings.Misc.getBoolean");
        }
    }

    private void hookPolicyConstructors() {
        Class<?> policy = Reflect.findClass(classLoader,
                "com.miui.powerkeeper.millet.MilletPolicy");
        if (policy == null) {
            return;
        }
        for (Constructor<?> constructor : policy.getDeclaredConstructors()) {
            constructor.setAccessible(true);
            xposed.hook(constructor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (enabled()) {
                            modifyPolicy(chain.getThisObject());
                        }
                        return result;
                    });
        }
        Logs.i("FCM: hooked MilletPolicy constructors");
    }

    private void modifyPolicy(Object policy) {
        removeFromList(policy, "mSystemBlackList", GMS);
        removeFromList(policy, "whiteApps", GMS);
        removeFromList(policy, "whiteApps", GMS_EXT);
        addToList(policy, "mDataWhiteList", GMS);
    }

    private static void removeFromList(Object target, String fieldName, String value) {
        try {
            Field field = Reflect.findField(target.getClass(), fieldName);
            if (field == null || !(field.get(target) instanceof List<?> list)) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> mutable = (List<Object>) list;
            if (mutable.remove(value)) {
                Logs.i("FCM: removed " + value + " from MilletPolicy." + fieldName);
            }
        } catch (Throwable ignored) {
            // Field layout differs; leave this list untouched.
        }
    }

    private static void addToList(Object target, String fieldName, String value) {
        try {
            Field field = Reflect.findField(target.getClass(), fieldName);
            if (field == null || !(field.get(target) instanceof List<?> list)) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object> mutable = (List<Object>) list;
            if (!mutable.contains(value)) {
                mutable.add(value);
                Logs.i("FCM: added " + value + " to MilletPolicy." + fieldName);
            }
        } catch (Throwable ignored) {
            // Field layout differs; leave this list untouched.
        }
    }

    private boolean enabled() {
        return HookPrefs.getBoolean(Keys.FCM_FIX_ENABLED, false);
    }
}
