package com.hyperosmod.singbox;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.FragmentSingboxBinding;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * sing-box 管理面板（主界面「sing-box」页与独立页面共用的实现）。
 *
 * <p>所有耗时动作都在单线程池里顺序执行，结果与进度写到页面底部的输出区。按钮在任务期间
 * 统一禁用，避免并发操作把二进制替换到一半。</p>
 */
public final class SingBoxFragment extends Fragment {

    private FragmentSingboxBinding binding;

    /**
     * 整个应用共用一个执行队列：原来是每个 Fragment 实例一个，视图重建后旧实例被
     * shutdown，后续所有 root 调用直接抛 RejectedExecutionException，界面就会永远停在
     * 「忙」的状态（按钮全灰、进度条一直转）。
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 是否有任务在跑；状态刷新时据此决定按钮是否可用。 */
    private boolean busy;

    /** 回填开关状态时不要触发监听，否则会把「读状态」当成「用户点击」。 */
    private boolean suppressAutostartListener;

    private static final int COLOR_RUNNING = Color.parseColor("#43A047");
    private static final int COLOR_STOPPED = Color.parseColor("#9E9E9E");
    private static final int COLOR_NO_ROOT = Color.parseColor("#E53935");

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
        binding.btnUpdate.setOnClickListener(v -> updateToChannel(false));
        binding.btnSwitch.setOnClickListener(v -> updateToChannel(true));
        binding.btnRollback.setOnClickListener(v -> showReleaseList());
        binding.btnConfig.setOnClickListener(v -> applyConfig());

        binding.configUrl.setText(Prefs.getString(Keys.SINGBOX_CONFIG_URL, ""));
        binding.webUrl.setText(Prefs.getString(Keys.SINGBOX_WEB_URL, ""));
        binding.switchTileWeb.setChecked(Prefs.getBoolean(Keys.SINGBOX_TILE_WEB, true));
        binding.switchTileWeb.setOnCheckedChangeListener((button, checked) ->
                Prefs.setBoolean(Keys.SINGBOX_TILE_WEB, checked));
        binding.btnOpenWeb.setOnClickListener(v -> {
            String url = text(binding.webUrl);
            if (TextUtils.isEmpty(url)) {
                toast(getString(R.string.singbox_web_no_url));
                return;
            }
            Prefs.setString(Keys.SINGBOX_WEB_URL, url);
            startActivity(new Intent(requireContext(), SingBoxWebActivity.class));
        });

        binding.switchAutostart.setOnCheckedChangeListener((button, checked) -> {
            if (suppressAutostartListener) {
                return;
            }
            applyAutostart(checked);
        });

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

