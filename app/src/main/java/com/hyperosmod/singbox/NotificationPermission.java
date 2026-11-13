package com.hyperosmod.singbox;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/**
 * 通知权限的统一入口。
 *
 * <p>Android 13 起常驻通知也要运行时授权，没授权时前台服务照样能跑，但通知栏里什么都看不到，
 * 用户会以为「开关打开了却没反应」。所以启动、打开开关、应用回到前台时都走这里检查一次。</p>
 */
public final class NotificationPermission {

    public static final int REQUEST_CODE = 21;

    private NotificationPermission() {
    }

    public static boolean granted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 缺权限就弹系统授权框；已经授权则什么都不做。 */
    public static void request(Activity activity) {
        if (granted(activity)) {
            return;
        }
        try {
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
        } catch (Throwable ignored) {
            // 个别 ROM 会限制请求，退回让用户手动去设置里开
        }
    }

    /** 用户拒绝过、系统不再弹框时的兜底：直接跳到本应用的通知设置。 */
    public static void openSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Throwable t) {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(fallback);
            } catch (Throwable ignored) {
                // 两条路都走不通就不拦着了
            }
        }
    }
}
