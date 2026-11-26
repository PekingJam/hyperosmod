package com.hyperosmod.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.hyperosmod.Keys;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * iOS 风格状态栏图标。
 *
 * <p>不新增 View、不改布局，只挂 {@code ImageView#setImageDrawable} 的后置钩子：命中状态栏那三颗
 * 图标（按资源 id 名字识别）就把 drawable 换成自绘的 iOS 版本，原图标的 level（信号格 / WiFi 格）
 * 继续沿用，所以状态一变就自动刷新，占位宽度也不变。</p>
 */
final class IosStatusBarHook {

    /** 诊断：true 时把命中的图标画成红块，用于确认替换是否真的上屏。 */
    private static final boolean DEBUG_MARK = false;

    /** 电池状态缓存：ImageView 的 context 里注册一次 ACTION_BATTERY_CHANGED。 */
    private static final List<WeakReference<IosIcons.Battery>> BATTERY_DRAWABLES = new ArrayList<>();
    private static volatile int batteryLevel = -1;
    private static volatile boolean batteryCharging;
    private static volatile boolean batteryReceiverReady;
    private static java.lang.ref.WeakReference<ImageView> previewSignals;
    private static final ThreadLocal<Boolean> WRITING = new ThreadLocal<>();

    private int logged;
    private static final java.util.Set<String> SEEN = new java.util.HashSet<>();

    private final XposedInterface xposed;

    IosStatusBarHook(XposedInterface xposed) {
        this.xposed = xposed;
    }

    void install() {
        hookHierarchyDump();
        hookBatteryView();
        try {
            xposed.hook(ImageView.class.getDeclaredMethod("setImageDrawable", Drawable.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            replace(chain.getThisObject(), chain.getArg(0));
                        } catch (Throwable t) {
                            // 换图标失败绝不能影响系统绘制
                        }
                        return result;
                    });
            Logs.i("iOS 状态栏图标钩子已安装");
        } catch (Throwable t) {
            Logs.e("挂 ImageView#setImageDrawable 失败", t);
        }
    }

    /** 一次性的状态栏层级探针：从时钟往上一路打印 class + id，找出真正上屏的图标控件。 */
    private void hookHierarchyDump() {
        try {
            java.lang.reflect.Method setText = android.widget.TextView.class
                    .getDeclaredMethod("setText", CharSequence.class);
            xposed.hook(setText)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            if (self instanceof View && !dumped) {
                                String name = idName((View) self);
                                if ("clock".equals(name)) {
                                    dumped = true;
                                    dumpHierarchy((View) self);
                                }
                            }
                        } catch (Throwable ignored) {
                            // 探针失败不影响显示
                        }
                        return result;
                    });
        } catch (Throwable t) {
            Logs.w("层级探针安装失败", t);
        }
    }

    private volatile boolean dumped;

    private void dumpHierarchy(View clock) {
        try {
            StringBuilder path = new StringBuilder();
            View node = clock;
            for (int i = 0; i < 8 && node != null; i++) {
                path.append(" <- ").append(node.getClass().getSimpleName())
                        .append('#').append(idName(node))
                        .append('(').append(node.getWidth()).append('x').append(node.getHeight()).append(')');
                android.view.ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
            Logs.i("状态栏层级: " + path);
            // 再往下把最外层容器的子孙列一遍（深度 4），看图标都是什么类型
            View root = clock;
            for (int i = 0; i < 4; i++) {
                android.view.ViewParent parent = root.getParent();
                if (!(parent instanceof View)) {
                    break;
                }
                root = (View) parent;
            }
            walk(root, 0, new StringBuilder());
        } catch (Throwable t) {
            Logs.w("层级 dump 失败", t);
        }
    }

    private void walk(View view, int depth, StringBuilder indent) {
        if (view == null || depth > 4) {
            return;
        }
        Logs.i("  " + indent + view.getClass().getSimpleName() + "#" + idName(view)
                + " vis=" + view.getVisibility());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            indent.append(' ');
            for (int i = 0; i < Math.min(group.getChildCount(), 24); i++) {
                walk(group.getChildAt(i), depth + 1, indent);
            }
            if (indent.length() > 0) {
                indent.setLength(indent.length() - 1);
            }
        }
    }

    /**
     * HyperOS 3 的电池是自绘 View（BatteryIndicator / BatteryMeterIconView），
     * 不走 setImageDrawable。这里 hook 它的 onDraw：先让系统画完，再把我们的药丸盖上去。
     */
    private void hookBatteryView() {
        String[] classes = {
                "com.android.systemui.statusbar.views.BatteryIndicator",
                "com.android.systemui.miui.statusbar.BatteryMeterIconView",
        };
        for (String name : classes) {
            try {
                Class<?> type = Class.forName(name);
                java.lang.reflect.Method onDraw = type.getDeclaredMethod(
                        "onDraw", android.graphics.Canvas.class);
                onDraw.setAccessible(true);
                xposed.hook(onDraw)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                if (HookPrefs.getBoolean(Keys.IOS_STATUSBAR, false)
                                        && chain.getThisObject() instanceof android.view.View) {
                                    android.view.View view = (android.view.View) chain.getThisObject();
                                    android.graphics.Canvas canvas =
                                            (android.graphics.Canvas) chain.getArg(0);
                                    drawBatteryOver(view, canvas);
                                }
                            } catch (Throwable ignored) {
                                // 画坏了也不能让系统界面崩
                            }
                            return result;
                        });
                Logs.i("电池视图 hook 已安装：" + name);
            } catch (Throwable t) {
                Logs.i("跳过电池视图 " + name + "：" + t.getClass().getSimpleName());
            }
        }
    }

    private static IosIcons.Battery batteryDrawable = null;

    private void drawBatteryOver(android.view.View view, android.graphics.Canvas canvas) {
        IosIcons.Battery battery = batteryDrawable;
        if (battery == null) {
            battery = new IosIcons.Battery();
            batteryDrawable = battery;
        }
        battery.setTintColor(tintOfView(view));
        battery.setBattery(batteryLevel < 0 ? 100 : batteryLevel, batteryCharging);
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // 只盖图标本体所在的横向区域，右边留给系统可能画的百分比文字
        android.graphics.RectF bounds = new android.graphics.RectF(
                view.getPaddingLeft() * 0.5f, 0,
                w - view.getPaddingRight() * 0.5f - w * 0.0f, h);
        battery.setBounds((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom);
        battery.draw(canvas);
    }

    private static int tintOfView(android.view.View view) {
        try {
            if (view instanceof android.widget.TextView) {
                return ((android.widget.TextView) view).getCurrentTextColor();
            }
        } catch (Throwable ignored) {
            // 退回白色
        }
        return android.graphics.Color.WHITE;
    }

    private void replace(Object target, Object latest) {
        if (Boolean.TRUE.equals(WRITING.get())) {
            return;
        }
        if (!(target instanceof ImageView) || !(latest instanceof Drawable)) {
            return;
        }
        if (latest instanceof IosIcons.Signal
                || latest instanceof IosIcons.Wifi
                || latest instanceof IosIcons.Battery
                || latest instanceof IosIcons.Marker
                || latest instanceof IosIcons.DualSignalPreview) {
            return;                                     // 自己设的，别再套一层
        }
        if (!HookPrefs.getBoolean(Keys.IOS_STATUSBAR, false)) {
            return;
        }
        ImageView view = (ImageView) target;
        String name = idName(view);
        if (name == null) {
            return;
        }
        if (SEEN.size() < 80 && SEEN.add(name)
                && (name.contains("signal") || name.contains("wifi") || name.contains("batt")
                    || name.contains("mobile") || name.contains("sim") || name.contains("clock"))) {
            Logs.i("图标普查 id=" + name + " class=" + view.getClass().getName());
        }
        Drawable source = (Drawable) latest;
        try {
            if (name.contains("signal") && name.contains("mobile")
                    || name.equals("status_bar_signal") || name.equals("signal_icon")) {
                if (DEBUG_MARK) {
                    setDrawing(view, new IosIcons.Marker());
                    debug("MARK 信号");
                    return;
                }
                IosIcons.Signal signal = new IosIcons.Signal();
                signal.setTintColor(tintOf(view));
                signal.setSignalLevel(source.getLevel());
                setDrawing(view, signal);
                debug("信号 level=" + source.getLevel());
            } else if (name.equals("wifi_activity") || name.equals("wifi_signal")) {
                if (DEBUG_MARK) {
                    setDrawing(view, new IosIcons.Marker());
                    debug("MARK WiFi");
                    return;
                }
                IosIcons.Wifi wifi = new IosIcons.Wifi();
                wifi.setTintColor(tintOf(view));
                wifi.setWifiLevel(source.getLevel() <= 0 ? 4 : source.getLevel());
                setDrawing(view, wifi);
                debug("WiFi level=" + source.getLevel());
                maybePreviewSignals(view);
            } else if (name.startsWith("battery") && !name.contains("percentage")
                    && !name.endsWith("_out_image")) {
                if (DEBUG_MARK) {
                    setDrawing(view, new IosIcons.Marker());
                    debug("MARK 电池");
                    return;
                }
                watchBattery(view.getContext());
                IosIcons.Battery battery = new IosIcons.Battery();
                battery.setTintColor(tintOf(view));
                int level = batteryLevel >= 0 ? batteryLevel : Math.max(0, source.getLevel());
                battery.setBattery(level, batteryCharging);
                battery.setShowNumber(systemShowsPercent(view));
                BATTERY_DRAWABLES.add(new WeakReference<>(battery));
                setDrawing(view, battery);
                debug("电池 " + level + "%");
            }
        } catch (Throwable t) {
            Logs.w("替换状态栏图标失败 " + name, t);
        }
    }

    /** 系统是否正在显示电量百分比（图标内或旁边都算）：有可见的 battery_percentage* 视图就跟随。 */
    private static boolean systemShowsPercent(View anchor) {
        try {
            ViewGroup parent = anchor.getParent() instanceof ViewGroup
                    ? (ViewGroup) anchor.getParent() : null;
            if (parent == null) {
                return true;
            }
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                String name = idName(child);
                if (name != null && name.contains("battery_percentage") && child.getVisibility() == View.VISIBLE) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 判断失败就当系统在显示，宁可多画数字
        }
        return true;
    }

    /**
     * 没有实体 SIM 时的预览：在 WiFi 左边插一组「双卡信号圆点」，并把系统的「无卡 / 无服务」
     * 图标藏起来。有卡时不走这条路（真实信号由 Signal 按 level 绘制）。
     */
    private void maybePreviewSignals(ImageView wifi) {
        try {
            Context context = wifi.getContext();
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            boolean absent = tm == null || tm.getSimState() == TelephonyManager.SIM_STATE_ABSENT
                    || tm.getSimState() == TelephonyManager.SIM_STATE_NOT_READY;
            ViewGroup parent = wifi.getParent() instanceof ViewGroup ? (ViewGroup) wifi.getParent() : null;
            if (parent == null) {
                return;
            }
            ImageView existing = previewSignals == null ? null : previewSignals.get();
            if (!absent) {
                if (existing != null && existing.getParent() == parent) {
                    parent.removeView(existing);
                }
                previewSignals = null;
                return;
            }

            // 藏掉系统的无卡图标（同一行里 id 带 sim / no_service 的）
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                String name = idName(child);
                if (name != null && (name.contains("sim_null") || name.contains("no_service"))) {
                    child.setVisibility(View.INVISIBLE);
                }
            }

            if (existing != null && existing.getParent() == parent) {
                return;
            }
            IosIcons.DualSignalPreview drawable = new IosIcons.DualSignalPreview();
            drawable.setTintColor(tintOf(wifi));
            float density = context.getResources().getDisplayMetrics().density;
            ImageView view = new ImageView(context);
            view.setImageDrawable(drawable);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    (int) (34 * density), wifi.getHeight() > 0 ? wifi.getHeight() : (int) (14 * density));
            params.setMarginEnd((int) (4 * density));
            int index = parent.indexOfChild(wifi);
            parent.addView(view, Math.max(0, index), params);
            previewSignals = new java.lang.ref.WeakReference<>(view);
            debug("模拟双卡预览已插入");
        } catch (Throwable t) {
            Logs.w("插入双卡预览失败", t);
        }
    }

    private void watchBattery(Context context) {
        if (batteryReceiverReady || context == null) {
            return;
        }
        batteryReceiverReady = true;
        try {
            Intent sticky = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) {
                applyBattery(sticky);
            }
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    applyBattery(intent);
                }
            }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable t) {
            Logs.w("监听电池状态失败", t);
        }
    }

    private void applyBattery(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        if (level >= 0 && scale > 0) {
            batteryLevel = Math.round(level * 100f / scale);
        }
        for (int i = BATTERY_DRAWABLES.size() - 1; i >= 0; i--) {
            IosIcons.Battery drawable = BATTERY_DRAWABLES.get(i).get();
            if (drawable == null) {
                BATTERY_DRAWABLES.remove(i);
            } else {
                drawable.setBattery(batteryLevel < 0 ? 100 : batteryLevel, batteryCharging);
            }
        }
    }

    private static void setDrawing(ImageView view, Drawable drawable) {
        WRITING.set(Boolean.TRUE);
        try {
            view.setImageDrawable(drawable);
        } finally {
            WRITING.set(Boolean.FALSE);
        }
    }

    private void debug(String message) {
        if (logged < 12) {
            logged++;
            Logs.i("iOS 图标：" + message);
        }
    }

    /** 图标颜色跟 ImageView 的 tint；取不到就按深色状态栏给白色。 */
    private static int tintOf(ImageView view) {
        try {
            android.content.res.ColorStateList tint = view.getImageTintList();
            if (tint != null) {
                int color = tint.getColorForState(view.getDrawableState(), 0);
                if (color != 0) {
                    return color;
                }
            }
        } catch (Throwable ignored) {
            // 退回默认色
        }
        return android.graphics.Color.WHITE;
    }

    private static String idName(View view) {
        try {
            int id = view.getId();
            if (id == 0) {
                return null;
            }
            Resources res = view.getResources();
            return res.getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }
}
