package com.hyperosmod.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hyperosmod.databinding.ItemAppBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** RecyclerView adapter for the update-protection picker. */
public final class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.Holder> {
    public static final int FILTER_ALL = 0;
    public static final int FILTER_USER = 1;
    public static final int FILTER_SYSTEM = 2;

    public interface OnSelectionChanged {
        void onSelectionChanged(Set<String> selected);
    }

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> visible = new ArrayList<>();
    private final Set<String> selected;
    private final OnSelectionChanged listener;

    private String query = "";
    private int filter = FILTER_ALL;

    public AppListAdapter(Set<String> initiallySelected, OnSelectionChanged listener) {
        this.selected = new HashSet<>(initiallySelected);
        this.listener = listener;
    }

    public void submit(List<AppEntry> entries) {
        all.clear();
        all.addAll(entries);
        applyFilter();
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    public void setFilter(int filter) {
        this.filter = filter;
        applyFilter();
    }

    public int visibleCount() {
        return visible.size();
    }

    public int selectedCount() {
        return selected.size();
    }

    public void clearSelection() {
        selected.clear();
        listener.onSelectionChanged(new HashSet<>());
        notifyDataSetChanged();
    }

    /**
     * 用新集合替换当前勾选（用于清理已卸载应用的残留包名）。
     *
     * <p>只更新本地状态，不回调 {@link SelectionListener}：调用方已经负责把结果写回
     * 偏好设置，再回调一次会形成「写—读—写」的回环。</p>
     */
    public void syncSelection(Set<String> packages) {
        selected.clear();
        if (packages != null) {
            selected.addAll(packages);
        }
        notifyDataSetChanged();
    }

    private void applyFilter() {
        visible.clear();
        for (AppEntry entry : all) {
            if (filter == FILTER_USER && entry.systemApp) {
                continue;
            }
            if (filter == FILTER_SYSTEM && !entry.systemApp) {
                continue;
            }
            if (!TextUtils.isEmpty(query)) {
                String label = entry.label.toLowerCase(Locale.ROOT);
                String pkg = entry.packageName.toLowerCase(Locale.ROOT);
                if (!label.contains(query) && !pkg.contains(query)) {
                    continue;
                }
            }
            visible.add(entry);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAppBinding binding = ItemAppBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        AppEntry entry = visible.get(position);
        ItemAppBinding binding = holder.binding;

        binding.icon.setImageDrawable(entry.icon);
        binding.label.setText(entry.label);
        binding.packageName.setText(entry.packageName);
        binding.version.setText(entry.versionName == null || entry.versionName.isEmpty()
                ? "v" + entry.versionCode
                : entry.versionName + " (" + entry.versionCode + ")");
        binding.systemBadge.setVisibility(entry.systemApp ? android.view.View.VISIBLE : android.view.View.GONE);

        boolean checked = selected.contains(entry.packageName);
        binding.checkbox.setOnCheckedChangeListener(null);
        binding.checkbox.setChecked(checked);
        binding.checkbox.setOnCheckedChangeListener((button, isChecked) -> {
            if (isChecked) {
                selected.add(entry.packageName);
            } else {
                selected.remove(entry.packageName);
            }
            listener.onSelectionChanged(new HashSet<>(selected));
        });

        binding.getRoot().setOnClickListener(v -> binding.checkbox.toggle());
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemAppBinding binding;

        Holder(ItemAppBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

