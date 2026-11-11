package com.hyperosmod.xposed;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.hyperosmod.Keys;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * 双击手势提示线锁屏。
 *
 * <p>手势提示线（底部那条横条）的触摸判定在<b>桌面进程</b> {@code com.miui.home} 里，
 * 不在 SystemUI：HyperOS 由 {@code com.miui.home.recents.gesture.NavStubGestureEventManager}
 * 分发长按 / 双击事件，平板走 {@code com.miui.home.recents.cts.NavBarEventHelper} 的
 * {@code onDoubleTap}。所以这个功能的作用域是桌面，不是系统界面。</p>
 *
 * <p>锁屏动作沿用 MIUI 自己的开关广播
 * {@code com.miui.app.ExtraStatusBarManager.action_TRIGGER_TOGGLE}（id 10 = 锁屏），
 * 由系统模拟「按一下电源键」，不需要 system_server、不需要 root，也不需要申请任何权限。</p>
 */
final class GestureLineHook {
    private static final String TAG = "gesture-line";

    /** 手机（HyperOS 2 / 3）的手势事件分发器。 */
    private static final String GESTURE_MANAGER_CLASS =
            "com.miui.home.recents.gesture.NavStubGestureEventManager";
    private static final String GESTURE_MANAGER_METHOD = "handleDoubleClickEvent";

    /** 平板的手势事件分发器。 */
    private static final String PAD_HELPER_CLASS = "com.miui.home.recents.cts.NavBarEventHelper";
    private static final String PAD_HELPER_METHOD = "onDoubleTap";

    private static final String TOGGLE_ACTION =
            "com.miui.app.ExtraStatusBarManager.action_TRIGGER_TOGGLE";
    private static final String TOGGLE_EXTRA =
            "com.miui.app.ExtraStatusBarManager.extra_TOGGLE_ID";
    /** MIUI 内部开关表里的「锁屏」编号。 */
    private static final int TOGGLE_LOCK_SCREEN = 10;

    private final XposedInterface xposed;
    private final ClassLoader classLoader;

    /** 桌面进程的 Context，第一次用到时取，之后复用。 */
    private static volatile Context appContext;

    GestureLineHook(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        int hooked = hookDoubleClick(GESTURE_MANAGER_CLASS, GESTURE_MANAGER_METHOD);
        hooked += hookDoubleClick(PAD_HELPER_CLASS, PAD_HELPER_METHOD);
        if (hooked == 0) {
            Logs.w(TAG + ": 没找到手势提示线的双击回调，锁屏功能不可用", null);
        } else {
            Logs.i(TAG + ": 已挂钩 " + hooked + " 个双击回调");
        }
    }

    /**
     * 把类里所有同名方法都挂上（不同 ROM 版本签名不一致），命中时先尝试锁屏，
     * 成功就吞掉事件，不让桌面再执行它自己的双击行为。
     */
    private int hookDoubleClick(String className, String methodName) {
        Class<?> type = Reflect.findClass(classLoader, className);
        if (type == null) {
            return 0;
        }
        Set<Method> targets = new LinkedHashSet<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (methodName.equals(method.getName())) {
                    targets.add(method);
                }
            }
            break;
        }
        int hooked = 0;
        for (Method method : targets) {
            final boolean booleanReturn = method.getReturnType() == boolean.class;
            try {
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (HookPrefs.getBoolean(Keys.GESTURE_LINE_LOCK, false)
                                    && lockScreen()) {
                                Logs.i(TAG + ": 双击手势提示线 → 锁屏");
                                // 返回 true 表示「事件已处理」；void 方法直接吞掉即可。
                                return booleanReturn ? Boolean.TRUE : null;
                            }
                            return chain.proceed();
                        });
                hooked++;
            } catch (Throwable t) {
                Logs.w(TAG + ": 无法 hook " + className + "#" + methodName, t);
            }
        }
        if (hooked > 0) {
            Logs.i(TAG + ": hooked " + className + "#" + methodName + " (" + hooked + ")");
        }
        return hooked;
    }

    /**
     * 用 MIUI 自己的开关广播锁屏，等价于按一下电源键。
     *
     * <p>{@code PowerManager.goToSleep} 需要 DEVICE_POWER 权限、桌面进程没有，所以不能直接用；
     * 这个广播是 MIUI 内部给快捷开关用的通道，桌面发得出去。</p>
     */
    private boolean lockScreen() {
        Context context = context();
        if (context == null) {
            Logs.w(TAG + ": 拿不到桌面 Context，无法发送锁屏广播", null);
            return false;
        }
        try {
            context.sendBroadcast(new Intent(TOGGLE_ACTION).putExtra(TOGGLE_EXTRA,
                    TOGGLE_LOCK_SCREEN));
            return true;
        } catch (Throwable t) {
            Logs.w(TAG + ": 发送锁屏广播失败", t);
            return false;
        }
    }

    /** 桌面进程里取 Context：优先 Application，其次系统上下文（首次点击时可能还没建好）。 */
    private static Context context() {
        Context cached = appContext;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Object app = activityThread.getMethod("getApplication").invoke(thread);
            if (app instanceof Context) {
                appContext = (Context) app;
                return appContext;
            }
            Object system = activityThread.getMethod("getSystemContext").invoke(thread);
            if (system instanceof Context) {
                appContext = (Context) system;
                return appContext;
            }
        } catch (Throwable t) {
            Logs.w(TAG + ": 获取桌面 Context 失败", t);
        }
        return null;
    }
}
