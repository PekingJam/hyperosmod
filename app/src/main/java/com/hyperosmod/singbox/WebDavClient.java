package com.hyperosmod.singbox;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 极简 WebDAV 客户端：只做「上传一个文件」和「测试连接」两件事。
 *
 * <p>云盘侧用的大多是 Nextcloud / 坚果云 / Alist 这类 WebDAV 实现，PUT 到目标路径即可；
 * 中间目录不存在时先逐级 MKCOL，失败也不影响后续 PUT（有的服务会自动建目录）。</p>
 */
public final class WebDavClient {

    private WebDavClient() {
    }

    /** 上传成功返回 null，失败返回给用户看的错误信息。 */
    public static String upload(String baseUrl, String user, String password,
                                String remotePath, String content) {
        String url = join(baseUrl, remotePath);
        if (url == null) {
            return "服务器地址或远端路径不合法";
        }
        // 逐级建目录，失败不阻断（很多网盘 PUT 时会自动创建）。
        String base = trimEnd(baseUrl);
        String path = trimStart(remotePath);
        String[] segments = path.split("/");
        StringBuilder current = new StringBuilder(base);
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].isEmpty()) {
                continue;
            }
            current.append('/').append(segments[i]);
            mkcol(current.toString(), user, password);
        }

        HttpURLConnection connection = null;
        try {
            connection = open(url, user, password);
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = content.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return null;
            }
            return "上传失败：HTTP " + code + describe(code);
        } catch (Throwable t) {
            return "上传失败：" + t.getMessage();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 探测根目录是否可访问。成功返回 null。 */
    public static String test(String baseUrl, String user, String password) {
        if (baseUrl == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            return "服务器地址必须以 http:// 或 https:// 开头";
        }
        HttpURLConnection connection = null;
        try {
            connection = open(baseUrl, user, password);
            // 用 OPTIONS 而不是 PROPFIND：Android 的 HttpURLConnection 会拒绝
            // PROPFIND 这类自定义方法，直接抛 ProtocolException。
            connection.setRequestMethod("OPTIONS");
            int code = connection.getResponseCode();
            if (code >= 200 && code < 400) {
                return null;
            }
            return "连接失败：HTTP " + code + describe(code);
        } catch (Throwable t) {
            return "连接失败：" + t.getMessage();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void mkcol(String url, String user, String password) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, user, password);
            // MKCOL 同样被 HttpURLConnection 拦，抛出来直接忽略：
            // 目标目录不存在时由 PUT 的错误码来告诉用户。
            connection.setRequestMethod("MKCOL");
            int code = connection.getResponseCode();
            // 201 新建成功、405 已存在，都算正常。
            if (code != 201 && code != 405) {
                return;
            }
        } catch (Throwable ignored) {
            // 目录建不了就交给 PUT 去报错。
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection open(String url, String user, String password)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        if (user != null && !user.isEmpty()) {
            String raw = user + ":" + (password == null ? "" : password);
            connection.setRequestProperty("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
        return connection;
    }

    private static String describe(int code) {
        switch (code) {
            case 401:
                return "（用户名或密码不对）";
            case 403:
                return "（没有写入权限）";
            case 404:
                return "（路径不存在）";
            case 507:
                return "（云盘空间不足）";
            default:
                return "";
        }
    }

    private static String join(String baseUrl, String remotePath) {
        if (baseUrl == null || remotePath == null) {
            return null;
        }
        String base = trimEnd(baseUrl);
        String path = trimStart(remotePath);
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return null;
        }
        return path.isEmpty() ? base : base + "/" + path;
    }

    private static String trimEnd(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String trimStart(String value) {
        String result = value == null ? "" : value.trim();
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }
}
