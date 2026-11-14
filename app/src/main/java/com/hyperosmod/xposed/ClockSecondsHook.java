package com.hyperosmod.xposed;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import com.hyperosmod.Keys;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface;

/**
 * 状态栏时钟显示秒。
 *
 * <p>HyperOS 的时钟是 {@code com.android.systemui.statusbar.views.MiuiClock}（一个 TextView），
 * 每分钟由 {@code updateTime()} 重新生成一次文本；AOSP 老实现是
 * {@code statusbar.policy.Clock#getSmallTime()}。这里不去改系统的格式串（各版本内部字段
 * 名字不同，硬改容易被版本升级打断），而是：</p>
 *
 * <ol>
 *   <li>hook 上面两个方法，记录「系统当前这一分钟想显示的文本」作为基准；</li>
 *   <li>把秒插到基准文本里最后一个 {@code HH:mm} 之后——12/24 小时制、多行日期、
 *       带「上午/下午」的格式都能用，不依赖任何私有字段；</li>
 *   <li>起一个 1 秒的 ticker 只刷新秒数，系统下一次 updateTime 时基准自动更新。</li>
 * </ol>
 *
 * <p>写入自己算出来的文本时会置 {@link #writing} 标志，避免把「带秒的文本」又当成基准，
 * 否则秒会越叠越长。</p>
 */
final class ClockSecondsHook {
    private static final String TAG = "clock-seconds";

    private static final String[] CLOCK_CLASSES = {
            "com.android.systemui.statusbar.views.MiuiClock",
            "com.android.systemui.statusbar.policy.Clock",
    };

    /** 状态栏时钟可能的 id 名（不同版本/主题下不一样）。 */
    private static final Set<String> CLOCK_ID_NAMES = new HashSet<>(java.util.Arrays.asList(
            "clock", "status_bar_clock", "miui_clock", "statusbar_clock", "clock_text"));

    /**
     * HH:mm，后面可能已经跟着 :ss（第二组）。
     *
     * <p>用一个模式同时表达两件事：插入秒时只看第二组是否为空，剥离时直接删第二组。
     * 早先用负向前瞻写「已经有秒就不匹配」，结果 "09:41:07" 里的 "41:07" 会被当成
     * 一个时间命中，剥离逻辑反而什么都删不掉。</p>
     */
    private static final Pattern TIME_PATTERN =
            Pattern.compile("(\\d{1,2}:\\d{2})(:\\d{2})?");

