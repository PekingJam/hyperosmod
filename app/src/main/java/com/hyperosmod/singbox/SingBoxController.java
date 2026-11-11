package com.hyperosmod.singbox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * sing-box 的完整控制逻辑：全部由应用自己完成，不依赖外部脚本。
 *
 * <p>shell 只用来做「必须 root 才干得了的事」——写文件到 {@code /data/local/tmp}、解压、
 * chmod、起停进程。所有判断（该不该更新、哪个通道、配置是否合法、pid 是否还活着）
 * 都在 Java 里做，界面上看到什么就是代码里算出来的什么。</p>
 *
 * <p>目录布局沿用原来的习惯，方便和旧配置共存：</p>
 * <pre>
 * /data/local/tmp/box/
 *   sing-box           二进制
 *   sing-box.bak       上一版二进制
 *   config.json        配置
 *   config.json.bak    上一版配置
 *   sing-box.log       运行日志
 *   sing-box.pid       进程号
 * </pre>
 */
public final class SingBoxController {

    public static final String WORK_DIR = "/data/local/tmp/box";
    private static final String BINARY = WORK_DIR + "/sing-box";
    private static final String BINARY_BAK = WORK_DIR + "/sing-box.bak";
    private static final String CONF = WORK_DIR + "/config.json";
    private static final String CONF_BAK = WORK_DIR + "/config.json.bak";
    private static final String LOG = WORK_DIR + "/sing-box.log";
    private static final String PID_FILE = WORK_DIR + "/sing-box.pid";

    private static final String REPO_API =
            "https://api.github.com/repos/sagernet/sing-box/releases?per_page=40";

    /** 进度回调：界面据此更新按钮状态与输出区。 */
    public interface Progress {
        void onStep(String message);
    }

    private SingBoxController() {
    }

    // ==================================================================================
    // 状态
    // ==================================================================================

    public static final class Status {
        public final String version;
        public final int pid;
        public final boolean rootGranted;

        Status(String version, int pid, boolean rootGranted) {
            this.version = version;
            this.pid = pid;
            this.rootGranted = rootGranted;
        }

        public boolean running() {
            return pid > 0;
        }

        public boolean beta() {
            return version != null && version.contains("-");
        }
    }

    public static Status status() {
        if (!RootShell.hasRoot()) {
            return new Status("—", 0, false);
        }
        String version = "未安装";
        RootShell.Result versionResult = RootShell.exec(
                "[ -x " + BINARY + " ] && " + BINARY + " version", 10);
        if (versionResult.ok() && !versionResult.output.isEmpty()) {
            String first = versionResult.output.split("\n")[0];
            version = first.replace("sing-box version ", "").trim();
        }
        int pid = currentPid();
        return new Status(version, pid, true);
    }

