package com.hyperosmod.xposed;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/** Small logging facade so hooks do not need a Context. */
final class Logs {
    private static final String TAG = "HyperOSMod";
    private static volatile XposedInterface xposed;

    private Logs() {
    }

    static void attach(XposedInterface api) {
        xposed = api;
    }

    static void i(String message) {
        XposedInterface api = xposed;
        if (api != null) {
            api.log(Log.INFO, TAG, message);
        } else {
            Log.i(TAG, message);
        }
    }

    static void w(String message, Throwable t) {
        XposedInterface api = xposed;
        if (api != null) {
            api.log(Log.WARN, TAG, message, t);
        } else {
            Log.w(TAG, message, t);
        }
    }

    static void e(String message, Throwable t) {
        XposedInterface api = xposed;
        if (api != null) {
            api.log(Log.ERROR, TAG, message, t);
        } else {
            Log.e(TAG, message, t);
        }
    }
}
