package com.hyperosmod.singbox;

/**
 * 开机自启：往 Magisk / KernelSU 的 {@code service.d} 丢一个启动脚本。
 *
 * <p>应用自己没法保证开机后还活着，所以自启必须交给 root 框架的启动钩子：Magisk 与
 * KernelSU 都会在 late_start 阶段以 root 身份执行 {@code /data/adb/service.d/} 下的脚本。
 * 脚本里等 {@code sys.boot_completed} 再起服务，避免网卡还没就绪导致 tun 初始化失败。</p>
 */
public final class Autostart {

    /** 脚本名固定，便于启用/停用/删除时精确定位。 */
    public static final String SCRIPT_PATH = "/data/adb/service.d/99-singbox.sh";

    private Autostart() {
    }

    /** 脚本内容：与手动启动完全一致（先 check，再 setsid 脱离会话）。 */
    private static String script() {
        return "#!/system/bin/sh\n"
                + "# 由 HyperOSMod 生成，用于开机拉起 sing-box\n"
                + "BOX=" + SingBoxController.WORK_DIR + "\n"
                + "LOG=$BOX/sing-box.log\n"
                + "[ -x $BOX/sing-box ] || exit 0\n"
                + "[ -f $BOX/config.json ] || exit 0\n"
                + "# 等系统启动完成、网络栈就绪\n"
                + "i=0\n"
                + "while [ \"$(getprop sys.boot_completed)\" != \"1\" ] && [ $i -lt 120 ]; do\n"
                + "  sleep 1; i=$((i+1))\n"
                + "done\n"
                + "sleep 5\n"
                + "pidof sing-box >/dev/null 2>&1 && exit 0\n"
                + "$BOX/sing-box check -D $BOX -c $BOX/config.json >>$LOG 2>&1 || exit 0\n"
                + "setsid \"$BOX/sing-box\" run -D $BOX -c $BOX/config.json >>$LOG 2>&1 </dev/null &\n"
                + "exit 0\n";
    }

    /** root 框架是否支持 service.d。 */
    public static String framework() {
        RootShell.Result magisk = RootShell.exec(
                "[ -d /data/adb/magisk ] || [ -f /data/adb/magisk.db ] && echo magisk", 10);
        if (magisk.output.contains("magisk")) {
            return "Magisk";
        }
        RootShell.Result ksu = RootShell.exec(
                "[ -d /data/adb/ksu ] && echo ksu", 10);
        if (ksu.output.contains("ksu")) {
            return "KernelSU";
        }
        RootShell.Result apatch = RootShell.exec(
                "[ -d /data/adb/ap ] && echo apatch", 10);
        if (apatch.output.contains("apatch")) {
            return "APatch";
        }
        return "";
    }

    public static boolean installed() {
        RootShell.Result result = RootShell.exec(
                "[ -x " + SCRIPT_PATH + " ] && echo yes", 10);
        return result.output.contains("yes");
    }

    public static RootShell.Result install() {
        String body = script();
        String encoded = android.util.Base64.encodeToString(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        RootShell.Result result = RootShell.exec(String.join(" && ",
                "mkdir -p /data/adb/service.d",
                "echo " + encoded + " | base64 -d > " + SCRIPT_PATH,
                "chmod 755 " + SCRIPT_PATH), 30);
        if (!result.ok()) {
            return new RootShell.Result(result.code, "写入自启脚本失败：" + result.output);
        }
        return new RootShell.Result(0, "已开启开机自启：" + SCRIPT_PATH);
    }

    public static RootShell.Result uninstall() {
        RootShell.Result result = RootShell.exec("rm -f " + SCRIPT_PATH, 15);
        return result.ok()
                ? new RootShell.Result(0, "已关闭开机自启")
                : new RootShell.Result(result.code, "删除自启脚本失败：" + result.output);
    }
}
