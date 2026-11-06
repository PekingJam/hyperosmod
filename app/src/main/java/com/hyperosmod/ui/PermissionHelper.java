package com.hyperosmod.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

import java.util.List;

/** Runtime checks and settings deep links for the permissions this module needs. */
public final class PermissionHelper {
    public static final String MI_FITNESS = "com.mi.health";
    public static final String MI_WEARABLE = "com.xiaomi.wearable";

    private PermissionHelper() {
    }

    /**
     * HyperOS restricts the package list for apps that have not been allowed to query all
     * packages. A normal install grants QUERY_ALL_PACKAGES automatically, but the OEM can
     * still limit the result, so check both the permission and a few sentinel packages.
     */
    public static boolean hasAppListAccess(Context context) {
        try {
            if (context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            List<ApplicationInfo> apps = context.getPackageManager()
                    .getInstalledApplications(PackageManager.MATCH_ALL);
            if (apps.size() < 5) {
                return false;
            }
            boolean hasSettings = false;
            boolean hasOtherUserApp = false;
            for (ApplicationInfo info : apps) {
                if ("com.android.settings".equals(info.packageName)) {
                    hasSettings = true;
                }
                if (!context.getPackageName().equals(info.packageName)
                        && (info.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                    hasOtherUserApp = true;
                }
            }
            return hasSettings && hasOtherUserApp;
        } catch (Throwable ignored) {
            // Fall through to "not granted".
        }
        return false;
    }

    public static boolean isMiFitnessInstalled(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : new String[]{MI_FITNESS, MI_WEARABLE}) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (Throwable ignored) {
                // Try the next package name.
            }
        }
        return false;
    }

    /**
     * DND access is a system-enforced special access granted to Mi Fitness itself. The
     * module cannot grant it, but it can read the secure setting and guide the user.
     */
    public static boolean hasMiFitnessDndAccess(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_policy_access_packages");
            if (enabled == null) {
                return false;
            }
            return enabled.contains(MI_FITNESS) || enabled.contains(MI_WEARABLE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Opens the MIUI permission editor for this app, with a stock Settings fallback. */
    public static void openAppListSettings(Context context) {
        String[][] editors = {
                {"com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity"}
        };
        for (String[] editor : editors) {
            try {
                Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
                intent.setClassName(editor[0], editor[1]);
                intent.putExtra("extra_pkgname", context.getPackageName());
                if (intent.resolveActivity(context.getPackageManager()) != null) {
                    start(context, intent);
                    return;
                }
            } catch (Throwable ignored) {
                // Try the next editor/fallback.
            }
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            start(context, intent);
        } catch (Throwable ignored) {
            // Nothing else we can do.
        }
    }

    /** Opens the system page that lists apps with DND access. */
    public static void openDndAccessSettings(Context context) {
        try {
            start(context, new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            return;
        } catch (Throwable ignored) {
            // Fall back to Mi Fitness app details.
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + MI_FITNESS));
            start(context, intent);
        } catch (Throwable ignored) {
            // Nothing else we can do.
        }
    }

    private static void start(Context context, Intent intent) {
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }
}
