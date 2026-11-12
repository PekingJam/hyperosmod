package com.hyperosmod.singbox;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.color.MaterialColors;
import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.DialogPanelGroupBinding;
import com.hyperosmod.databinding.FragmentPanelBinding;
import com.hyperosmod.databinding.ItemPanelGroupBinding;
import com.hyperosmod.databinding.ItemPanelNodeBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 原生控制面板：直连 sing-box 的 Clash 接口，替代原来的 WebView 面板。
 *
 * <p>一个分组一张卡片，两列并排；卡片上只显示当前选中的节点。点卡片弹出底部弹窗，里面把
 * 该组全部节点按两列排开——这样组里的节点再多也不会把主页面撑长。</p>
 */
public final class PanelFragment extends Fragment {

    /** 测速目标，和多数 dashboard 的默认值一致。 */
    private static final String TEST_URL = "http://www.gstatic.com/generate_204";

    private FragmentPanelBinding binding;
    private final Adapter adapter = new Adapter();

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    private final AtomicBoolean trafficRunning = new AtomicBoolean(false);
    private ClashApi api;

    /** 节点类型（Vmess / Shadowsocks …），用于节点行第二段文字。 */
    private final Map<String, String> types = new HashMap<>();
    /** 延迟缓存，key 是 "分组\u0000节点"。 */
    private final Map<String, Integer> delays = new HashMap<>();
    private final Map<String, Boolean> testing = new HashMap<>();

    private final List<ClashApi.Group> groups = new ArrayList<>();
    private final Handler statsHandler = new Handler(Looper.getMainLooper());
    private boolean statsRefreshing;

