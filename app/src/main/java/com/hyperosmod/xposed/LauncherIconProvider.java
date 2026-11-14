package com.hyperosmod.xposed;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读取某个应用「当前正在使用的桌面图标」。
 *
 * <p>Telegram 这类客户端的换图标功能是靠一组 {@code <activity-alias>} 实现的：清单里为每个
 * 图标各声明一个 alias（各自的 {@code android:icon}），换图标时只把选中的 alias 设为
 * enabled。所以桌面图标 = 当前启用的启动入口（alias）的图标，而 {@code <application>}
 * 级别的图标始终是原始那个——这就是「桌面变了、通知栏没变」的根因。</p>
 *
 * <p>本类在 SystemUI 与目标应用两个进程里都会被加载，因此只依赖 PackageManager，
 * 不依赖模块 App 的任何东西。</p>
 */
final class LauncherIconProvider {
    /** 图标缓存时长：换图标是低频操作，短缓存只是为了避免同一条通知里反复 IPC。 */
    private static final long TTL_MS = 2000L;
    /** 取不到图标（包被冻结/卸载中）时的负缓存，别每次都去问 PackageManager。 */
    private static final long MISSING_TTL_MS = 5000L;
    /** 通知小图标的位图边长，够 3x 屏幕放大显示。 */
    private static final int BITMAP_SIZE = 144;
    /** 自适应图标前景层只占 108dp 里的 72dp 安全区，放大到铺满才接近桌面上的观感。 */
    private static final float FOREGROUND_FILL = 1.25f;

    /**
     * 不可变快照：写入时整份替换，读取完全无锁。
     *
     * <p>这条路径在通知刷新与资源 hook 里会被高频调用，原来每次读都要抢同一把锁。</p>
     */
    private static volatile Map<String, Entry> cache = Collections.emptyMap();
    private static final Object CACHE_LOCK = new Object();
    private static final String LOG_TAG = "notify-icon: ";
    /** 只在桌面图标真的变了时打一行日志，避免每次重算都刷屏。 */
    private static final Map<String, String> LAST_LOGGED = new ConcurrentHashMap<>();

    /** 反射出来的 ActivityThread 入口，只解析一次。 */
    private static volatile Method currentActivityThread;
    private static volatile Method getApplication;
    private static volatile Method getSystemContext;
    private static volatile boolean methodsResolved;
    /** 同一个进程里 PackageManager 是稳定的，拿到就留着。 */
    private static volatile PackageManager packageManagerCache;

    private static final class Entry {
        final Drawable drawable;
        final Icon icon;
        final long expires;

        Entry(Drawable drawable, Icon icon, long expires) {
            this.drawable = drawable;
            this.icon = icon;
            this.expires = expires;
        }
    }

    private LauncherIconProvider() {
    }

    /** 桌面图标的 Drawable（每次返回独立副本，调用方随便改 bounds / mutate）。 */
    static Drawable launcherDrawable(PackageManager pm, String packageName) {
        Entry entry = entry(pm, packageName);
        return entry == null ? null : copy(entry.drawable);
    }

    /** 给通知用的小图标（位图 Icon）。 */
    static Icon notificationIcon(PackageManager pm, String packageName) {
        Entry entry = entry(pm, packageName);
        return entry == null ? null : entry.icon;
    }

    /** 从当前进程里摸一个 PackageManager 出来；SystemUI 用的是系统用户的 Context。 */
    static PackageManager packageManager() {
        PackageManager cached = packageManagerCache;
        if (cached != null) {
            return cached;
        }
        return resolvePackageManager();
    }

