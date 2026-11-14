package com.hyperosmod.xposed;

import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * Read-only view of the module preferences inside a hooked process.
 *
 * <p>LSPosed serves these values from its own process; the manager app mirrors its
 * private SharedPreferences into the {@code hyperosmod_prefs} group.</p>
 *
 * <p><b>每次读取都进一趟 SharedPreferences 太贵</b>：通知图标、状态栏这类路径每秒会读很多次，
 * 所以这里按 key 缓存。失效有两条路，确保「切了开关不重启系统界面也能生效」：</p>
 * <ul>
 *   <li>注册 {@code OnSharedPreferenceChangeListener}，一变立刻清缓存；</li>
 *   <li>兜底 TTL：时间到了整份丢掉重读，即使监听从未回调，最多也只旧 {@value #TTL_MS}ms。</li>
 * </ul>
 */
final class HookPrefs {
    /** 缓存兜底有效期；只在读取时比一次 uptime，命中路径成本可忽略。 */
    private static final long TTL_MS = 1500L;

    private static volatile SharedPreferences prefs;

    private static final ConcurrentHashMap<String, Boolean> BOOLEANS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> INTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> STRINGS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Set<String>> SETS = new ConcurrentHashMap<>();

    private static volatile long clearedAt;

    private HookPrefs() {
    }

    static void init(XposedInterface xposed) {
        try {
            prefs = xposed.getRemotePreferences(com.hyperosmod.Keys.REMOTE_GROUP);
        } catch (Throwable t) {
            prefs = null;
            Logs.w("Remote preferences are unavailable; all features stay disabled", t);
        }
        clear();
        listen();
    }

    private static void listen() {
        SharedPreferences p = prefs;
        if (p == null) {
            return;
        }
        try {
            p.registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> clear());
        } catch (Throwable t) {
            Logs.w("Unable to observe preference changes; falling back to a 1.5s refresh", t);
        }
    }

    /** 清空全部缓存；任何一条设置变化、或 TTL 到期时调用。 */
    private static void clear() {
        BOOLEANS.clear();
        INTS.clear();
        STRINGS.clear();
        SETS.clear();
        clearedAt = SystemClock.uptimeMillis();
    }

    private static void expireIfStale() {
        if (SystemClock.uptimeMillis() - clearedAt > TTL_MS) {
            clear();
        }
    }

    static boolean getBoolean(String key, boolean def) {
        expireIfStale();
        Boolean cached = BOOLEANS.get(key);
        if (cached != null) {
            return cached;
        }
        SharedPreferences p = prefs;
        boolean value = def;
        if (p != null) {
            try {
                value = p.getBoolean(key, def);
            } catch (Throwable ignored) {
                value = def;
            }
        }
        BOOLEANS.put(key, value);
        return value;
    }

    static int getInt(String key, int def) {
        expireIfStale();
        Integer cached = INTS.get(key);
        if (cached != null) {
            return cached;
        }
        SharedPreferences p = prefs;
        int value = def;
        if (p != null) {
            try {
                value = p.getInt(key, def);
            } catch (Throwable t) {
                // Tolerate values that were written as strings by older builds.
                try {
                    String raw = p.getString(key, null);
                    if (raw != null) {
                        value = Integer.parseInt(raw);
                    }
                } catch (Throwable ignored) {
                    value = def;
                }
            }
        }
        INTS.put(key, value);
        return value;
    }

    static String getString(String key, String def) {
        expireIfStale();
        String cached = STRINGS.get(key);
        if (cached != null) {
            return cached;
        }
        SharedPreferences p = prefs;
        String value = def;
        if (p != null) {
            try {
                value = p.getString(key, def);
            } catch (Throwable ignored) {
                value = def;
            }
        }
        if (value != null) {
            STRINGS.put(key, value);
        }
        return value;
    }

    static Set<String> getStringSet(String key) {
        expireIfStale();
        Set<String> cached = SETS.get(key);
        if (cached != null) {
            return cached;
        }
        SharedPreferences p = prefs;
        Set<String> value = Collections.emptySet();
        if (p != null) {
            try {
                Set<String> set = p.getStringSet(key, null);
                if (set != null && !set.isEmpty()) {
                    // 复制一份并冻结：调用方只读，省掉每次调用都新建集合。
                    value = Collections.unmodifiableSet(new LinkedHashSet<>(set));
                }
            } catch (Throwable ignored) {
                value = Collections.emptySet();
            }
        }
        SETS.put(key, value);
        return value;
    }

    static SharedPreferences raw() {
        return prefs;
    }
}
