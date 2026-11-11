package com.hyperosmod.singbox;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.hyperosmod.R;
import com.hyperosmod.databinding.ActivitySingboxWebBinding;
import com.hyperosmod.ui.UiUtils;

/** 网页面板的独立页面：内容由 {@link SingBoxWebFragment} 提供。 */
public final class SingBoxWebActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivitySingboxWebBinding binding = ActivitySingboxWebBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        setTitle(R.string.singbox_web_title);
    }

    /** 返回键优先回退网页历史。 */
    @Override
    public void onBackPressed() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.container);
        if (fragment instanceof SingBoxWebFragment
                && ((SingBoxWebFragment) fragment).handleBack()) {
            return;
        }
        super.onBackPressed();
    }

    @NonNull
    @Override
    public String toString() {
        return "SingBoxWebActivity";
    }
}
