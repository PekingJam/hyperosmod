package com.hyperosmod.singbox;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.hyperosmod.R;
import com.hyperosmod.databinding.ActivitySingboxBinding;
import com.hyperosmod.ui.UiUtils;

/**
 * sing-box 管理的独立页面：内容由 {@link SingBoxFragment} 提供，
 * 磁贴长按或从别处跳进来时用，日常也可以直接在主界面的 tab 里看。
 */
public final class SingBoxActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivitySingboxBinding binding = ActivitySingboxBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        setTitle(R.string.singbox_title);
    }
}
