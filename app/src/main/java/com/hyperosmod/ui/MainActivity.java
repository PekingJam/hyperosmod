package com.hyperosmod.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.SystemClock;
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

    /** Root 授权状态：起 su 有成本，查一次缓存一分钟。 */
    private static final long ROOT_CACHE_MS = 60_000L;
    private final java.util.concurrent.atomic.AtomicBoolean rootChecking =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean rootKnown;
    private volatile boolean rootGranted;
    private volatile long rootCheckedAt;

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
        binding.footerStatusbar.setText(getString(R.string.footer, BuildConfig.VERSION_NAME));
        binding.btnRestartSystemui.setOnClickListener(v -> restartSystemUi());
        binding.btnRestartSystem.setOnClickListener(v -> confirmReboot());
        binding.btnOpenLsposed.setOnClickListener(v -> LsposedLauncher.open(this));
    }

    @Override
    protected void onStart() {
        super.onStart();
        Prefs.addServiceListener(serviceListener);
        refreshAll();
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
            target = R.id.tab_system;
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
    }

    /** 重启系统不可撤销，先确认一次。 */
    private void confirmReboot() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reboot_confirm_title)
                .setMessage(R.string.reboot_confirm_message)
                .setNegativeButton(R.string.later, null)
                .setPositiveButton(R.string.reboot_confirm_ok, (dialog, which) -> rebootSystem())
                .show();
    }

    private void rebootSystem() {
        binding.btnRestartSystem.setEnabled(false);
        executor.execute(() -> {
            final boolean ok = runRoot("reboot");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                binding.btnRestartSystem.setEnabled(true);
                Toast.makeText(this, ok ? R.string.reboot_sent : R.string.reboot_failed,
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    /** 以 root 执行一条命令，返回退出码是否为 0。 */
    private static boolean runRoot(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            return process.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
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
            notifyPanelVisible();
        }
        binding.scrollContent.scrollTo(0, 0);
    }

    /** 面板页每次被切过来时通知一次：它自己决定要不要自动跑全量测速。 */
    private void notifyPanelVisible() {
        FragmentManager manager = getSupportFragmentManager();
        manager.executePendingTransactions();
        Fragment panel = manager.findFragmentByTag("panel");
        if (panel instanceof PanelFragment) {
            ((PanelFragment) panel).onTabVisible();
        }
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
        binding.statusState.setTextColor(ContextCompat.getColor(this,
                active ? R.color.accent_green : R.color.accent_red));
        binding.statusState.setText(active
                ? R.string.state_connected : R.string.state_disconnected);
        binding.statusDot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, active ? R.color.accent_green : R.color.accent_red)));

        // Root 授权要起 su 进程，不能每次都同步查：缓存一分钟，结果回来再刷一次。
        if (!rootKnown || SystemClock.uptimeMillis() - rootCheckedAt > ROOT_CACHE_MS) {
            checkRootAsync();
        }
        applyRootState(rootKnown ? Boolean.valueOf(rootGranted) : null);
    }

    private void checkRootAsync() {
        if (!rootChecking.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            final boolean granted = com.hyperosmod.singbox.RootShell.hasRoot();
            runOnUiThread(() -> {
                rootChecking.set(false);
                rootGranted = granted;
                rootKnown = true;
                rootCheckedAt = SystemClock.uptimeMillis();
                if (!isFinishing() && !isDestroyed()) {
                    applyRootState(granted);
                }
            });
        });
    }

    private void applyRootState(Boolean granted) {
        int state;
        int color;
        if (granted == null) {
            state = R.string.state_checking;
            color = R.color.accent_amber;
        } else if (granted) {
            state = R.string.state_root_granted;
            color = R.color.accent_green;
        } else {
            state = R.string.state_root_denied;
            color = R.color.accent_red;
        }
        binding.rootState.setText(state);
        binding.rootState.setTextColor(ContextCompat.getColor(this, color));
        binding.rootDot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, color)));
    }

    private void restartSystemUi() {
        binding.btnRestartSystemui.setEnabled(false);
        executor.execute(() -> {
            boolean ok = runRoot("killall com.android.systemui") || runRoot("pkill -f com.android.systemui");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                binding.btnRestartSystemui.setEnabled(true);
                Toast.makeText(this,
                        ok ? R.string.restart_success : R.string.restart_failed,
                        Toast.LENGTH_LONG).show();
            });
        });
    }
}
