package com.hyperosmod.ui;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.ActivityBlockedAppsBinding;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lets the user choose packages for update protection or telephony spoofing. */
public final class BlockedAppsActivity extends AppCompatActivity {
    public static final String EXTRA_MODE = "mode";
    public static final int MODE_BLOCK_UPDATES = 0;
    public static final int MODE_TELEPHONY_TARGETS = 1;
    public static final int MODE_FCM_TARGETS = 2;

    private ActivityBlockedAppsBinding binding;
    private AppListAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Set<String> selectedPackages = new HashSet<>();
    private boolean permissionMissing;
    private boolean permissionDialogShown;
    private int mode;
    private String storeKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBlockedAppsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);

        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_BLOCK_UPDATES);
        storeKey = mode == MODE_TELEPHONY_TARGETS
                ? Keys.TELEPHONY_TARGETS
                : (mode == MODE_FCM_TARGETS ? Keys.FCM_TARGETS : Keys.BLOCKED_PACKAGES);

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        int titleRes;
        if (mode == MODE_TELEPHONY_TARGETS) {
            titleRes = R.string.telephony_targets_picker_title;
        } else if (mode == MODE_FCM_TARGETS) {
            titleRes = R.string.fcm_targets_picker_title;
        } else {
            titleRes = R.string.app_picker_title;
        }
        binding.toolbar.setTitle(titleRes);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        selectedPackages = Prefs.getStringSet(storeKey);
        adapter = new AppListAdapter(selectedPackages, this::onSelectionChanged);
        binding.appList.setLayoutManager(new LinearLayoutManager(this));
        binding.appList.setAdapter(adapter);
        binding.appList.addItemDecoration(
                new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));

        binding.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                adapter.setQuery(s == null ? "" : s.toString());
                updateEmptyState();
            }
        });

        binding.filterGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chip_user)) {
                adapter.setFilter(AppListAdapter.FILTER_USER);
            } else if (checkedIds.contains(R.id.chip_system)) {
                adapter.setFilter(AppListAdapter.FILTER_SYSTEM);
            } else {
                adapter.setFilter(AppListAdapter.FILTER_ALL);
            }
            updateEmptyState();
        });

        binding.clearAll.setOnClickListener(v -> confirmClearAll());
        binding.selectedCount.setText(getString(countStringRes(), adapter.selectedCount()));
        permissionMissing = !PermissionHelper.hasAppListAccess(this);
        if (permissionMissing) {
            showPermissionDialog();
            binding.loading.setVisibility(View.GONE);
            binding.empty.setText(R.string.app_list_permission_message);
            binding.empty.setVisibility(View.VISIBLE);
        } else {
            loadApps();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiUtils.applySystemBars(this);
        if (permissionMissing && PermissionHelper.hasAppListAccess(this)) {
            permissionMissing = false;
            binding.empty.setText(R.string.empty_apps);
            loadApps();
        }
    }

    private void showPermissionDialog() {
        if (permissionDialogShown) {
            return;
        }
        permissionDialogShown = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.app_list_permission_title)
                .setMessage(R.string.app_list_permission_message)
                .setNegativeButton(R.string.later, null)
                .setPositiveButton(R.string.grant_permission, (dialog, which) ->
                        PermissionHelper.openAppListSettings(this))
                .show();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void loadApps() {
        binding.loading.setVisibility(View.VISIBLE);
        binding.empty.setVisibility(View.GONE);
        executor.execute(() -> {
            // 勾选名单里可能残留已卸载应用的包名：先剔掉，再据此生成列表，
            // 否则「已选 N 个」永远比列表里能看到的勾选多，用户还不能取消。
            Set<String> pruned = PackagePruner.prune(getPackageManager(), selectedPackages);
            boolean changed = pruned != selectedPackages;
            List<AppEntry> entries = readInstalledApps(pruned);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (changed) {
                    selectedPackages = new HashSet<>(pruned);
                    Prefs.setStringSet(storeKey, pruned);
                    adapter.syncSelection(pruned);
                    binding.selectedCount.setText(getString(countStringRes(), pruned.size()));
                }
                adapter.submit(entries);
                binding.loading.setVisibility(View.GONE);
                updateEmptyState();
            });
        });
    }

    private List<AppEntry> readInstalledApps(Set<String> selected) {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> infos = pm.getInstalledApplications(PackageManager.MATCH_ALL);
        List<AppEntry> entries = new ArrayList<>(infos.size());
        String self = getPackageName();

        for (ApplicationInfo info : infos) {
            if (self.equals(info.packageName)) {
                continue;
            }
            String label;
            try {
                label = info.loadLabel(pm).toString();
            } catch (Throwable t) {
                label = info.packageName;
            }
            String versionName = "";
            long versionCode = 0;
            try {
                PackageInfo packageInfo = pm.getPackageInfo(info.packageName, 0);
                versionName = packageInfo.versionName == null ? "" : packageInfo.versionName;
                versionCode = packageInfo.getLongVersionCode();
            } catch (Throwable ignored) {
                // A package can disappear while the list is being built.
            }
            Drawable icon;
            try {
                icon = info.loadIcon(pm);
            } catch (Throwable t) {
                icon = null;
            }
            boolean system = (info.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            entries.add(new AppEntry(label, info.packageName, versionName, versionCode, system, icon));
        }

        Collator collator = Collator.getInstance(Locale.CHINA);
        entries.sort((left, right) -> {
            boolean leftSelected = selected.contains(left.packageName);
            boolean rightSelected = selected.contains(right.packageName);
            if (leftSelected != rightSelected) {
                return leftSelected ? -1 : 1;
            }
            return collator.compare(left.label, right.label);
        });
        return entries;
    }

    private int countStringRes() {
        return mode == MODE_BLOCK_UPDATES
                ? R.string.selected_count : R.string.selected_targets_count;
    }

    private void onSelectionChanged(Set<String> selected) {
        selectedPackages = new HashSet<>(selected);
        Prefs.setStringSet(storeKey, selected);
        binding.selectedCount.setText(getString(countStringRes(), selected.size()));
    }

    private void updateEmptyState() {
        boolean empty = binding.loading.getVisibility() != View.VISIBLE
                && adapter.visibleCount() == 0;
        binding.empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.appList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void confirmClearAll() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_all)
                .setMessage(R.string.clear_all_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_all, (dialog, which) -> {
                    adapter.clearSelection();
                    updateEmptyState();
                })
                .show();
    }
}


