package com.hyperosmod.xposed;

import com.hyperosmod.Keys;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * Rejects updates of user-selected packages in system_server.
 *
 * <p>Every installer (HyperOS Market, Play Store, adb, a file manager...) eventually
 * reaches {@code InstallPackageHelper}. Hooking the prepare step, after the APK has been
 * parsed and its package name is known, lets us fail the request with a regular
 * {@code PrepareFailure}. New installs are never touched; only replacements of an
 * already installed package are rejected.</p>
 */
final class UpdateGuard {
    /** {@code PackageManager.INSTALL_FAILED_ABORTED}: the install was deliberately stopped. */
    private static final int INSTALL_FAILED_ABORTED = -115;

    private final XposedInterface xposed;
    private final ClassLoader classLoader;

    UpdateGuard(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        Class<?> helper = Reflect.findClass(classLoader,
                "com.android.server.pm.InstallPackageHelper");
        if (helper == null) {
            Logs.w("InstallPackageHelper not found; update guard disabled", null);
            return;
        }

        Method prepare = findPrepareMethod(helper, "preparePackage");
        if (prepare == null) {
            prepare = findPrepareMethod(helper, "preparePackageLI");
        }
        if (prepare == null) {
            Logs.w("No InstallPackageHelper prepare method found; update guard disabled", null);
            return;
        }

        final Method target = prepare;
        // PASSTHROUGH is required: a PrepareFailure thrown after proceed() must reach the
        // caller's catch block. All exploratory reflection is wrapped so only our own
        // deliberately constructed failure can escape.
        xposed.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    maybeBlock(chain.getArg(0));
                    return result;
                });
        Logs.i("Hooked InstallPackageHelper#" + target.getName() + " for update blocking");
    }

    private static Method findPrepareMethod(Class<?> helper, String name) {
        for (Method method : helper.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }
            if (!"com.android.server.pm.InstallRequest".equals(
                    method.getParameterTypes()[0].getName())) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    /**
     * Runs after the original prepare step. Throws the host's own install-failure type to
     * abort the update; every other error is swallowed so a bug here cannot brick installs.
     */
    private void maybeBlock(Object request) throws Throwable {
        Throwable failure = null;
        try {
            if (request == null
                    || !HookPrefs.getBoolean(Keys.BLOCK_UPDATES_ENABLED, false)) {
                return;
            }
            Set<String> blocked = HookPrefs.getStringSet(Keys.BLOCKED_PACKAGES);
            if (blocked.isEmpty()) {
                return;
            }
            String packageName = (String) invoke(request, "getName");
            if (packageName == null || !blocked.contains(packageName)) {
                return;
            }
            Object replace = invoke(request, "isInstallReplace");
            if (!(replace instanceof Boolean) || !((Boolean) replace)) {
                return;
            }
            failure = newBlockedFailure(packageName);
            if (failure != null) {
                Logs.i("Blocked update of " + packageName);
            }
        } catch (Throwable t) {
            Logs.w("Update guard check failed; allowing install", t);
            return;
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Object invoke(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Builds the exception type expected by the host Android version. Android 16 uses
     * {@code PrepareFailure} (package-private); Android 14/15 use
     * {@code PackageManagerException}.
     */
    private Throwable newBlockedFailure(String packageName) {
        String message = "更新已被 HyperOS Mod 阻止：" + packageName;

        // Android 16: PrepareFailure is the type caught by prepareInstallPackages().
        Class<?> prepareFailure = Reflect.findClass(classLoader, "com.android.server.pm.PrepareFailure");
        if (prepareFailure != null) {
            Throwable failure = constructFailure(prepareFailure, message);
            if (failure != null) {
                return failure;
            }
            // Do not substitute a PackageManagerException here: Android 16 only catches
            // PrepareFailure around the prepare step, so a different type could escape
            // and crash the install worker. Fail closed (allow the install) instead.
            return null;
        }

        // Android 14/15 fallback: preparePackageLI throws PackageManagerException.
        Class<?> managerException = Reflect.findClass(classLoader,
                "android.content.pm.PackageManagerException");
        return managerException == null ? null : constructFailure(managerException, message);
    }

    private static Throwable constructFailure(Class<?> type, String message) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(int.class, String.class);
            constructor.setAccessible(true);
            return (Throwable) constructor.newInstance(INSTALL_FAILED_ABORTED, message);
        } catch (Throwable ignored) {
            // Fall through to the single-argument constructor.
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return (Throwable) constructor.newInstance(INSTALL_FAILED_ABORTED);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

