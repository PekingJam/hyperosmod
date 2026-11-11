package com.hyperosmod.singbox;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.EditText;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.ActivityConfigEditorBinding;
import com.hyperosmod.ui.UiUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置文件编辑器：语法高亮 + JSON 校验 + 格式化 + 字段补全 + 保存 / 上传云盘。
 *
 * <p>补全的逻辑很克制：只认光标所在的单词，前缀匹配优先，匹配不到再算编辑距离 1 的
 * 「疑似拼错」。点击候选会把整个单词替换掉，不是简单在光标处插入。</p>
 */
public final class ConfigEditorActivity extends AppCompatActivity {

    private ActivityConfigEditorBinding binding;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Pattern CHAR_POSITION = Pattern.compile("at character (\\d+)");

    private boolean highlighting;
    /** 当前查找串：文本一变就按它重新标记所有匹配位置。 */
    private String findQuery = "";
    /** 全部匹配的起始偏移，以及当前停在第几个。 */
    private final java.util.List<Integer> matches = new java.util.ArrayList<>();
    private int matchIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityConfigEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (highlighting) {
                    return;
                }
                highlighting = true;
                JsonHighlighter.apply(s, palette());
                highlightMatches(s);
                highlighting = false;
                updateSuggestion();
            }
        });

        binding.btnFind.setOnClickListener(v -> toggleFindBar());
        binding.btnFindNext.setOnClickListener(v -> moveMatch(1));
        binding.btnFindPrev.setOnClickListener(v -> moveMatch(-1));
        binding.btnFindClose.setOnClickListener(v -> toggleFindBar());
        binding.btnReplace.setOnClickListener(v -> replaceCurrent());
        binding.btnReplaceAll.setOnClickListener(v -> replaceAllMatches());
        binding.findInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                findQuery = s == null ? "" : s.toString();
                matchIndex = -1;
                highlightMatches(binding.editor.getText());
            }
        });
        binding.btnGoto.setOnClickListener(v -> showGotoLine());
        binding.btnMinify.setOnClickListener(v -> minify());
        binding.btnValidate.setOnClickListener(v -> validate());
        binding.btnFormat.setOnClickListener(v -> format());
        binding.btnSave.setOnClickListener(v -> save());
        binding.lineNumbers.attach(binding.editor);
        binding.lineNumbers.setTextColor(getColor(R.color.line_number));

        binding.toolbar.inflateMenu(R.menu.menu_config_editor);
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_reload) {
                load();
                return true;
            }
            if (item.getItemId() == R.id.action_upload) {
                upload();
                return true;
            }
            return false;
        });

        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private JsonHighlighter.Palette palette() {
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            return new JsonHighlighter.Palette(
                    Color.parseColor("#7FB6FF"), Color.parseColor("#B7DFA8"),
                    Color.parseColor("#F0B77A"), Color.parseColor("#6FC3FF"),
                    Color.parseColor("#C79BFF"));
        }
        return new JsonHighlighter.Palette(
                Color.parseColor("#0B57D0"), Color.parseColor("#2E7D32"),
                Color.parseColor("#B45309"), Color.parseColor("#1565C0"),
                Color.parseColor("#7B1FA2"));
    }

    private String text() {
        return binding.editor.getText() == null ? "" : binding.editor.getText().toString();
    }

    // ==================================================================================
    // 载入 / 保存
    // ==================================================================================

    private void load() {
        binding.status.setText(R.string.editor_loading);
        setBusy(true);
        EXECUTOR.execute(() -> {
            String read = SingBoxController.readConfig();
            final String config = read.isEmpty()
                    ? "{\n  \"log\": {\n    \"level\": \"info\"\n  }\n}" : read;
            runOnUiThread(() -> {
                setBusy(false);
                if (isFinishing()) {
                    return;
                }
                binding.editor.setText(config);
                findQuery = "";
                binding.findInput.setText("");
                matches.clear();
                matchIndex = -1;
                JsonHighlighter.apply(binding.editor.getText(), palette());
                binding.status.setText(getString(R.string.editor_loaded,
                        config.split("\n").length));
            });
        });
    }

    private void save() {
        String json = text();
        int errorLine = jsonErrorLine(json);
        if (errorLine > 0) {
            binding.status.setText("JSON 有语法错误，先修好再保存");
            return;
        }
        setBusy(true);
        binding.status.setText("正在校验并保存…");
        EXECUTOR.execute(() -> {
            RootShell.Result result = SingBoxController.saveConfig(json, true);
            runOnUiThread(() -> {
                setBusy(false);
                binding.status.setText(result.ok()
                        ? getString(R.string.editor_saved) : result.output);
                toast(result.ok() ? getString(R.string.editor_saved) : result.output);
            });
        });
    }

    private void upload() {
        String json = text();
        String base = Prefs.getString(Keys.SINGBOX_WEBDAV_URL, "");
        String user = Prefs.getString(Keys.SINGBOX_WEBDAV_USER, "");
        String pass = SecretStore.decrypt(this, Prefs.getString(Keys.SINGBOX_WEBDAV_PASS, ""));
        String path = Prefs.getString(Keys.SINGBOX_WEBDAV_PATH, "singbox/config.json");
        if (base.isEmpty() || user.isEmpty()) {
            toast(getString(R.string.singbox_webdav_missing));
            return;
        }
        boolean minify = Prefs.getBoolean(Keys.SINGBOX_WEBDAV_MINIFY, true);
        final String payload = minify ? JsonHighlighter.minify(json) : json;
        setBusy(true);
        binding.status.setText("正在上传到云盘…");
        EXECUTOR.execute(() -> {
            String error = WebDavClient.upload(base, user, pass, path, payload);
            runOnUiThread(() -> {
                setBusy(false);
                binding.status.setText(error == null ? "已上传到 " + path : error);
                toast(error == null ? "已上传到云盘" : error);
            });
        });
    }

    // ==================================================================================
    // 校验 / 格式化
    // ==================================================================================

    private void validate() {
        String json = text();
        int errorLine = jsonErrorLine(json);
        if (errorLine > 0) {
            binding.status.setText("第 " + errorLine + " 行附近的 JSON 语法有问题");
            return;
        }
        binding.status.setText(R.string.editor_valid);
        setBusy(true);
        EXECUTOR.execute(() -> {
            RootShell.Result check = SingBoxController.validate(json);
            runOnUiThread(() -> {
                setBusy(false);
                binding.status.setText(check.ok()
                        ? getString(R.string.editor_check_ok)
                        : getString(R.string.editor_check_failed, check.output));
            });
        });
    }

    /** 返回 0 表示 JSON 可以解析，否则返回出错行号。 */
    private int jsonErrorLine(String json) {
        try {
            new JSONObject(json);
            return 0;
        } catch (JSONException e) {
            Matcher matcher = CHAR_POSITION.matcher(String.valueOf(e.getMessage()));
            if (!matcher.find()) {
                return 1;
            }
            int offset = Math.min(json.length(), Integer.parseInt(matcher.group(1)));
            int line = 1;
            for (int i = 0; i < offset; i++) {
                if (json.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }
    }

    // ==================================================================================
    // 查找 / 替换（常驻面板，关掉才停止）
    // ==================================================================================

    private void toggleFindBar() {
        boolean showing = binding.findBar.getVisibility() == View.VISIBLE;
        if (showing) {
            binding.findBar.setVisibility(View.GONE);
            // 先把关键字清空，再摘掉黄底：输入框还在监听文本变化，源头不断就一直重标。
            findQuery = "";
            binding.findInput.setText("");
            binding.replaceInput.setText("");
            matches.clear();
            matchIndex = -1;
            clearHighlights();
            updateMatchCounter();
            binding.status.setText("");
            return;
        }
        binding.findBar.setVisibility(View.VISIBLE);
        String selected = selectedText();
        if (!selected.isEmpty() && !selected.contains("\n")) {
            binding.findInput.setText(selected);
        }
        binding.findInput.requestFocus();
        highlightMatches(binding.editor.getText());
    }

    private String selectedText() {
        int start = binding.editor.getSelectionStart();
        int end = binding.editor.getSelectionEnd();
        if (start < 0 || end < 0 || start == end) {
            return "";
        }
        return binding.editor.getText().subSequence(Math.min(start, end),
                Math.max(start, end)).toString();
    }

    /**
     * 标出全部匹配并统计数量。
     *
     * <p>匹配位置存进 {@link #matches}，这样「下一个 / 上一个」只是在数组里挪指针，
     * 不用每次重新扫全文，序号也就能稳定显示成 {@code 3/7}。</p>
     */
    /** 只做一件事：把黄底全部摘掉。关闭查找栏时直接调它，不依赖任何状态。 */
    private void clearHighlights() {
        Editable editable = binding.editor.getText();
        if (editable == null) {
            return;
        }
        for (BackgroundColorSpan span : editable.getSpans(0, editable.length(),
                BackgroundColorSpan.class)) {
            editable.removeSpan(span);
        }
    }

    private void highlightMatches(Editable editable) {
        if (editable == null) {
            return;
        }
        for (BackgroundColorSpan span : editable.getSpans(0, editable.length(),
                BackgroundColorSpan.class)) {
            editable.removeSpan(span);
        }
        matches.clear();
        if (findQuery.isEmpty()) {
            updateMatchCounter();
            return;
        }
        String text = editable.toString();
        int index = 0;
        while (index <= text.length() - findQuery.length()) {
            int hit = text.indexOf(findQuery, index);
            if (hit < 0) {
                break;
            }
            matches.add(hit);
            editable.setSpan(new BackgroundColorSpan(0x66FFD54F), hit, hit + findQuery.length(),
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            index = hit + Math.max(1, findQuery.length());
        }
        if (matchIndex >= matches.size()) {
            matchIndex = -1;
        }
        updateMatchCounter();
    }

    private void updateMatchCounter() {
        if (binding == null) {
            return;
        }
        int total = matches.size();
        int current = matchIndex >= 0 && matchIndex < total ? matchIndex + 1 : 0;
        binding.findCount.setText(getString(R.string.editor_find_count, current, total));
    }

    /** 在匹配之间移动；到底了从另一头继续。 */
    private void moveMatch(int delta) {
        int total = matches.size();
        if (total == 0) {
            binding.status.setText(R.string.editor_not_found);
            return;
        }
        if (matchIndex < 0) {
            matchIndex = delta > 0 ? 0 : total - 1;
        } else {
            matchIndex = (matchIndex + delta + total) % total;
        }
        int start = matches.get(matchIndex);
        binding.editor.setSelection(start, Math.min(start + findQuery.length(),
                binding.editor.length()));
        scrollToOffset(start);
        updateMatchCounter();
    }

    /** 替换当前这一处；没定位到就先跳到下一处。 */
    private void replaceCurrent() {
        if (findQuery.isEmpty() || matches.isEmpty()) {
            return;
        }
        String replacement = textOf(binding.replaceInput);
        if (matchIndex < 0) {
            moveMatch(1);
            return;
        }
        int start = matches.get(matchIndex);
        Editable editable = binding.editor.getText();
        editable.replace(start, Math.min(start + findQuery.length(), editable.length()),
                replacement);
        highlightMatches(editable);
        moveMatch(1);
    }

    private void replaceAllMatches() {
        if (findQuery.isEmpty() || matches.isEmpty()) {
            binding.status.setText(R.string.editor_not_found);
            return;
        }
        int count = matches.size();
        String replacement = textOf(binding.replaceInput);
        binding.editor.setText(text().replace(findQuery, replacement));
        findQuery = replacement.isEmpty() ? "" : replacement;
        matchIndex = -1;
        highlightMatches(binding.editor.getText());
        binding.status.setText(getString(R.string.editor_replaced, count));
    }

    private String textOf(EditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString();
    }

    /** 跳到指定行：把光标放到该行行首并滚动过去。 */
    private void showGotoLine() {
        View view = getLayoutInflater().inflate(R.layout.dialog_goto_line, null);
        EditText input = view.findViewById(R.id.line_input);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_goto)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int line;
                    try {
                        line = Integer.parseInt(input.getText().toString().trim());
                    } catch (Throwable t) {
                        return;
                    }
                    gotoLine(line);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * 跳到指定行（逻辑行，按换行符计数）。
     *
     * <p>和左侧行号列保持同一套口径：折行的续行不单独编号，所以 350 就是文件里第 350 个
     * 换行之后那一行，和 VS Code 的行为一致。</p>
     */
    private void gotoLine(int line) {
        String text = text();
        int total = logicalLineCount(text);
        if (line < 1 || line > total) {
            toast(getString(R.string.editor_line_invalid));
            return;
        }
        int offset = offsetOfLine(text, line);
        binding.editor.setSelection(Math.min(offset, binding.editor.length()));
        scrollToOffset(offset);
    }

    private int logicalLineCount(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** 第 N 行行首的字符偏移。 */
    private int offsetOfLine(String text, int line) {
        if (line <= 1) {
            return 0;
        }
        int seen = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                seen++;
                if (seen == line) {
                    return i + 1;
                }
            }
        }
        return Math.max(0, text.length() - 1);
    }

    /** 按逻辑行返回行号（与左侧行号列一致）。 */
    private int lineOf(String text, int offset) {
        int line = 1;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 滚动到指定字符位置：用布局行高换算成像素。 */
    private void scrollToOffset(int offset) {
        android.text.Layout layout = binding.editor.getLayout();
        if (layout == null) {
            return;
        }
        int line = layout.getLineForOffset(Math.min(offset, binding.editor.length()));
        int y = layout.getLineTop(line);
        binding.editorScroll.smoothScrollTo(0, Math.max(0, y - dp(40)));
    }

    private void minify() {
        binding.editor.setText(JsonHighlighter.minify(text()));
        binding.status.setText(R.string.editor_minified);
    }

    private void format() {
        String formatted = JsonHighlighter.format(text());
        int cursor = binding.editor.getSelectionStart();
        binding.editor.setText(formatted);
        binding.editor.setSelection(Math.min(cursor, formatted.length()));
        binding.status.setText(R.string.editor_format_done);
    }

    // ==================================================================================
    // 补全 / 拼写提示
    // ==================================================================================

    private void updateSuggestion() {
        binding.suggestions.removeAllViews();
        int cursor = binding.editor.getSelectionStart();
        String token = JsonHighlighter.tokenAt(binding.editor.getText(), cursor);
        if (token.length() < 2) {
            binding.suggestionScroll.setVisibility(View.GONE);
            binding.status.setText("");
            return;
        }
        List<String> hits = SingboxSchema.suggest(token);
        String misspell = hits.isEmpty() ? SingboxSchema.nearest(token) : "";
        if (!misspell.isEmpty()) {
            binding.status.setText(getString(R.string.editor_hint_misspell, misspell));
        } else {
            binding.status.setText("");
        }
        if (hits.isEmpty()) {
            binding.suggestionScroll.setVisibility(View.GONE);
            return;
        }
        binding.suggestionScroll.setVisibility(View.VISIBLE);
        for (String hit : hits) {
            binding.suggestions.addView(chip(hit));
        }
    }

    private View chip(String word) {
        MaterialButton chip = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        chip.setText(word);
        chip.setTextSize(12f);
        chip.setAllCaps(false);
        chip.setTypeface(Typeface.MONOSPACE);
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(0);
        chip.setMinimumWidth(0);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        android.widget.LinearLayout.LayoutParams params =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(8));
        chip.setLayoutParams(params);
        chip.setOnClickListener(v -> replaceToken(word));
        return chip;
    }

    private void replaceToken(String word) {
        Editable text = binding.editor.getText();
        int cursor = binding.editor.getSelectionStart();
        int start = JsonHighlighter.tokenStart(text, cursor);
        text.replace(start, cursor, word);
        binding.editor.setSelection(start + word.length());
        binding.suggestionScroll.setVisibility(View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ==================================================================================

    private void setBusy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.btnSave.setEnabled(!busy);
        binding.btnValidate.setEnabled(!busy);
        binding.btnFormat.setEnabled(!busy);
        binding.btnMinify.setEnabled(!busy);
        binding.btnFind.setEnabled(!busy);
        binding.btnGoto.setEnabled(!busy);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

}
