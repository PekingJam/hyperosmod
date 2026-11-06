package com.hyperosmod;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.material.color.DynamicColors;
import com.hyperosmod.data.Prefs;

import java.util.Collections;
import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class App extends Application implements XposedServiceHelper.OnServiceListener {
    private static final String TAG = "HyperOSMod";

    private static volatile XposedService service;

    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        DynamicColors.applyToActivitiesIfAvailable(this);
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(@NonNull XposedService boundService) {
        service = boundService;
        try {
            Prefs.attachRemote(boundService.getRemotePreferences(Keys.REMOTE_GROUP));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to attach remote preferences", t);
            Prefs.detachRemote();
            return;
        }
        try {
            Log.i(TAG, "LSPosed service bound, scope=" + boundService.getScope());
        } catch (Throwable t) {
            Log.w(TAG, "Unable to query module scope", t);
        }
    }

    @Override
    public void onServiceDied(@NonNull XposedService diedService) {
        if (service == diedService) {
            service = null;
        }
        Prefs.detachRemote();
        Log.i(TAG, "LSPosed service died");
    }

    public static boolean isActivated() {
        return service != null && Prefs.isServiceBound();
    }

    /** True when the module is active and both static scopes are present. */
    public static boolean hasRequiredScope() {
        XposedService current = service;
        if (current == null) {
            return false;
        }
        try {
            List<String> scope = current.getScope();
            if (scope == null) {
                return false;
            }
            return scope.contains("com.android.systemui") && scope.contains("system");
        } catch (Throwable t) {
            // The service is bound; static scope is declared in the APK. If this framework
            // build cannot answer getScope(), do not claim the scope is broken.
            return true;
        }
    }

    public static List<String> currentScope() {
        XposedService current = service;
        if (current == null) {
            return Collections.emptyList();
        }
        try {
            List<String> scope = current.getScope();
            return scope == null ? Collections.emptyList() : scope;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }
}
