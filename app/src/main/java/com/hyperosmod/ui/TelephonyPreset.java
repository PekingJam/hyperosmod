package com.hyperosmod.ui;

import java.util.Arrays;
import java.util.List;

/** Ready-made telephony profiles for common regions. */
public final class TelephonyPreset {
    public final String id;
    public final String label;
    public final String phone;
    public final String simSerial;
    public final String networkCountry;
    public final String networkOperator;
    public final String networkOperatorName;
    public final String simCountry;
    public final String simOperator;
    public final String simOperatorName;

    private TelephonyPreset(String id, String label, String phone, String simSerial,
                            String networkCountry, String networkOperator,
                            String networkOperatorName, String simCountry,
                            String simOperator, String simOperatorName) {
        this.id = id;
        this.label = label;
        this.phone = phone;
        this.simSerial = simSerial;
        this.networkCountry = networkCountry;
        this.networkOperator = networkOperator;
        this.networkOperatorName = networkOperatorName;
        this.simCountry = simCountry;
        this.simOperator = simOperator;
        this.simOperatorName = simOperatorName;
    }

    public static final List<TelephonyPreset> PRESETS = Arrays.asList(
            new TelephonyPreset("us", "美国 / United States",
                    "+12025550147", "89014103211111111111",
                    "us", "310260", "T-Mobile",
                    "us", "310260", "T-Mobile"),
            new TelephonyPreset("sg", "新加坡 / Singapore",
                    "+6581234567", "89650123456789012345",
                    "sg", "52501", "Singtel",
                    "sg", "52501", "Singtel"),
            new TelephonyPreset("hk", "中国香港 / Hong Kong",
                    "+85291234567", "89852012345678901234",
                    "hk", "45400", "CSL",
                    "hk", "45400", "CSL"),
            new TelephonyPreset("tw", "中国台湾 / Taiwan",
                    "+886912345678", "89886012345678901234",
                    "tw", "46692", "Chunghwa Telecom",
                    "tw", "46692", "Chunghwa Telecom"),
            new TelephonyPreset("jp", "日本 / Japan",
                    "+819012345678", "89811012345678901234",
                    "jp", "44010", "NTT DOCOMO",
                    "jp", "44010", "NTT DOCOMO")
    );

    public static TelephonyPreset byId(String id) {
        for (TelephonyPreset preset : PRESETS) {
            if (preset.id.equals(id)) {
                return preset;
            }
        }
        return PRESETS.get(0);
    }
}
