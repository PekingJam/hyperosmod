package com.hyperosmod.singbox;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.FragmentSingboxWebBinding;

/**
 * 网页面板：用 WebView 渲染用户配置的地址。
 *
 * <p>顶部三个按钮：刷新、切换缓存策略、清除缓存。缓存策略三档——默认（按 HTTP 头）、
 * 缓存优先（断网也能打开上次内容）、不走缓存（每次取最新）。</p>
 */
public final class SingBoxWebFragment extends Fragment {

    private FragmentSingboxWebBinding binding;
    private WebView webView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSingboxWebBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        webView = binding.webView;
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(cacheMode());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (binding != null) {
                    binding.progress.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (binding != null) {
                    binding.progress.setVisibility(View.GONE);
                }
            }
        });

        binding.btnRefresh.setOnClickListener(v -> reload());
        binding.btnCache.setOnClickListener(v -> cycleCache());
        binding.btnClear.setOnClickListener(v -> clearCache());

        loadConfiguredUrl();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 地址可能在「sing-box」页刚被改过，回到这个页时重新读一次。
        loadConfiguredUrl();
    }

    private void loadConfiguredUrl() {
        if (binding == null || webView == null) {
            return;
        }
        String url = Prefs.getString(Keys.SINGBOX_WEB_URL, "");
        if (TextUtils.isEmpty(url)) {
            binding.empty.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);
            return;
        }
        binding.empty.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        if (!url.equals(webView.getUrl())) {
            webView.loadUrl(url);
        }
    }

    private void reload() {
        if (webView != null && webView.getUrl() != null) {
            webView.reload();
        } else {
            loadConfiguredUrl();
        }
    }

    private int cacheMode() {
        switch (Prefs.getInt(Keys.SINGBOX_WEB_CACHE, 0)) {
            case 1:
                return WebSettings.LOAD_CACHE_ELSE_NETWORK;
            case 2:
                return WebSettings.LOAD_NO_CACHE;
            default:
                return WebSettings.LOAD_DEFAULT;
        }
    }

    private void cycleCache() {
        int next = (Prefs.getInt(Keys.SINGBOX_WEB_CACHE, 0) + 1) % 3;
        Prefs.setInt(Keys.SINGBOX_WEB_CACHE, next);
        if (webView != null) {
            webView.getSettings().setCacheMode(cacheMode());
        }
        Toast.makeText(requireContext(),
                getString(R.string.singbox_web_cache_changed, cacheLabel(next)),
                Toast.LENGTH_SHORT).show();
        reload();
    }

    private void clearCache() {
        if (webView != null) {
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
        }
        Toast.makeText(requireContext(), R.string.singbox_web_cache_cleared,
                Toast.LENGTH_SHORT).show();
        reload();
    }

    private String cacheLabel(int mode) {
        switch (mode) {
            case 1:
                return getString(R.string.singbox_web_cache_prefer);
            case 2:
                return getString(R.string.singbox_web_cache_off);
            default:
                return getString(R.string.singbox_web_cache_default);
        }
    }

    /** 供宿主 Activity 处理返回键；返回 true 表示已经消费（回退网页历史）。 */
    public boolean handleBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
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
        super.onDestroyView();
    }
}
