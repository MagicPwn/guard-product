package com.example.lockdowndpc.guard;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import com.example.lockdowndpc.admin.LockdownAdminReceiver;
import com.example.lockdowndpc.kiosk.KioskController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Installs Guard as the managed HOME surface when the signed policy requests it. */
public final class GuardLauncherController {
    private static final String ACTIVITY = GuardLauncherActivity.class.getName();

    private GuardLauncherController() {}

    public static Result apply(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        DevicePolicyManager manager = app.getSystemService(DevicePolicyManager.class);
        if (manager == null || !manager.isDeviceOwnerApp(app.getPackageName())) {
            return new Result(false, Collections.singletonList("Device Owner is not configured"));
        }
        if (KioskController.isActive(app)) {
            return new Result(true, Collections.emptyList());
        }
        ComponentName admin = LockdownAdminReceiver.componentName(app);
        ComponentName activity = new ComponentName(app.getPackageName(), ACTIVITY);
        List<String> errors = new ArrayList<>();
        try {
            if (enabled) {
                app.getPackageManager().setComponentEnabledSetting(activity,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
                filter.addCategory(Intent.CATEGORY_HOME);
                filter.addCategory(Intent.CATEGORY_DEFAULT);
                manager.addPersistentPreferredActivity(admin, filter, activity);
            } else {
                manager.clearPackagePersistentPreferredActivities(admin, app.getPackageName());
                app.getPackageManager().setComponentEnabledSetting(activity,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
        } catch (RuntimeException exception) {
            errors.add("guard-home:" + exception.getClass().getSimpleName());
        }
        return new Result(errors.isEmpty(), errors);
    }

    public static final class Result {
        private final boolean verified;
        private final List<String> errors;

        Result(boolean verified, List<String> errors) {
            this.verified = verified;
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        }

        public boolean verified() { return verified; }
        public List<String> errors() { return errors; }
    }
}
