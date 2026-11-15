package com.example.lockdowndpc.guard;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.telecom.TelecomManager;

import com.example.lockdowndpc.admin.LockdownAdminReceiver;
import com.example.lockdowndpc.policy.LockdownPackages;
import com.example.lockdowndpc.policy.PolicyReconciliationCoordinator;
import com.example.lockdowndpc.recovery.EmergencyAccessController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GuardPolicyEnforcer {
    private GuardPolicyEnforcer() {}

    public static Result enforce(Context context) {
        return PolicyReconciliationCoordinator.callOnPolicyThread(() -> {
            try {
                return enforceOnPolicyThread(context.getApplicationContext());
            } catch (RuntimeException exception) {
                return new Result(false, GuardPolicyStore.readSuspendedPackages(context),
                        Collections.singletonList("Enforcement failed: " + exception.getClass().getSimpleName()));
            }
        });
    }

    private static Result enforceOnPolicyThread(Context context) {
        GuardPolicy policy = GuardPolicyStore.read(context);
        DevicePolicyManager manager = context.getSystemService(DevicePolicyManager.class);
        if (manager == null || !manager.isDeviceOwnerApp(context.getPackageName())) {
            return new Result(false, GuardPolicyStore.readSuspendedPackages(context),
                    Collections.singletonList("Device Owner is not configured"));
        }
        ComponentName admin = LockdownAdminReceiver.componentName(context);
        Set<String> previous = GuardPolicyStore.readSuspendedPackages(context);
        List<String> errors = new ArrayList<>();
        boolean emergencyActive = EmergencyAccessController.read(context).active();
        boolean usageAvailable = emergencyActive || !policy.enabled() || policy.dailyBudgets().isEmpty()
                || UsageLedger.hasUsageAccess(context);
        Map<String, Long> usage = !emergencyActive && usageAvailable && policy.enabled()
                ? UsageLedger.today(context, policy.timezone()) : Collections.emptyMap();
        Set<String> desired = emergencyActive
                ? new HashSet<>()
                : GuardPolicyEngine.blockedPackages(policy, usage,
                        GuardRewardStore.active(context).keySet());
        if (!usageAvailable) {
            errors.add("Usage Access unavailable; existing time-limit restrictions retained");
            for (String packageName : previous) {
                if (policy.dailyBudgets().containsKey(packageName)) desired.add(packageName);
            }
        }
        desired.removeAll(protectedPackages(context));
        Set<String> tracked = new HashSet<>(previous);
        for (String packageName : previous) {
            if (desired.contains(packageName)) continue;
            if (!isInstalled(context, packageName)) {
                tracked.remove(packageName);
                continue;
            }
            if (setSuspended(manager, admin, packageName, false, errors)) {
                tracked.remove(packageName);
            }
        }
        for (String packageName : desired) {
            if (!isInstalled(context, packageName)) {
                errors.add("Target is not installed: " + packageName);
                continue;
            }
            if (!tracked.contains(packageName)) {
                tracked.add(packageName);
                if (!GuardPolicyStore.writeSuspendedPackages(context, tracked)) {
                    errors.add("Cannot persist recovery state: " + packageName);
                    tracked.remove(packageName);
                    continue;
                }
            } else {
                try {
                    if (manager.isPackageSuspended(admin, packageName)) continue;
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
            setSuspended(manager, admin, packageName, true, errors);
        }
        if (!GuardPolicyStore.writeSuspendedPackages(context, tracked)) {
            errors.add("Cannot persist suspension recovery state");
        }
        GuardLauncherController.Result home = GuardLauncherController.apply(
                context, !emergencyActive && policy.launcherEnabled());
        if (!home.verified()) errors.addAll(home.errors());
        if (errors.isEmpty() && !GuardPolicyStore.markEnforced(context)) {
            errors.add("Cannot persist enforcement timestamp");
        }
        return new Result(errors.isEmpty(), tracked, errors);
    }

    /**
     * Releases every suspension this DPC recorded, regardless of the policy's
     * current rules. Used when an administrator deliberately gives up Device
     * Owner management: the release must be proved before Android ownership is
     * cleared, because afterwards this app can no longer undo a missed step.
     */
    public static Result releaseAll(Context context) {
        return PolicyReconciliationCoordinator.callOnPolicyThread(
                () -> releaseAllOnPolicyThread(context.getApplicationContext()));
    }

    private static Result releaseAllOnPolicyThread(Context context) {
        DevicePolicyManager manager = context.getSystemService(DevicePolicyManager.class);
        if (manager == null || !manager.isDeviceOwnerApp(context.getPackageName())) {
            return new Result(false, GuardPolicyStore.readSuspendedPackages(context),
                    Collections.singletonList("Device Owner is not configured"));
        }
        ComponentName admin = LockdownAdminReceiver.componentName(context);
        List<String> errors = new ArrayList<>();
        Set<String> tracked = new HashSet<>(GuardPolicyStore.readSuspendedPackages(context));
        for (String packageName : new HashSet<>(tracked)) {
            if (!isInstalled(context, packageName)) {
                tracked.remove(packageName);
                continue;
            }
            if (setSuspended(manager, admin, packageName, false, errors)) {
                tracked.remove(packageName);
            }
        }
        if (!GuardPolicyStore.writeSuspendedPackages(context, tracked)) {
            errors.add("Cannot persist suspension release state");
            tracked.addAll(GuardPolicyStore.readSuspendedPackages(context));
        }
        GuardLauncherController.Result home = GuardLauncherController.apply(context, false);
        if (!home.verified()) errors.addAll(home.errors());
        return new Result(errors.isEmpty(), tracked, errors);
    }

    public static Set<String> protectedPackages(Context context) {
        Set<String> result = new HashSet<>(LockdownPackages.ESSENTIAL_SYSTEM);
        result.add(context.getPackageName());
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        if (telecom != null && telecom.getDefaultDialerPackage() != null) {
            result.add(telecom.getDefaultDialerPackage());
        }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo activity : context.getPackageManager().queryIntentActivities(home, 0)) {
            result.add(activity.activityInfo.packageName);
        }
        return result;
    }

    private static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
    }

    private static boolean setSuspended(DevicePolicyManager manager, ComponentName admin,
            String packageName, boolean suspended, List<String> errors) {
        try {
            String[] failed = manager.setPackagesSuspended(admin, new String[]{packageName}, suspended);
            if (failed != null && failed.length != 0) {
                errors.add("Suspension request rejected: " + packageName);
                return false;
            }
            if (manager.isPackageSuspended(admin, packageName) != suspended) {
                errors.add("Suspension readback differs: " + packageName);
                return false;
            }
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
            errors.add("Suspension failed: " + packageName + " (" + exception.getClass().getSimpleName() + ")");
            return false;
        }
    }

    public static final class Result {
        private final boolean verified;
        private final Set<String> suspended;
        private final List<String> errors;

        private Result(boolean verified, Set<String> suspended, List<String> errors) {
            this.verified = verified;
            this.suspended = Collections.unmodifiableSet(new HashSet<>(suspended));
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        }

        public boolean verified() { return verified; }
        public Set<String> suspended() { return suspended; }
        public List<String> errors() { return errors; }
    }
}
