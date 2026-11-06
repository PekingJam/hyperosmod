package com.hyperosmod;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.provider.Settings;

import com.hyperosmod.ui.PermissionHelper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** MIUI force-enables DND access when Mi Fitness has notification-listener access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class PermissionHelperTest {

    @Test
    public void notificationListenerAccessCountsAsDndAccess() {
        Context context = RuntimeEnvironment.getApplication();
        Settings.Secure.putString(context.getContentResolver(),
                "enabled_notification_policy_access_packages", "");
        Settings.Secure.putString(context.getContentResolver(),
                "enabled_notification_listeners",
                "com.mi.health/.notification.MiHealthNotificationListener");

        assertTrue(PermissionHelper.hasMiFitnessDndAccess(context));
    }
}
