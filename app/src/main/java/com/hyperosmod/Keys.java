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
    /** SystemUI: override the Monet seed color. */
    public static final String CUSTOM_THEME_ENABLED = "custom_theme_enabled";
    /** SystemUI: ARGB seed color used when {@link #CUSTOM_THEME_ENABLED} is on. */
    public static final String CUSTOM_THEME_COLOR = "custom_theme_color";

    /** system_server: reject updates of the packages in {@link #BLOCKED_PACKAGES}. */
    public static final String BLOCK_UPDATES_ENABLED = "block_updates_enabled";
    /** Set&lt;String&gt; of package names whose updates must be rejected. */
    public static final String BLOCKED_PACKAGES = "blocked_packages";

    /** Mi Fitness: restore the band &lt;-&gt; phone DND/zen-mode sync removed on Android 15+. */
    public static final String RESTORE_DND_SYNC = "restore_dnd_sync";

    /** Material-ish indigo used when the user enables the color override without picking one. */
    public static final int DEFAULT_THEME_COLOR = 0xFF4C6FFF;

    private Keys() {
    }
}
