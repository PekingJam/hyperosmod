package com.hyperosmod.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
    public static final String GROUP_NOTIFY_ICON = "notify_icon";

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
        } else if (GROUP_NOTIFY_ICON.equals(group)) {
            binding.toolbar.setTitle(R.string.section_notify_icon);
            buildNotifyIcon();
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
        } else if (GROUP_NOTIFY_ICON.equals(getIntent().getStringExtra(EXTRA_GROUP))) {
            updateNotifyIconRow();
        } else if (GROUP_LOCK.equals(getIntent().getStringExtra(EXTRA_GROUP))) {
            updateKeyguardNotifyRow();
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
        RowSwitchBinding keyguardNotify = addSwitch(card, Keys.KEYGUARD_NOTIFY_TARGETS_ENABLED,
                R.string.keyguard_notify_enabled_title, R.string.keyguard_notify_enabled_summary,
                false);
        keyguardNotify.switchView.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setBoolean(Keys.KEYGUARD_NOTIFY_TARGETS_ENABLED, checked);
            if (checked && !App.isInScope("com.android.systemui")) {
                Toast.makeText(this, R.string.keyguard_notify_scope_missing,
                        Toast.LENGTH_LONG).show();
            }
        });
        keyguardNotifyRow = addEntry(card, R.string.keyguard_notify_targets_title,
                summaryOfKeyguardNotifyTargets(), v -> {
                    Intent intent = new Intent(this, BlockedAppsActivity.class);
                    intent.putExtra(BlockedAppsActivity.EXTRA_MODE,
                            BlockedAppsActivity.MODE_KEYGUARD_NOTIFY_TARGETS);
                    startActivity(intent);
                });
        addSwitch(card, Keys.KEEP_NOTIFICATION,
                R.string.keep_notification_title, R.string.keep_notification_summary, false);
    }

    private RowAppsBinding keyguardNotifyRow;

    private void updateKeyguardNotifyRow() {
        if (keyguardNotifyRow != null) {
            keyguardNotifyRow.summary.setText(summaryOfKeyguardNotifyTargets());
        }
    }

    private String summaryOfKeyguardNotifyTargets() {
        Set<String> targets = Prefs.getStringSet(Keys.KEYGUARD_NOTIFY_TARGETS);
        if (targets.isEmpty()) {
            return getString(R.string.keyguard_notify_targets_summary_zero);
        }
        return getString(R.string.keyguard_notify_targets_summary, targets.size());
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

    private void buildNotifyIcon() {
        ViewSettingsCardBinding card = card();
        RowSwitchBinding toggle = addSwitch(card, Keys.NOTIFY_ICON_ENABLED,
                R.string.notify_icon_enabled_title, R.string.notify_icon_enabled_summary, false);
        toggle.switchView.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setBoolean(Keys.NOTIFY_ICON_ENABLED, checked);
            if (checked) {
                // 只有用户主动打开这个功能时才检查作用域：平时不打扰，缺了就直接引导去授权。
                checkScopeOnEnable();
            }
        });
        notifyIconTargetsRow = addEntry(card, R.string.notify_icon_targets_title,
                summaryOfNotifyIconTargets(), v -> {
                    Intent intent = new Intent(this, BlockedAppsActivity.class);
                    intent.putExtra(BlockedAppsActivity.EXTRA_MODE,
                            BlockedAppsActivity.MODE_NOTIFY_ICON_TARGETS);
                    startActivity(intent);
                });
        notifyIconScopeRow = addEntry(card, R.string.notify_icon_scope_title,
                scopeSummary(), v -> {
                    updateNotifyIconRow();
                    warnMissingScope();
                });
        notifyIconAodRow = addEntry(card, R.string.notify_icon_aod_title,
                aodSummary(), v -> {
                    updateNotifyIconRow();
                    warnMissingScope();
                });
        addEntry(card, R.string.notify_icon_diag_row,
                getString(R.string.notify_icon_diag_summary),
                v -> showRuntimeDiagnostics());
        updateNotifyIconRow();
    }

    /** 息屏显示到底是不是 com.miui.aod 在画、进程有没有起来——直接问系统，别猜。 */
    private static final String DIAG_COMMAND = "echo '=== AOD 进程 ==='; "
            + "ps -A -o PID,NAME,ARGS 2>/dev/null | grep -i aod; "
            + "echo; echo '=== 含 aod / doze 的包 ==='; "
            + "pm list packages 2>/dev/null | grep -i -E 'aod|doze'; "
            + "echo; echo '=== 相关属性 ==='; "
            + "getprop 2>/dev/null | grep -i -E 'aod|doze'";

    private void showRuntimeDiagnostics() {
        if (!com.hyperosmod.singbox.RootShell.hasRoot()) {
            Toast.makeText(this, R.string.notify_icon_diag_no_root, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.notify_icon_diag_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final String output = com.hyperosmod.singbox.RootShell.exec(DIAG_COMMAND, 20).output;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.notify_icon_diag_title)
                        .setMessage(output.trim().isEmpty()
                                ? getString(R.string.notify_icon_diag_empty) : output)
                        .setNegativeButton(R.string.action_close, null)
                        .setNeutralButton(R.string.action_copy, (dialog, which) -> {
                            ClipboardManager clipboard = (ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(
                                        ClipData.newPlainText("aod-diag", output));
                            }
                            Toast.makeText(this, R.string.singbox_log_copied,
                                    Toast.LENGTH_SHORT).show();
                        })
                        .show();
            });
        }, "aod-diag").start();
    }

    private RowAppsBinding notifyIconScopeRow;
    private RowAppsBinding notifyIconAodRow;
    private RowAppsBinding notifyIconTargetsRow;

    private void updateNotifyIconRow() {
        if (notifyIconTargetsRow == null) {
            return;
        }
        notifyIconTargetsRow.summary.setText(summaryOfNotifyIconTargets());
        if (notifyIconScopeRow != null) {
            notifyIconScopeRow.summary.setText(scopeSummary());
        }
        if (notifyIconAodRow != null) {
            notifyIconAodRow.summary.setText(aodSummary());
        }
    }

    /** 「系统界面 ✓ · 息屏显示 ✗ · 目标应用 ✓」——漏勾哪个一眼就能看出来。 */
    private String scopeSummary() {
        Set<String> targets = Prefs.getStringSet(Keys.NOTIFY_ICON_TARGETS);
        boolean targetsInScope = true;
        for (String packageName : targets) {
            if (!App.isInScope(packageName)) {
                targetsInScope = false;
                break;
            }
        }
        String aodMark;
        java.util.List<String> aod = aodPackages();
        if (aod.isEmpty()) {
            aodMark = getString(R.string.notify_icon_scope_absent);
        } else {
            boolean any = false;
            for (String packageName : aod) {
                if (App.isInScope(packageName)) {
                    any = true;
                    break;
                }
            }
            aodMark = mark(any);
        }
        return getString(R.string.notify_icon_scope_row,
                mark(App.isInScope("com.android.systemui")),
                aodMark,
                mark(!targets.isEmpty() && targetsInScope));
    }

    /** 设备上可能负责息屏显示图标的包；HyperOS 是 com.miui.aod，别的 ROM 可能叫法不同。 */
    private static java.util.List<String> AOD_PACKAGES;

    private java.util.List<String> aodPackages() {
        if (AOD_PACKAGES != null) {
            return AOD_PACKAGES;
        }
        java.util.List<String> found = new java.util.ArrayList<>();
        try {
            for (android.content.pm.PackageInfo info
                    : getPackageManager().getInstalledPackages(0)) {
                String name = info.packageName.toLowerCase(java.util.Locale.ROOT);
                if (name.contains("aod") || name.contains("alwayson")) {
                    found.add(info.packageName);
                }
            }
        } catch (Throwable ignored) {
            // 拿不到列表就当作没找到，下面会给出提示文案。
        }
        AOD_PACKAGES = found;
        return found;
    }

    private String aodSummary() {
        java.util.List<String> packages = aodPackages();
        if (packages.isEmpty()) {
            return getString(R.string.notify_icon_aod_none);
        }
        StringBuilder builder = new StringBuilder();
        for (String packageName : packages) {
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            builder.append(packageName).append(' ')
                    .append(App.isInScope(packageName)
                            ? getString(R.string.notify_icon_scope_ok)
                            : getString(R.string.notify_icon_scope_no));
        }
        return builder.toString();
    }

    private String mark(boolean ok) {
        return getString(ok ? R.string.notify_icon_scope_ok : R.string.notify_icon_scope_no);
    }

    private String summaryOfNotifyIconTargets() {
        Set<String> targets = Prefs.getStringSet(Keys.NOTIFY_ICON_TARGETS);
        if (targets.isEmpty()) {
            return getString(R.string.notify_icon_targets_summary_zero);
        }
        return getString(R.string.notify_icon_targets_summary, targets.size());
    }

    /** 目标应用必须同时被勾进 LSPosed 作用域，否则模块根本不会进它的进程。 */
    private void warnMissingScope() {
        // SystemUI 与息屏显示都要进作用域：前者管通知面板/锁屏，后者管 AOD。
        String[] required = {"com.android.systemui", "com.miui.aod"};
        for (String packageName : required) {
            if (isInstalled(packageName) && !App.isInScope(packageName)) {
                warnScope(packageName);
                return;
            }
        }
        for (String packageName : Prefs.getStringSet(Keys.NOTIFY_ICON_TARGETS)) {
            if (!App.isInScope(packageName)) {
                warnScope(packageName);
                return;
            }
        }
    }

    /**
     * 打开「跟随桌面图标」时检查一次作用域，缺口直接弹窗并给「打开 LSPosed」的入口。
     *
     * <p>这个功能的「没生效」里绝大多数是作用域没勾全：模块不会被注入那个进程，日志里
     * 连一行都看不到，用户只会觉得功能坏了。所以只在用户主动开启时提示，平时不打扰。</p>
     */
    private void checkScopeOnEnable() {
        if (!App.isActivated()) {
            return;
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (!App.isInScope("com.android.systemui")) {
            missing.add(getString(R.string.notify_icon_scope_systemui));
        }
        java.util.List<String> aod = aodPackages();
        if (!aod.isEmpty()) {
            boolean anyInScope = false;
            for (String packageName : aod) {
                if (App.isInScope(packageName)) {
                    anyInScope = true;
                    break;
                }
            }
            if (!anyInScope) {
                missing.add(getString(R.string.notify_icon_scope_aod));
            }
        }
        for (String packageName : Prefs.getStringSet(Keys.NOTIFY_ICON_TARGETS)) {
            if (!App.isInScope(packageName)) {
                missing.add(appLabel(packageName));
            }
        }
        updateNotifyIconRow();
        if (missing.isEmpty()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notify_icon_scope_dialog_title)
                .setMessage(getString(R.string.notify_icon_scope_dialog_message,
                        android.text.TextUtils.join("、", missing)))
                .setNegativeButton(R.string.later, null)
                .setPositiveButton(R.string.notify_icon_scope_dialog_open,
                        (dialog, which) -> openLsposedManager())
                .show();
    }

    private void openLsposedManager() {
        LsposedLauncher.open(this);
    }

    private void warnScope(String packageName) {
        Toast.makeText(this, getString(R.string.notify_icon_scope_missing, appLabel(packageName)),
                Toast.LENGTH_LONG).show();
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
