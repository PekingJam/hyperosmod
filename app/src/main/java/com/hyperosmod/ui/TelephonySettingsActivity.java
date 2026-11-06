package com.hyperosmod.ui;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hyperosmod.Keys;
import com.hyperosmod.R;
import com.hyperosmod.data.Prefs;
import com.hyperosmod.databinding.ActivityTelephonyBinding;
import com.hyperosmod.databinding.ViewTelephonySimBinding;

public final class TelephonySettingsActivity extends AppCompatActivity {
    private static final String[] FIELD_KEYS = {
            Keys.TELEPHONY_PHONE,
            Keys.TELEPHONY_SIM_SERIAL,
            Keys.TELEPHONY_NETWORK_COUNTRY,
            Keys.TELEPHONY_NETWORK_OPERATOR,
            Keys.TELEPHONY_NETWORK_OPERATOR_NAME,
            Keys.TELEPHONY_SIM_COUNTRY,
            Keys.TELEPHONY_SIM_OPERATOR,
            Keys.TELEPHONY_SIM_OPERATOR_NAME
    };

    private ActivityTelephonyBinding binding;
    private final ViewTelephonySimBinding[] sims = new ViewTelephonySimBinding[2];
    private String selectedPresetId = "us";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityTelephonyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        UiUtils.applySystemBars(this);
        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        for (int slot = 0; slot < 2; slot++) {
            ViewTelephonySimBinding sim = ViewTelephonySimBinding.inflate(
                    getLayoutInflater(), binding.simContainer, false);
            sim.title.setText(getString(R.string.telephony_sim_title, slot + 1));
            binding.simContainer.addView(sim.getRoot());
            sims[slot] = sim;
        }

        setupPreset();
        loadValues();
        binding.copyToSecond.setOnClickListener(v -> copyFirstToSecond());
        binding.save.setOnClickListener(v -> saveValues());
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiUtils.applySystemBars(this);
    }

    private void setupPreset() {
        String[] labels = new String[TelephonyPreset.PRESETS.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = TelephonyPreset.PRESETS.get(i).label;
        }
        binding.preset.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, labels));
        binding.preset.setOnItemClickListener((parent, view, position, id) ->
                applyPreset(TelephonyPreset.PRESETS.get(position)));
    }

    private void loadValues() {
        selectedPresetId = Prefs.getString(Keys.TELEPHONY_PRESET, "us");
        TelephonyPreset preset = TelephonyPreset.byId(selectedPresetId);
        boolean hasSaved = false;
        for (int slot = 0; slot < 2 && !hasSaved; slot++) {
            for (String field : FIELD_KEYS) {
                if (!Prefs.getString(Keys.telephony(field, slot), "").isEmpty()) {
                    hasSaved = true;
                    break;
                }
            }
        }
        if (hasSaved) {
            for (int slot = 0; slot < 2; slot++) {
                setValues(slot, Prefs.getString(Keys.telephony(FIELD_KEYS[0], slot), ""),
                        Prefs.getString(Keys.telephony(FIELD_KEYS[1], slot), ""),
                        Prefs.getString(Keys.telephony(FIELD_KEYS[2], slot), ""),
                        Prefs.getString(Keys.telephony(FIELD_KEYS[3], slot), ""),
                        Prefs.getString(Keys.telephony(FIELD_KEYS[4], slot), ""),
                        Prefs.getString(Keys.telephony(FIELD_KEYS[5], slot), ""),
                        Prefs.getString(Keys.telephony(FIELD_KEYS[6], slot), ""),
                        Prefs.getString(Keys.telephony(FIELD_KEYS[7], slot), ""));
            }
        } else {
            applyPreset(preset);
        }
        binding.preset.setText(preset.label, false);
        int defaultSlot = Prefs.getInt(Keys.TELEPHONY_DEFAULT_SLOT, 0);
        binding.defaultSlotGroup.check(defaultSlot == 1 ? R.id.chip_slot_2 : R.id.chip_slot_1);
    }

    private void applyPreset(TelephonyPreset preset) {
        selectedPresetId = preset.id;
        binding.preset.setText(preset.label, false);
        for (int slot = 0; slot < 2; slot++) {
            setValues(slot, preset.phone, preset.simSerial, preset.networkCountry,
                    preset.networkOperator, preset.networkOperatorName,
                    preset.simCountry, preset.simOperator, preset.simOperatorName);
        }
    }

    private void setValues(int slot, String phone, String simSerial, String networkCountry,
                           String networkOperator, String networkOperatorName,
                           String simCountry, String simOperator, String simOperatorName) {
        ViewTelephonySimBinding sim = sims[slot];
        sim.editPhone.setText(phone);
        sim.editSimSerial.setText(simSerial);
        sim.editNetworkCountry.setText(networkCountry);
        sim.editNetworkOperator.setText(networkOperator);
        sim.editNetworkOperatorName.setText(networkOperatorName);
        sim.editSimCountry.setText(simCountry);
        sim.editSimOperator.setText(simOperator);
        sim.editSimOperatorName.setText(simOperatorName);
    }

    private void copyFirstToSecond() {
        ViewTelephonySimBinding first = sims[0];
        ViewTelephonySimBinding second = sims[1];
        second.editPhone.setText(text(first.editPhone));
        second.editSimSerial.setText(text(first.editSimSerial));
        second.editNetworkCountry.setText(text(first.editNetworkCountry));
        second.editNetworkOperator.setText(text(first.editNetworkOperator));
        second.editNetworkOperatorName.setText(text(first.editNetworkOperatorName));
        second.editSimCountry.setText(text(first.editSimCountry));
        second.editSimOperator.setText(text(first.editSimOperator));
        second.editSimOperatorName.setText(text(first.editSimOperatorName));
    }

    private void saveValues() {
        for (int slot = 0; slot < 2; slot++) {
            ViewTelephonySimBinding sim = sims[slot];
            Prefs.setString(Keys.telephony(Keys.TELEPHONY_PHONE, slot), text(sim.editPhone));
            Prefs.setString(Keys.telephony(Keys.TELEPHONY_SIM_SERIAL, slot), text(sim.editSimSerial));
            Prefs.setString(Keys.telephony(Keys.TELEPHONY_NETWORK_COUNTRY, slot), text(sim.editNetworkCountry));
            Prefs.setString(Keys.telephony(Keys.TELEPHONY_NETWORK_OPERATOR, slot), text(sim.editNetworkOperator));
            Prefs.setString(Keys.telephony(Keys.TELEPHONY_NETWORK_OPERATOR_NAME, slot), text(sim.editNetworkOperatorName));
            Prefs.setString(Keys.telephony(Keys.TELEPHONY_SIM_COUNTRY, slot), text(sim.editSimCountry));
            Prefs.setString(Keys.telephony(Keys.TELEPHONY_SIM_OPERATOR, slot), text(sim.editSimOperator));
            Prefs.setString(Keys.telephony(Keys.TELEPHONY_SIM_OPERATOR_NAME, slot), text(sim.editSimOperatorName));
        }
        Prefs.setString(Keys.TELEPHONY_PRESET, selectedPresetId);
        Prefs.setInt(Keys.TELEPHONY_DEFAULT_SLOT,
                binding.defaultSlotGroup.getCheckedChipId() == R.id.chip_slot_2 ? 1 : 0);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private static String text(android.widget.EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }
}
