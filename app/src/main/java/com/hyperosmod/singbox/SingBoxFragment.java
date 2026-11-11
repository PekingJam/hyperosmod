package com.hyperosmod.singbox;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hyperosmod.R;
import com.hyperosmod.databinding.FragmentSingboxBinding;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * sing-box 一级页面：状态、启停、日志，加二级入口。
 *
 * <p>日志刻意放在一级页面、固定高度可滚动——启动失败时第一眼就能看到原因，
 * 不需要再点任何菜单。日志只保留尾部 200 行。</p>
 */
public final class SingBoxFragment extends Fragment {

    private FragmentSingboxBinding binding;
    /** 全局队列：视图重建后不会像实例级线程池那样被关掉。 */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private boolean busy;
    private boolean autostartListenerArmed;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSingboxBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.btnStart.setOnClickListener(v -> action("启动", SingBoxController::start));
        binding.btnStop.setOnClickListener(v -> action("停止", SingBoxController::stop));
        binding.btnRestart.setOnClickListener(v -> action("重启", SingBoxController::restart));
        binding.btnLogCopy.setOnClickListener(v -> copyLog());
        binding.btnLogClear.setOnClickListener(v -> binding.output.setText(""));

        binding.rowVersion.title.setText(R.string.singbox_entry_version);
        binding.rowVersion.summary.setText(R.string.singbox_entry_version_summary);
        binding.rowVersion.getRoot().setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SingBoxVersionActivity.class)));

        binding.rowConfig.title.setText(R.string.singbox_entry_config);
        binding.rowConfig.summary.setText(R.string.singbox_entry_config_summary);
        binding.rowConfig.getRoot().setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SingBoxConfigActivity.class)));

        binding.switchAutostart.setOnCheckedChangeListener((button, checked) -> {
            if (!autostartListenerArmed) {
                return;
            }
            applyAutostart(checked);
        });
        autostartListenerArmed = true;

        refreshStatus();
        refreshAutostart();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            refreshStatus();
            refreshAutostart();
        }
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    // ==================================================================================
    // 状态
    // ==================================================================================

    private void refreshStatus() {
        EXECUTOR.execute(() -> {
            SingBoxController.Status status = SingBoxController.status();
            ui(() -> {
                if (binding == null) {
                    return;
                }
                boolean root = status.rootGranted;
                binding.statusVersion.setText(getString(R.string.singbox_state_version,
                        status.version,
                        status.beta() ? getString(R.string.singbox_channel_beta)
                                : getString(R.string.singbox_channel_stable)));
                binding.statusRun.setText(status.running()
                        ? getString(R.string.singbox_state_running, status.pid)
                        : getString(R.string.singbox_state_stopped));
                binding.statusRun.setTextColor(status.running() ? 0xFF43A047 : 0xFF9E9E9E);
                binding.statusRoot.setText(root
                        ? R.string.singbox_state_ready : R.string.singbox_state_no_root);
                binding.statusRoot.setTextColor(root ? 0xFF3482FF : 0xFFF5A524);
                applyEnabled(root && !busy);
            });
        });
    }

    private void applyEnabled(boolean enabled) {
        if (binding == null) {
            return;
        }
        binding.btnStart.setEnabled(enabled);
        binding.btnStop.setEnabled(enabled);
        binding.btnRestart.setEnabled(enabled);
    }

    // ==================================================================================
    // 动作
    // ==================================================================================

    private interface Call {
        RootShell.Result run();
    }

    private void action(String label, Call call) {
        setBusy(true);
        append(getString(R.string.singbox_output_running, label));
        EXECUTOR.execute(() -> {
            RootShell.Result result;
            try {
                result = call.run();
            } catch (Throwable t) {
                result = new RootShell.Result(1, String.valueOf(t));
            }
            RootShell.Result finalResult = result;
            ui(() -> {
                if (binding == null) {
                    return;
                }
                if (!TextUtils.isEmpty(finalResult.output)) {
                    append(finalResult.output);
                }
                append(finalResult.ok() ? "✔ 完成" : "✘ 失败");
                setBusy(false);
                refreshStatus();
            });
        });
    }

    private void setBusy(boolean active) {
        busy = active;
        if (binding == null) {
            return;
        }
        binding.progressIndicator.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            applyEnabled(false);
        } else {
            EXECUTOR.execute(() -> {
                boolean root = SingBoxController.status().rootGranted;
                ui(() -> applyEnabled(root));
            });
        }
    }

    /** 追加日志并滚到底部，只留尾部 200 行。 */
    private void append(String text) {
        if (binding == null || TextUtils.isEmpty(text)) {
            return;
        }
        String current = binding.output.getText() == null ? ""
                : binding.output.getText().toString();
        String merged = current + text + "\n";
        String[] lines = merged.split("\n");
        if (lines.length > 200) {
            StringBuilder builder = new StringBuilder();
            for (int i = lines.length - 200; i < lines.length; i++) {
                builder.append(lines[i]).append('\n');
            }
            merged = builder.toString();
        }
        binding.output.setText(merged);
        binding.logScroll.post(() -> binding.logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copyLog() {
        if (binding == null) {
            return;
        }
        CharSequence text = binding.output.getText();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        android.content.ClipboardManager manager = (android.content.ClipboardManager)
                requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        manager.setPrimaryClip(android.content.ClipData.newPlainText("sing-box log", text));
        toast(getString(R.string.singbox_log_copied));
    }

    // ==================================================================================
    // 开机自启
    // ==================================================================================

    private void refreshAutostart() {
        EXECUTOR.execute(() -> {
            boolean installed = Autostart.installed();
            String framework = Autostart.framework();
            ui(() -> {
                if (binding == null) {
                    return;
                }
                autostartListenerArmed = false;
                binding.switchAutostart.setChecked(installed);
                autostartListenerArmed = true;
                boolean supported = !framework.isEmpty();
                binding.switchAutostart.setEnabled(supported);
                binding.autostartHint.setText(supported
                        ? getString(R.string.singbox_autostart_hint, framework,
                        Autostart.SCRIPT_PATH)
                        : getString(R.string.singbox_autostart_unsupported));
            });
        });
    }

    private void applyAutostart(boolean enable) {
        append(getString(R.string.singbox_output_running,
                enable ? "开启开机自启" : "关闭开机自启"));
        EXECUTOR.execute(() -> {
            RootShell.Result result = enable ? Autostart.install() : Autostart.uninstall();
            boolean installed = Autostart.installed();
            ui(() -> {
                if (binding == null) {
                    return;
                }
                append(TextUtils.isEmpty(result.output) ? "✔ 完成" : result.output);
                autostartListenerArmed = false;
                binding.switchAutostart.setChecked(installed);
                autostartListenerArmed = true;
            });
        });
    }

    private void toast(String message) {
        if (isAdded()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    private void ui(Runnable runnable) {
        if (isAdded()) {
            requireActivity().runOnUiThread(runnable);
        }
    }
}