    private String text(EditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString().trim();
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
                // 运行中用绿色，停止用灰色，没 root 用红色，一眼能看出当前态。
                binding.statusRun.setTextColor(status.running()
                        ? COLOR_RUNNING : COLOR_STOPPED);
                binding.statusRoot.setText(root
                        ? R.string.singbox_state_ready
                        : R.string.singbox_state_no_root);
                binding.statusRoot.setTextColor(root ? COLOR_STOPPED : COLOR_NO_ROOT);
                applyEnabled(root && !busy);
            });
        });
    }

    /** 读一下自启脚本是否存在，以及 root 框架是否支持 service.d。 */
    private void refreshAutostart() {
        EXECUTOR.execute(() -> {
            boolean installed = Autostart.installed();
            String framework = Autostart.framework();
            ui(() -> {
                if (binding == null) {
                    return;
                }
                suppressAutostartListener = true;
                binding.switchAutostart.setChecked(installed);
                suppressAutostartListener = false;
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
            boolean stillInstalled = Autostart.installed();
            ui(() -> {
                if (binding == null) {
                    return;
                }
                append(result.ok() ? result.output
                        : getString(R.string.singbox_output_done, "",
                        String.valueOf(result.code)) + "\n" + result.output);
                suppressAutostartListener = true;
                binding.switchAutostart.setChecked(stillInstalled);
                suppressAutostartListener = false;
                if (!result.ok() && isAdded()) {
                    toast(result.output);
                }
            });
        });
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
                append(getString(R.string.singbox_output_done, label,
                        finalResult.ok() ? getString(R.string.singbox_output_ok)
                                : String.valueOf(finalResult.code)));
                if (!TextUtils.isEmpty(finalResult.output)) {
                    append(finalResult.output);
                }
                setBusy(false);
                refreshStatus();
            });
        });
    }

    private void updateToChannel(boolean switchChannel) {
        setBusy(true);
        append(getString(R.string.singbox_output_running,
                switchChannel ? "切换通道" : "更新"));
        EXECUTOR.execute(() -> {
            SingBoxController.Status status = SingBoxController.status();
            boolean targetBeta = switchChannel ? !status.beta() : status.beta();
            SingBoxController.Release release = SingBoxController.latestForChannel(targetBeta);
            if (release == null) {
                finishAction(getString(R.string.singbox_no_release),
                        getString(R.string.singbox_no_release));
                return;
            }
            RootShell.Result result = SingBoxController.install(requireContext(), release,
                    step -> ui(() -> append(step)));
            finishAction(result.output,
                    result.ok() ? getString(R.string.singbox_output_ok)
                            : String.valueOf(result.code));
        });
    }

    private void showReleaseList() {
        setBusy(true);
        EXECUTOR.execute(() -> {
            List<SingBoxController.Release> releases = SingBoxController.releases();
            ui(() -> {
                setBusy(false);
                if (binding == null || !isAdded()) {
                    return;
                }
                if (releases.isEmpty()) {
                    toast(getString(R.string.singbox_no_release));
                    return;
                }
                String[] items = new String[releases.size()];
                for (int i = 0; i < releases.size(); i++) {
                    items[i] = releases.get(i).display();
                }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.singbox_rollback)
                        .setItems(items, (dialog, which) -> install(releases.get(which)))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        });
    }

    private void install(SingBoxController.Release release) {
        setBusy(true);
        append(getString(R.string.singbox_output_running, "安装 " + release.tag));
        EXECUTOR.execute(() -> {
            RootShell.Result result = SingBoxController.install(requireContext(), release,
                    step -> ui(() -> append(step)));
            finishAction(result.output,
                    result.ok() ? getString(R.string.singbox_output_ok)
                            : String.valueOf(result.code));
        });
    }

    private void applyConfig() {
        String url = text(binding.configUrl);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(getString(R.string.singbox_config_bad_url));
            return;
        }
        Prefs.setString(Keys.SINGBOX_CONFIG_URL, url);
        setBusy(true);
        append(getString(R.string.singbox_output_running, "更新配置"));
        EXECUTOR.execute(() -> {
            RootShell.Result result = SingBoxController.updateConfig(requireContext(), url,
                    step -> ui(() -> append(step)));
            finishAction(result.output,
                    result.ok() ? getString(R.string.singbox_output_ok)
                            : String.valueOf(result.code));
        });
    }

    private void finishAction(String output, String statusText) {
        ui(() -> {
            if (binding == null) {
                return;
            }
            if (!TextUtils.isEmpty(output)) {
                append(output);
            }
            append(statusText);
            setBusy(false);
            refreshStatus();
        });
    }

    // ==================================================================================
    // 界面杂项
    // ==================================================================================

    private void setBusy(boolean active) {
        busy = active;
        if (binding == null) {
            return;
        }
        binding.progressIndicator.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            applyEnabled(false);
        } else {
            // 结束忙碌时要按真实的 root 状态回填，不能一律置为可用。
            EXECUTOR.execute(() -> {
                boolean root = SingBoxController.status().rootGranted;
                ui(() -> applyEnabled(root));
            });
        }
    }

    private void applyEnabled(boolean enabled) {
        if (binding == null) {
            return;
        }
        binding.btnStart.setEnabled(enabled);
        binding.btnStop.setEnabled(enabled);
        binding.btnRestart.setEnabled(enabled);
        binding.btnUpdate.setEnabled(enabled);
        binding.btnSwitch.setEnabled(enabled);
        binding.btnRollback.setEnabled(enabled);
        binding.btnConfig.setEnabled(enabled);
    }

    private void append(String text) {
        if (binding == null || TextUtils.isEmpty(text)) {
            return;
        }
        CharSequence current = binding.output.getText();
        String merged = (current == null ? "" : current.toString()) + text + "\n";
        String[] lines = merged.split("\n");
        if (lines.length > 200) {
            StringBuilder builder = new StringBuilder();
            for (int i = lines.length - 200; i < lines.length; i++) {
                builder.append(lines[i]).append('\n');
            }
            merged = builder.toString();
        }
        binding.output.setText(merged);
    }

    private void toast(String message) {
        if (isAdded()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    /** 一律投递到主线程；用全局 Handler，Fragment 暂时不在前台也不会丢更新。 */
    private void ui(Runnable runnable) {
        MAIN.post(runnable);
    }
}