    private static int currentPid() {
        RootShell.Result result = RootShell.exec("pidof -s sing-box", 5);
        if (!result.ok() || result.output.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(result.output.split("\\s+")[0].trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    // ==================================================================================
    // 启停
    // ==================================================================================

    public static RootShell.Result start() {
        int pid = currentPid();
        if (pid > 0) {
            return new RootShell.Result(0, "已在运行（PID: " + pid + "）");
        }
        if (!exists(BINARY)) {
            return new RootShell.Result(1, "未找到 " + BINARY + "，请先更新安装");
        }
        if (!exists(CONF)) {
            return new RootShell.Result(1, "配置不存在：" + CONF + "，请先更新配置");
        }
        RootShell.Result check = RootShell.exec(
                BINARY + " check -D " + WORK_DIR + " -c " + CONF, 30);
        if (!check.ok()) {
            return new RootShell.Result(check.code,
                    "配置检查失败，未启动：\n" + check.output);
        }
        // setsid + nohup：脱离当前会话，界面的进程退出后 sing-box 继续跑。
        RootShell.Result launched = RootShell.exec(
                "cd " + WORK_DIR + " && setsid nohup " + BINARY + " run -D " + WORK_DIR
                        + " -c " + CONF + " >> " + LOG + " 2>&1 </dev/null & echo $!",
                15);
        int newPid = currentPid();
        if (newPid <= 0) {
            return new RootShell.Result(1,
                    "启动失败，日志末尾：\n" + tail(15) + "\n" + launched.output);
        }
        RootShell.exec("echo " + newPid + " > " + PID_FILE, 5);
        return new RootShell.Result(0, "启动成功（PID: " + newPid + "）");
    }

    public static RootShell.Result stop() {
        int pid = currentPid();
        if (pid <= 0) {
            return new RootShell.Result(0, "sing-box 未在运行");
        }
        RootShell.exec("kill " + pid, 10);
        for (int i = 0; i < 10; i++) {
            if (currentPid() <= 0) {
                RootShell.exec("rm -f " + PID_FILE, 5);
                return new RootShell.Result(0, "已停止（PID: " + pid + "）");
            }
            sleep(1000);
        }
        RootShell.exec("kill -9 " + pid, 10);
        RootShell.exec("rm -f " + PID_FILE, 5);
        return new RootShell.Result(currentPid() <= 0 ? 0 : 1,
                currentPid() <= 0 ? "已强制停止" : "停止失败，进程仍在");
    }

    public static RootShell.Result restart() {
        RootShell.Result stopped = stop();
        RootShell.Result started = start();
        return new RootShell.Result(started.code, stopped.output + "\n" + started.output);
    }

    // ==================================================================================
    // 版本
    // ==================================================================================

    /** GitHub 上的一个发布。 */
    public static final class Release {
        public final String tag;
        public final boolean prerelease;
        public final String assetUrl;
        public final String assetName;

        Release(String tag, boolean prerelease, String assetUrl, String assetName) {
            this.tag = tag;
            this.prerelease = prerelease;
            this.assetUrl = assetUrl;
            this.assetName = assetName;
        }

        public String display() {
            return prerelease ? tag + "  [测试版]" : tag;
        }
    }

    /** 拉取发布列表；应用自身能联网即可，不需要 root。 */
    public static List<Release> releases() {
        List<Release> result = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(REPO_API).openConnection();
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            if (connection.getResponseCode() != 200) {
                return result;
            }
            String body = readAll(connection.getInputStream());
            JSONArray array = new JSONArray(body);
            for (int i = 0; i < array.length(); i++) {
                JSONObject release = array.optJSONObject(i);
                if (release == null || release.optBoolean("draft")) {
                    continue;
                }
                String tag = release.optString("tag_name", "");
                if (tag.isEmpty()) {
                    continue;
                }
                JSONArray assets = release.optJSONArray("assets");
                String url = "";
                String name = "";
                if (assets != null) {
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject asset = assets.optJSONObject(j);
                        if (asset == null) {
                            continue;
                        }
                        String assetName = asset.optString("name", "");
                        if (assetName.endsWith("android-arm64.tar.gz")
                                || assetName.endsWith("linux-arm64.tar.gz")) {
                            url = asset.optString("browser_download_url", "");
                            name = assetName;
                            break;
                        }
                    }
                }
                result.add(new Release(tag, release.optBoolean("prerelease"), url, name));
            }
        } catch (Throwable ignored) {
            // 网络异常返回已解析部分
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return result;
    }

    /** 当前通道的最新发布。 */
    public static Release latestForChannel(boolean beta) {
        for (Release release : releases()) {
            if (release.prerelease == beta && !release.assetUrl.isEmpty()) {
                return release;
            }
        }
        return null;
    }

    /** 安装指定版本：下载 → 解压 → 试运行 → 替换（失败自动回滚）。 */
    public static RootShell.Result install(Context context, Release release, Progress progress) {
        if (release == null || release.assetUrl.isEmpty()) {
            return new RootShell.Result(1, "没有可用的 arm64 安装包");
        }
        File downloaded;
        try {
            progress.onStep("下载 " + release.assetName + " …");
            downloaded = download(context, release.assetUrl, release.assetName);
        } catch (Throwable t) {
            return new RootShell.Result(1, "下载失败：" + t.getMessage());
        }

        RootShell.exec("mkdir -p " + WORK_DIR + "/tmp && rm -rf " + WORK_DIR + "/tmp/ex", 10);
        progress.onStep("解压中…");
        RootShell.Result unpack = RootShell.exec(
                "mkdir -p " + WORK_DIR + "/tmp/ex && tar -xzf " + downloaded.getAbsolutePath()
                        + " -C " + WORK_DIR + "/tmp/ex", 120);
        if (!unpack.ok()) {
            // toybox tar 有时不支持 -z，退回 gzip 管道
            unpack = RootShell.exec("gzip -dc " + downloaded.getAbsolutePath()
                    + " | tar -xf - -C " + WORK_DIR + "/tmp/ex", 120);
        }
        if (!unpack.ok()) {
            return new RootShell.Result(1, "解压失败：" + unpack.output);
        }

        progress.onStep("校验新版本能否运行…");
        RootShell.Result probe = RootShell.exec(
                "chmod 755 " + WORK_DIR + "/tmp/ex/sing-box && " + WORK_DIR + "/tmp/ex/sing-box version", 30);
        if (!probe.ok() || !probe.output.contains("sing-box")) {
            RootShell.exec("rm -rf " + WORK_DIR + "/tmp", 10);
            return new RootShell.Result(1, "新版本无法在本设备运行，已取消替换：" + probe.output);
        }

        boolean wasRunning = currentPid() > 0;
        if (wasRunning) {
            progress.onStep("停止当前服务…");
            stop();
        }

        progress.onStep("替换二进制…");
        RootShell.Result replace = RootShell.exec(String.join(" && ",
                "[ -f " + BINARY + " ] && cp -f " + BINARY + " " + BINARY_BAK,
                "cp -f " + WORK_DIR + "/tmp/ex/sing-box " + BINARY,
                "chmod 755 " + BINARY,
                "rm -rf " + WORK_DIR + "/tmp"), 60);
        if (!replace.ok()) {
            RootShell.exec("[ -f " + BINARY_BAK + " ] && cp -f " + BINARY_BAK + " " + BINARY, 30);
            if (wasRunning) {
                start();
            }
            return new RootShell.Result(1, "替换失败，已恢复旧版本：" + replace.output);
        }

        if (wasRunning) {
            progress.onStep("重新启动服务…");
            start();
        }
        try {
            downloaded.delete();
        } catch (Throwable ignored) {
            // 缓存文件删不掉不影响结果
        }
        return new RootShell.Result(0, "已安装 " + release.tag + "（旧版本备份为 sing-box.bak）");
    }

    // ==================================================================================
    // 配置
    // ==================================================================================

    public static RootShell.Result updateConfig(Context context, String url, Progress progress) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return new RootShell.Result(1, "地址必须以 http:// 或 https:// 开头");
        }
        File temp;
        try {
            progress.onStep("下载配置…");
            temp = download(context, url, "config.new.json");
        } catch (Throwable t) {
            return new RootShell.Result(1, "下载失败：" + t.getMessage());
        }

        try {
            String body = readAll(new java.io.FileInputStream(temp));
            new JSONObject(body);
        } catch (Throwable t) {
            temp.delete();
            return new RootShell.Result(1, "下载内容不是合法 JSON，已取消替换");
        }

        progress.onStep("用 sing-box 校验配置…");
        if (exists(BINARY)) {
            RootShell.Result check = RootShell.exec(
                    BINARY + " check -D " + WORK_DIR + " -c " + temp.getAbsolutePath(), 30);
            if (!check.ok()) {
                temp.delete();
                return new RootShell.Result(1, "校验未通过，已保留原配置：\n" + check.output);
            }
        } else {
            progress.onStep("未安装 sing-box，跳过校验");
        }

        RootShell.Result write = RootShell.exec(String.join(" && ",
                "[ -f " + CONF + " ] && cp -f " + CONF + " " + CONF_BAK,
                "cp -f " + temp.getAbsolutePath() + " " + CONF), 30);
        temp.delete();
        if (!write.ok()) {
            return new RootShell.Result(1, "写入配置失败：" + write.output);
        }

        if (currentPid() > 0) {
            progress.onStep("重启以应用新配置…");
            restart();
        }
        return new RootShell.Result(0, "配置已更新（旧配置备份为 config.json.bak）");
    }

