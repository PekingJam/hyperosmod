package com.hyperosmod.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * App-side preference store.
 *
 * <p>Values are stored in a normal private SharedPreferences file and mirrored to the
 * libxposed remote preference group so that hooked processes can read them. The mirror is
 * only available while the LSPosed service is bound.</p>
 */
public final class Prefs {
    private static volatile SharedPreferences local;
    private static volatile SharedPreferences remote;

    private static final CopyOnWriteArrayList<Runnable> SERVICE_LISTENERS = new CopyOnWriteArrayList<>();

    private Prefs() {
    }

    public static void init(Context context) {
        local = context.getApplicationContext()
                .getSharedPreferences(com.hyperosmod.Keys.PREFS_FILE, Context.MODE_PRIVATE);
    }

    /** Called from Application when LSPosed binds. */
    public static synchronized void attachRemote(SharedPreferences remotePrefs) {
        remote = remotePrefs;
        syncLocalToRemote();
        notifyServiceListeners();
    }

    /** Called from Application when LSPosed dies. */
    public static synchronized void detachRemote() {
        remote = null;
        notifyServiceListeners();
    }

    public static boolean isServiceBound() {
        return remote != null;
    }

    public static void addServiceListener(Runnable listener) {
        SERVICE_LISTENERS.addIfAbsent(listener);
    }

    public static void removeServiceListener(Runnable listener) {
        SERVICE_LISTENERS.remove(listener);
    }

    private static void notifyServiceListeners() {
        for (Runnable listener : SERVICE_LISTENERS) {
            try {
                listener.run();
            } catch (Throwable ignored) {
                // A broken listener must not take down the app.
            }
        }
    }

    private static SharedPreferences local() {
        SharedPreferences p = local;
        if (p == null) {
            throw new IllegalStateException("Prefs.init() was not called");
        }
        return p;
    }

    private static void syncLocalToRemote() {
        SharedPreferences r = remote;
        if (r == null) {
            return;
        }
        try {
            Map<String, ?> localAll = local().getAll();
            Map<String, ?> remoteAll = r.getAll();
            if (localAll.equals(remoteAll)) {
                return;
            }
            SharedPreferences.Editor editor = r.edit().clear();
            for (Map.Entry<String, ?> entry : localAll.entrySet()) {
                put(editor, entry.getKey(), entry.getValue());
            }
            editor.commit();
        } catch (Throwable ignored) {
            // The framework may be read-only while it is shutting down; local values remain.
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof Set<?>) {
            editor.putStringSet(key, new LinkedHashSet<>((Set<String>) value));
        }
    }

    private static void write(String key, Object value) {
        SharedPreferences.Editor localEditor = local().edit();
        put(localEditor, key, value);
        localEditor.apply();

        SharedPreferences r = remote;
        if (r != null) {
            try {
                SharedPreferences.Editor remoteEditor = r.edit();
                put(remoteEditor, key, value);
                remoteEditor.apply();
            } catch (Throwable ignored) {
                // Remote is best effort; the next bind will re-sync everything.
            }
        }
    }

    public static boolean getBoolean(String key, boolean def) {
        return local().getBoolean(key, def);
    }

    public static void setBoolean(String key, boolean value) {
        write(key, value);
    }

    public static int getInt(String key, int def) {
        return local().getInt(key, def);
    }

    public static void setInt(String key, int value) {
        write(key, value);
    }

    public static Set<String> getStringSet(String key) {
        Set<String> stored = local().getStringSet(key, Collections.emptySet());
        return stored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stored);
    }

    public static void setStringSet(String key, Set<String> value) {
        write(key, new LinkedHashSet<>(value));
    }

    /**
     * Copy every local value into the remote group. Used by the application when the
     * service is (re)connected and by the settings screen after a bulk update.
     */
    public static synchronized void pushAll() {
        syncLocalToRemote();
    }
}

