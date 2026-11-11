package com.hyperosmod.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
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
import com.hyperosmod.databinding.RowSliderBinding;
import com.hyperosmod.databinding.RowSwitchBinding;
import com.hyperosmod.singbox.SingBoxFragment;
import com.hyperosmod.singbox.TileEntryActivity;
import com.hyperosmod.singbox.SingBoxWebFragment;

import java.util.Locale;
import java.util.Set;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);
        setSupportActionBar(binding.toolbar);

        bindLockScreenRows();
        bindThemeRows();
        bindStatusBarRows();
        bindUpdateRows();
        bindDndRows();
        bindTelephonyRows();
        bindFcmRows();

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
            target = R.id.tab_web;
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
        binding.pageWeb.setVisibility(itemId == R.id.tab_web ? View.VISIBLE : View.GONE);
        if (itemId == R.id.tab_singbox) {
            ensureFragment(R.id.container_singbox, SingBoxFragment::new, "singbox");
        } else if (itemId == R.id.tab_web) {
            ensureFragment(R.id.container_web, SingBoxWebFragment::new, "web");
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

    private void bindLockScreenRows() {
        bindSwitch(binding.rowGestureLineLock, Keys.GESTURE_LINE_LOCK,
                R.string.gesture_line_lock_title, R.string.gesture_line_lock_summary);
        // 手势提示线的触摸判定在桌面进程里，桌面没进作用域时这个开关等于没开，直接提示。
        binding.rowGestureLineLock.switchView.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setBoolean(Keys.GESTURE_LINE_LOCK, checked);
            if (checked && !App.isInScope("com.miui.home")) {
                Toast.makeText(this, R.string.gesture_line_lock_scope_missing,
                        Toast.LENGTH_LONG).show();
            }
        });
        bindSwitch(binding.rowUnlockNotification, Keys.UNLOCK_NOTIFICATION_RESTRICT,
                R.string.unlock_notification_title, R.string.unlock_notification_summary);
        bindSwitch(binding.rowKeepNotification, Keys.KEEP_NOTIFICATION,
                R.string.keep_notification_title, R.string.keep_notification_summary);
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
        bindSliderRow(binding.rowDualSignalScale,
                R.string.dual_signal_scale_title,
                R.string.dual_signal_scale_summary,
                Keys.DUAL_SIGNAL_SCALE,
                Keys.DUAL_SIGNAL_SCALE_MIN,
                Keys.DUAL_SIGNAL_SCALE_MAX,
                Keys.DUAL_SIGNAL_SCALE_DEFAULT);
        bindSliderRow(binding.rowDualSignalThickness,
                R.string.dual_signal_thickness_title,
                R.string.dual_signal_thickness_summary,
                Keys.DUAL_SIGNAL_THICKNESS,
                Keys.DUAL_SIGNAL_THICKNESS_MIN,
                Keys.DUAL_SIGNAL_THICKNESS_MAX,
                Keys.DUAL_SIGNAL_THICKNESS_DEFAULT);
        bindSliderRow(binding.rowDualTextScale,
                R.string.dual_text_scale_title,
                R.string.dual_text_scale_summary,
                Keys.DUAL_TEXT_SCALE,
                Keys.DUAL_TEXT_SCALE_MIN,
                Keys.DUAL_TEXT_SCALE_MAX,
                Keys.DUAL_TEXT_SCALE_DEFAULT);
        bindSliderRow(binding.rowDualTextThickness,
                R.string.dual_text_thickness_title,
                R.string.dual_text_thickness_summary,
                Keys.DUAL_TEXT_THICKNESS,
                Keys.DUAL_TEXT_THICKNESS_MIN,
                Keys.DUAL_TEXT_THICKNESS_MAX,
                Keys.DUAL_TEXT_THICKNESS_DEFAULT);
    }

    private void bindSliderRow(RowSliderBinding row, int title, int summary, String key,
                               int min, int max, int defaultValue) {
        row.title.setText(title);
        row.slider.setValueFrom(min);
        row.slider.setValueTo(max);
        row.slider.setStepSize(5);
        int value = clamp(Prefs.getInt(key, defaultValue), min, max);
        row.slider.setValue(value);
        row.summary.setText(getString(summary, value));
        row.slider.addOnChangeListener((slider, current, fromUser) -> {
            int rounded = Math.round(current);
            row.summary.setText(getString(summary, rounded));
            if (fromUser) {
                Prefs.setInt(key, rounded);
            }
        });
    }

    private void updateDualSignalRows() {
        boolean enabled = Prefs.getBoolean(Keys.DUAL_ROW_SIGNAL, false);
        binding.dualSignalHint.setVisibility(enabled ? View.VISIBLE : View.GONE);
        float alpha = enabled ? 1f : 0.45f;
        binding.rowDualSignalScale.getRoot().setAlpha(alpha);
        binding.rowDualSignalScale.slider.setEnabled(enabled);
        binding.rowDualSignalThickness.getRoot().setAlpha(alpha);
        binding.rowDualSignalThickness.slider.setEnabled(enabled);
        binding.rowDualTextScale.getRoot().setAlpha(alpha);
        binding.rowDualTextScale.slider.setEnabled(enabled);
        binding.rowDualTextThickness.getRoot().setAlpha(alpha);
        binding.rowDualTextThickness.slider.setEnabled(enabled);
    }

    private void updateDualSignalValues() {
        binding.rowDualSignalScale.slider.setValue(clamp(
                Prefs.getInt(Keys.DUAL_SIGNAL_SCALE, Keys.DUAL_SIGNAL_SCALE_DEFAULT),
                Keys.DUAL_SIGNAL_SCALE_MIN, Keys.DUAL_SIGNAL_SCALE_MAX));
        binding.rowDualSignalThickness.slider.setValue(clamp(
                Prefs.getInt(Keys.DUAL_SIGNAL_THICKNESS, Keys.DUAL_SIGNAL_THICKNESS_DEFAULT),
                Keys.DUAL_SIGNAL_THICKNESS_MIN, Keys.DUAL_SIGNAL_THICKNESS_MAX));
        binding.rowDualTextScale.slider.setValue(clamp(
                Prefs.getInt(Keys.DUAL_TEXT_SCALE, Keys.DUAL_TEXT_SCALE_DEFAULT),
                Keys.DUAL_TEXT_SCALE_MIN, Keys.DUAL_TEXT_SCALE_MAX));
        binding.rowDualTextThickness.slider.setValue(clamp(
                Prefs.getInt(Keys.DUAL_TEXT_THICKNESS, Keys.DUAL_TEXT_THICKNESS_DEFAULT),
                Keys.DUAL_TEXT_THICKNESS_MIN, Keys.DUAL_TEXT_THICKNESS_MAX));
    }

    private void bindUpdateRows() {
        bindSwitch(binding.rowBlockUpdates, Keys.BLOCK_UPDATES_ENABLED,
                R.string.block_updates_title, R.string.block_updates_summary);
        binding.rowBlockedApps.title.setText(R.string.choose_apps_title);
        binding.rowBlockedApps.getRoot().setOnClickListener(v ->
                startActivity(new Intent(this, BlockedAppsActivity.class)));
    }

    private void bindDndRows() {
        bindSwitch(binding.rowRestoreDnd, Keys.RESTORE_DND_SYNC,
                R.string.restore_dnd_title, R.string.restore_dnd_summary);
        binding.rowRestoreDnd.switchView.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setBoolean(Keys.RESTORE_DND_SYNC, checked);
            updateDndRow();
            if (checked) {
                maybePromptDndAccess();
            }
        });

        binding.rowDndPermission.title.setText(R.string.dnd_permission_title);
        binding.rowDndPermission.getRoot().setOnClickListener(v ->
                PermissionHelper.openDndAccessSettings(this));
    }

    private void updateDndRow() {
        boolean installed = PermissionHelper.isMiFitnessInstalled(this);
        boolean granted = installed && PermissionHelper.hasMiFitnessDndAccess(this);
        boolean enabled = Prefs.getBoolean(Keys.RESTORE_DND_SYNC, false);

        binding.rowDndPermission.getRoot().setEnabled(installed);
        binding.rowDndPermission.getRoot().setAlpha(installed ? 1f : 0.45f);
        if (!installed) {
            binding.rowDndPermission.summary.setText(R.string.dnd_permission_not_installed);
        } else if (granted) {
            binding.rowDndPermission.summary.setText(R.string.dnd_permission_granted);
        } else {
            binding.rowDndPermission.summary.setText(R.string.dnd_permission_missing);
        }
        binding.dndHint.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void bindTelephonyRows() {
        bindSwitch(binding.rowTelephonyEnabled, Keys.TELEPHONY_ENABLED,
                R.string.telephony_enabled_title, R.string.telephony_enabled_summary);
        binding.rowTelephonyTargets.title.setText(R.string.telephony_targets_title);
        binding.rowTelephonyTargets.getRoot().setOnClickListener(v -> {
            Intent intent = new Intent(this, BlockedAppsActivity.class);
            intent.putExtra(BlockedAppsActivity.EXTRA_MODE,
                    BlockedAppsActivity.MODE_TELEPHONY_TARGETS);
            startActivity(intent);
        });
        binding.rowTelephonyEdit.title.setText(R.string.telephony_edit_title);
        binding.rowTelephonyEdit.getRoot().setOnClickListener(v ->
                startActivity(new Intent(this, TelephonySettingsActivity.class)));
    }

    private void updateTelephonyRows() {
        boolean enabled = Prefs.getBoolean(Keys.TELEPHONY_ENABLED, false);
        Set<String> targets = Prefs.getStringSet(Keys.TELEPHONY_TARGETS);
        binding.rowTelephonyTargets.summary.setText(targets.isEmpty()
                ? getString(R.string.telephony_targets_summary_zero)
                : getString(R.string.telephony_targets_summary, targets.size()));
        binding.rowTelephonyEdit.summary.setText(R.string.telephony_edit_summary);
        binding.telephonyHint.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void bindFcmRows() {
        bindSwitch(binding.rowFcmEnabled, Keys.FCM_FIX_ENABLED,
                R.string.fcm_fix_title, R.string.fcm_fix_summary);
        binding.rowFcmTargets.title.setText(R.string.fcm_targets_title);
        binding.rowFcmTargets.getRoot().setOnClickListener(v -> {
            Intent intent = new Intent(this, BlockedAppsActivity.class);
            intent.putExtra(BlockedAppsActivity.EXTRA_MODE,
                    BlockedAppsActivity.MODE_FCM_TARGETS);
            startActivity(intent);
        });
        bindSwitch(binding.rowFcmKeep, Keys.FCM_KEEP_NOTIFICATION,
                R.string.fcm_keep_title, R.string.fcm_keep_summary, true);
    }

    private void updateFcmRows() {
        boolean enabled = Prefs.getBoolean(Keys.FCM_FIX_ENABLED, false);
        Set<String> targets = Prefs.getStringSet(Keys.FCM_TARGETS);
        binding.rowFcmTargets.summary.setText(targets.isEmpty()
                ? getString(R.string.fcm_targets_summary_zero)
                : getString(R.string.fcm_targets_summary, targets.size()));
        binding.rowFcmKeep.getRoot().setEnabled(enabled);
        binding.rowFcmKeep.getRoot().setAlpha(enabled ? 1f : 0.45f);
        binding.rowFcmKeep.switchView.setEnabled(enabled);

        if (!enabled) {
            binding.fcmHint.setVisibility(View.GONE);
            return;
        }
        binding.fcmHint.setVisibility(View.VISIBLE);
        if (targets.isEmpty()) {
            binding.fcmHint.setText(R.string.fcm_targets_missing);
        } else if (!App.isInScope("system") || !App.isInScope("com.miui.powerkeeper")) {
            binding.fcmHint.setText(R.string.fcm_scope_missing);
        } else if (Boolean.FALSE.equals(PermissionHelper.isGmsInMilletWhitelist(this))) {
            binding.fcmHint.setText(R.string.fcm_whitelist_missing);
        } else {
            binding.fcmHint.setText(R.string.fcm_hint);
        }
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
        binding.rowGestureLineLock.switchView.setChecked(
                Prefs.getBoolean(Keys.GESTURE_LINE_LOCK, false));
        binding.rowUnlockNotification.switchView.setChecked(
                Prefs.getBoolean(Keys.UNLOCK_NOTIFICATION_RESTRICT, false));
        binding.rowKeepNotification.switchView.setChecked(
                Prefs.getBoolean(Keys.KEEP_NOTIFICATION, false));
        binding.rowCustomTheme.switchView.setChecked(
                Prefs.getBoolean(Keys.CUSTOM_THEME_ENABLED, false));
        binding.rowDualSignal.switchView.setChecked(
                Prefs.getBoolean(Keys.DUAL_ROW_SIGNAL, false));
        binding.rowBlockUpdates.switchView.setChecked(
                Prefs.getBoolean(Keys.BLOCK_UPDATES_ENABLED, false));
        binding.rowTelephonyEnabled.switchView.setChecked(
                Prefs.getBoolean(Keys.TELEPHONY_ENABLED, false));
        binding.rowFcmEnabled.switchView.setChecked(
                Prefs.getBoolean(Keys.FCM_FIX_ENABLED, false));
        binding.rowFcmKeep.switchView.setChecked(
                Prefs.getBoolean(Keys.FCM_KEEP_NOTIFICATION, true));

        updateThemeRow();
        updateDualSignalValues();
        updateDualSignalRows();
        pruneUninstalledTargets();
        updateBlockedAppsRow();
        updateDndRow();
        updateTelephonyRows();
        updateFcmRows();
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
    }

    private void pruneOne(String key) {
        Set<String> current = Prefs.getStringSet(key);
        Set<String> pruned = PackagePruner.prune(getPackageManager(), current);
        // prune() 在没有任何条目被移除时会原样返回入参，用引用相等判断就不必多写一次盘。
        if (pruned != current) {
            Prefs.setStringSet(key, pruned);
        }
    }

    private void updateBlockedAppsRow() {
        Set<String> blocked = Prefs.getStringSet(Keys.BLOCKED_PACKAGES);
        binding.rowBlockedApps.summary.setText(blocked.isEmpty()
                ? getString(R.string.choose_apps_summary_zero)
                : getString(R.string.choose_apps_summary, blocked.size()));
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
