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

    /** 自编内核清单（Android 系统 DNS 分支）。优先于 GitHub。 */
    private static final String DL_MANIFEST = "https://dl.889969.xyz/singbox/latest.json";
    /** 兜底：dl 挂掉时才回落到上游官方发布（官方包在 Android 上读不到系统 DNS）。 */
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

    /** 一个可用版本：可能来自 dl 自编清单，也可能来自 GitHub 官方发布。 */
    public static final class Release {
        public final String tag;
        public final boolean prerelease;
        public final String assetUrl;
        public final String assetName;
        /** 为空表示来源不提供校验值（GitHub 旧接口）。 */
        public final String sha256;
        public final boolean selfBuilt;

        Release(String tag, boolean prerelease, String assetUrl, String assetName,
                String sha256, boolean selfBuilt) {
            this.tag = tag;
            this.prerelease = prerelease;
            this.assetUrl = assetUrl;
            this.assetName = assetName;
            this.sha256 = sha256 == null ? "" : sha256;
            this.selfBuilt = selfBuilt;
        }

        public String display() {
            if (selfBuilt && prerelease) {
                return tag + "  [自编·测试版]";
            }
            if (selfBuilt) {
                return tag + "  [自编]";
            }
            return prerelease ? tag + "  [测试版]" : tag;
        }
    }

    /** 拉取发布列表：先问 dl 自编清单，拿不到再回落 GitHub。不需要 root。 */
    public static List<Release> releases() {
        List<Release> fromDl = dlReleases();
        if (!fromDl.isEmpty()) {
            return fromDl;
        }
        return githubReleases();
    }

    // ------------------------------------------------------------------------------
    // 版本比较：只有服务器版本比本地新才值得更新
    // ------------------------------------------------------------------------------

    /**
     * 比较两个版本号，语义同 semver：忽略开头的 v 和 +build 部分，先比数字三段，
     * 相同再比预发布标记（无预发布 > alpha < beta < rc < 另一串）。
     *
     * @return 正数表示 {@code left} 更新，0 表示相同，负数表示更旧
     */
    public static int compareVersions(String left, String right) {
        String a = normalizeVersion(left);
        String b = normalizeVersion(right);
        if (a.equals(b)) {
            return 0;
        }
        // git describe 形态（1.14.4-132-g0f06352a5）表示「该 tag 之后又 132 个提交」，
        // 比同名 tag 本身新，但语义上不属于预发布，先单独处理再按 semver 比。
        String[] gitDesc = describeBase(a);
        if (gitDesc != null && b.equals(gitDesc[0])) {
            return 1;
        }
        String[] gitDescRight = describeBase(b);
        if (gitDescRight != null && a.equals(gitDescRight[0])) {
            return -1;
        }
        String[] aParts = a.split("-", 2);
        String[] bParts = b.split("-", 2);
        int core = compareCore(aParts[0], bParts[0]);
        if (core != 0) {
            return core;
        }
        String aPre = aParts.length > 1 ? aParts[1] : "";
        String bPre = bParts.length > 1 ? bParts[1] : "";
        if (aPre.isEmpty() || bPre.isEmpty()) {
            // 正式版比任何预发布都新
            return aPre.isEmpty() ? (bPre.isEmpty() ? 0 : 1) : -1;
        }
        return comparePre(aPre, bPre);
    }

    /** 命中 git describe 形态时返回 {基础版本, 提交数}，否则 null。 */
    private static String[] describeBase(String version) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^(\\d+\\.\\d+\\.\\d+)-(\\d+)-g[0-9a-f]{7,}$")
                        .matcher(version);
        if (!matcher.matches()) {
            return null;
        }
        return new String[]{matcher.group(1), matcher.group(2)};
    }

    private static String normalizeVersion(String version) {
        if (version == null) {
            return "0";
        }
        String value = version.trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        int plus = value.indexOf('+');
        if (plus >= 0) {
            value = value.substring(0, plus);
        }
        return value.isEmpty() ? "0" : value;
    }

    private static int compareCore(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length ? parseIntSafe(a[i]) : 0;
            int y = i < b.length ? parseIntSafe(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** alpha < beta < rc < 其它；同一级再比点分数字（alpha.13 > alpha.9）。 */
    private static int comparePre(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            String x = i < a.length ? a[i] : "";
            String y = i < b.length ? b[i] : "";
            if (x.equals(y)) {
                continue;
            }
            // 短的那一方视为更旧（alpha < alpha.1）
            if (x.isEmpty() || y.isEmpty()) {
                return x.isEmpty() ? -1 : 1;
            }
            boolean xNum = x.chars().allMatch(Character::isDigit);
            boolean yNum = y.chars().allMatch(Character::isDigit);
            if (xNum && yNum) {
                return Integer.compare(parseIntSafe(x), parseIntSafe(y));
            }
            if (xNum != yNum) {
                return xNum ? -1 : 1;
            }
            int rank = Integer.compare(preRank(x), preRank(y));
            if (rank != 0) {
                return rank;
            }
            return x.compareTo(y);
        }
        return 0;
    }

    private static int preRank(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        if (value.startsWith("alpha")) {
            return 0;
        }
        if (value.startsWith("beta")) {
            return 1;
        }
        if (value.startsWith("rc")) {
            return 2;
        }
        return 3;
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 本地已装版本（可能是「未安装」「—」这种占位）；能装就算 0。 */
    private static String installedVersionOrZero(String version) {
        if (version == null || version.isEmpty() || version.contains("未") || version.equals("—")) {
            return "0";
        }
        return version;
    }

    /** 服务器上的这个版本是否比本地新。 */
    public static boolean isNewerThanInstalled(Release release, String installedVersion) {
        if (release == null || release.tag == null || release.tag.isEmpty()) {
            return false;
        }
        return compareVersions(release.tag, installedVersionOrZero(installedVersion)) > 0;
    }

    /** 读 dl.889969.xyz 的 singbox 清单，只取「内核」且是 tar.gz 的条目。 */
    private static List<Release> dlReleases() {
        List<Release> result = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(DL_MANIFEST).openConnection();
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(15_000);
            if (connection.getResponseCode() != 200) {
                return result;
            }
            JSONObject manifest = new JSONObject(readAll(connection.getInputStream()));
            JSONArray artifacts = manifest.optJSONArray("artifacts");
            if (artifacts == null) {
                return result;
            }
            for (int i = 0; i < artifacts.length(); i++) {
                JSONObject artifact = artifacts.optJSONObject(i);
                if (artifact == null) {
                    continue;
                }
                String url = artifact.optString("url", "");
                String name = url.isEmpty() ? "" : url.substring(url.lastIndexOf('/') + 1);
                if (!name.endsWith(".tar.gz")) {
                    continue;   // 面板之类的静态产物跳过，只认内核
                }
                String kind = artifact.optString("kind", "");
                if (!kind.isEmpty() && !kind.contains("内核")) {
                    continue;
                }
                String version = artifact.optString("version", manifest.optString("version", ""));
                if (version.isEmpty()) {
                    continue;
                }
                boolean beta = "beta".equalsIgnoreCase(artifact.optString("channel", ""));
                result.add(new Release(version, beta, url, name,
                        artifact.optString("sha256", ""), true));
            }
        } catch (Throwable ignored) {
            // 网络异常当作没有清单，交给 GitHub 兜底
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return result;
    }

    /** 拉取上游官方发布（兜底）。 */
    private static List<Release> githubReleases() {
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
                result.add(new Release(tag, release.optBoolean("prerelease"), url, name, "", false));
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

    /**
     * 当前通道的最新发布。自编清单目前只出一个通道，所有条目都算「可装」：
     * 先找与所选通道一致的，找不到就退而取第一条（自编版本号带 md 后缀，
     * status.beta() 会判成测试版，但清单里实际只有 stable，不能因此判定「没有更新」）。
     */
    public static Release latestForChannel(boolean beta) {
        List<Release> list = releases();
        for (Release release : list) {
            if (release.prerelease == beta && !release.assetUrl.isEmpty()) {
                return release;
            }
        }
        for (Release release : list) {
            if (!release.assetUrl.isEmpty()) {
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

        if (!release.sha256.isEmpty()) {
            progress.onStep("校验文件完整性…");
            String actual = sha256Of(downloaded);
            if (!release.sha256.equalsIgnoreCase(actual)) {
                downloaded.delete();
                return new RootShell.Result(1,
                        "下载的文件校验不通过，已取消：\n期望 " + release.sha256 + "\n实际 " + actual);
            }
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

    /** 算文件 SHA-256；出任何问题返回空串（调用方按不匹配处理）。 */
    private static String sha256Of(File file) {
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(file))) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "";
        }
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
