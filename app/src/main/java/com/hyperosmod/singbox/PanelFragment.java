package com.hyperosmod.singbox;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
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
    private ClashApi api;

    /** 切到面板页时自动跑一次全量测速；间隔太近就不重复跑，免得费电。 */
    private static final long AUTO_TEST_INTERVAL_MS = 60_000L;
    private long lastAutoTestAt;
    private boolean autoTestPending;

    /** 节点类型（Vmess / Shadowsocks …），用于节点行第二段文字。 */
    private final Map<String, String> types = new HashMap<>();
    /** 延迟缓存，key 是 "分组\u0000节点"。 */
    private final Map<String, Integer> delays = new HashMap<>();
    private final Map<String, Boolean> testing = new HashMap<>();

    private final List<ClashApi.Group> groups = new ArrayList<>();

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
        binding.sessionCard.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ConnectionsActivity.class)));
        load();
        if (autoTestPending) {
            autoTestIfDue();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            load();
        }
    }

    /** 主界面切到本 tab 时调用：按需自动跑一次全量测速。 */
    public void onTabVisible() {
        if (binding == null) {
            autoTestPending = true;
            return;
        }
        autoTestIfDue();
    }

    private void autoTestIfDue() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastAutoTestAt < AUTO_TEST_INTERVAL_MS) {
            return;
        }
        if (groups.isEmpty()) {
            // 分组还没拉回来，等 render() 之后再测。
            autoTestPending = true;
            return;
        }
        autoTestPending = false;
        lastAutoTestAt = now;
        testAll();
    }

    @Override
    public void onDestroyView() {
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
            setBackendStatus(R.string.panel_backend_unconfigured, null, R.color.accent_amber);
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
                // 连接快照只在刷新时取一次，不轮询、不开长连接。
                long upload = 0;
                long download = 0;
                int connCount = 0;
                boolean hasConnections = false;
                try {
                    ClashApi.Connections connections = client.connections();
                    upload = connections.uploadTotal;
                    download = connections.downloadTotal;
                    connCount = connections.items.size();
                    hasConnections = true;
                } catch (Throwable ignored) {
                    // 连接接口单独失败不影响分组列表。
                }
                final long finalUpload = upload;
                final long finalDownload = download;
                final int finalConnCount = connCount;
                final boolean finalHasConnections = hasConnections;
                runOnUi(() -> {
                    if (binding == null) {
                        return;
                    }
                    binding.panelProgress.setVisibility(View.GONE);
                    types.clear();
                    types.putAll(fetched);
                    String versionText = normalizeVersion(version);
                    setBackendStatus(versionText.isEmpty()
                                    ? R.string.panel_backend_connected : R.string.panel_backend_ok,
                            versionText.isEmpty() ? null : versionText,
                            R.color.accent_green);
                    render(loaded);
                    updateSessionCard(finalUpload, finalDownload,
                            finalConnCount, finalHasConnections);
                });
            } catch (Throwable t) {
                runOnUi(() -> {
                    if (binding == null) {
                        return;
                    }
                    binding.panelProgress.setVisibility(View.GONE);
                    setBackendStatus(R.string.panel_backend_failed,
                            String.valueOf(t.getMessage()), R.color.accent_red);
                    binding.panelEmpty.setVisibility(View.VISIBLE);
                    binding.panelEmpty.setText(getString(R.string.panel_unreachable, t.getMessage()));
                    binding.panelList.setVisibility(View.GONE);
                });
            }
        });
    }

    /**
     * 控制接口返回的 version 有的带「sing-box 」前缀、有的只有号码，
     * 统一成一处前缀，避免出现「sing-box sing-box 1.x」。
     */
    private static String normalizeVersion(String version) {
        if (version == null) {
            return "";
        }
        String text = version.trim();
        if (text.isEmpty()) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).startsWith("sing-box") ? text : "sing-box " + text;
    }

    /** 会话概览卡第二行：活跃连接数与累计流量（一次快照，不轮询）。 */
    private void updateSessionCard(long upload, long download, int connections,
                                   boolean hasConnections) {
        if (binding == null) {
            return;
        }
        if (!hasConnections) {
            binding.sessionMeta.setText(R.string.panel_session_waiting);
            return;
        }
        binding.sessionMeta.setText(getString(R.string.panel_session_meta,
                connections, formatBytes(upload), formatBytes(download)));
    }

    /** 延迟三档配色：≤200 绿 / ≤500 琥珀 / 其余（含超时）红。 */
    private static int latencyColor(int milliseconds) {
        return milliseconds <= 0 ? 0xFFE53935
                : (milliseconds <= 200 ? 0xFF43A047
                : (milliseconds <= 500 ? 0xFFF5A524 : 0xFFE53935));
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

    /** 顶部一行：控制接口是否连上、后端版本号。 */
    private void setBackendStatus(int textRes, String formatArg, int colorRes) {
        if (binding == null || !isAdded()) {
            return;
        }
        binding.panelState.setText(formatArg == null
                ? getString(textRes) : getString(textRes, formatArg));
        binding.panelDot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), colorRes)));
    }

    private void render(List<ClashApi.Group> loaded) {
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
        if (autoTestPending) {
            autoTestIfDue();
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

    /** 节点行统一绑定：名字、类型与延迟（主列表再加上计数）、选中态高亮。 */
    private void bindNode(ItemPanelNodeBinding row, ClashApi.Group group, String node,
                          String suffix, View.OnClickListener onClick) {
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

        String detail = "";
        int delayColor = 0;
        if (Boolean.TRUE.equals(testing.get(key))) {
            detail = getString(R.string.panel_testing);
        } else if (delays.containsKey(key)) {
            int delay = delays.get(key);
            detail = delay == 0 ? getString(R.string.panel_timeout) : delay + " ms";
            // 只有延迟这个数字上色，类型与计数跟着卡片文字色走。
            delayColor = latencyColor(delay);
        }

        int baseColor = selected ? textColor : MaterialColors.getColor(row.nodeDelay,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        row.nodeDelay.setTextColor(baseColor);
        row.nodeDelay.setText(joinParts(typeText, detail, suffix, delayColor));
        row.nodeCard.setOnClickListener(onClick);
    }

    /**
     * 用「 · 」拼非空片段，并只给延迟片段染色。
     *
     * <p>弹窗里是「TROJAN · 217 ms」，主列表是「TROJAN · 217 ms · 2/4」；类型和计数保持
     * 卡片的文字色（选中时就是白的），只有延迟按快慢上色。</p>
     */
    private static CharSequence joinParts(String type, String detail, String suffix,
                                          int detailColor) {
        String[] parts = {type, detail, suffix};
        SpannableStringBuilder builder = new SpannableStringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" · ");
            }
            int start = builder.length();
            builder.append(part);
            if (i == 1 && detailColor != 0) {
                builder.setSpan(new ForegroundColorSpan(detailColor), start, builder.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return builder;
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
            bindNode(row, group, group.now, countLabel(group), v -> showGroupSheet(group));
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
            bindNode(holder.binding, group, node, null, v -> {
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