    /** 当前打开的弹窗列表，测速/切换后需要刷新它。 */
    private NodeAdapter openSheet;
    private DialogPanelGroupBinding openSheetBinding;
    private ClashApi.Group openSheetGroup;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPanelBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.panelList.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.panelList.setAdapter(adapter);
        binding.btnRefresh.setOnClickListener(v -> load());
        binding.btnTestAll.setOnClickListener(v -> testAll());
        binding.btnConnections.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ConnectionsActivity.class)));
        load();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            load();
            statsHandler.post(statsTick);
        }
    }

    @Override
    public void onPause() {
        statsHandler.removeCallbacks(statsTick);
        super.onPause();
    }

    /** 顶部统计：累计上传/下载 + 活动连接数，3 秒取一次。 */
    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            refreshStats();
            statsHandler.postDelayed(this, 3000L);
        }
    };

    private void refreshStats() {
        if (binding == null || statsRefreshing) {
            return;
        }
        statsRefreshing = true;
        ClashApi client = api();
        EXECUTOR.execute(() -> {
            long up = 0;
            long down = 0;
            int count = 0;
            try {
                ClashApi.Connections connections = client.connections();
                up = connections.uploadTotal;
                down = connections.downloadTotal;
                count = connections.items.size();
            } catch (Throwable ignored) {
                // 控制接口不可达时保留上一帧的数字
            }
            final long finalUp = up;
            final long finalDown = down;
            final int finalCount = count;
            runOnUi(() -> {
                statsRefreshing = false;
                if (binding == null) {
                    return;
                }
                binding.panelStats.setText(getString(R.string.panel_stats,
                        formatBytes(finalUp), formatBytes(finalDown), finalCount));
            });
        });
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576f);
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824f);
    }

    @Override
    public void onDestroyView() {
        if (api != null) {
            api.stopTraffic();
        }
        trafficRunning.set(false);
        openSheet = null;
        binding = null;
        super.onDestroyView();
    }

    // ==================================================================================
    // 数据
    // ==================================================================================

    private ClashApi api() {
        if (api == null) {
            api = new ClashApi(Prefs.getString(Keys.SINGBOX_API_URL, "http://127.0.0.1:9090"),
                    Prefs.getString(Keys.SINGBOX_API_SECRET, ""));
        }
        return api;
    }

    private void load() {
        if (binding == null) {
            return;
        }
        ClashApi client = api();
        if (!client.configured()) {
            binding.panelEmpty.setVisibility(View.VISIBLE);
            binding.panelEmpty.setText(R.string.panel_not_configured);
            binding.panelList.setVisibility(View.GONE);
            return;
        }
        binding.panelProgress.setVisibility(View.VISIBLE);
        EXECUTOR.execute(() -> {
            try {
                String version = client.version();
                Map<String, String> fetched = new HashMap<>();
                List<ClashApi.Group> loaded = client.groups(fetched);
                runOnUi(() -> {
                    if (binding == null) {
                        return;
                    }
                    binding.panelProgress.setVisibility(View.GONE);
                    types.clear();
                    types.putAll(fetched);
                    binding.panelHint.setText(version.isEmpty() ? ""
                            : getString(R.string.panel_version, version));
                    render(loaded);
                    startTraffic();
                });
            } catch (Throwable t) {
                runOnUi(() -> {
                    if (binding == null) {
                        return;
                    }
                    binding.panelProgress.setVisibility(View.GONE);
                    binding.panelEmpty.setVisibility(View.VISIBLE);
                    binding.panelEmpty.setText(getString(R.string.panel_unreachable, t.getMessage()));
                    binding.panelList.setVisibility(View.GONE);
                });
            }
        });
    }

    private void render(List<ClashApi.Group> loaded) {
        String currentNode = "";
        String currentGroup = "";
        for (ClashApi.Group group : loaded) {
            // GLOBAL 只在全局模式下才有意义，顶部不显示它。
            if ("GLOBAL".equalsIgnoreCase(group.name) || group.now.isEmpty()) {
                continue;
            }
            currentNode = group.now;
            currentGroup = group.name;
            break;
        }
        binding.panelNode.setText(currentNode.isEmpty()
                ? getString(R.string.panel_current_none)
                : getString(R.string.panel_current, currentNode, currentGroup));

        groups.clear();
        groups.addAll(sortGroups(loaded));
        adapter.submit(groups);
        binding.panelEmpty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
        binding.panelList.setVisibility(groups.isEmpty() ? View.GONE : View.VISIBLE);
        if (groups.isEmpty()) {
            binding.panelEmpty.setText(R.string.panel_no_groups);
        }
        if (openSheet != null) {
            openSheet.notifyDataSetChanged();
        }
    }

    /** GLOBAL 放最后：默认规则模式下用不到它。 */
    private static List<ClashApi.Group> sortGroups(List<ClashApi.Group> loaded) {
        List<ClashApi.Group> sorted = new ArrayList<>(loaded);
        sorted.sort((left, right) -> Boolean.compare(isGlobal(left), isGlobal(right)));
        return sorted;
    }

    private static boolean isGlobal(ClashApi.Group group) {
        return "GLOBAL".equalsIgnoreCase(group.name);
    }

    private void startTraffic() {
        if (!trafficRunning.compareAndSet(false, true)) {
            return;
        }
        ClashApi client = api();
        new Thread(() -> client.streamTraffic((up, down) -> runOnUi(() -> {
            if (binding == null) {
                return;
            }
            binding.panelTraffic.setText(getString(R.string.panel_traffic,
                    formatRate(up), formatRate(down)));
        })), "panel-traffic").start();
    }

    // ==================================================================================
    // 弹窗
    // ==================================================================================

    private void showGroupSheet(ClashApi.Group group) {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        DialogPanelGroupBinding sheet = DialogPanelGroupBinding.inflate(getLayoutInflater());
        sheet.sheetTitle.setText(group.name);
        sheet.sheetCount.setText(countLabel(group));
        openSheetBinding = sheet;
        openSheetGroup = group;
        sheet.sheetTest.setOnClickListener(v -> testGroup(group));

        NodeAdapter nodeAdapter = new NodeAdapter(group, dialog);
        openSheet = nodeAdapter;
        sheet.sheetList.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        sheet.sheetList.setAdapter(nodeAdapter);
        dialog.setOnDismissListener(d -> {
            openSheet = null;
            openSheetBinding = null;
            openSheetGroup = null;
        });
        dialog.setContentView(sheet.getRoot());
        dialog.show();
    }

    // ==================================================================================
    // 操作
    // ==================================================================================

    private void select(ClashApi.Group group, String node) {
        binding.panelProgress.setVisibility(View.VISIBLE);
        EXECUTOR.execute(() -> {
            try {
                api().select(group.name, node);
            } catch (Throwable t) {
                runOnUi(() -> toast(getString(R.string.panel_select_failed, t.getMessage())));
            }
            runOnUi(this::load);
        });
    }

    private void testGroup(ClashApi.Group group) {
        for (String node : group.all) {
            startDelay(group.name, node);
        }
    }

    private void testAll() {
        for (ClashApi.Group group : groups) {
            testGroup(group);
        }
    }

    private void startDelay(String group, String node) {
        String key = key(group, node);
        if (Boolean.TRUE.equals(testing.get(key))) {
            return;
        }
        testing.put(key, true);
        adapter.notifyDataSetChanged();
        EXECUTOR.execute(() -> {
            int delay = api().delay(node, TEST_URL, 5000);
            delays.put(key, delay);
            testing.remove(key);
            runOnUi(() -> {
                if (binding == null) {
                    return;
                }
                adapter.notifyDataSetChanged();
                if (openSheet != null) {
                    openSheet.notifyDataSetChanged();
                }
                if (openSheetBinding != null && openSheetGroup != null) {
                    openSheetBinding.sheetCount.setText(countLabel(openSheetGroup));
                }
            });
        });
    }

    // ==================================================================================
    // 工具
    // ==================================================================================

    private static String key(String group, String node) {
        return group + "\u0000" + node;
    }

    private static String formatRate(long bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return bytesPerSecond + " B/s";
        }
        if (bytesPerSecond < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024f);
        }
        return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1048576f);
    }

    private void runOnUi(Runnable runnable) {
        if (isAdded()) {
            requireActivity().runOnUiThread(runnable);
        }
    }

    private void toast(String message) {
        if (isAdded()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 分组计数：测过速就显示「可连通/总数」，没测过只显示总数。
     *
     * <p>「可连通」用延迟缓存里大于 0 的条目算——Clash 的 delay 接口失败时返回 0 或超时，
     * 所以大于 0 就代表这次握手通了。</p>
     */
    private String countLabel(ClashApi.Group group) {
        int total = group.all.size();
        int reachable = 0;
        boolean tested = false;
        for (String node : group.all) {
            Integer delay = delays.get(key(group.name, node));
            if (delay == null) {
                continue;
            }
            tested = true;
            if (delay > 0) {
                reachable++;
            }
        }
        return tested ? getString(R.string.panel_group_count_pair, reachable, total)
                : getString(R.string.panel_group_count, total);
    }

    /** 节点行统一绑定：名字、类型与延迟、选中态高亮。 */
    private void bindNode(ItemPanelNodeBinding row, ClashApi.Group group, String node,
                          View.OnClickListener onClick) {
        boolean selected = node.equals(group.now);
        String key = key(group.name, node);

        row.nodeName.setText(node);
        int cardColor = MaterialColors.getColor(row.nodeCard,
                selected ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorSurfaceContainerHighest);
        row.nodeCard.setCardBackgroundColor(cardColor);
        int textColor = MaterialColors.getColor(row.nodeName,
                selected ? com.google.android.material.R.attr.colorOnPrimary
                        : com.google.android.material.R.attr.colorOnSurface);
        row.nodeName.setTextColor(textColor);
        row.nodeName.setTypeface(null, Typeface.NORMAL);

        String type = types.get(node);
        String typeText = type == null || type.isEmpty() ? "" : type.toUpperCase(Locale.ROOT);
        if (Boolean.TRUE.equals(testing.get(key))) {
            row.nodeDelay.setText(typeText.isEmpty() ? getString(R.string.panel_testing)
                    : typeText + " · " + getString(R.string.panel_testing));
            row.nodeDelay.setTextColor(textColor);
        } else if (delays.containsKey(key)) {
            int delay = delays.get(key);
            String delayText = delay == 0 ? getString(R.string.panel_timeout) : delay + " ms";
            row.nodeDelay.setText(typeText.isEmpty() ? delayText : typeText + " · " + delayText);
            row.nodeDelay.setTextColor(selected ? textColor
                    : (delay == 0 ? 0xFFE53935 : (delay <= 200 ? 0xFF43A047
                    : (delay <= 500 ? 0xFFF5A524 : 0xFFE53935))));
        } else {
            row.nodeDelay.setText(typeText);
            row.nodeDelay.setTextColor(MaterialColors.getColor(row.nodeDelay,
                    selected ? com.google.android.material.R.attr.colorOnPrimary
                            : com.google.android.material.R.attr.colorOnSurfaceVariant));
        }
        row.nodeCard.setOnClickListener(onClick);
    }

    // ==================================================================================
    // 主列表：一张卡片 = 一个分组（只显示当前节点）
    // ==================================================================================

    private final class Adapter extends RecyclerView.Adapter<GroupHolder> {

        private final List<ClashApi.Group> items = new ArrayList<>();

        void submit(List<ClashApi.Group> next) {
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public GroupHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new GroupHolder(ItemPanelGroupBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull GroupHolder holder, int position) {
            ClashApi.Group group = items.get(position);
            ItemPanelGroupBinding card = holder.binding;
            card.groupName.setText(group.name);
            card.groupCount.setText(countLabel(group));
            card.groupChevron.setText("▸");
            card.groupTest.setOnClickListener(v -> testGroup(group));
            card.groupHeader.setOnClickListener(v -> showGroupSheet(group));
            card.getRoot().setOnClickListener(v -> showGroupSheet(group));

            card.nodeContainer.removeAllViews();
            if (group.now.isEmpty()) {
                return;
            }
            ItemPanelNodeBinding row = ItemPanelNodeBinding.inflate(
                    LayoutInflater.from(card.nodeContainer.getContext()),
                    card.nodeContainer, false);
            bindNode(row, group, group.now, v -> showGroupSheet(group));
            card.nodeContainer.addView(row.getRoot());
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class GroupHolder extends RecyclerView.ViewHolder {
        final ItemPanelGroupBinding binding;

        GroupHolder(ItemPanelGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    // ==================================================================================
    // 弹窗列表：两列节点
    // ==================================================================================

    private final class NodeAdapter extends RecyclerView.Adapter<NodeHolder> {

        private final ClashApi.Group group;
        private final BottomSheetDialog dialog;

        NodeAdapter(ClashApi.Group group, BottomSheetDialog dialog) {
            this.group = group;
            this.dialog = dialog;
        }

        @NonNull
        @Override
        public NodeHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new NodeHolder(ItemPanelNodeBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull NodeHolder holder, int position) {
            String node = group.all.get(position);
            bindNode(holder.binding, group, node, v -> {
                select(group, node);
                dialog.dismiss();
            });
        }

        @Override
        public int getItemCount() {
            return group.all.size();
        }
    }

    private static final class NodeHolder extends RecyclerView.ViewHolder {
        final ItemPanelNodeBinding binding;

        NodeHolder(ItemPanelNodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
