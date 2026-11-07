package com.hyperosmod.xposed;

import android.content.Intent;
import android.service.notification.NotificationListenerService;

import com.hyperosmod.Keys;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * FCM/GCM fixes for HyperOS.
 *
 * <p>The GMS process delivers an FCM message with a broadcast such as
 * {@code <package>.android.c2dm.intent.RECEIVE}. When the target app is in the stopped
 * state, Android refuses it with "Failed to broadcast to stopped app". This hook adds
 * {@link Intent#FLAG_INCLUDE_STOPPED_PACKAGES} for selected packages and bypasses the
 * HyperOS auto-start / smart-power broadcast gates. It also keeps notifications when the
 * stopped package is cleaned up and lets local notifications through MIUI's restriction.</p>
 *
 * <p>Logic is based on fcmfix (GPL-3.0), adapted to libxposed API 102 and Android 16.</p>
 */
final class FcmSystemHooks {
    private final XposedInterface xposed;
    private final ClassLoader classLoader;

    FcmSystemHooks(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        Logs.i("FCM: installing system hooks (enabled=" + enabled()
                + ", targets=" + HookPrefs.getStringSet(Keys.FCM_TARGETS).size() + ")");
        try {
            hookBroadcastController();
        } catch (Throwable t) {
            Logs.e("FCM: failed to hook BroadcastController", t);
        }
        try {
            hookHyperOsAutoStart();
        } catch (Throwable t) {
            Logs.e("FCM: failed to hook HyperOS auto-start", t);
        }
        try {
            hookLocalNotification();
        } catch (Throwable t) {
            Logs.e("FCM: failed to hook local-notification gate", t);
        }
        try {
            hookKeepNotification();
        } catch (Throwable t) {
            Logs.e("FCM: failed to hook notification cleanup", t);
        }
        try {
            new FcmGmsWhitelist().install();
        } catch (Throwable t) {
            Logs.e("FCM: failed to install GMS whitelist guard", t);
        }
    }

    // ---------------------------------------------------------------------
    // 1. Allow stopped-package FCM broadcasts (AOSP BroadcastController path)
    // ---------------------------------------------------------------------

    private void hookBroadcastController() {
        Class<?> controller = Reflect.findClass(classLoader,
                "com.android.server.am.BroadcastController");
        Method target = findMethodWithMostParams(controller, "broadcastIntentLocked");
        if (target == null) {
            controller = Reflect.findClass(classLoader,
                    "com.android.server.am.ActivityManagerService");
            target = findMethodWithMostParams(controller, "broadcastIntentLocked");
        }
        if (target == null) {
            Logs.w("FCM: broadcastIntentLocked not found", null);
            return;
        }

        final int intentIndex = findIntentIndex(target);
        final int appOpIndex = findAppOpIndex(target);
        if (intentIndex < 0 || appOpIndex < 0) {
            Logs.w("FCM: broadcastIntentLocked argument layout unknown", null);
            return;
        }
        Logs.i("FCM: broadcast target=" + target.getDeclaringClass().getName()
                + "#broadcastIntentLocked intentIndex=" + intentIndex
                + " appOpIndex=" + appOpIndex);
        final Class<?> declaring = target.getDeclaringClass();
        xposed.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (!enabled()) {
                        return chain.proceed();
                    }
                    Object arg = chain.getArg(intentIndex);
                    if (!(arg instanceof Intent)) {
                        return chain.proceed();
                    }
                    Intent intent = (Intent) arg;
                    if ((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0
                            || !isFcmAction(intent.getAction())) {
                        return chain.proceed();
                    }
                    String targetPackage = targetOf(intent);
                    if (!targetAllowed(targetPackage)) {
                        Logs.i("FCM: skip unselected target=" + targetPackage
                                + " action=" + intent.getAction());
                        return chain.proceed();
                    }
                    int appOp = (Integer) chain.getArg(appOpIndex);
                    Object[] args = chain.getArgs().toArray();
                    if (appOp == -1) {
                        args[appOpIndex] = 11; // AppOpsManager.OP_WAKE_LOCK
                    }
                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    Logs.i("FCM: wake stopped app " + targetPackage
                            + " via " + declaring.getSimpleName());
                    return chain.proceed(args);
                });
        Logs.i("FCM: hooked " + declaring.getName() + "#broadcastIntentLocked");
    }

    // ---------------------------------------------------------------------
    // 2. HyperOS broadcast/auto-start/smart-power gates
    // ---------------------------------------------------------------------

    private void hookHyperOsAutoStart() {
        Class<?> modernStub = Reflect.findClass(classLoader,
                "com.android.server.am.BroadcastQueueModernStubImpl");
        // HyperOS may not expose the original action at these two gates; fcmfix allows
        // any intent for a user-selected package here, so mirror that behaviour.
        hookRecordGate(modernStub, "checkApplicationAutoStart", true, false);
        hookRecordGate(modernStub, "checkReceiverIfRestricted", false, true);

        Class<?> autoStart = Reflect.findClass(classLoader,
                "com.android.server.am.AutoStartManagerServiceStubImpl");
        hookIntentGate(autoStart, "isAllowStartService", true, false);

        Class<?> smartPower = Reflect.findClass(classLoader,
                "com.android.server.am.SmartPowerService");
        hookRecordGate(smartPower, "shouldInterceptBroadcast", false, true);

        Class<?> smartPolicy = Reflect.findClass(classLoader,
                "com.miui.server.smartpower.SmartPowerPolicyManager");
        hookIntentGate(smartPolicy, "shouldInterceptService", false, true);
    }

    /** Methods whose relevant argument is a BroadcastRecord-like object with an intent field. */
    private void hookRecordGate(Class<?> type, String methodName, boolean allowValue,
                                boolean requireFcm) {
        if (type == null) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)
                    || method.getReturnType() != boolean.class) {
                continue;
            }
            method.setAccessible(true);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!enabled()) {
                            return chain.proceed();
                        }
                        Intent intent = intentFromRecordArgs(chain.getArgs());
                        if (intent != null
                                && (requireFcm
                                ? isFcmTarget(intent)
                                : targetAllowed(targetOf(intent)))) {
                            Logs.i("FCM: " + type.getSimpleName() + "#" + methodName
                                    + " -> " + allowValue + " for " + targetOf(intent));
                            return allowValue;
                        }
                        return chain.proceed();
                    });
        }
    }

    /** Methods with an Intent argument (or a record containing one). */
    private void hookIntentGate(Class<?> type, String methodName, boolean allowValue,
                                boolean requireFcm) {
        if (type == null) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)
                    || method.getReturnType() != boolean.class) {
                continue;
            }
            method.setAccessible(true);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!enabled()) {
                            return chain.proceed();
                        }
                        Intent intent = intentFromArgs(chain.getArgs());
                        if (intent != null
                                && (requireFcm
                                ? isFcmTarget(intent)
                                : targetAllowed(targetOf(intent)))) {
                            Logs.i("FCM: " + type.getSimpleName() + "#" + methodName
                                    + " -> " + allowValue + " for " + targetOf(intent));
                            return allowValue;
                        }
                        return chain.proceed();
                    });
        }
    }

    // ---------------------------------------------------------------------
    // 3. MIUI/HyperOS local notification restriction
    // ---------------------------------------------------------------------

    private void hookLocalNotification() {
        hookLocalNotificationClass(
                "com.android.server.notification.NotificationManagerServiceInjector");
        hookLocalNotificationClass(
                "com.android.server.notification.NotificationManagerServiceImpl");
    }

    private void hookLocalNotificationClass(String className) {
        Class<?> type = Reflect.findClass(classLoader, className);
        if (type == null) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName();
            boolean allow = "isAllowLocalNotification".equals(name);
            boolean deny = "isDeniedLocalNotification".equals(name);
            if ((!allow && !deny) || method.getReturnType() != boolean.class) {
                continue;
            }
            method.setAccessible(true);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!enabled()) {
                            return chain.proceed();
                        }
                        String pkg = targetPackageFromArgs(chain.getArgs());
                        if (pkg != null) {
                            Logs.i("FCM: local notification " + name + " -> " + allow
                                    + " for " + pkg);
                            return allow;
                        }
                        return chain.proceed();
                    });
        }
    }

    // ---------------------------------------------------------------------
    // 4. Keep notifications when an app is stopped
    // ---------------------------------------------------------------------

    private void hookKeepNotification() {
        Class<?> type = Reflect.findClass(classLoader,
                "com.android.server.notification.NotificationManagerService");
        Method target = findMethodWithMostParams(type, "cancelAllNotificationsInt");
        if (target == null) {
            Logs.w("FCM: cancelAllNotificationsInt not found", null);
            return;
        }
        final int pkgIndex = firstStringIndex(target);
        final int reasonIndex = lastIntIndex(target);
        if (pkgIndex < 0 || reasonIndex < 0) {
            Logs.w("FCM: cancelAllNotificationsInt layout unknown", null);
            return;
        }
        xposed.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (!enabled()
                            || !HookPrefs.getBoolean(Keys.FCM_KEEP_NOTIFICATION, true)) {
                        return chain.proceed();
                    }
                    Object pkgArg = chain.getArg(pkgIndex);
                    if (!(pkgArg instanceof String)
                            || !targetAllowed((String) pkgArg)) {
                        return chain.proceed();
                    }
                    int reason = (Integer) chain.getArg(reasonIndex);
                    if (reason == NotificationListenerService.REASON_PACKAGE_CHANGED
                            || reason == 10020 || reason == 10021) {
                        Logs.i("FCM: keep notifications for " + pkgArg
                                + " (reason=" + reason + ")");
                        return null;
                    }
                    return chain.proceed();
                });
        Logs.i("FCM: hooked NotificationManagerService#cancelAllNotificationsInt");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private boolean enabled() {
        return HookPrefs.getBoolean(Keys.FCM_FIX_ENABLED, false);
    }

    private boolean targetAllowed(String packageName) {
        if (packageName == null) {
            return false;
        }
        Set<String> targets = HookPrefs.getStringSet(Keys.FCM_TARGETS);
        return targets.contains(packageName);
    }

    private boolean isFcmTarget(Intent intent) {
        return intent != null && isFcmAction(intent.getAction())
                && targetAllowed(targetOf(intent));
    }

    private static boolean isFcmAction(String action) {
        return action != null && (action.endsWith(".android.c2dm.intent.RECEIVE")
                || "com.google.firebase.MESSAGING_EVENT".equals(action)
                || "com.google.firebase.INSTANCE_ID_EVENT".equals(action));
    }

    private String targetPackageFromArgs(java.util.List<Object> args) {
        // The MIUI methods put the package at index 3, but scan for a known target to
        // survive signature changes between HyperOS versions.
        for (Object arg : args) {
            if (arg instanceof String && targetAllowed((String) arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    private static String targetOf(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (intent.getComponent() != null) {
            return intent.getComponent().getPackageName();
        }
        return intent.getPackage();
    }

    private static Intent intentFromRecordArgs(java.util.List<Object> args) {
        for (Object arg : args) {
            Intent intent = intentFromRecord(arg);
            if (intent != null) {
                return intent;
            }
        }
        return null;
    }

    private static Intent intentFromArgs(java.util.List<Object> args) {
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
            Intent nested = intentFromRecord(arg);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static Intent intentFromRecord(Object record) {
        if (record == null) {
            return null;
        }
        Object intent = Reflect.getField(record, "intent");
        return intent instanceof Intent ? (Intent) intent : null;
    }

    private static Method findMethodWithMostParams(Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        Method best = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (best == null || method.getParameterCount() > best.getParameterCount()) {
                best = method;
            }
        }
        if (best != null) {
            best.setAccessible(true);
        }
        return best;
    }

    private static int findIntentIndex(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Intent.class) {
                return i;
            }
        }
        return -1;
    }

    private static int findAppOpIndex(Method method) {
        Class<?>[] types = method.getParameterTypes();
        Parameter[] params = method.getParameters();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class && "appOp".equals(params[i].getName())) {
                return i;
            }
        }

        int bundleIndex = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == android.os.Bundle.class) {
                bundleIndex = i;
                break;
            }
        }

        // Android 16 signature: (..., String[] excludedPackages, int appOp, Bundle, ...)
        // Find the last String[] before the options Bundle, then the int right after it.
        int lastStringArray = -1;
        int limit = bundleIndex > 0 ? bundleIndex : types.length;
        for (int i = 0; i < limit; i++) {
            if (types[i] == String[].class) {
                lastStringArray = i;
            }
        }
        if (lastStringArray >= 0 && lastStringArray + 1 < types.length
                && types[lastStringArray + 1] == int.class) {
            return lastStringArray + 1;
        }

        // Last int before the Bundle options is the next-best heuristic.
        if (bundleIndex > 0 && types[bundleIndex - 1] == int.class) {
            return bundleIndex - 1;
        }
        return -1;
    }

    private static int firstStringIndex(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == String.class) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIntIndex(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = types.length - 1; i >= 0; i--) {
            if (types[i] == int.class) {
                return i;
            }
        }
        return -1;
    }
}

