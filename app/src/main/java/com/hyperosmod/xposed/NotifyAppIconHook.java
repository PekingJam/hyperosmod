package com.hyperosmod.xposed;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import com.hyperosmod.Keys;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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

    /** 「应用级图标资源 id -> 包名」缓存，给 Resources#getDrawable 那条路径用。 */
    private static final Map<Integer, String> ICON_RESOURCES = new HashMap<>();
    private static long iconResourcesExpires;
    private static final long ICON_RESOURCES_TTL_MS = 2000L;
    /** 每条命中路径只打一行日志，用户抓日志就能看出 AOD 走的是哪条。 */
    private static final Set<String> LOGGED = java.util.Collections.synchronizedSet(
            new java.util.HashSet<>());

    private static void logOnce(String key, String message) {
        if (LOGGED.add(key)) {
            Logs.i(LOG_TAG + message);
        }
    }

    private final XposedInterface xposed;
    private final ClassLoader classLoader;
    private final String processName;

    NotifyAppIconHook(XposedInterface xposed, ClassLoader classLoader) {
        this(xposed, classLoader, "");
    }

    NotifyAppIconHook(XposedInterface xposed, ClassLoader classLoader, String processName) {
        this.xposed = xposed;
        this.classLoader = classLoader;
        this.processName = processName == null ? "" : processName;
    }

    void install() {
        installIconSources();
        try {
            hookStatusBarIconView();
        } catch (Throwable t) {
            Logs.e(LOG_TAG + "hook StatusBarIconView 失败", t);
        }
    }

    /**
     * 只装「应用图标来源」这一组 hook。
     *
     * <p>息屏显示（{@code com.miui.aod}）是一个独立应用，它自己按包名取图标，SystemUI 里
     * 的 hook 完全管不到它；那个进程里又没有 StatusBarIconView，所以分开装。</p>
     */
    void installIconSources() {
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
            hookPackageManagerDrawable();
        } catch (Throwable t) {
            Logs.e(LOG_TAG + "hook PackageManager#getDrawable 失败", t);
        }
        try {
            hookResourcesIcon();
        } catch (Throwable t) {
            Logs.e(LOG_TAG + "hook Resources#getDrawable 失败", t);
        }
        try {
            hookIconCustomizer();
        } catch (Throwable t) {
            Logs.e(LOG_TAG + "hook IconCustomizer 失败", t);
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
            // 部分 ROM 已经裁掉这个隐藏 API，不影响其它入口。
            Logs.i(LOG_TAG + "ApplicationInfo#loadUnbadgedIcon 不存在，改用其它图标入口");
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
                        String pkg = packageOf(chain.getArg(0));
                        Drawable replacement = replacementFor(pkg);
                        if (replacement != null) {
                            logOnce("getApplicationIcon:" + pkg,
                                    "PackageManager#getApplicationIcon 命中 " + pkg);
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
                    String pkg = packageOf(chain.getThisObject());
                    Drawable replacement = replacementFor(pkg);
                    if (replacement != null) {
                        logOnce("itemIcon:" + pkg, "PackageItemInfo#loadIcon 命中 " + pkg);
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

    /**
     * 息屏显示（com.miui.aod）按「应用图标的资源 id」取图，而不是 getApplicationIcon：
     * 先问 {@code PackageManager#getDrawable(pkg, resId, ai)}，或者直接拿目标应用的
     * Resources 去 {@code getDrawable(resId)}。这两条路都要接管，否则 AOD 里还是原版图标。
     */
    private void hookPackageManagerDrawable() {
        Class<?> type = Reflect.findClass(classLoader, "android.app.ApplicationPackageManager");
        Method method = Reflect.findMethod(type, "getDrawable", String.class, int.class,
                ApplicationInfo.class);
        if (method == null) {
            Logs.w(LOG_TAG + "PackageManager#getDrawable 不存在，跳过", null);
            return;
        }
        method.setAccessible(true);
        xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object resArg = chain.getArg(1);
                    Object infoArg = chain.getArg(2);
                    int resId = resArg instanceof Integer ? (Integer) resArg : 0;
                    int appIconRes = infoArg instanceof ApplicationInfo
                            ? ((ApplicationInfo) infoArg).icon : 0;
                    if (resId != 0 && resId == appIconRes) {
                        Object pkgArg = chain.getArg(0);
                        String pkg = pkgArg instanceof String ? (String) pkgArg : null;
                        Drawable replacement = replacementFor(pkg);
                        if (replacement != null) {
                            logOnce("pm-getDrawable:" + pkg + ":" + resId,
                                    "PackageManager#getDrawable 命中 " + pkg);
                            return replacement;
                        }
                    }
                    return chain.proceed();
                });
        Logs.i(LOG_TAG + "已 hook PackageManager#getDrawable");
    }

    private void hookResourcesIcon() {
        Class<?> type = Reflect.findClass(classLoader, "android.content.res.Resources");
        if (type == null) {
            Logs.w(LOG_TAG + "Resources 不存在，跳过", null);
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
            try {
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object idArg = chain.getArg(0);
                            if (idArg instanceof Integer) {
                                Resources resources = chain.getThisObject() instanceof Resources
                                        ? (Resources) chain.getThisObject() : null;
                                Drawable replacement = replacementForResource(
                                        resources, (Integer) idArg);
                                if (replacement != null) {
                                    logOnce("resources:" + idArg,
                                            "Resources#getDrawable 命中 resId=" + idArg);
                                    return replacement;
                                }
                                traceTargetResource(resources, (Integer) idArg);
                            }
                            return chain.proceed();
                        });
                hooked++;
            } catch (Throwable t) {
                Logs.w(LOG_TAG + "hook Resources#" + name + " 失败", t);
            }
        }
        Logs.i(LOG_TAG + "已 hook " + hooked + " 个 Resources#getDrawable*");
    }

    private static Drawable replacementForResource(Resources resources, int resId) {
        if (resources == null || resId == 0) {
            return null;
        }
        String packageName = packageForIconResource(resources, resId);
        return packageName == null ? null : replacementFor(packageName);
    }

    private static String packageForIconResource(Resources resources, int resId) {
        if (!HookPrefs.getBoolean(Keys.NOTIFY_ICON_ENABLED, false)) {
            return null;
        }
        long now = SystemClock.uptimeMillis();
        synchronized (ICON_RESOURCES) {
            if (now < iconResourcesExpires) {
                return ICON_RESOURCES.get(resId);
            }
        }
        Map<Integer, String> rebuilt = new HashMap<>();
        PackageManager pm = LauncherIconProvider.packageManager();
        Set<String> targets = HookPrefs.getStringSet(Keys.NOTIFY_ICON_TARGETS);
        if (pm != null) {
            for (String target : targets) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(target, 0);
                    if (info.icon == 0) {
                        continue;
                    }
                    // 所有应用共用 0x7f 资源包段，id 会撞车，必须再用资源所属包名核对
                    if (target.equals(resources.getResourcePackageName(info.icon))) {
                        rebuilt.put(info.icon, target);
                    }
                } catch (Throwable ignored) {
                    // 包被卸载 / 资源不在这个 Resources 里。
                }
            }
        }
        synchronized (ICON_RESOURCES) {
            ICON_RESOURCES.clear();
            ICON_RESOURCES.putAll(rebuilt);
            iconResourcesExpires = now + ICON_RESOURCES_TTL_MS;
        }
        return rebuilt.get(resId);
    }

    // ==================================================================================
    // MIUI 图标管线与诊断
    // ==================================================================================

    /** 已经被打印过调用栈的资源 id，避免刷屏。 */
    private static final Set<Integer> TRACED_RESOURCES =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final int TRACE_CAP = 40;

    /**
     * 诊断：某个进程从目标包里读了资源就打印一次调用栈。
     *
     * <p>息屏显示的图标如果既不经过 PackageManager 也不经过我们已 hook 的资源 id，
     * 这条日志会直接告诉我们到底是哪个类在画。</p>
     */
    private static void traceTargetResource(Resources resources, int resId) {
        if (resources == null || resId == 0 || TRACED_RESOURCES.size() >= TRACE_CAP) {
            return;
        }
        if (!TRACED_RESOURCES.add(resId)) {
            return;
        }
        String owner;
        try {
            owner = resources.getResourcePackageName(resId);
        } catch (Throwable t) {
            return;
        }
        if (owner == null || !HookPrefs.getStringSet(Keys.NOTIFY_ICON_TARGETS).contains(owner)) {
            return;
        }
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder builder = new StringBuilder("读目标包资源 " + owner + " resId=" + resId + " 栈=");
        for (int i = 2; i < Math.min(stack.length, 9); i++) {
            builder.append('\n').append("    ").append(stack[i].getClassName())
                    .append('#').append(stack[i].getMethodName());
        }
        Logs.i(LOG_TAG + builder);
    }

    /**
     * MIUI / HyperOS 自己的图标管线 {@code miui.content.res.IconCustomizer}。
     *
     * <p>桌面、最近任务、息屏显示这类系统界面走它取图标时是绕开 PackageManager 的，
     * 所以得单独接管。各家版本签名不同，这里按「静态、返回 Drawable、参数里带包名或
     * ApplicationInfo」扫描，并把扫到的签名打进日志，方便下一次按真实签名收窄。</p>
     */
    private void hookIconCustomizer() {
        Class<?> type = Reflect.findClass(classLoader, "miui.content.res.IconCustomizer");
        if (type == null) {
            Logs.i(LOG_TAG + "IconCustomizer 不存在");
            return;
        }
        StringBuilder signatures = new StringBuilder();
        int hooked = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (!Drawable.class.isAssignableFrom(returnType)) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            signatures.append(method.getName()).append('(');
            for (Class<?> parameter : parameters) {
                signatures.append(parameter.getSimpleName()).append(',');
            }
            signatures.append(") ");
            if (!hasPackageParameter(parameters)) {
                continue;
            }
            try {
                method.setAccessible(true);
                final String methodName = method.getName();
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            String packageName = packageFromItems(chain.getArgs());
                            Drawable replacement = replacementFor(packageName);
                            if (replacement != null) {
                                logOnce("iconCustomizer:" + packageName + ":" + methodName,
                                        "IconCustomizer#" + methodName + " 命中 " + packageName);
                                return replacement;
                            }
                            return chain.proceed();
                        });
                hooked++;
            } catch (Throwable t) {
                Logs.w(LOG_TAG + "hook IconCustomizer#" + method.getName() + " 失败", t);
            }
        }
        Logs.i(LOG_TAG + "IconCustomizer 签名: " + signatures);
        Logs.i(LOG_TAG + "已 hook " + hooked + " 个 IconCustomizer 方法");
    }

    private static boolean hasPackageParameter(Class<?>[] parameters) {
        for (Class<?> parameter : parameters) {
            if (parameter == String.class || parameter == ApplicationInfo.class) {
                return true;
            }
        }
        return false;
    }

    /** 从参数里找出属于目标包的项；找不到就返回 null。 */
    private static String packageFromItems(java.util.List<Object> args) {
        for (Object arg : args) {
            if (arg instanceof ApplicationInfo) {
                return ((ApplicationInfo) arg).packageName;
            }
        }
        for (Object arg : args) {
            if (arg instanceof String
                    && HookPrefs.getStringSet(Keys.NOTIFY_ICON_TARGETS).contains(arg)) {
                return (String) arg;
            }
        }
        return null;
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
            // 这个类只在 SystemUI 里存在，桌面 / 息屏显示等进程没有它是正常的，
            // 安静跳过即可，不然每次开机都会在每个注入进程里留一条警告。
            if (processName.startsWith("com.android.systemui")) {
                Logs.i(LOG_TAG + "SystemUI 里没找到 StatusBarIconView，通知图标兜底未安装");
            }
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
