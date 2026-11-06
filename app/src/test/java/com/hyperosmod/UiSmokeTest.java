package com.hyperosmod;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;

import androidx.appcompat.app.AppCompatActivity;

import com.hyperosmod.ui.BlockedAppsActivity;
import com.hyperosmod.ui.ColorPickerDialog;
import com.hyperosmod.ui.MainActivity;
import com.hyperosmod.ui.TelephonyPreset;
import com.hyperosmod.ui.TelephonySettingsActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** Smoke tests: the settings screens and the color dialog must inflate on a clean install. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class UiSmokeTest {

    @Test
    public void mainActivityInflates() {
        AppCompatActivity activity = Robolectric.buildActivity(MainActivity.class)
                .setup()
                .get();
        assertNotNull(activity);
        assertEquals("HyperOS Mod", activity.getString(R.string.app_name));
    }

    @Test
    public void blockedAppsActivityInflates() {
        AppCompatActivity activity = Robolectric.buildActivity(BlockedAppsActivity.class)
                .setup()
                .get();
        assertNotNull(activity);
    }

    @Test
    public void telephonySettingsInflates() {
        AppCompatActivity activity = Robolectric.buildActivity(TelephonySettingsActivity.class)
                .setup()
                .get();
        assertNotNull(activity);
        assertEquals(5, TelephonyPreset.PRESETS.size());
    }

    @Test
    public void colorPickerDialogInflates() {
        AppCompatActivity activity = Robolectric.buildActivity(MainActivity.class)
                .setup()
                .get();
        ColorPickerDialog.show(activity, 0xFF4C6FFF, color -> {
        });
        ShadowLooper.idleMainLooper();
    }
}
