package com.hyperosmod.singbox;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.hyperosmod.Keys;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.ui.MainActivity;

/**
 * 磁贴长按的落点。
 *
 * <p>系统默认把长按接到「应用信息」页，只有声明了
 * {@code android.service.quicksettings.action.QS_TILE_PREFERENCES} 的 activity 才会被优先拉起。
 * 这里只做一次转发：拉起主界面并指定要落在哪个 tab，不另开独立页面。</p>
 */
public final class TileEntryActivity extends Activity {

    /** 让主界面直接切到指定 tab。{@link #TAB_PANEL} 管理面板，{@link #TAB_WEB} 网页。 */
    public static final String EXTRA_TAB = "singbox_target_tab";
    public static final int TAB_PANEL = 0;
    public static final int TAB_WEB = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = Prefs.getString(Keys.SINGBOX_WEB_URL, "");
        boolean toWeb = Prefs.getBoolean(Keys.SINGBOX_TILE_WEB, true) && !url.isEmpty();
        Intent next = new Intent(this, MainActivity.class);
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        next.putExtra(EXTRA_TAB, toWeb ? TAB_WEB : TAB_PANEL);
        startActivity(next);
        finish();
    }
}
