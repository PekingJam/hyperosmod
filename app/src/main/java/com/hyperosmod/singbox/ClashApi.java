package com.hyperosmod.singbox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sing-box 的 Clash 控制接口客户端。
 *
 * <p>只做面板真正用到的几件事：读代理分组、切换节点、测延迟、订阅实时流量。以前这些是
 * 交给 dashboard 网页去做的，现在拿掉 WebView 之后由这里直连——好处是不用加载前端资源，
 * 冷启动直接在毫秒级拿到数据，也不用为本地控制台开明文 WebView。</p>
 */
public final class ClashApi {

    /** 一个代理分组。 */
    public static final class Group {
        public final String name;
        public final String type;
        public String now;
        public final List<String> all = new ArrayList<>();

        Group(String name, String type, String now) {
            this.name = name;
            this.type = type;
            this.now = now;
        }

        public boolean selectable() {
            return "Selector".equals(type);
        }
    }

    /** 一条活动连接。 */
    public static final class Connection {
        public String id = "";
        public String host = "";
        public String destination = "";
        public String network = "";
        public String type = "";
        public String chains = "";
        public long download;
        public long upload;
        public long start = -1;
    }

    /** 连接列表快照。 */
    public static final class Connections {
        public final List<Connection> items = new ArrayList<>();
        public long downloadTotal;
        public long uploadTotal;
    }

    /** 实时流量回调，运行在读取线程上。 */
    public interface TrafficListener {
        void onTraffic(long up, long down);
    }

    private final String base;
    private final String secret;

    public ClashApi(String base, String secret) {
        String trimmed = base == null ? "" : base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.base = trimmed;
        this.secret = secret == null ? "" : secret.trim();
    }

    public boolean configured() {
        return !base.isEmpty()
                && (base.startsWith("http://") || base.startsWith("https://"));
    }

    /** 版本号，用来判断控制接口是否可用。 */
    public String version() throws Exception {
        JSONObject json = get("/version");
        return json.optString("version", "");
    }

    /** 读取全部分组（只保留带 all 的组，即真正能切节点的）；顺带收集每个节点的类型。 */
    public List<Group> groups(Map<String, String> typesOut) throws Exception {
        JSONObject json = get("/proxies");
        JSONObject proxies = json.optJSONObject("proxies");
        List<Group> groups = new ArrayList<>();
        if (proxies == null) {
            return groups;
        }
        for (java.util.Iterator<String> it = proxies.keys(); it.hasNext(); ) {
            String name = it.next();
            JSONObject node = proxies.optJSONObject(name);
            if (node == null) {
                continue;
            }
            if (typesOut != null) {
                typesOut.put(name, node.optString("type", ""));
            }
            JSONArray all = node.optJSONArray("all");
            if (all == null || all.length() == 0) {
                continue;
            }
            Group group = new Group(name, node.optString("type", ""),
                    node.optString("now", ""));
            for (int i = 0; i < all.length(); i++) {
                group.all.add(all.optString(i));
            }
            groups.add(group);
        }
        return groups;
    }

    /** 切换分组当前节点。 */
    public void select(String group, String node) throws Exception {
        JSONObject body = new JSONObject();
        body.put("name", node);
        put("/proxies/" + encode(group), body.toString());
    }

    /**
     * 测单个节点的延迟。
     *
     * @return 毫秒，0 表示超时或失败
     */
    public int delay(String node, String testUrl, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            String query = "?url=" + encode(testUrl) + "&timeout=" + timeoutMs;
            connection = open("/proxies/" + encode(node) + "/delay" + query, "GET");
            if (connection.getResponseCode() != 200) {
                return 0;
            }
            JSONObject json = new JSONObject(read(connection.getInputStream()));
            return json.optInt("delay", 0);
        } catch (Throwable t) {
            return 0;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 当前的活动连接。 */
    public Connections connections() throws Exception {
        JSONObject json = get("/connections");
        Connections snapshot = new Connections();
        snapshot.downloadTotal = json.optLong("downloadTotal");
        snapshot.uploadTotal = json.optLong("uploadTotal");
        JSONArray array = json.optJSONArray("connections");
        if (array == null) {
            return snapshot;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Connection connection = new Connection();
            connection.id = item.optString("id", "");
            JSONObject metadata = item.optJSONObject("metadata");
            if (metadata != null) {
                connection.host = metadata.optString("host", "");
                String ip = metadata.optString("destinationIP", "");
                int port = metadata.optInt("destinationPort", 0);
                connection.destination = ip.isEmpty() ? ""
                        : (port > 0 ? ip + ":" + port : ip);
                connection.network = metadata.optString("network", "");
                connection.type = metadata.optString("type", "");
                if (connection.host.isEmpty()) {
                    connection.host = metadata.optString("sniffHost", "");
                }
                if (connection.host.isEmpty() && !connection.destination.isEmpty()) {
                    connection.host = connection.destination;
                }
            }
            JSONArray chains = item.optJSONArray("chains");
            if (chains != null) {
                StringBuilder builder = new StringBuilder();
                for (int j = chains.length() - 1; j >= 0; j--) {
                    if (builder.length() > 0) {
                        builder.append(" → ");
                    }
                    builder.append(chains.optString(j));
                }
                connection.chains = builder.toString();
            }
            connection.download = item.optLong("download");
            connection.upload = item.optLong("upload");
            connection.start = item.optLong("start", -1);
            if (connection.host.isEmpty()) {
                connection.host = item.optString("rule", "连接");
            }
            snapshot.items.add(connection);
        }
        return snapshot;
    }

    /**
     * 订阅实时流量。
     *
     * <p>服务端按行推 JSON，这里阻塞读到流结束或 {@link #stopTraffic()} 关闭连接为止，
     * 所以必须在后台线程调用。</p>
     */
    public void streamTraffic(TrafficListener listener) {
        HttpURLConnection connection = null;
        trafficConnection = null;
        try {
            connection = open("/traffic", "GET");
            trafficConnection = connection;
            if (connection.getResponseCode() != 200) {
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    JSONObject json = new JSONObject(line);
                    listener.onTraffic(json.optLong("up"), json.optLong("down"));
                }
            }
        } catch (Throwable ignored) {
            // 连接被 stopTraffic() 打断或网络抖动，由调用方决定是否重连。
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            trafficConnection = null;
        }
    }

    private volatile HttpURLConnection trafficConnection;

    public void stopTraffic() {
        HttpURLConnection connection = trafficConnection;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
                // 断开即可
            }
        }
    }

    // ==================================================================================

    private JSONObject get(String path) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = open(path, "GET");
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code);
            }
            return new JSONObject(read(connection.getInputStream()));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void put(String path, String body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = open(path, "PUT");
            connection.setDoOutput(true);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(0);
        connection.setRequestProperty("Content-Type", "application/json");
        if (!secret.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + secret);
        }
        return connection;
    }

    private static String read(InputStream input) throws Exception {
        try (InputStream stream = input) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return value == null ? "" : value;
        }
    }

    /** 面板展示用的中文分组类型名。 */
    public static String typeLabel(String type) {
        switch (type == null ? "" : type) {
            case "Selector":
                return "手动选择";
            case "URLTest":
                return "自动测速";
            case "Fallback":
                return "故障转移";
            case "LoadBalance":
                return "负载均衡";
            default:
                return type == null ? "" : type;
        }
    }

    /** 把分组按名字排序，让 GLOBAL 之类的排在后面。 */
    public static Map<String, Group> indexByName(List<Group> groups) {
        Map<String, Group> map = new LinkedHashMap<>();
        for (Group group : groups) {
            map.put(group.name, group);
        }
        return map;
    }
}
