package com.hyperosmod.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hyperosmod.App;
import com.hyperosmod.BuildConfig;
import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.ActivityMainBinding;
import com.hyperosmod.databinding.RowSwitchBinding;
import com.hyperosmod.singbox.SingBoxFragment;
import com.hyperosmod.singbox.TileEntryActivity;
import com.hyperosmod.singbox.NotificationPermission;
import com.hyperosmod.singbox.PanelFragment;

import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private final Runnable serviceListener = () -> runOnUiThread(() -> {
        if (!isFinishing() && !isDestroyed()) {
            refreshAll();
        }
    });
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean appListPromptShown;
    private boolean dndPromptShown;
    /** 本次进程内只提示一次通知权限，别每次回到前台都弹。 */
    private boolean notificationPrompted;
    /** 作用域缺口只提示一次。 */
    private boolean notifyIconScopePrompted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);
        setSupportActionBar(binding.toolbar);

        bindThemeRows();
        bindStatusBarRows();
        bindGroupRows();

        binding.bottomNav.setOnItemSelectedListener(item -> {
            showPage(item.getItemId());
            return true;
        });
        routeFromIntent(getIntent());

        binding.footer.setText(getString(R.string.footer, BuildConfig.VERSION_NAME));
        binding.buttonRestart.setOnClickListener(v -> restartSystemUi());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Prefs.addServiceListener(serviceListener);
        refreshAll();
        // 通知栏常驻是用户显式打开的，应用每次启动时对齐一次状态。
        if (Prefs.getBoolean(Keys.SINGBOX_NOTIFY, false)) {
            com.hyperosmod.singbox.PanelStatusService.start(this);
        }
    }

    @Override
    protected void onStop() {
        Prefs.removeServiceListener(serviceListener);
        super.onStop();
    }

    /** 从磁贴长按进来时，按 extra 直接落到对应 tab；正常启动则停在状态栏。 */
    private void routeFromIntent(Intent intent) {
        int tab = intent == null ? -1
                : intent.getIntExtra(TileEntryActivity.EXTRA_TAB, -1);
        int target;
        if (tab == TileEntryActivity.TAB_WEB) {
            target = R.id.tab_panel;
        } else if (tab == TileEntryActivity.TAB_PANEL) {
            target = R.id.tab_singbox;
        } else {
            target = R.id.tab_statusbar;
        }
        binding.bottomNav.setSelectedItemId(target);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        routeFromIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiUtils.applySystemBars(this);
        refreshAll();
        checkPermissions();
        checkNotificationPermission();
        checkNotifyIconScope();
    }

    /**
     * 通知图标同步开着、但有该进作用域的进程没勾时，直接把缺口摆到脸上。
     *
     * <p>这个功能的「没生效」里绝大多数是作用域没勾全：模块不会被注入那个进程，日志里
     * 连一行都看不到，用户只会觉得功能坏了。</p>
     */
    private void checkNotifyIconScope() {
        if (notifyIconScopePrompted
                || !Prefs.getBoolean(Keys.NOTIFY_ICON_ENABLED, false)
                || !App.isActivated()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (!App.isInScope("com.android.systemui")) {
            missing.add(getString(R.string.notify_icon_scope_systemui));
        }
        if (isInstalled("com.miui.aod") && !App.isInScope("com.miui.aod")) {
            missing.add(getString(R.string.notify_icon_scope_aod));
        }
        for (String packageName : Prefs.getStringSet(Keys.NOTIFY_ICON_TARGETS)) {
            if (!App.isInScope(packageName)) {
                missing.add(appLabel(packageName));
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        notifyIconScopePrompted = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notify_icon_scope_dialog_title)
                .setMessage(getString(R.string.notify_icon_scope_dialog_message,
                        TextUtils.join("、", missing)))
                .setNegativeButton(R.string.later, null)
                .setPositiveButton(R.string.notify_icon_scope_dialog_open,
                        (dialog, which) -> openLsposedManager())
                .show();
    }

    private void openLsposedManager() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
            if (intent != null) {
                startActivity(intent);
                return;
            }
        } catch (Throwable ignored) {
            // 落到下面的提示。
        }
        Toast.makeText(this, R.string.notify_icon_scope_dialog_manual, Toast.LENGTH_LONG).show();
    }

    private boolean isInstalled(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String appLabel(String packageName) {
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(packageName, 0))
                    .toString();
        } catch (Throwable t) {
            return packageName;
        }
    }

    /**
     * 通知栏常驻开关开着、但权限没给时补一次授权请求。
     *
     * <p>没有通知权限时前台服务照样在跑，只是通知栏看不到东西——用户会以为功能坏了，
     * 所以在应用启动/回前台时顺手补一次，且每次进程只弹一次，不骚扰。</p>
     */
    private void checkNotificationPermission() {
        if (!Prefs.getBoolean(Keys.SINGBOX_NOTIFY, false)) {
            return;
        }
        if (NotificationPermission.granted(this)) {
            com.hyperosmod.singbox.PanelStatusService.start(this);
            return;
        }
        if (!notificationPrompted) {
            notificationPrompted = true;
            NotificationPermission.request(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NotificationPermission.REQUEST_CODE) {
            checkNotificationPermission();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    /**
     * 底部导航：状态栏 / 系统 共用滚动区；sing-box 与网页各是一块 fragment 容器，
     * 首次切到对应 tab 时才创建，避免启动时就把 WebView 和 root 调用拉起来。
     */
    private void showPage(int itemId) {
        boolean scroll = itemId == R.id.tab_statusbar || itemId == R.id.tab_system;
        binding.scrollContent.setVisibility(scroll ? View.VISIBLE : View.GONE);
        binding.pageStatusbar.setVisibility(
                itemId == R.id.tab_statusbar ? View.VISIBLE : View.GONE);
        binding.pageSystem.setVisibility(itemId == R.id.tab_system ? View.VISIBLE : View.GONE);
        binding.pageSingbox.setVisibility(itemId == R.id.tab_singbox ? View.VISIBLE : View.GONE);
        binding.pagePanel.setVisibility(itemId == R.id.tab_panel ? View.VISIBLE : View.GONE);
        if (itemId == R.id.tab_singbox) {
            ensureFragment(R.id.container_singbox, SingBoxFragment::new, "singbox");
        } else if (itemId == R.id.tab_panel) {
            ensureFragment(R.id.container_panel, PanelFragment::new, "panel");
        }
        binding.scrollContent.scrollTo(0, 0);
    }

    private void ensureFragment(int containerId, java.util.function.Supplier<Fragment> factory,
                                String tag) {
        FragmentManager manager = getSupportFragmentManager();
        if (manager.findFragmentByTag(tag) != null) {
            return;
        }
        manager.beginTransaction()
                .add(containerId, factory.get(), tag)
                .commitAllowingStateLoss();
    }

    /** 「系统」一级页只放入口，具体开关都在分组页里，和 sing-box 页保持同一套结构。 */
    private void bindGroupRows() {
        bindGroup(binding.rowGroupLock, R.string.section_lockscreen,
                R.string.group_lock_summary, GroupSettingsActivity.GROUP_LOCK);
        bindGroup(binding.rowGroupFitness, R.string.section_mifitness,
                R.string.group_fitness_summary, GroupSettingsActivity.GROUP_FITNESS);
        bindGroup(binding.rowGroupUpdate, R.string.section_updates,
                R.string.group_update_summary, GroupSettingsActivity.GROUP_UPDATE);
        bindGroup(binding.rowGroupTelephony, R.string.section_telephony,
                R.string.group_telephony_summary, GroupSettingsActivity.GROUP_TELEPHONY);
        bindGroup(binding.rowGroupFcm, R.string.section_fcm,
                R.string.group_fcm_summary, GroupSettingsActivity.GROUP_FCM);
        bindGroup(binding.rowGroupNotifyIcon, R.string.section_notify_icon,
                R.string.group_notify_icon_summary, GroupSettingsActivity.GROUP_NOTIFY_ICON);
    }

    private void bindGroup(com.hyperosmod.databinding.RowAppsBinding row, int title,
                           int summary, String group) {
        row.title.setText(title);
        row.summary.setText(summary);
        row.getRoot().setOnClickListener(v -> {
            Intent intent = new Intent(this, GroupSettingsActivity.class);
            intent.putExtra(GroupSettingsActivity.EXTRA_GROUP, group);
            startActivity(intent);
        });
    }

    private void bindThemeRows() {
        bindSwitch(binding.rowCustomTheme, Keys.CUSTOM_THEME_ENABLED,
                R.string.custom_theme_title, R.string.custom_theme_summary);
        binding.rowCustomTheme.switchView.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setBoolean(Keys.CUSTOM_THEME_ENABLED, checked);
            updateThemeRow();
        });

        binding.rowThemeColor.title.setText(R.string.theme_color_title);
        binding.rowThemeColor.getRoot().setOnClickListener(v -> {
            if (!Prefs.getBoolean(Keys.CUSTOM_THEME_ENABLED, false)) {
                return;
            }
            int current = Prefs.getInt(Keys.CUSTOM_THEME_COLOR, Keys.DEFAULT_THEME_COLOR);
            ColorPickerDialog.show(this, current, color -> {
                Prefs.setInt(Keys.CUSTOM_THEME_COLOR, color);
                updateThemeRow();
            });
        });
    }

    private void bindStatusBarRows() {
        bindSwitch(binding.rowDualSignal, Keys.DUAL_ROW_SIGNAL,
                R.string.dual_signal_title, R.string.dual_signal_summary);
        binding.rowDualSignal.switchView.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setBoolean(Keys.DUAL_ROW_SIGNAL, checked);
            updateDualSignalRows();
        });
        bindSwitch(binding.rowClockSeconds, Keys.CLOCK_SECONDS,
                R.string.clock_seconds_title, R.string.clock_seconds_summary);
        binding.rowDualSignalDetail.title.setText(R.string.dual_signal_detail_title);
        binding.rowDualSignalDetail.summary.setText(R.string.dual_signal_detail_summary);
        binding.rowDualSignalDetail.getRoot().setOnClickListener(v ->
                startActivity(new Intent(this, DualSignalSettingsActivity.class)));
    }

    private void updateDualSignalRows() {
        boolean enabled = Prefs.getBoolean(Keys.DUAL_ROW_SIGNAL, false);
        binding.dualSignalHint.setVisibility(enabled ? View.GONE : View.VISIBLE);
        binding.rowDualSignalDetail.summary.setText(enabled
                ? R.string.dual_signal_detail_summary
                : R.string.dual_signal_detail_summary_off);
    }


    private void checkPermissions() {
        if (!PermissionHelper.hasAppListAccess(this)) {
            if (!appListPromptShown) {
                appListPromptShown = true;
                showAppListPermissionDialog();
            }
            return;
        }
        appListPromptShown = false;

        if (Prefs.getBoolean(Keys.RESTORE_DND_SYNC, false)) {
            maybePromptDndAccess();
        }
    }

    private void showAppListPermissionDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_list_permission_title)
                .setMessage(R.string.app_list_permission_message)
                .setNegativeButton(R.string.later, null)
                .setPositiveButton(R.string.grant_permission, (dialog, which) ->
                        PermissionHelper.openAppListSettings(this))
                .show();
    }

    private void maybePromptDndAccess() {
        if (dndPromptShown
                || !PermissionHelper.isMiFitnessInstalled(this)
                || PermissionHelper.hasMiFitnessDndAccess(this)) {
            return;
        }
        dndPromptShown = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dnd_permission_dialog_title)
                .setMessage(R.string.dnd_permission_dialog_message)
                .setNegativeButton(R.string.later, null)
                .setPositiveButton(R.string.grant_permission, (dialog, which) ->
                        PermissionHelper.openDndAccessSettings(this))
                .show();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void bindSwitch(RowSwitchBinding row, String key, int title, int summary) {
        bindSwitch(row, key, title, summary, false);
    }

    private void bindSwitch(RowSwitchBinding row, String key, int title, int summary,
                            boolean defaultValue) {
        row.title.setText(title);
        row.summary.setText(summary);
        row.switchView.setChecked(Prefs.getBoolean(key, defaultValue));
        row.switchView.setOnCheckedChangeListener((button, checked) ->
                Prefs.setBoolean(key, checked));
    }

    private void refreshAll() {
        binding.rowCustomTheme.switchView.setChecked(
                Prefs.getBoolean(Keys.CUSTOM_THEME_ENABLED, false));
        binding.rowDualSignal.switchView.setChecked(
                Prefs.getBoolean(Keys.DUAL_ROW_SIGNAL, false));
        binding.rowClockSeconds.switchView.setChecked(
                Prefs.getBoolean(Keys.CLOCK_SECONDS, false));

        updateThemeRow();
        updateDualSignalRows();
        pruneUninstalledTargets();
        refreshStatus();
    }

    private void updateThemeRow() {
        boolean enabled = Prefs.getBoolean(Keys.CUSTOM_THEME_ENABLED, false);
        int color = Prefs.getInt(Keys.CUSTOM_THEME_COLOR, Keys.DEFAULT_THEME_COLOR);

        binding.themeHint.setVisibility(enabled ? View.VISIBLE : View.GONE);
        binding.rowThemeColor.getRoot().setEnabled(enabled);
        binding.rowThemeColor.getRoot().setAlpha(enabled ? 1f : 0.45f);
        binding.rowThemeColor.colorDot.setBackgroundTintList(ColorStateList.valueOf(color));
        binding.rowThemeColor.summary.setText(
                String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
    }

    /**
     * 三个「按包名勾选」的名单（阻止更新、伪装目标、FCM 目标）都要清理已卸载应用的残留
     * 包名：不然主界面一直显示「已选 N 个」，进列表却只能看到 N-1 个勾，用户既取消不了
     * 也解释不了。
     */
    private void pruneUninstalledTargets() {
        pruneOne(Keys.BLOCKED_PACKAGES);
        pruneOne(Keys.TELEPHONY_TARGETS);
        pruneOne(Keys.FCM_TARGETS);
        pruneOne(Keys.NOTIFY_ICON_TARGETS);
    }

    private void pruneOne(String key) {
        Set<String> current = Prefs.getStringSet(key);
        Set<String> pruned = PackagePruner.prune(getPackageManager(), current);
        // prune() 在没有任何条目被移除时会原样返回入参，用引用相等判断就不必多写一次盘。
        if (pruned != current) {
            Prefs.setStringSet(key, pruned);
        }
    }

    private void refreshStatus() {
        boolean active = App.isActivated();

        int title;
        int summary;
        int dotColor;
        if (active) {
            title = R.string.status_active;
            summary = R.string.status_hint_active;
            dotColor = R.color.accent_green;
        } else {
            title = R.string.status_inactive;
            summary = R.string.status_hint_inactive;
            dotColor = R.color.accent_red;
        }

        binding.statusTitle.setText(title);
        binding.statusSummary.setText(summary);
        binding.statusDot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, dotColor)));
    }

    private void restartSystemUi() {
        binding.buttonRestart.setEnabled(false);
        binding.buttonRestart.setText(R.string.restart_running);
        executor.execute(() -> {
            boolean ok = false;
            String[][] commands = {
                    {"su", "-c", "killall com.android.systemui"},
                    {"su", "-c", "pkill -f com.android.systemui"}
            };
            for (String[] command : commands) {
                try {
                    Process process = Runtime.getRuntime().exec(command);
                    if (process.waitFor() == 0) {
                        ok = true;
                        break;
                    }
                } catch (Throwable ignored) {
                    // Try the next command.
                }
            }
            final boolean success = ok;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                binding.buttonRestart.setEnabled(true);
                binding.buttonRestart.setText(R.string.restart_systemui);
                Toast.makeText(this,
                        success ? R.string.restart_success : R.string.restart_failed,
                        Toast.LENGTH_LONG).show();
            });
        });
    }
}
