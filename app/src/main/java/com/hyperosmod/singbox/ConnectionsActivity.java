package com.hyperosmod.singbox;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.ActivityConnectionsBinding;
import com.hyperosmod.databinding.ItemConnectionBinding;
import com.hyperosmod.ui.UiUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 活动连接列表。
 *
 * <p>Clash 接口给的是每条连接的累计字节数，没有速度字段，所以每秒采样一次、和上一次的
 * 快照相减得到瞬时下载速度。排序有两种：按当前下载速度、按累计已下载流量。</p>
 */
public final class ConnectionsActivity extends AppCompatActivity {

    private ActivityConnectionsBinding binding;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Adapter adapter = new Adapter();

    /** 上一次采样的累计下载量，用来算速度。 */
    private final Map<String, Long> lastDownload = new HashMap<>();
    private final Map<String, Long> speeds = new HashMap<>();
    private long lastSampleAt;
    private boolean refreshing;
    private boolean sortBySpeed = true;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityConnectionsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.connList.setLayoutManager(new LinearLayoutManager(this));
        binding.connList.setAdapter(adapter);

        binding.sortSpeed.setOnClickListener(v -> {
            sortBySpeed = true;
            applySortStyle();
            adapter.resort();
        });
        binding.sortDownload.setOnClickListener(v -> {
            sortBySpeed = false;
            applySortStyle();
            adapter.resort();
        });
        applySortStyle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiUtils.applySystemBars(this);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void applySortStyle() {
        int active = com.google.android.material.color.MaterialColors.getColor(binding.sortSpeed,
                com.google.android.material.R.attr.colorSecondaryContainer);
        int idle = android.graphics.Color.TRANSPARENT;
        binding.sortSpeed.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(sortBySpeed ? active : idle));
        binding.sortDownload.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(sortBySpeed ? idle : active));
    }

    private void refresh() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        String base = Prefs.getString(Keys.SINGBOX_API_URL, "http://127.0.0.1:9090");
        String secret = Prefs.getString(Keys.SINGBOX_API_SECRET, "");
        EXECUTOR.execute(() -> {
            ClashApi api = new ClashApi(base, secret);
            ClashApi.Connections snapshot = null;
            String error = null;
            try {
                snapshot = api.connections();
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final ClashApi.Connections data = snapshot;
            final String failure = error;
            runOnUiThread(() -> {
                refreshing = false;
                if (isFinishing()) {
                    return;
                }
                if (data == null) {
                    binding.connEmpty.setVisibility(View.VISIBLE);
                    binding.connEmpty.setText(getString(R.string.panel_unreachable,
                            failure == null ? "" : failure));
                    binding.connList.setVisibility(View.GONE);
                    return;
                }
                computeSpeeds(data);
                binding.connEmpty.setVisibility(data.items.isEmpty()
                        ? View.VISIBLE : View.GONE);
                binding.connList.setVisibility(data.items.isEmpty() ? View.GONE : View.VISIBLE);
                binding.connSummary.setText(getString(R.string.conn_summary,
                        formatBytes(data.downloadTotal), formatBytes(data.uploadTotal),
                        data.items.size()));
                adapter.submit(data.items);
            });
        });
    }

    /** 用两次采样的差值算每条连接的下载速度。 */
    private void computeSpeeds(ClashApi.Connections snapshot) {
        long now = System.currentTimeMillis();
        double seconds = lastSampleAt == 0 ? 1.0 : Math.max(0.2, (now - lastSampleAt) / 1000.0);
        Map<String, Long> next = new HashMap<>();
        Map<String, Long> nextSpeeds = new HashMap<>();
        for (ClashApi.Connection connection : snapshot.items) {
            next.put(connection.id, connection.download);
            Long previous = lastDownload.get(connection.id);
            long delta = previous == null ? 0 : Math.max(0, connection.download - previous);
            nextSpeeds.put(connection.id, Math.round(delta / seconds));
        }
        lastDownload.clear();
        lastDownload.putAll(next);
        speeds.clear();
        speeds.putAll(nextSpeeds);
        lastSampleAt = now;
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

    private static String formatSpeed(long bytesPerSecond) {
        return formatBytes(bytesPerSecond) + "/s";
    }

    // ==================================================================================

    private final class Adapter extends RecyclerView.Adapter<Holder> {

        private final List<ClashApi.Connection> items = new ArrayList<>();

        void submit(List<ClashApi.Connection> next) {
            items.clear();
            items.addAll(next);
            resort();
        }

        /** 排序在本地做：切换排序条件时不必重新拉数据。 */
        void resort() {
            items.sort((a, b) -> {
                if (sortBySpeed) {
                    long sa = speeds.getOrDefault(a.id, 0L);
                    long sb = speeds.getOrDefault(b.id, 0L);
                    if (sa != sb) {
                        return Long.compare(sb, sa);
                    }
                }
                return Long.compare(b.download, a.download);
            });
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemConnectionBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ClashApi.Connection connection = items.get(position);
            holder.binding.connHost.setText(connection.host);
            String chain = connection.chains.isEmpty() ? connection.type
                    : connection.chains;
            holder.binding.connChain.setText(chain);

            long speed = speeds.getOrDefault(connection.id, 0L);
            String text = getString(R.string.conn_row_traffic,
                    formatSpeed(speed), formatBytes(connection.download),
                    formatBytes(connection.upload));
            holder.binding.connTraffic.setText(text);
            // 速度越快越绿，静默连接压灰，方便一眼扫出在跑的流量。
            int color = speed > 512 * 1024 ? 0xFFE53935
                    : (speed > 32 * 1024 ? 0xFF43A047 : 0xFF9E9E9E);
            holder.binding.connTraffic.setTextColor(color);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ItemConnectionBinding binding;

        Holder(ItemConnectionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

}