    private final XposedInterface xposed;
    private final ClassLoader classLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 时钟视图 → 系统给的那条「分钟级」基准文本。 */
    private final Map<TextView, String> bases = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * 认领到的时钟视图。
     *
     * <p>原来用 {@code synchronizedSet(WeakHashMap)}，而 {@code contains} 会在 SystemUI 的
     * <b>每一次</b> {@code TextView#setText} 上抢同一把锁——通知列表滚动时这是纯损失。
     * 改成弱引用列表：读路径无锁，元素通常只有 1～2 个。</p>
     */
    private final List<WeakReference<TextView>> clockRefs = new CopyOnWriteArrayList<>();
    /** view id → 是否是时钟，热路径只做一次 Integer 比较。 */
    private final Map<Integer, Boolean> clockIdVerdicts = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean loggedFirstClock;

    private final AtomicBoolean ticking = new AtomicBoolean();
    /** 我们自己写文本时置位，防止自激。 */
    private volatile boolean writing;

    private Runnable ticker;

    ClockSecondsHook(XposedInterface xposed, ClassLoader classLoader) {
        this.xposed = xposed;
        this.classLoader = classLoader;
    }

    void install() {
        // 通用层优先：不认类名，只认「id 叫 clock 的 TextView」，任何实现都能兜住。
        int hooked = hookTextViewSetText();
        for (String className : CLOCK_CLASSES) {
            hooked += hookClockClass(className);
        }
        if (hooked == 0) {
            Logs.w(TAG + ": 没有任何 hook 装上，显秒不可用", null);
        } else {
            Logs.i(TAG + ": 已安装 " + hooked + " 个 hook");
        }
        registerPrefsListener();
        if (isEnabled()) {
            startTicker();
            warnIfClockMissing();
        }
    }

    /**
     * 通用层：SystemUI 里每次时钟刷新最终都会落到 {@code TextView#setText}。命中判定只看
     * 一次 id（有缓存），其它 TextView（通知、运营商名…）不会付额外代价。
     */
    private int hookTextViewSetText() {
        int hooked = 0;
        for (String name : new String[]{"setText", "setTextKeepState"}) {
            try {
                Method method = TextView.class.getDeclaredMethod(name, CharSequence.class);
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof TextView && !writing) {
                                    maybeRegister((TextView) self);
                                }
                            } catch (Throwable t) {
                                Logs.w(TAG + ": setText callback failed", t);
                            }
                            return result;
                        });
                hooked++;
            } catch (Throwable t) {
                Logs.w(TAG + ": 无法 hook TextView#" + name, t);
            }
        }
        if (hooked > 0) {
            Logs.i(TAG + ": hooked TextView#setText");
        }
        return hooked;
    }

    private void maybeRegister(TextView view) {
        // 快路径：先查 id 判定（无锁、无分配），非时钟视图一次整数比较就退出。
        Boolean verdict = clockIdVerdicts.get(view.getId());
        if (Boolean.FALSE.equals(verdict)) {
            return;
        }
        if (isClock(view)) {
            captureBase(view);
            if (isEnabled()) {
                applySeconds(view);
                startTicker();
            }
            return;
        }
        if (looksLikeClock(view)) {
            register(view);
        }
    }

    /** 视图是否已经被认领为时钟（弱引用列表，无锁）。 */
    private boolean isClock(TextView view) {
        for (WeakReference<TextView> ref : clockRefs) {
            if (ref.get() == view) {
                return true;
            }
        }
        return false;
    }

    private boolean hasClocks() {
        for (WeakReference<TextView> ref : clockRefs) {
            if (ref.get() != null) {
                return true;
            }
        }
        return false;
    }

    /** id 名或类名判定，结果按 id 缓存。 */
    private boolean looksLikeClock(TextView view) {
        int id = view.getId();
        Boolean verdict = clockIdVerdicts.get(id);
        if (verdict != null) {
            return verdict;
        }
        boolean isClock = false;
        if (id != android.view.View.NO_ID) {
            try {
                String entry = view.getContext().getResources().getResourceEntryName(id);
                isClock = entry != null
                        && CLOCK_ID_NAMES.contains(entry.toLowerCase(Locale.ROOT));
            } catch (Throwable ignored) {
                isClock = false;
            }
        }
        if (!isClock) {
            String simple = view.getClass().getSimpleName();
            isClock = simple != null && simple.contains("Clock");
        }
        if (id != android.view.View.NO_ID) {
            clockIdVerdicts.put(id, isClock);
        }
        return isClock;
    }

    /** 开关开着却迟迟没认领到时钟时，留一条可以直接抄回来的诊断行。 */
    private void warnIfClockMissing() {
        mainHandler.postDelayed(() -> {
            if (isEnabled() && !hasClocks()) {
                Logs.w(TAG + ": 开关已开，但 5 秒内没抓到状态栏时钟；"
                        + "可能是 Compose 时钟，或时钟不在 SystemUI 进程", null);
            }
        }, 5000L);
    }

    private int hookClockClass(String className) {
        Class<?> type = Reflect.findClass(classLoader, className);
        if (type == null) {
            return 0;
        }
        Set<Method> targets = new LinkedHashSet<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                String name = method.getName();
                if ("updateTime".equals(name) && method.getParameterCount() == 0) {
                    targets.add(method);
                } else if ("onAttachedToWindow".equals(name) && method.getParameterCount() == 0) {
                    targets.add(method);
                }
            }
            // updateTime / onAttachedToWindow 只在声明类里找一层就够，继续往上跑会
            // 重复 hook TextView 的同名方法（那是系统全局的，不能碰）。
            break;
        }
        int hooked = 0;
        for (Method method : targets) {
            try {
                method.setAccessible(true);
                xposed.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self instanceof TextView) {
                                    register((TextView) self);
                                }
                            } catch (Throwable t) {
                                Logs.w(TAG + ": callback failed", t);
                            }
                            return result;
                        });
                hooked++;
            } catch (Throwable t) {
                Logs.w(TAG + ": 无法 hook " + className + "#" + method.getName(), t);
            }
        }
        return hooked;
    }

    // ==================================================================================
    // 状态
    // ==================================================================================

    private boolean isEnabled() {
        return HookPrefs.getBoolean(Keys.CLOCK_SECONDS, false);
    }

    private void register(TextView clock) {
        if (clock == null) {
            return;
        }
        boolean first = !isClock(clock);
        if (first) {
            // 顺手清掉已被回收的弱引用，列表长度保持在 1～2 个。
            clockRefs.removeIf(ref -> ref.get() == null);
            clockRefs.add(new WeakReference<>(clock));
        }
        if (first && !loggedFirstClock) {
            loggedFirstClock = true;
            String entry = IconFinder.entryName(clock);
            Logs.i(TAG + ": 认领时钟 " + clock.getClass().getName()
                    + " id=" + (entry == null ? String.valueOf(clock.getId()) : entry)
                    + " 开关=" + isEnabled());
        }
        // 系统刚写完文本，这就是这一分钟的基准。
        captureBase(clock);
        if (isEnabled()) {
            applySeconds(clock);
            startTicker();
        } else if (bases.containsKey(clock)) {
            restore(clock);
        }
    }

    private void captureBase(TextView clock) {
        if (writing) {
            return;
        }
        CharSequence text = clock.getText();
        if (text == null || text.length() == 0) {
            return;
        }
        String base = stripSeconds(text.toString());
        if (base.isEmpty()) {
            return;
        }
        bases.put(clock, base);
    }

    /** 把已经带秒的旧文本还原成分钟级，避免叠加。 */
    static String stripSeconds(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher matcher = TIME_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        while (matcher.find()) {
            out.append(text, cursor, matcher.start());
            out.append(matcher.group(1));
            cursor = matcher.end();
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    private void applySeconds(TextView clock) {
        String base = bases.get(clock);
        if (base == null || base.isEmpty()) {
            return;
        }
        String withSeconds = insertSeconds(base, currentSecond());
        if (withSeconds.equals(clock.getText() == null ? "" : clock.getText().toString())) {
            return;
        }
        writing = true;
        try {
            clock.setText(withSeconds);
        } finally {
            writing = false;
        }
    }

    private void restore(TextView clock) {
        String base = bases.get(clock);
        if (base == null) {
            return;
        }
        CharSequence current = clock.getText();
        if (current != null && base.contentEquals(current)) {
            return;
        }
        writing = true;
        try {
            clock.setText(base);
        } finally {
            writing = false;
        }
    }

    /** 在最后一个 HH:mm 之后插入 :ss；文本里没有时间时不改。 */
    static String insertSeconds(String base, int second) {
        if (base == null || base.isEmpty()) {
            return base;
        }
        Matcher matcher = TIME_PATTERN.matcher(base);
        int insertAt = -1;
        while (matcher.find()) {
            if (matcher.group(2) == null) {
                insertAt = matcher.end();
            }
        }
        if (insertAt < 0) {
            // 没有时间，或者系统格式里本来就带秒：一律不重复追加。
            return base;
        }
        String suffix = String.format(Locale.ROOT, ":%02d", Math.max(0, Math.min(59, second)));
        return base.substring(0, insertAt) + suffix + base.substring(insertAt);
    }

    private static int currentSecond() {
        return (int) ((System.currentTimeMillis() / 1000L) % 60L);
    }

    // ==================================================================================
    // Ticker
    // ==================================================================================

    private void startTicker() {
        if (!ticking.compareAndSet(false, true)) {
            return;
        }
        ticker = new Runnable() {
            @Override
            public void run() {
                if (!isEnabled()) {
                    ticking.set(false);
                    ticker = null;
                    restoreAll();
                    return;
                }
                try {
                    for (TextView clock : snapshot()) {
                        applySeconds(clock);
                    }
                } catch (Throwable t) {
                    Logs.w(TAG + ": tick failed", t);
                }
                mainHandler.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L));
            }
        };
        mainHandler.post(ticker);
    }

    private void restoreAll() {
        for (TextView clock : snapshot()) {
            restore(clock);
        }
    }

    private ArrayList<TextView> snapshot() {
        ArrayList<TextView> result = new ArrayList<>(clockRefs.size());
        for (WeakReference<TextView> ref : clockRefs) {
            TextView clock = ref.get();
            if (clock != null) {
                result.add(clock);
            }
        }
        return result;
    }

    private void registerPrefsListener() {
        android.content.SharedPreferences prefs = HookPrefs.raw();
        if (prefs == null) {
            Logs.w(TAG + ": 无法监听开关，切换后需重启系统界面", null);
            return;
        }
        prefs.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
            if (!Keys.CLOCK_SECONDS.equals(key)) {
                return;
            }
            if (isEnabled()) {
                mainHandler.post(() -> {
                    for (TextView clock : snapshot()) {
                        captureBase(clock);
                        applySeconds(clock);
                    }
                    startTicker();
                });
            } else {
                mainHandler.post(this::restoreAll);
            }
        });
    }
}
