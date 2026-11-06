package com.hyperosmod.ui;

import android.graphics.drawable.Drawable;

/** Immutable row model for the app picker. */
public final class AppEntry {
    public final String label;
    public final String packageName;
    public final String versionName;
    public final long versionCode;
    public final boolean systemApp;
    public final Drawable icon;

    public AppEntry(String label, String packageName, String versionName, long versionCode,
                    boolean systemApp, Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.systemApp = systemApp;
        this.icon = icon;
    }
}
