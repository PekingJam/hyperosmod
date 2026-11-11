package com.hyperosmod.singbox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * sing-box 配置里常见字段与取值的小词表。
 *
 * <p>不做完整的 schema 校验（那要跟版本走），只在编辑时给「有提示」这件事托底：
 * 输入前缀就列出候选，拼错一个字母就提示最接近的正确写法。</p>
 */
public final class SingboxSchema {

    /** 字段名。 */
    private static final String[] KEYS = {
            "log", "level", "timestamp", "output", "disabled",
            "dns", "servers", "rules", "final", "strategy", "disable_cache", "independent_cache",
            "inbounds", "outbounds", "route", "endpoints", "experimental",
            "type", "tag", "server", "server_port", "username", "password", "method",
            "uuid", "flow", "security", "alter_id", "network", "udp", "packet_encoding",
            "tls", "enabled", "insecure", "sni", "alpn", "min_version", "max_version", "utls",
            "reality", "public_key", "short_id", "fingerprint",
            "transport", "headers", "host", "path", "service_name", "idle_timeout",
            "multiplex", "protocol", "obfs", "obfs_param", "up_mbps", "down_mbps",
            "tun", "stack", "address", "mtu", "auto_route", "strict_route", "auto_redirect",
            "inet4_address", "inet6_address", "interface_name", "default_mark",
            "sniff", "sniff_override_destination", "domain_strategy", "detour",
            "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only", "override_destination",
            "cache_file", "store_fakeip", "store_rdrc", "clash_api", "external_controller",
            "external_ui", "secret", "default_mode", "external_ui_download_url",
            "download_url", "interval", "subscribe", "geoip", "geosite",
            "wireguard", "private_key", "peers", "allowed_ips", "persistent_keepalive_interval",
            "public_peer", "pre_shared_key", "reserved", "mtu_size",
            "ntp", "domain", "geoip_url", "geosite_url",
            "dns_servers", "use_system_proxy", "environment", "settings"
    };

    /** 常见取值。 */
    private static final String[] VALUES = {
            "true", "false", "null",
            "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only", "as_is",
            "direct", "block", "proxy", "reject", "dns",
            "system", "gvisor", "mixed", "raw",
            "socks", "http", "shadowsocks", "vmess", "vless", "trojan", "hysteria2",
            "tuic", "wireguard", "anytls", "ssh", "tor", "quic",
            "ws", "grpc", "httpupgrade", "http2", "tls", "reality", "none",
            "trace", "debug", "info", "warn", "error", "fatal", "panic",
            "auto", "single", "random", "round_robin", "consistent_hash",
            "chacha20-ietf-poly1305", "aes-128-gcm", "aes-256-gcm", "2022-blake3-aes-128-gcm",
            "local", "remote", "h2", "cpu", "memory", "client", "server"
    };

    private SingboxSchema() {
    }

    /** 前缀匹配的候选，最多 5 个。 */
    public static List<String> suggest(String token) {
        List<String> hits = new ArrayList<>();
        if (token == null || token.length() < 2) {
            return hits;
        }
        String lower = token.toLowerCase();
        for (String word : all()) {
            if (hits.size() >= 5) {
                break;
            }
            String candidate = word.toLowerCase();
            if (candidate.startsWith(lower) && !candidate.equals(lower)) {
                hits.add(word);
            }
        }
        return hits;
    }

    /** 编辑距离为 1 的「像是拼错了」提示。 */
    public static String nearest(String token) {
        if (token == null || token.length() < 3) {
            return "";
        }
        String lower = token.toLowerCase();
        if (contains(lower)) {
            return "";
        }
        for (String word : all()) {
            if (word.toLowerCase().equals(lower)) {
                return "";
            }
            if (distance(lower, word.toLowerCase()) == 1) {
                return word;
            }
        }
        return "";
    }

    private static boolean contains(String lower) {
        for (String word : all()) {
            if (word.toLowerCase().equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> all() {
        List<String> words = new ArrayList<>(KEYS.length + VALUES.length);
        words.addAll(Arrays.asList(KEYS));
        words.addAll(Arrays.asList(VALUES));
        return words;
    }

    private static int distance(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 2) {
            return 99;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }
}
