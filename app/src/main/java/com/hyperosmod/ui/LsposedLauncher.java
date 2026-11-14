package com.hyperosmod.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import com.hyperosmod.R;

/**
 * 打开 LSPosed 管理器。
 *
 * <p>1.x 的管理器是个普通应用，{@code getLaunchIntentForPackage} 就能拉起；<b>2.x 换了机制</b>：
 * 管理器寄生在 {@code com.android.shell} 里、桌面图标默认隐藏，包里也没有可用的启动入口，
 * 所以直接查启动 Intent 会返回 null（表现为「明明装了却提示找不到」）。2.x 的官方唤起方式是
 * 发一次秘密代码广播 {@code *#*#5776733#*#*}，需要 root 才发得出去。</p>
 *
 * <p>这里按「普通启动 → 显式 Activity → 2.x 秘密代码（root）→ 应用详情页」的顺序退让，
 * 每一步失败才走下一步。</p>
 */
final class LsposedLauncher {

    private static final String MANAGER_PACKAGE = "org.lsposed.manager";
    private static final String MANAGER_ACTIVITY = MANAGER_PACKAGE + ".ui.activity.MainActivity";
    /** LSPosed 注册的秘密代码，等价于拨号盘输入 *#*#5776733#*#*。 */
    private static final String SECRET_CODE_COMMAND =
            "am broadcast -a android.telephony.action.SECRET_CODE"
                    + " -d android_secret_code://5776733";

    private LsposedLauncher() {
    }

    static void open(Context context) {
        final Context app = context.getApplicationContext();
        if (start(app, launchIntent(app))) {
            return;
        }
        if (start(app, explicitIntent())) {
            return;
        }
        // 2.x：只能由 root 发秘密代码广播，管理器会自己把界面挂到 com.android.shell 上。
        new Thread(() -> {
            boolean ok = runRoot(SECRET_CODE_COMMAND);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (ok) {
                    return;
                }
                if (openAppDetails(app)) {
                    return;
                }
                Toast.makeText(app, R.string.open_lsposed_missing, Toast.LENGTH_LONG).show();
            });
        }, "open-lsposed").start();
    }

    private static Intent launchIntent(Context context) {
        try {
            return context.getPackageManager().getLaunchIntentForPackage(MANAGER_PACKAGE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Intent explicitIntent() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(MANAGER_PACKAGE, MANAGER_ACTIVITY));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static boolean start(Context context, Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 实在打不开管理器时，至少把它的应用详情页摆到用户面前。 */
    private static boolean openAppDetails(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MANAGER_PACKAGE, 0);
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + MANAGER_PACKAGE));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean runRoot(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            return process.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
