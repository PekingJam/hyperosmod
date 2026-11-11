package com.hyperosmod;

/**
 * Keys and defaults shared by the manager app and the hooked processes.
 *
 * <p>Keep this class free of Android dependencies: it is loaded in system_server and
 * SystemUI as well.</p>
 */
public final class Keys {
    public static final String PREFS_FILE = "hyperosmod";
    public static final String REMOTE_GROUP = "hyperosmod_prefs";

    /** SystemUI: let every notification be shown on the keyguard. */
    public static final String UNLOCK_NOTIFICATION_RESTRICT = "unlock_notification_restrict";
    /** SystemUI: keep keyguard notifications after unlocking. */
    public static final String KEEP_NOTIFICATION = "keep_notification";
    /** sing-box：磁贴长按打开应用时优先显示网页。 */
    public static final String SINGBOX_TILE_WEB = "singbox_tile_web";
    /** sing-box：网页面板地址（磁贴长按 / 面板里的「打开网页」都用它）。 */
    public static final String SINGBOX_WEB_URL = "singbox_web_url";
    /** sing-box：配置文件下载地址（面板里可改，脚本会自己记住）。 */
    public static final String SINGBOX_CONFIG_URL = "singbox_config_url";
    /** sing-box：WebView 缓存策略，0=默认 1=缓存优先 2=不走缓存。 */
    public static final String SINGBOX_WEB_CACHE = "singbox_web_cache";

    /** 桌面（com.miui.home）：双击手势提示线直接锁屏。 */
    public static final String GESTURE_LINE_LOCK = "gesture_line_lock";
    /** SystemUI: override the Monet seed color. */
    public static final String CUSTOM_THEME_ENABLED = "custom_theme_enabled";
    /** SystemUI: ARGB seed color used when {@link #CUSTOM_THEME_ENABLED} is on. */
    public static final String CUSTOM_THEME_COLOR = "custom_theme_color";
    /** SystemUI: stack the two SIM signal icons and show the data SIM network type first. */
    public static final String DUAL_ROW_SIGNAL = "dual_row_signal";
    /** SystemUI: percentage scale of the dual-row signal icon. */
    public static final String DUAL_SIGNAL_SCALE = "dual_signal_scale";
    /** SystemUI: percentage thickness of the dual-row signal bars/dots. */
    public static final String DUAL_SIGNAL_THICKNESS = "dual_signal_thickness";
    /** SystemUI: percentage scale of the 5G / 4G network type text. */
    public static final String DUAL_TEXT_SCALE = "dual_signal_text_scale";
    /** SystemUI: percentage weight of the 5G / 4G network type text. */
    public static final String DUAL_TEXT_THICKNESS = "dual_signal_text_thickness";
    public static final int DUAL_SIGNAL_SCALE_MIN = 75;
    public static final int DUAL_SIGNAL_SCALE_MAX = 200;
    public static final int DUAL_SIGNAL_SCALE_DEFAULT = 175;
    public static final int DUAL_SIGNAL_THICKNESS_MIN = 50;
    public static final int DUAL_SIGNAL_THICKNESS_MAX = 200;
    public static final int DUAL_SIGNAL_THICKNESS_DEFAULT = 125;
    public static final int DUAL_TEXT_SCALE_MIN = 75;
    public static final int DUAL_TEXT_SCALE_MAX = 200;
    public static final int DUAL_TEXT_SCALE_DEFAULT = 100;
    public static final int DUAL_TEXT_THICKNESS_MIN = 50;
    public static final int DUAL_TEXT_THICKNESS_MAX = 200;
    public static final int DUAL_TEXT_THICKNESS_DEFAULT = 100;

    /** SystemUI: show seconds in the status bar clock. */
    public static final String CLOCK_SECONDS = "status_bar_clock_seconds";

    /** system_server: reject updates of the packages in {@link #BLOCKED_PACKAGES}. */
    public static final String BLOCK_UPDATES_ENABLED = "block_updates_enabled";
    /** Set&lt;String&gt; of package names whose updates must be rejected. */
    public static final String BLOCKED_PACKAGES = "blocked_packages";

    /** Mi Fitness: restore the band &lt;-&gt; phone DND/zen-mode sync removed on Android 15+. */
    public static final String RESTORE_DND_SYNC = "restore_dnd_sync";

    /** Telephony/SIM spoofing for selected apps. */
    public static final String TELEPHONY_ENABLED = "telephony_enabled";
    /** Set&lt;String&gt; of packages that should receive the spoofed telephony values. */
    public static final String TELEPHONY_TARGETS = "telephony_targets";
    public static final String TELEPHONY_DEFAULT_SLOT = "telephony_default_slot";
    public static final String TELEPHONY_PRESET = "telephony_preset";

    public static final String TELEPHONY_PHONE = "phone";
    public static final String TELEPHONY_SIM_SERIAL = "sim_serial";
    public static final String TELEPHONY_NETWORK_COUNTRY = "network_country";
    public static final String TELEPHONY_NETWORK_OPERATOR = "network_operator";
    public static final String TELEPHONY_NETWORK_OPERATOR_NAME = "network_operator_name";
    public static final String TELEPHONY_SIM_COUNTRY = "sim_country";
    public static final String TELEPHONY_SIM_OPERATOR = "sim_operator";
    public static final String TELEPHONY_SIM_OPERATOR_NAME = "sim_operator_name";

    /** FCM/GCM wake-up and HyperOS restriction fixes. */
    public static final String FCM_FIX_ENABLED = "fcm_fix_enabled";
    public static final String FCM_TARGETS = "fcm_targets";
    public static final String FCM_KEEP_NOTIFICATION = "fcm_keep_notification";

    /** Preference key for one telephony field of SIM slot 0 or 1. */
    public static String telephony(String field, int slot) {
        return "telephony_" + field + "_" + slot;
    }

    /** Material-ish indigo used when the user enables the color override without picking one. */
    public static final int DEFAULT_THEME_COLOR = 0xFF4C6FFF;

    private Keys() {
    }
}
