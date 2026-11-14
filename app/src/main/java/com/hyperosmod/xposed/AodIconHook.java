package com.hyperosmod.xposed;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.hyperosmod.Keys;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 息屏显示（{@code com.miui.aod}）的通知图标同步。
 *
 * <p>AOD 是独立应用：它不看通知里的 {@code smallIcon}，而是自己按包名/资源 id 取应用图标，
 * 所以 SystemUI 与目标应用里的 hook 都管不到它。</p>
 *
 * <p>这里做两件事：</p>
 * <ol>
 *   <li>直接 hook {@code AODView#bindView(ImageView, int)}，命中目标应用时把图标换成当前
 *       桌面图标——AOD 怎么取图都不影响这条；</li>
 *   <li>打诊断日志：AODView 有哪些相关方法、AOD 到底从哪个包读了哪个资源、IconCustomizer
 *       是否存在。万一 bindView 在某个 ROM 上改了签名，日志也能直接告诉我们换哪条路。</li>
 * </ol>
 */
final class AodIconHook {
    private static final String LOG_TAG = "notify-icon: ";
    private static final int TRACE_CAP = 30;

    private final XposedInterface xposed;
    private final ClassLoader classLoader;
    private final Set<Integer> tracedResources = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger bindLogs = new AtomicInteger();

    AodIconHook(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        Class<?> aodView = Reflect.findClass(classLoader, "com.miui.aod.AODView");
        if (aodView == null) {
            // 不是 AOD 进程，安静退出即可（本类在每个注入进程里都会被试一次）。
            return;
        }
        Logs.i(LOG_TAG + "AODView 类存在，安装息屏显示 hook");
        report(aodView);
        try {
            hookBindView(aodView);
        } catch (Throwable t) {
            Logs.e(LOG_TAG + "hook AODView#bindView 失败", t);
        }
        try {
            hookResourcesTrace();
        } catch (Throwable t) {
            Logs.e(LOG_TAG + "hook AOD Resources 诊断失败", t);
        }
        Class<?> customizer = Reflect.findClass(classLoader, "miui.content.res.IconCustomizer");
        Logs.i(LOG_TAG + "IconCustomizer=" + (customizer != null));
    }

    /** 把 AODView 里和图标/包名有关的方法签名打出来，下一版按它调整 hook 目标。 */
    private void report(Class<?> aodView) {
        StringBuilder sb = new StringBuilder("AODView 相关方法: ");
        int count = 0;
        for (Method method : aodView.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.toLowerCase().contains("icon")
                    && !name.toLowerCase().contains("pkg")
                    && !name.toLowerCase().contains("bind")
                    && !name.toLowerCase().contains("badge")) {
                continue;
            }
            sb.append(name).append('(');
            for (Class<?> parameter : method.getParameterTypes()) {
                sb.append(parameter.getSimpleName()).append(',');
            }
            sb.append(") ");
            count++;
        }
        Logs.i(LOG_TAG + sb + "共 " + count + " 个");
    }

    private void hookBindView(Class<?> aodView) {
        int hooked = 0;
        for (Method method : aodView.getDeclaredMethods()) {
            if (!"bindView".equals(method.getName()) || method.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (!ImageView.class.isAssignableFrom(parameters[0]) || parameters[1] != int.class) {
                continue;
            }
            method.setAccessible(true);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            replace(chain.getThisObject(), chain.getArg(0), chain.getArg(1));
                        } catch (Throwable t) {
                            Logs.w(LOG_TAG + "替换 AOD 图标失败", t);
                        }
                        return result;
                    });
            hooked++;
        }
        Logs.i(LOG_TAG + "已 hook " + hooked + " 个 AODView#bindView");
    }

    private void replace(Object aodView, Object viewArg, Object indexArg) {
        if (!(viewArg instanceof ImageView) || !(indexArg instanceof Integer)) {
            return;
        }
        Object pkgValue = Reflect.call(aodView, "getPkg", indexArg);
        String packageName = pkgValue instanceof String ? (String) pkgValue : null;
        boolean first = bindLogs.incrementAndGet() <= 10;
        if (first) {
            Logs.i(LOG_TAG + "AODView#bindView 视图=" + viewArg.getClass().getName()
                    + " index=" + indexArg + " pkg=" + packageName);
        }
        if (packageName == null
                || !HookPrefs.getBoolean(Keys.NOTIFY_ICON_ENABLED, false)
                || !HookPrefs.getStringSet(Keys.NOTIFY_ICON_TARGETS).contains(packageName)) {
            return;
        }
        PackageManager pm = LauncherIconProvider.packageManager();
        Drawable icon = LauncherIconProvider.launcherDrawable(pm, packageName);
        if (icon == null) {
            return;
        }
        ImageView image = (ImageView) viewArg;
        boolean hadImage = image.getDrawable() != null;
        image.setImageDrawable(icon);
        if (!hadImage && image.getBackground() != null) {
            // 有些 AOD 版本用 background 画图标。
            image.setBackground(icon);
        }
        Logs.i(LOG_TAG + "AOD 图标已替换 pkg=" + packageName + " 原有图=" + hadImage);
    }

    /**
     * 诊断：AOD 只要从目标包读过任何资源（不管是不是图标），就把这次读取打一行 + 调用栈。
     * 拿不到这条日志说明 AOD 压根没走 Resources，得去 hook IconCustomizer。
     */
    private void hookResourcesTrace() {
        Class<?> type = Reflect.findClass(classLoader, "android.content.res.Resources");
        if (type == null) {
            return;
        }
        int hooked = 0;
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName();
            if (!"getDrawable".equals(name) && !"getDrawableForDensity".equals(name)) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 0 || parameters[0] != int.class) {
                continue;
            }
            method.setAccessible(true);
            xposed.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            trace(method.getName(), chain.getThisObject(), chain.getArg(0));
                        } catch (Throwable ignored) {
                            // 诊断失败绝不能影响原调用。
                        }
                        return chain.proceed();
                    });
            hooked++;
        }
        Logs.i(LOG_TAG + "AOD 诊断 hook 数=" + hooked);
    }

    private void trace(String methodName, Object resources, Object idArg) {
        if (!(resources instanceof android.content.res.Resources)
                || !(idArg instanceof Integer)
                || tracedResources.size() >= TRACE_CAP) {
            return;
        }
        int resId = (Integer) idArg;
        if (!tracedResources.add(resId)) {
            return;
        }
        android.content.res.Resources res = (android.content.res.Resources) resources;
        String owner;
        try {
            owner = res.getResourcePackageName(resId);
        } catch (Throwable t) {
            return;
        }
        if (owner == null || !HookPrefs.getStringSet(Keys.NOTIFY_ICON_TARGETS).contains(owner)) {
            return;
        }
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder("AOD 读目标包资源 " + owner + " resId=" + resId
                + " via " + methodName + " 栈=");
        for (int i = 2; i < Math.min(stack.length, 10); i++) {
            sb.append('\n').append("    ").append(stack[i].getClassName())
                    .append('#').append(stack[i].getMethodName());
        }
        Logs.i(LOG_TAG + sb);
    }
}
