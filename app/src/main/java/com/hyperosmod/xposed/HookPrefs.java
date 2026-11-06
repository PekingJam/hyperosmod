package com.hyperosmod.xposed;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * Read-only view of the module preferences inside a hooked process.
 *
 * <p>LSPosed serves these values from its own process; the manager app mirrors its
 * private SharedPreferences into the {@code hyperosmod_prefs} group.</p>
 */
final class HookPrefs {
    private static volatile SharedPreferences prefs;

    private HookPrefs() {
    }

    static void init(XposedInterface xposed) {
        try {
            prefs = xposed.getRemotePreferences(com.hyperosmod.Keys.REMOTE_GROUP);
        } catch (Throwable t) {
            prefs = null;
            Logs.w("Remote preferences are unavailable; all features stay disabled", t);
        }
    }

    static boolean getBoolean(String key, boolean def) {
        SharedPreferences p = prefs;
        if (p == null) {
            return def;
        }
        try {
            return p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static int getInt(String key, int def) {
        SharedPreferences p = prefs;
        if (p == null) {
            return def;
        }
        try {
            return p.getInt(key, def);
        } catch (Throwable t) {
            // Tolerate values that were written as strings by older builds.
            try {
                String raw = p.getString(key, null);
                return raw == null ? def : Integer.parseInt(raw);
            } catch (Throwable ignored) {
                return def;
            }
        }
    }

    static Set<String> getStringSet(String key) {
        SharedPreferences p = prefs;
        if (p == null) {
            return Collections.emptySet();
        }
        try {
            Set<String> set = p.getStringSet(key, null);
            return set == null ? Collections.emptySet() : new LinkedHashSet<>(set);
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    static SharedPreferences raw() {
        return prefs;
    }
}
