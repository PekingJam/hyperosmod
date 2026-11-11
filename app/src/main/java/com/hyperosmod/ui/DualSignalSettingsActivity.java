package com.hyperosmod.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.ActivityDualSignalBinding;
import com.hyperosmod.databinding.RowSliderBinding;

/** 二级页面：双排信号的尺寸与粗细。 */
public final class DualSignalSettingsActivity extends AppCompatActivity {

    private ActivityDualSignalBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDualSignalBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        bindSlider(binding.rowScale,
                R.string.dual_signal_scale_title, R.string.dual_signal_scale_summary,
                Keys.DUAL_SIGNAL_SCALE, Keys.DUAL_SIGNAL_SCALE_MIN,
                Keys.DUAL_SIGNAL_SCALE_MAX, Keys.DUAL_SIGNAL_SCALE_DEFAULT);
        bindSlider(binding.rowThickness,
                R.string.dual_signal_thickness_title, R.string.dual_signal_thickness_summary,
                Keys.DUAL_SIGNAL_THICKNESS, Keys.DUAL_SIGNAL_THICKNESS_MIN,
                Keys.DUAL_SIGNAL_THICKNESS_MAX, Keys.DUAL_SIGNAL_THICKNESS_DEFAULT);
        bindSlider(binding.rowTextScale,
                R.string.dual_text_scale_title, R.string.dual_text_scale_summary,
                Keys.DUAL_TEXT_SCALE, Keys.DUAL_TEXT_SCALE_MIN,
                Keys.DUAL_TEXT_SCALE_MAX, Keys.DUAL_TEXT_SCALE_DEFAULT);
        bindSlider(binding.rowTextThickness,
                R.string.dual_text_thickness_title, R.string.dual_text_thickness_summary,
                Keys.DUAL_TEXT_THICKNESS, Keys.DUAL_TEXT_THICKNESS_MIN,
                Keys.DUAL_TEXT_THICKNESS_MAX, Keys.DUAL_TEXT_THICKNESS_DEFAULT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiUtils.applySystemBars(this);
        boolean enabled = Prefs.getBoolean(Keys.DUAL_ROW_SIGNAL, false);
        float alpha = enabled ? 1f : 0.45f;
        for (RowSliderBinding row : new RowSliderBinding[]{
                binding.rowScale, binding.rowThickness,
                binding.rowTextScale, binding.rowTextThickness}) {
            row.getRoot().setAlpha(alpha);
            row.slider.setEnabled(enabled);
        }
        binding.hint.setVisibility(enabled ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void bindSlider(RowSliderBinding row, int title, int summary, String key,
                            int min, int max, int defaultValue) {
        row.title.setText(title);
        row.slider.setValueFrom(min);
        row.slider.setValueTo(max);
        row.slider.setStepSize(5);
        int value = Math.max(min, Math.min(max, Prefs.getInt(key, defaultValue)));
        row.slider.setValue(value);
        row.summary.setText(getString(summary, value));
        row.slider.addOnChangeListener((slider, current, fromUser) -> {
            int rounded = Math.round(current);
            row.summary.setText(getString(summary, rounded));
            if (fromUser) {
                Prefs.setInt(key, rounded);
            }
        });
    }
}
