package com.hyperosmod.xposed;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.hyperosmod.Keys;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * HyperOS 3 PowerKeeper/Greezer keeps its own "no restriction" list in
 * {@code Settings.System.MILLET_NO_RESTRICT_APP}. If GMS is missing from that list, the
 * FCM/MCS connection is killed in the background even when the broadcast hooks are
 * working. This class repairs the list from system_server and kicks GMS to reconnect.
 *
 * <p>Based on FCMGuard-HyperOS's observation of HyperOS 3. This is more reliable than
 * only modifying {@code MilletPolicy} in the powerkeeper process, because HyperOS can
 * rebuild its in-memory lists from this setting.</p>
 */
final class FcmGmsWhitelist {
    private static final String KEY = "MILLET_NO_RESTRICT_APP";
    private static final String GMS = "com.google.android.gms";
    private static final long INITIAL_DELAY_MS = 15_000L;
    private static final long PERIODIC_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context systemContext;
    private ContentObserver observer;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    void install() {
        try {
            systemContext = systemContext();
        } catch (Throwable t) {
            Logs.w("FCM: system context unavailable, cannot repair GMS whitelist", t);
            return;
        }
        if (systemContext == null) {
            Logs.w("FCM: system context is null, cannot repair GMS whitelist", null);
            return;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                repair("initial");
                handler.postDelayed(this, PERIODIC_MS);
            }
        }, INITIAL_DELAY_MS);

        try {
            observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    handler.removeCallbacksAndMessages(this);
                    handler.postDelayed(() -> repair("setting changed"), 400L);
                }
            };
            ContentResolver resolver = systemContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(KEY), false, observer);
        } catch (Throwable t) {
            Logs.w("FCM: unable to observe " + KEY, t);
        }

        try {
            SharedPreferences prefs = HookPrefs.raw();
            if (prefs != null) {
                prefListener = (sharedPreferences, key) -> {
                    if (Keys.FCM_FIX_ENABLED.equals(key) || Keys.FCM_TARGETS.equals(key)) {
                        handler.post(() -> repair("preference changed"));
                    }
                };
                prefs.registerOnSharedPreferenceChangeListener(prefListener);
            }
        } catch (Throwable t) {
            Logs.w("FCM: unable to observe FCM preferences", t);
        }

        repair("install");
    }

    private void repair(String reason) {
        try {
            if (!HookPrefs.getBoolean(Keys.FCM_FIX_ENABLED, false)) {
                return;
            }
            String current = Settings.System.getString(systemContext.getContentResolver(), KEY);
            if (containsGms(current)) {
                return;
            }
            LinkedHashSet<String> packages = parse(current);
            packages.add(GMS);
            String repaired = join(packages);
            boolean ok = Settings.System.putString(
                    systemContext.getContentResolver(), KEY, repaired);
            if (ok) {
                Logs.i("FCM: repaired GMS whitelist (" + reason + "): " + repaired);
                kickGms();
            } else {
                Logs.w("FCM: GMS whitelist write rejected; current=" + current, null);
            }
        } catch (Throwable t) {
            Logs.w("FCM: failed to repair GMS whitelist", t);
        }
    }

    private void kickGms() {
        String[] actions = {
                "com.google.android.intent.action.GCM_RECONNECT",
                "com.google.android.intent.action.GTALK_HEARTBEAT",
                "com.google.android.intent.action.MCS_HEARTBEAT"
        };
        for (String action : actions) {
            try {
                Intent intent = new Intent(action).setPackage(GMS);
                systemContext.sendBroadcast(intent);
            } catch (Throwable ignored) {
                // Best effort only.
            }
        }
        Logs.i("FCM: sent GMS reconnect/heartbeat broadcasts");
    }

    private static boolean containsGms(String value) {
        for (String part : parse(value)) {
            if (GMS.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private static LinkedHashSet<String> parse(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null) {
            return result;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String join(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static Context systemContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
        Method getSystemContext = activityThread.getMethod("getSystemContext");
        return (Context) getSystemContext.invoke(thread);
    }
}
