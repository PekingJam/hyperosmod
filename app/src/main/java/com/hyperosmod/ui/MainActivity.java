package com.hyperosmod.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hyperosmod.App;
import com.hyperosmod.BuildConfig;
import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.ActivityMainBinding;
import com.hyperosmod.databinding.RowSwitchBinding;

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
        bindUpdateRows();
        bindDndRows();
        bindTelephonyRows();

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

    private void bindLockScreenRows() {
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

    private void bindSwitch(RowSwitchBinding row, String key, int title, int summary) {
        row.title.setText(title);
        row.summary.setText(summary);
        row.switchView.setChecked(Prefs.getBoolean(key, false));
        row.switchView.setOnCheckedChangeListener((button, checked) ->
                Prefs.setBoolean(key, checked));
    }

    private void refreshAll() {
        binding.rowUnlockNotification.switchView.setChecked(
                Prefs.getBoolean(Keys.UNLOCK_NOTIFICATION_RESTRICT, false));
        binding.rowKeepNotification.switchView.setChecked(
                Prefs.getBoolean(Keys.KEEP_NOTIFICATION, false));
        binding.rowCustomTheme.switchView.setChecked(
                Prefs.getBoolean(Keys.CUSTOM_THEME_ENABLED, false));
        binding.rowBlockUpdates.switchView.setChecked(
                Prefs.getBoolean(Keys.BLOCK_UPDATES_ENABLED, false));
        binding.rowTelephonyEnabled.switchView.setChecked(
                Prefs.getBoolean(Keys.TELEPHONY_ENABLED, false));

        updateThemeRow();
        updateBlockedAppsRow();
        updateDndRow();
        updateTelephonyRows();
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
