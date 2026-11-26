package com.hyperosmod.singbox;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hyperosmod.R;
import com.hyperosmod.databinding.FragmentPanelBinding;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 面板：用 WebView 渲染本地面板页面（默认 http://127.0.0.1:32590）。
 *
 * <p>底部一排是 sing-box 的服务控制：启动 / 停止 / 重启 / 更新 / 版本。原来这些按钮分散在
 * 「sing-box」页与版本管理的二级页面里，现在集中到面板上，面板就是一个页面加一片按钮。</p>
 */
public final class PanelFragment extends Fragment {

    /** 本地面板地址；改端口只改这里。 */
    private static final String PANEL_URL = "http://127.0.0.1:32590/ui";

    /** 切回面板时刷新页面的最小间隔，避免来回切 tab 时反复重载。 */
    private static final long RELOAD_MIN_INTERVAL_MS = 3_000L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private FragmentPanelBinding binding;
    private WebView webView;

    private boolean busy;
    private long lastLoadAt;

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

        webView = binding.panelWeb;
        setupWeb();

        binding.btnStart.setOnClickListener(v -> runService("启动", SingBoxController::start));
        binding.btnStop.setOnClickListener(v -> runService("停止", SingBoxController::stop));
        binding.btnRestart.setOnClickListener(v -> runService("重启", SingBoxController::restart));
        binding.btnUpdate.setOnClickListener(v -> confirmUpdate());
        binding.btnVersion.setOnClickListener(v -> startActivity(
                new Intent(requireContext(), SingBoxVersionActivity.class)));

        load();
        refreshRootState();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding == null) {
            return;
        }
        reloadIfStale();
        refreshRootState();
    }

    /** 主界面切到本 tab 时调用。 */
    public void onTabVisible() {
        reloadIfStale();
        refreshRootState();
    }

    @Override
    public void onDestroyView() {
        if (binding != null) {
            binding.getRoot().removeAllViews();
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        binding = null;
        super.onDestroyView();
    }

    // ==================================================================================
    // 网页
    // ==================================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (binding != null && !busy) {
                    binding.panelProgress.setVisibility(View.VISIBLE);
                    binding.panelError.setVisibility(View.GONE);
                    view.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (binding != null) {
                    binding.panelProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // 只有主文档失败才提示，子资源（图标、接口）失败交给页面自己处理。
                if (binding == null || request == null || !request.isForMainFrame()) {
                    return;
                }
                binding.panelProgress.setVisibility(View.GONE);
                // WebView 自带的错误页信息量太少（只说 ERR_CONNECTION_REFUSED），
                // 压掉它，换成带地址与排查提示的文案。
                view.setVisibility(View.GONE);
                binding.panelError.setText(getString(R.string.panel_web_error, PANEL_URL));
                binding.panelError.setVisibility(View.VISIBLE);
            }
        });
    }

    private void load() {
        if (webView == null || binding == null) {
            return;
        }
        lastLoadAt = SystemClock.uptimeMillis();
        binding.panelProgress.setVisibility(View.VISIBLE);
        webView.loadUrl(PANEL_URL);
    }

    /** 页面已经开着就重载一次；太频繁就跳过（切 tab 时会连着触发 onResume / onTabVisible）。 */
    private void reloadIfStale() {
        if (webView == null || binding == null) {
            return;
        }
        if (webView.getUrl() == null) {
            load();
            return;
        }
        if (SystemClock.uptimeMillis() - lastLoadAt < RELOAD_MIN_INTERVAL_MS) {
            return;
        }
        lastLoadAt = SystemClock.uptimeMillis();
        webView.reload();
    }

    // ==================================================================================
    // 服务控制
    // ==================================================================================

    private interface Call {
        RootShell.Result run();
    }

    private void runService(String label, Call call) {
        if (busy || binding == null) {
            return;
        }
        setBusy(true);
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
                setBusy(false);
                toast(label + (finalResult.ok() ? "：完成" : "：失败")
                        + tail(finalResult.output));
                refreshRootState();
                reloadIfStale();
            });
        });
    }

    private void confirmUpdate() {
        if (busy || binding == null) {
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.panel_update_title)
                .setMessage(R.string.panel_update_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.panel_update_ok, (dialog, which) -> doUpdate())
                .show();
    }

    private void doUpdate() {
        if (binding == null) {
            return;
        }
        final Context context = requireContext().getApplicationContext();
        setBusy(true);
        EXECUTOR.execute(() -> {
            String message;
            try {
                SingBoxController.Status status = SingBoxController.status();
                SingBoxController.Release release =
                        SingBoxController.latestForChannel(status.beta());
                if (release == null) {
                    message = getString(R.string.singbox_no_release);
                } else {
                    RootShell.Result result = SingBoxController.install(context, release, step -> {
                        // 下载/安装进度直接喂到顶部那条进度条上，不打断网页。
                    });
                    message = (result.ok() ? "已更新到 " : "更新失败：") + release.tag
                            + tail(result.output);
                }
            } catch (Throwable t) {
                message = "更新失败：" + t;
            }
            String finalMessage = message;
            ui(() -> {
                if (binding == null) {
                    return;
                }
                setBusy(false);
                toast(finalMessage);
                refreshRootState();
                reloadIfStale();
            });
        });
    }

    // ==================================================================================
    // 状态
    // ==================================================================================

    /** 有 root 才能启停；没 root 时按钮置灰，点了也有 toast 说明。 */
    private void refreshRootState() {
        EXECUTOR.execute(() -> {
            boolean root = RootShell.hasRoot();
            ui(() -> applyEnabled(root));
        });
    }

    private void setBusy(boolean active) {
        busy = active;
        if (binding == null) {
            return;
        }
        binding.panelProgress.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            applyEnabled(false);
        } else {
            refreshRootState();
        }
    }

    private void applyEnabled(boolean enabled) {
        if (binding == null) {
            return;
        }
        boolean usable = enabled && !busy;
        binding.btnStart.setEnabled(usable);
        binding.btnStop.setEnabled(usable);
        binding.btnRestart.setEnabled(usable);
        binding.btnUpdate.setEnabled(usable);
    }

    /** 输出取最后两行塞进 toast，避免 root 命令的长输出刷屏。 */
    private static String tail(String output) {
        if (TextUtils.isEmpty(output)) {
            return "";
        }
        String[] lines = output.trim().split("\n");
        int from = Math.max(0, lines.length - 2);
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            builder.append(' ').append(lines[i].trim());
        }
        String text = builder.toString().trim();
        return text.isEmpty() ? "" : "（" + (text.length() > 120
                ? text.substring(0, 120) + "…" : text) + "）";
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
