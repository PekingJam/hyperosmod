package com.hyperosmod.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;
import android.widget.SeekBar;

import androidx.annotation.ColorInt;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hyperosmod.R;
import com.hyperosmod.databinding.DialogColorPickerBinding;

import java.util.Locale;

/** HSV color picker with a hex field and a preset palette. */
public final class ColorPickerDialog {
    @FunctionalInterface
    public interface OnColorPicked {
        void onColorPicked(@ColorInt int color);
    }

    private static final int[] PRESETS = {
            0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5, 0xFF2196F3,
            0xFF00BCD4, 0xFF009688, 0xFF4CAF50, 0xFF8BC34A, 0xFFCDDC39, 0xFFFFC107,
            0xFFFF9800, 0xFFFF5722, 0xFF795548, 0xFF607D8B, 0xFF000000, 0xFF37474F,
            0xFF4C6FFF, 0xFF7C4DFF, 0xFF00C2A8, 0xFFFF6F91, 0xFFFFB86B, 0xFFE0E0E0
    };

    private ColorPickerDialog() {
    }

    public static void show(Context context, @ColorInt int initialColor, OnColorPicked callback) {
        DialogColorPickerBinding binding =
                DialogColorPickerBinding.inflate(LayoutInflater.from(context));
        int[] selected = {initialColor};
        float[] hsv = new float[3];
        Color.colorToHSV(initialColor, hsv);

        final boolean[] updating = {false};

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (updating[0]) {
                    return;
                }
                hsv[0] = binding.hue.getProgress();
                hsv[1] = binding.saturation.getProgress() / 100f;
                hsv[2] = binding.brightness.getProgress() / 100f;
                selected[0] = Color.HSVToColor(hsv);
                updatePreview(binding, selected[0], true);
                updateLabels(binding, hsv);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };

        binding.hue.setOnSeekBarChangeListener(listener);
        binding.saturation.setOnSeekBarChangeListener(listener);
        binding.brightness.setOnSeekBarChangeListener(listener);

        binding.hex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (updating[0]) {
                    return;
                }
                String value = editable.toString().trim();
                if (!value.startsWith("#")) {
                    value = "#" + value;
                }
                if (!value.matches("#[0-9a-fA-F]{6}")) {
                    return;
                }
                try {
                    int color = Color.parseColor(value);
                    selected[0] = color;
                    Color.colorToHSV(color, hsv);
                    updating[0] = true;
                    binding.hue.setProgress(Math.round(hsv[0]));
                    binding.saturation.setProgress(Math.round(hsv[1] * 100));
                    binding.brightness.setProgress(Math.round(hsv[2] * 100));
                    updating[0] = false;
                    updatePreview(binding, color, false);
                    updateLabels(binding, hsv);
                } catch (IllegalArgumentException ignored) {
                    // Keep the last valid color while the user is typing.
                }
            }
        });

        buildPresets(context, binding, color -> {
            selected[0] = color;
            Color.colorToHSV(color, hsv);
            updating[0] = true;
            binding.hue.setProgress(Math.round(hsv[0]));
            binding.saturation.setProgress(Math.round(hsv[1] * 100));
            binding.brightness.setProgress(Math.round(hsv[2] * 100));
            updating[0] = false;
            updatePreview(binding, color, true);
            updateLabels(binding, hsv);
        });

        updating[0] = true;
        binding.hue.setProgress(Math.round(hsv[0]));
        binding.saturation.setProgress(Math.round(hsv[1] * 100));
        binding.brightness.setProgress(Math.round(hsv[2] * 100));
        updating[0] = false;
        updatePreview(binding, initialColor, true);
        updateLabels(binding, hsv);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.color_picker_title)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> callback.onColorPicked(selected[0]))
                .show();
    }

    private static void updatePreview(DialogColorPickerBinding binding, int color, boolean updateHex) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(binding.getRoot().getContext(), 16));
        background.setColor(color);
        background.setStroke(dp(binding.getRoot().getContext(), 1), 0x22000000);
        binding.preview.setBackground(background);
        if (updateHex) {
            binding.hex.setText(String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
            binding.hex.setSelection(binding.hex.getText().length());
        }
    }

    private static void updateLabels(DialogColorPickerBinding binding, float[] hsv) {
        binding.hueValue.setText(String.format(Locale.ROOT, "%d°", Math.round(hsv[0])));
        binding.saturationValue.setText(String.format(Locale.ROOT, "%d%%", Math.round(hsv[1] * 100)));
        binding.brightnessValue.setText(String.format(Locale.ROOT, "%d%%", Math.round(hsv[2] * 100)));
    }

    private static void buildPresets(Context context, DialogColorPickerBinding binding,
                                     OnColorPicked onPicked) {
        int size = dp(context, 36);
        int margin = dp(context, 5);
        for (int color : PRESETS) {
            View swatch = new View(context);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = size;
            params.height = size;
            params.setMargins(margin, margin, margin, margin);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(color);
            background.setStroke(dp(context, 1), 0x22000000);
            swatch.setBackground(background);
            swatch.setContentDescription(String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
            swatch.setOnClickListener(v -> onPicked.onColorPicked(color));
            binding.presets.addView(swatch, params);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
