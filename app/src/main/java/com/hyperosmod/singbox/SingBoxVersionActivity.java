package com.hyperosmod.singbox;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hyperosmod.R;
import com.hyperosmod.databinding.ActivitySingboxVersionBinding;
import com.hyperosmod.ui.UiUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 二级页面：版本管理（更新 / 通道互切 / 回退历史版本）。 */
public final class SingBoxVersionActivity extends AppCompatActivity {

    private ActivitySingboxVersionBinding binding;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySingboxVersionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.btnUpdate.setOnClickListener(v -> updateToChannel(false));
        binding.btnSwitch.setOnClickListener(v -> updateToChannel(true));
        binding.btnRollback.setOnClickListener(v -> showReleaseList());
    }

    private void updateToChannel(boolean switchChannel) {
        setBusy(true);
        append(switchChannel ? "正在切换通道…" : "正在检查更新…");
        EXECUTOR.execute(() -> {
            SingBoxController.Status status = SingBoxController.status();
            boolean targetBeta = switchChannel ? !status.beta() : status.beta();
            SingBoxController.Release release = SingBoxController.latestForChannel(targetBeta);
            if (release == null) {
                finish(getString(R.string.singbox_no_release));
                return;
            }
            RootShell.Result result = SingBoxController.install(this, release,
                    step -> runOnUiThread(() -> append(step)));
            finish(result.output);
        });
    }

    private void showReleaseList() {
        setBusy(true);
        EXECUTOR.execute(() -> {
            List<SingBoxController.Release> releases = SingBoxController.releases();
            runOnUiThread(() -> {
                setBusy(false);
                if (isFinishing()) {
                    return;
                }
                if (releases.isEmpty()) {
                    Toast.makeText(this, R.string.singbox_no_release, Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] items = new String[releases.size()];
                for (int i = 0; i < releases.size(); i++) {
                    items[i] = releases.get(i).display();
                }
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.singbox_rollback)
                        .setItems(items, (dialog, which) -> install(releases.get(which)))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            });
        });
    }

    private void install(SingBoxController.Release release) {
        setBusy(true);
        append("正在安装 " + release.tag + " …");
        EXECUTOR.execute(() -> {
            RootShell.Result result = SingBoxController.install(this, release,
                    step -> runOnUiThread(() -> append(step)));
            finish(result.output);
        });
    }

    private void finish(String message) {
        runOnUiThread(() -> {
            setBusy(false);
            if (!TextUtils.isEmpty(message)) {
                append(message);
            }
        });
    }

    private void append(String line) {
        if (binding == null || TextUtils.isEmpty(line)) {
            return;
        }
        binding.output.append(line + "\n");
    }

    private void setBusy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.btnUpdate.setEnabled(!busy);
        binding.btnSwitch.setEnabled(!busy);
        binding.btnRollback.setEnabled(!busy);
    }
}
