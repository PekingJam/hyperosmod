package com.hyperosmod.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hyperosmod.App;
import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.ActivityGroupSettingsBinding;
import com.hyperosmod.databinding.RowAppsBinding;
import com.hyperosmod.databinding.RowSwitchBinding;
import com.hyperosmod.databinding.ViewSettingsCardBinding;

import java.util.Set;

/**
 * 二级页面：主界面「系统」里各个功能分组的详细设置。
 *
 * <p>用同一个 Activity 承载五组设置，按 extra 决定往卡片里塞哪些行——避免为每个分组
 * 复制一份几乎相同的 Activity。行本身仍然是 {@code row_switch} / {@code row_apps}，
 * 和一级页面完全同源，样式不会漂移。</p>
 */
public final class GroupSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_GROUP = "group";
    public static final String GROUP_LOCK = "lock";
    public static final String GROUP_UPDATE = "update";
    public static final String GROUP_TELEPHONY = "telephony";
    public static final String GROUP_FCM = "fcm";
    public static final String GROUP_FITNESS = "fitness";

    private ActivityGroupSettingsBinding binding;
    private LayoutInflater inflater;
    private ViewSettingsCardBinding currentCard;
    private boolean firstRowInCard;
    private boolean dndPromptShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGroupSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);
        inflater = LayoutInflater.from(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        String group = getIntent().getStringExtra(EXTRA_GROUP);
        if (GROUP_UPDATE.equals(group)) {
            binding.toolbar.setTitle(R.string.section_updates);
            buildUpdate();
        } else if (GROUP_TELEPHONY.equals(group)) {
            binding.toolbar.setTitle(R.string.section_telephony);
            buildTelephony();
        } else if (GROUP_FCM.equals(group)) {
            binding.toolbar.setTitle(R.string.section_fcm);
            buildFcm();
        } else if (GROUP_FITNESS.equals(group)) {
            binding.toolbar.setTitle(R.string.section_mifitness);
            buildFitness();
        } else {
            binding.toolbar.setTitle(R.string.section_lockscreen);
            buildLockScreen();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiUtils.applySystemBars(this);
        if (GROUP_FITNESS.equals(getIntent().getStringExtra(EXTRA_GROUP))) {
            updateFitnessRow();
        }
    }

    // ==================================================================================
    // 行构造
    // ==================================================================================

    private ViewSettingsCardBinding card() {
        ViewSettingsCardBinding card = ViewSettingsCardBinding.inflate(inflater,
                binding.container, false);
        binding.container.addView(card.getRoot());
        firstRowInCard = true;
        return card;
    }

    private View divider(LinearLayout parent) {
        View view = inflater.inflate(R.layout.view_divider, parent, false);
        parent.addView(view);
        return view;
    }

    private RowSwitchBinding addSwitch(ViewSettingsCardBinding card, String key,
                                       int title, int summary, boolean defaultValue) {
        LinearLayout parent = card.cardContent;
        if (!firstRowInCard) {
            divider(parent);
        }
        firstRowInCard = false;
        View view = inflater.inflate(R.layout.row_switch, parent, false);
        parent.addView(view);
        RowSwitchBinding row = RowSwitchBinding.bind(view);
        row.title.setText(title);
        row.summary.setText(summary);
        row.switchView.setChecked(Prefs.getBoolean(key, defaultValue));
        row.switchView.setOnCheckedChangeListener((button, checked) ->
                Prefs.setBoolean(key, checked));
        return row;
    }

    private RowAppsBinding addEntry(ViewSettingsCardBinding card, int title, String summary,
                                    View.OnClickListener listener) {
        LinearLayout parent = card.cardContent;
        if (!firstRowInCard) {
            divider(parent);
        }
        firstRowInCard = false;
        View view = inflater.inflate(R.layout.row_apps, parent, false);
        parent.addView(view);
        RowAppsBinding row = RowAppsBinding.bind(view);
        row.title.setText(title);
        row.summary.setText(summary);
        row.getRoot().setOnClickListener(listener);
        return row;
    }

    // ==================================================================================
    // 各分组
    // ==================================================================================

    private void buildLockScreen() {
        ViewSettingsCardBinding card = card();
        RowSwitchBinding gesture = addSwitch(card, Keys.GESTURE_LINE_LOCK,
                R.string.gesture_line_lock_title, R.string.gesture_line_lock_summary, false);
        // 手势提示线的触摸判定在桌面进程，桌面没进作用域时这个开关等于没开。
        gesture.switchView.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setBoolean(Keys.GESTURE_LINE_LOCK, checked);
            if (checked && !App.isInScope("com.miui.home")) {
                Toast.makeText(this, R.string.gesture_line_lock_scope_missing,
                        Toast.LENGTH_LONG).show();
            }
        });
        addSwitch(card, Keys.UNLOCK_NOTIFICATION_RESTRICT,
                R.string.unlock_notification_title, R.string.unlock_notification_summary, false);
        addSwitch(card, Keys.KEEP_NOTIFICATION,
                R.string.keep_notification_title, R.string.keep_notification_summary, false);
    }

    private void buildUpdate() {
        ViewSettingsCardBinding card = card();
        addSwitch(card, Keys.BLOCK_UPDATES_ENABLED,
                R.string.block_updates_title, R.string.block_updates_summary, false);
        addEntry(card, R.string.choose_apps_title, summaryOfBlockedApps(),
                v -> startActivity(new Intent(this, BlockedAppsActivity.class)));
    }

    private void buildTelephony() {
        ViewSettingsCardBinding card = card();
        addSwitch(card, Keys.TELEPHONY_ENABLED,
                R.string.telephony_enabled_title, R.string.telephony_enabled_summary, false);
        addEntry(card, R.string.telephony_targets_title, summaryOfTelephonyTargets(), v -> {
            Intent intent = new Intent(this, BlockedAppsActivity.class);
            intent.putExtra(BlockedAppsActivity.EXTRA_MODE,
                    BlockedAppsActivity.MODE_TELEPHONY_TARGETS);
            startActivity(intent);
        });
        addEntry(card, R.string.telephony_edit_title, getString(R.string.telephony_edit_summary),
                v -> startActivity(new Intent(this, TelephonySettingsActivity.class)));
    }

    private void buildFcm() {
        ViewSettingsCardBinding card = card();
        addSwitch(card, Keys.FCM_FIX_ENABLED,
                R.string.fcm_fix_title, R.string.fcm_fix_summary, false);
        addEntry(card, R.string.fcm_targets_title, summaryOfFcmTargets(), v -> {
            Intent intent = new Intent(this, BlockedAppsActivity.class);
            intent.putExtra(BlockedAppsActivity.EXTRA_MODE, BlockedAppsActivity.MODE_FCM_TARGETS);
            startActivity(intent);
        });
        addSwitch(card, Keys.FCM_KEEP_NOTIFICATION,
                R.string.fcm_keep_title, R.string.fcm_keep_summary, true);
    }

    private void buildFitness() {
        ViewSettingsCardBinding card = card();
        RowSwitchBinding restore = addSwitch(card, Keys.RESTORE_DND_SYNC,
                R.string.restore_dnd_title, R.string.restore_dnd_summary, false);
        restore.switchView.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setBoolean(Keys.RESTORE_DND_SYNC, checked);
            if (checked) {
                maybePromptDndAccess();
            }
        });
        fitnessRow = addEntry(card, R.string.dnd_permission_title, "", v -> {
        });
        fitnessRow.getRoot().setOnClickListener(v ->
                PermissionHelper.openDndAccessSettings(this));
        updateFitnessRow();
    }

    private RowAppsBinding fitnessRow;

    private void updateFitnessRow() {
        if (fitnessRow == null) {
            return;
        }
        boolean installed = PermissionHelper.isMiFitnessInstalled(this);
        boolean granted = installed && PermissionHelper.hasMiFitnessDndAccess(this);
        fitnessRow.getRoot().setEnabled(installed);
        fitnessRow.getRoot().setAlpha(installed ? 1f : 0.45f);
        if (!installed) {
            fitnessRow.summary.setText(R.string.dnd_permission_not_installed);
        } else if (granted) {
            fitnessRow.summary.setText(R.string.dnd_permission_granted);
        } else {
            fitnessRow.summary.setText(R.string.dnd_permission_missing);
        }
    }

    // ==================================================================================

    private String summaryOfBlockedApps() {
        Set<String> blocked = Prefs.getStringSet(Keys.BLOCKED_PACKAGES);
        return blocked.isEmpty() ? getString(R.string.choose_apps_summary_zero)
                : getString(R.string.choose_apps_summary, blocked.size());
    }

    private String summaryOfTelephonyTargets() {
        Set<String> targets = Prefs.getStringSet(Keys.TELEPHONY_TARGETS);
        return targets.isEmpty() ? getString(R.string.telephony_targets_summary_zero)
                : getString(R.string.telephony_targets_summary, targets.size());
    }

    private String summaryOfFcmTargets() {
        Set<String> targets = Prefs.getStringSet(Keys.FCM_TARGETS);
        return targets.isEmpty() ? getString(R.string.fcm_targets_summary_zero)
                : getString(R.string.fcm_targets_summary, targets.size());
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
}
