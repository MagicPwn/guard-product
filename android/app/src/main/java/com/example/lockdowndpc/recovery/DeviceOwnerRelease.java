package com.example.lockdowndpc.recovery;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;

import com.example.lockdowndpc.guard.GuardMonitorService;
import com.example.lockdowndpc.guard.GuardPolicyEnforcer;
import com.example.lockdowndpc.kiosk.KioskConfigStore;
import com.example.lockdowndpc.maintenance.MaintenanceStore;
import com.example.lockdowndpc.policy.AuditLog;
import com.example.lockdowndpc.policy.LockdownPolicyController;
import com.example.lockdowndpc.policy.PolicyReconciliationCoordinator;
import com.example.lockdowndpc.security.AdminSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered, verified exit from Device Owner management.
 *
 * <p>Ownership is cleared only after this app has proved that Guard's
 * suspensions and the managed application policy are gone. If any release step
 * cannot be verified, the owner is left in place so the administrator can retry
 * instead of being stranded with a device that no longer has the privilege
 * needed to undo a half-finished release.
 */
public final class DeviceOwnerRelease {
    private DeviceOwnerRelease() {}

    public record Result(boolean released, boolean restrictionsRestored, List<String> errors) {
        public Result {
            errors = List.copyOf(errors);
        }
    }

    public static Result release(Context context) {
        if (!AdminSession.isUnlocked()) {
            return failure("admin-session-required", false);
        }
        Context app = context.getApplicationContext();
        return PolicyReconciliationCoordinator.callOnPolicyThread(
                () -> releaseOnPolicyThread(app));
    }

    private static Result releaseOnPolicyThread(Context context) {
        DevicePolicyManager manager = context.getSystemService(DevicePolicyManager.class);
        if (manager == null || !manager.isDeviceOwnerApp(context.getPackageName())) {
            return failure("device-owner-required", false);
        }
        if (MaintenanceStore.liveWindow(context) != null || MaintenanceStore.restorePending(context)) {
            return failure("close-maintenance-first", false);
        }
        if (KioskConfigStore.requiresContainment(context)) {
            return failure("exit-kiosk-first", false);
        }

        GuardPolicyEnforcer.Result guard = GuardPolicyEnforcer.releaseAll(context);
        if (!guard.verified()) {
            return failureWithDetails("guard-release-unverified", guard.errors(), false);
        }

        LockdownPolicyController.PolicyResult policy = LockdownPolicyController.pause(context);
        if (!policy.deviceOwner() || !policy.applied()) {
            return failureWithDetails("managed-policy-release-unverified", policy.errors(), false);
        }

        KioskConfigStore.clear(context);
        EmergencyAccessController.clearForRelease(context);
        context.stopService(new Intent(context, GuardMonitorService.class));

        try {
            manager.clearDeviceOwnerApp(context.getPackageName());
        } catch (RuntimeException exception) {
            return failure("clear-device-owner:" + exception.getClass().getSimpleName(), true);
        }
        if (manager.isDeviceOwnerApp(context.getPackageName())) {
            return failure("device-owner-still-active", true);
        }
        AuditLog.append(context, "device-owner-released");
        return new Result(true, true, Collections.emptyList());
    }

    private static Result failure(String reason, boolean restored) {
        return failureWithDetails(reason, Collections.emptyList(), restored);
    }

    private static Result failureWithDetails(
            String reason,
            List<String> details,
            boolean restored
    ) {
        ArrayList<String> errors = new ArrayList<>();
        errors.add(reason);
        if (details != null) errors.addAll(details);
        return new Result(false, restored, errors);
    }
}