    // ==================================================================================
    // 配置文件读写与校验
    // ==================================================================================

    /** 读取当前配置文件；不存在返回空串。 */
    public static String readConfig() {
        RootShell.Result result = RootShell.exec(
                "[ -f " + CONF + " ] && cat " + CONF, 20);
        return result.ok() ? result.output : "";
    }

    /**
     * 把内容写进临时文件跑一次 {@code sing-box check}。
     *
     * @return {@code ok()==false} 时 output 就是 sing-box 的报错
     */
    public static RootShell.Result validate(String json) {
        if (!exists(BINARY)) {
            return new RootShell.Result(0, "未安装 sing-box，跳过校验");
        }
        String temp = WORK_DIR + "/tmp/config.check.json";
        RootShell.exec("mkdir -p " + WORK_DIR + "/tmp", 10);
        String encoded = android.util.Base64.encodeToString(
                json.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        RootShell.Result write = RootShell.exec(
                "echo " + encoded + " | base64 -d > " + temp, 20);
        if (!write.ok()) {
            return new RootShell.Result(1, "写入临时文件失败：" + write.output);
        }
        RootShell.Result check = RootShell.exec(
                BINARY + " check -D " + WORK_DIR + " -c " + temp, 60);
        RootShell.exec("rm -f " + temp, 10);
        return check;
    }

    /** 校验通过后落盘，旧配置留一份 .bak，并按需重启服务。 */
    public static RootShell.Result saveConfig(String json, boolean restartIfRunning) {
        RootShell.Result check = validate(json);
        if (!check.ok()) {
            return new RootShell.Result(1, "配置未通过 sing-box 校验，未保存：\n" + check.output);
        }
        String encoded = android.util.Base64.encodeToString(
                json.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        RootShell.Result write = RootShell.exec(String.join(" && ",
                "mkdir -p " + WORK_DIR,
                "[ -f " + CONF + " ] && cp -f " + CONF + " " + CONF_BAK,
                "echo " + encoded + " | base64 -d > " + CONF), 30);
        if (!write.ok()) {
            return new RootShell.Result(1, "写入失败：" + write.output);
        }
        if (restartIfRunning && currentPid() > 0) {
            restart();
            return new RootShell.Result(0, "已保存，服务已重启");
        }
        return new RootShell.Result(0, "已保存");
    }

    // ==================================================================================
    // 工具
    // ==================================================================================

    private static boolean exists(String path) {
        return RootShell.exec("[ -f " + path + " ] && echo yes", 10).output.contains("yes");
    }

    private static String tail(int lines) {
        RootShell.Result result = RootShell.exec(
                "[ -f " + LOG + " ] && tail -n " + lines + " " + LOG, 10);
        return result.output;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 下载到应用缓存目录，再由 root 复制走——应用进程本身没有写 /data/local/tmp 的权限。 */
    private static File download(Context context, String url, String name) throws Exception {
        File dir = new File(context.getCacheDir(), "singbox");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建缓存目录");
        }
        File target = new File(dir, name);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "sing-box");
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
        if (target.length() == 0) {
            throw new IllegalStateException("下载内容为空");
        }
        return target;
    }

    private static String readAll(InputStream input) throws Exception {
        try (InputStream stream = input) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String describe(Release release) {
        return release == null ? "—"
                : String.format(Locale.ROOT, "%s (%s)", release.tag, release.assetName);
    }
}