    private static synchronized PackageManager resolvePackageManager() {
        PackageManager cached = packageManagerCache;
        if (cached != null) {
            return cached;
        }
        try {
            if (!methodsResolved) {
                Class<?> threadClass = Class.forName("android.app.ActivityThread");
                currentActivityThread = threadClass.getMethod("currentActivityThread");
                getApplication = threadClass.getMethod("getApplication");
                getSystemContext = threadClass.getMethod("getSystemContext");
                methodsResolved = true;
            }
            Object thread = currentActivityThread.invoke(null);
            if (thread == null) {
                return null;
            }
            Object application = getApplication.invoke(thread);
            if (application instanceof Context) {
                PackageManager pm = ((Context) application).getPackageManager();
                packageManagerCache = pm;
                return pm;
            }
            Object system = getSystemContext.invoke(thread);
            if (system instanceof Context) {
                PackageManager pm = ((Context) system).getPackageManager();
                packageManagerCache = pm;
                return pm;
            }
        } catch (Throwable ignored) {
            // 调用方自己处理 null。
        }
        return null;
    }

    private static Entry entry(PackageManager pm, String packageName) {
        if (pm == null || packageName == null || packageName.isEmpty()) {
            return null;
        }
        long now = SystemClock.uptimeMillis();
        Entry cached = cache.get(packageName);
        if (cached != null && cached.expires > now) {
            return cached;
        }
        Entry built = build(pm, packageName, now);
        Entry stored = built != null ? built : new Entry(null, null, now + MISSING_TTL_MS);
        synchronized (CACHE_LOCK) {
            Map<String, Entry> next = new HashMap<>(cache);
            next.put(packageName, stored);
            cache = next;
        }
        return built;
    }

    private static Entry build(PackageManager pm, String packageName, long now) {
        ComponentName component = null;
        Drawable drawable = null;
        try {
            Intent launch = pm.getLaunchIntentForPackage(packageName);
            if (launch != null) {
                component = launch.getComponent();
            }
        } catch (Throwable ignored) {
            // 包刚卸载 / 被冻结时可能抛，退回应用图标。
        }
        if (component != null) {
            try {
                drawable = pm.getActivityIcon(component);
            } catch (Throwable ignored) {
                // 继续退回应用图标。
            }
        }
        if (drawable == null) {
            try {
                drawable = pm.getApplicationIcon(packageName);
            } catch (Throwable ignored) {
                // 拿不到就只能放弃。
            }
        }
        if (drawable == null) {
            return null;
        }
        Bitmap bitmap = render(drawable, BITMAP_SIZE);
        Icon icon = bitmap == null ? null : Icon.createWithBitmap(bitmap);
        String componentName = component == null ? "?" : component.flattenToShortString();
        if (!componentName.equals(LAST_LOGGED.get(packageName))) {
            LAST_LOGGED.put(packageName, componentName);
            Logs.i(LOG_TAG + "桌面图标 " + packageName + " 组件=" + componentName
                    + " 小图标=" + (icon != null));
        }
        return new Entry(drawable, icon, now + TTL_MS);
    }

    /**
     * 把桌面图标画成位图。
     *
     * <p>自适应图标优先用系统给的 monochrome 层（专门给单色小图标准备的），没有就只用前景层：
     * 连背景层一起画的话整块会变成一个实心方块，通知栏里分不出是哪个应用。</p>
     */
    private static Bitmap render(Drawable drawable, int size) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            if (drawable instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) drawable;
                Drawable source = null;
                try {
                    source = adaptive.getMonochrome();
                } catch (Throwable ignored) {
                    // 低版本 / 没有 monochrome 层。
                }
                if (source == null) {
                    source = adaptive.getForeground();
                }
                if (source != null) {
                    int scaled = Math.round(size * FOREGROUND_FILL);
                    int offset = (size - scaled) / 2;
                    source.setBounds(offset, offset, offset + scaled, offset + scaled);
                    source.draw(canvas);
                    return bitmap;
                }
            }
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable t) {
            Logs.w(LOG_TAG + "渲染桌面图标失败", t);
            return null;
        }
    }

    static Drawable copy(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        try {
            Drawable.ConstantState state = drawable.getConstantState();
            if (state != null) {
                return state.newDrawable().mutate();
            }
        } catch (Throwable ignored) {
            // 用原对象也行。
        }
        return drawable;
    }
}
