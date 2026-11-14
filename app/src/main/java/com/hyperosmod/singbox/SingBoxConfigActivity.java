package com.hyperosmod.singbox;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.ActivitySingboxConfigBinding;
import com.hyperosmod.ui.UiUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 二级页面：配置文件。
 *
 * <p>三块内容——在线编辑入口、订阅地址、云盘上传（WebDAV）。密码用 Keystore 加密后
 * 存进 SharedPreferences，明文不落盘。</p>
 */
public final class SingBoxConfigActivity extends AppCompatActivity {

    private ActivitySingboxConfigBinding binding;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySingboxConfigBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.rowEdit.title.setText(R.string.singbox_config_edit);
        binding.rowEdit.summary.setText(R.string.singbox_config_edit_summary);
        binding.rowEdit.getRoot().setOnClickListener(v ->
                startActivity(new Intent(this, ConfigEditorActivity.class)));

        binding.configUrl.setText(Prefs.getString(Keys.SINGBOX_CONFIG_URL, ""));
        binding.btnConfig.setOnClickListener(v -> applySubscribe());

        binding.apiUrl.setText(Prefs.getString(Keys.SINGBOX_API_URL, "http://127.0.0.1:9090"));
        binding.apiSecret.setText(Prefs.getString(Keys.SINGBOX_API_SECRET, ""));

        binding.davUrl.setText(Prefs.getString(Keys.SINGBOX_WEBDAV_URL, ""));
        binding.davUser.setText(Prefs.getString(Keys.SINGBOX_WEBDAV_USER, ""));
        binding.davPass.setText(SecretStore.decrypt(this,
                Prefs.getString(Keys.SINGBOX_WEBDAV_PASS, "")));
        binding.davPath.setText(Prefs.getString(Keys.SINGBOX_WEBDAV_PATH, "singbox/config.json"));
        binding.switchDavMinify.setChecked(Prefs.getBoolean(Keys.SINGBOX_WEBDAV_MINIFY, true));
        binding.switchDavMinify.setOnCheckedChangeListener((button, checked) ->
                Prefs.setBoolean(Keys.SINGBOX_WEBDAV_MINIFY, checked));

        binding.btnDavTest.setOnClickListener(v -> testCloud());
        binding.btnDavUpload.setOnClickListener(v -> uploadCloud());
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 接口地址随手改随手生效，不用额外的保存按钮。
        Prefs.setString(Keys.SINGBOX_API_URL, text(binding.apiUrl));
        Prefs.setString(Keys.SINGBOX_API_SECRET, text(binding.apiSecret));
    }

    private String text(android.widget.EditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString().trim();
    }

    private void applySubscribe() {
        String url = text(binding.configUrl);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(getString(R.string.singbox_config_bad_url));
            return;
        }
        Prefs.setString(Keys.SINGBOX_CONFIG_URL, url);
        setBusy(true);
        EXECUTOR.execute(() -> {
            RootShell.Result result = SingBoxController.updateConfig(this, url,
                    step -> runOnUiThread(() -> append(step)));
            runOnUiThread(() -> {
                setBusy(false);
                append(result.output);
            });
        });
    }

    private void saveCloudSettings() {
        Prefs.setString(Keys.SINGBOX_WEBDAV_URL, text(binding.davUrl));
        Prefs.setString(Keys.SINGBOX_WEBDAV_USER, text(binding.davUser));
        Prefs.setString(Keys.SINGBOX_WEBDAV_PASS,
                SecretStore.encrypt(this, text(binding.davPass)));
        String path = text(binding.davPath);
        Prefs.setString(Keys.SINGBOX_WEBDAV_PATH,
                path.isEmpty() ? "singbox/config.json" : path);
        Prefs.setBoolean(Keys.SINGBOX_WEBDAV_MINIFY, binding.switchDavMinify.isChecked());
    }

    private void testCloud() {
        saveCloudSettings();
        String url = Prefs.getString(Keys.SINGBOX_WEBDAV_URL, "");
        String user = Prefs.getString(Keys.SINGBOX_WEBDAV_USER, "");
        if (url.isEmpty() || user.isEmpty()) {
            toast(getString(R.string.singbox_webdav_missing));
            return;
        }
        setBusy(true);
        EXECUTOR.execute(() -> {
            String error = WebDavClient.test(url, user,
                    SecretStore.decrypt(this, Prefs.getString(Keys.SINGBOX_WEBDAV_PASS, "")));
            runOnUiThread(() -> {
                setBusy(false);
                append(error == null ? "连接成功" : error);
            });
        });
    }

    private void uploadCloud() {
        saveCloudSettings();
        String url = Prefs.getString(Keys.SINGBOX_WEBDAV_URL, "");
        String user = Prefs.getString(Keys.SINGBOX_WEBDAV_USER, "");
        if (url.isEmpty() || user.isEmpty()) {
            toast(getString(R.string.singbox_webdav_missing));
            return;
        }
        setBusy(true);
        append("正在上传 config.json …");
        EXECUTOR.execute(() -> {
            String raw = SingBoxController.readConfig();
            // 默认压缩：压成单行体积小很多，同时仍然是合法可用的配置。
            boolean minify = Prefs.getBoolean(Keys.SINGBOX_WEBDAV_MINIFY, true);
            String payload = minify ? JsonHighlighter.minify(raw) : raw;
            String error = WebDavClient.upload(url, user,
                    SecretStore.decrypt(this, Prefs.getString(Keys.SINGBOX_WEBDAV_PASS, "")),
                    Prefs.getString(Keys.SINGBOX_WEBDAV_PATH, "singbox/config.json"), payload);
            final String result = error == null
                    ? getString(R.string.singbox_webdav_uploaded,
                    bytes(raw), bytes(payload))
                    : error;
            runOnUiThread(() -> {
                setBusy(false);
                append(result);
            });
        });
    }

    private String bytes(String value) {
        int length = value == null ? 0 : value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (length < 1024) {
            return length + " B";
        }
        return String.format(java.util.Locale.ROOT, "%.1f KB", length / 1024f);
    }

    private void append(String line) {
        if (binding == null || TextUtils.isEmpty(line)) {
            return;
        }
        CharSequence current = binding.output.getText();
        String merged = (current == null ? "" : current.toString()) + line + "\n";
        if (merged.length() > 6000) {
            merged = merged.substring(merged.length() - 6000);
        }
        binding.output.setText(merged);
    }

    private void setBusy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.btnConfig.setEnabled(!busy);
        binding.btnDavTest.setEnabled(!busy);
        binding.btnDavUpload.setEnabled(!busy);
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }
}
