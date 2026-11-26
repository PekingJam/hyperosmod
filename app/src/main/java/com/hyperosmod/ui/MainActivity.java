package com.hyperosmod.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
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

        installSwipeNavigation();

        binding.tabSingboxMode.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                showSingboxSegment(tab.getPosition());
            }
            @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) { }
            @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) { }
        });

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
        boolean web = tab == TileEntryActivity.TAB_WEB;
        if (web || tab == TileEntryActivity.TAB_PANEL) {
            target = R.id.tab_singbox;
        } else {
            target = R.id.tab_system;
        }
        binding.bottomNav.setSelectedItemId(target);
        // 底部 tab 选中是异步的，等它就位后再切分段，否则会被 showPage 的默认分段覆盖。
        binding.bottomNav.post(() -> selectSingboxSegment(web ? SEGMENT_WEB : SEGMENT_MANAGE));
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

    /** sing-box 页内的两段：管理（控制/版本/配置入口）与网页面板。 */
    private static final int SEGMENT_MANAGE = 0;
    private static final int SEGMENT_WEB = 1;

    /**
     * 底部导航：状态栏 / 系统 共用滚动区；sing-box 页里再分「管理 / 网页面板」两段，
     * 首次切到某一段时才创建对应 fragment，避免启动时就把 WebView 和 root 调用拉起来。
     */
    private void showPage(int itemId) {
        boolean scroll = itemId == R.id.tab_statusbar || itemId == R.id.tab_system;
        binding.scrollContent.setVisibility(scroll ? View.VISIBLE : View.GONE);
        binding.pageStatusbar.setVisibility(
                itemId == R.id.tab_statusbar ? View.VISIBLE : View.GONE);
        binding.pageSystem.setVisibility(itemId == R.id.tab_system ? View.VISIBLE : View.GONE);
        binding.pageSingbox.setVisibility(itemId == R.id.tab_singbox ? View.VISIBLE : View.GONE);
        if (itemId == R.id.tab_singbox) {
            com.google.android.material.tabs.TabLayout.Tab tab =
                    binding.tabSingboxMode.getTabAt(binding.tabSingboxMode.getSelectedTabPosition());
            showSingboxSegment(tab == null ? SEGMENT_MANAGE : tab.getPosition());
        }
        binding.scrollContent.scrollTo(0, 0);
    }

    private void selectSingboxSegment(int segment) {
        com.google.android.material.tabs.TabLayout.Tab tab = binding.tabSingboxMode.getTabAt(segment);
        if (tab != null) {
            tab.select();
        } else {
            showSingboxSegment(segment);
        }
    }

    /** 只切内容容器，不重建已存在的 fragment：面板里的 WebView 不会因为来回切而重新加载。 */
    private void showSingboxSegment(int segment) {
        boolean web = segment == SEGMENT_WEB;
        binding.containerSingbox.setVisibility(web ? View.GONE : View.VISIBLE);
        binding.containerPanel.setVisibility(web ? View.VISIBLE : View.GONE);
        if (web) {
            ensureFragment(R.id.container_panel, PanelFragment::new, "panel");
            notifyPanelVisible();
        } else {
            ensureFragment(R.id.container_singbox, SingBoxFragment::new, "singbox");
        }
    }

    // ───────────────────────── 左右滑动切 tab ─────────────────────────

    /** 一级 tab 的顺序，横滑时按这个数组前后走。 */
    private static final int[] TAB_ORDER = {R.id.tab_system, R.id.tab_statusbar, R.id.tab_singbox};
    private static final float SWIPE_MIN_VELOCITY = 900f;      // px/s，太慢当成拖拽
    private GestureDetector swipeDetector;
    private boolean touchStartedInPanel;

    /**
     * 在 Activity 的 dispatch 层识别横滑，不拦截事件（照常下发给子 View），
     * 所以列表照样能上下滚、面板照样能滚、TabLayout 照样能点。
     */
    private void installSwipeNavigation() {
        final float density = getResources().getDisplayMetrics().density;
        final int minDistance = (int) (density * 72);       // 普通页面：72dp
        final int panelDistance = (int) (density * 150);    // 网页面板：150dp，避免和网页横向拖动打架
        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent down, MotionEvent up, float velocityX, float velocityY) {
                if (down == null || up == null) {
                    return false;
                }
                float dx = up.getX() - down.getX();
                float dy = up.getY() - down.getY();
                float required = touchStartedInPanel ? panelDistance : minDistance;
                if (Math.abs(dx) < required) {
                    return false;
                }
                // 竖直分量大于横向的三分之二，说明是在滚页面，不是切 tab
                if (Math.abs(dx) < Math.abs(dy) * 1.5f) {
                    return false;
                }
                if (Math.abs(velocityX) < SWIPE_MIN_VELOCITY) {
                    return false;
                }
                if (dx < 0) {
                    goNextPage();
                } else {
                    goPreviousPage();
                }
                return true;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (swipeDetector != null) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchStartedInPanel = isInside(event.getRawX(), event.getRawY(), binding.containerPanel);
            }
            swipeDetector.onTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    /** 触点是否落在某个 View 上（统一用屏幕坐标比较，避开窗口/状态栏偏移）。 */
    private static boolean isInside(float rawX, float rawY, View view) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getWidth() == 0) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return rawX >= location[0] && rawX <= location[0] + view.getWidth()
                && rawY >= location[1] && rawY <= location[1] + view.getHeight();
    }

    private int currentTabIndex() {
        int id = binding.bottomNav.getSelectedItemId();
        for (int i = 0; i < TAB_ORDER.length; i++) {
            if (TAB_ORDER[i] == id) {
                return i;
            }
        }
        return 0;
    }

    /** 往左滑：下一个；sing-box 页里先管理 → 网页面板。 */
    private void goNextPage() {
        if (binding.bottomNav.getSelectedItemId() == R.id.tab_singbox) {
            if (binding.containerPanel.getVisibility() != View.VISIBLE) {
                selectSingboxSegment(SEGMENT_WEB);
                return;
            }
            return;                       // 已经在最后一段，不再往后
        }
        int next = currentTabIndex() + 1;
        if (next < TAB_ORDER.length) {
            binding.bottomNav.setSelectedItemId(TAB_ORDER[next]);
            slideIn(1);
        }
    }

    /** 往右滑：上一个；sing-box 页里先网页面板 → 管理。 */
    private void goPreviousPage() {
        if (binding.bottomNav.getSelectedItemId() == R.id.tab_singbox
                && binding.containerPanel.getVisibility() == View.VISIBLE) {
            selectSingboxSegment(SEGMENT_MANAGE);
            return;
        }
        int previous = currentTabIndex() - 1;
        if (previous >= 0) {
            binding.bottomNav.setSelectedItemId(TAB_ORDER[previous]);
            slideIn(-1);
        }
    }

    /** 轻微的横向滑入动画：direction 1 = 从右边进来，-1 = 从左边进来。 */
    private void slideIn(int direction) {
        View page = binding.scrollContent.getVisibility() == View.VISIBLE
                ? binding.scrollContent : binding.pageSingbox;
        float offset = getResources().getDisplayMetrics().density * 36 * direction;
        page.animate().cancel();
        page.setTranslationX(offset);
        page.setAlpha(0.35f);
        page.animate().translationX(0f).alpha(1f).setDuration(170).start();
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
        bindSwitch(binding.rowAllowThirdTheme, Keys.ALLOW_THIRD_THEME,
                R.string.allow_third_theme_title, R.string.allow_third_theme_summary);
        binding.rowAllowThirdTheme.switchView.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setBoolean(Keys.ALLOW_THIRD_THEME, checked);
            // 主题商店不在作用域里时，导入校验发生在它自己进程，光开开关没用——当场提示。
            if (checked && !App.isInScope("com.android.thememanager")) {
                Toast.makeText(this, R.string.allow_third_theme_scope_missing,
                        Toast.LENGTH_LONG).show();
            }
        });

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

    /** 状态栏只剩「时钟显秒」。 */
    private void bindStatusBarRows() {
        bindSwitch(binding.rowIosIcons, Keys.IOS_STATUSBAR,
                R.string.ios_icons_title, R.string.ios_icons_summary);
        bindSwitch(binding.rowClockSeconds, Keys.CLOCK_SECONDS,
                R.string.clock_seconds_title, R.string.clock_seconds_summary);
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
        binding.rowIosIcons.switchView.setChecked(
                Prefs.getBoolean(Keys.IOS_STATUSBAR, false));
        binding.rowClockSeconds.switchView.setChecked(
                Prefs.getBoolean(Keys.CLOCK_SECONDS, false));

        updateThemeRow();
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
