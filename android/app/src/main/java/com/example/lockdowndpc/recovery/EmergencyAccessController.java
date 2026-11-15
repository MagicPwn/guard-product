package com.example.lockdowndpc.recovery;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import com.example.lockdowndpc.guard.GuardPolicyEnforcer;
import com.example.lockdowndpc.policy.AuditLog;
import com.example.lockdowndpc.policy.PolicyReconciliationCoordinator;
import com.example.lockdowndpc.security.AdminSession;

/**
 * Persists and enforces a short, visible exception to Guard's app limits.
 *
 * <p>This is intentionally narrower than pausing all protection. It releases
 * Guard's own suspension policy for a bounded period, records the exception in
 * the administrator audit log, and lets the existing enforcement pass put every
 * restriction back when the window ends. The window is never extended by a
 * reboot or by moving the clock backwards.
 */
public final class EmergencyAccessController {
    private static final String PREFS = "guard_emergency_access";
    private static final String KEY_STARTED_ELAPSED = "started_elapsed";
    private static final String KEY_EXPIRES_ELAPSED = "expires_elapsed";
    private static final String KEY_STARTED_WALL = "started_wall";
    private static final String KEY_BOOT_COUNT = "boot_count";
    private static final String ACTION_EXPIRE = "com.example.lockdowndpc.action.EXPIRE_EMERGENCY";
    private static final int ALARM_REQUEST_CODE = 8411;

    private EmergencyAccessController() {}

    public record Snapshot(EmergencyAccessWindow.Status status, long remainingMillis,
            long expiresAtWallMillis) {
        public boolean active() {
            return status == EmergencyAccessWindow.Status.ACTIVE;
        }
    }

    public static Snapshot read(Context context) {
        Context app = context.getApplicationContext();
        EmergencyAccessWindow.Window window = readWindow(app);
        if (window == null) {
            return new Snapshot(EmergencyAccessWindow.Status.MISSING, 0L, 0L);
        }
        EmergencyAccessWindow.Evaluation evaluation = EmergencyAccessWindow.evaluate(
                window,
                SystemClock.elapsedRealtime(),
                System.currentTimeMillis(),
                currentBootCount(app)
        );
        if (!evaluation.active()) {
            clearStored(app);
            cancelAlarm(app);
            AuditLog.append(app, "guard-emergency-ended:" + evaluation.status().name().toLowerCase());
            return new Snapshot(evaluation.status(), 0L, 0L);
        }
        return new Snapshot(
                evaluation.status(),
                evaluation.remainingMillis(),
                window.startedWallMillis() + (window.expiresElapsedMillis() - window.startedElapsedMillis())
        );
    }

    /**
     * Starts an emergency window and immediately verifies that the existing
     * Guard enforcement pass released its suspensions.
     */
    public static Snapshot start(Context context, long durationMillis) {
        if (!AdminSession.isUnlocked()) {
            throw new SecurityException("admin-session-required");
        }
        Context app = context.getApplicationContext();
        ensureDeviceOwner(app);
        EmergencyAccessWindow.Window window = EmergencyAccessWindow.start(
                SystemClock.elapsedRealtime(),
                System.currentTimeMillis(),
                currentBootCount(app),
                durationMillis
        );
        boolean stored = prefs(app).edit()
                .putLong(KEY_STARTED_ELAPSED, window.startedElapsedMillis())
                .putLong(KEY_EXPIRES_ELAPSED, window.expiresElapsedMillis())
                .putLong(KEY_STARTED_WALL, window.startedWallMillis())
                .putInt(KEY_BOOT_COUNT, window.bootCount())
                .commit();
        if (!stored) {
            throw new IllegalStateException("emergency-window-not-saved");
        }
        scheduleAlarm(app, window.startedWallMillis()
                + (window.expiresElapsedMillis() - window.startedElapsedMillis()));
        AuditLog.append(app, "guard-emergency-started:"
                + Math.max(1L, durationMillis / 60_000L) + "m");

        GuardPolicyEnforcer.Result enforcement = GuardPolicyEnforcer.enforce(app);
        if (!enforcement.verified()) {
            clearStored(app);
            cancelAlarm(app);
            GuardPolicyEnforcer.enforce(app);
            AuditLog.append(app, "guard-emergency-start-failed");
            String detail = enforcement.errors().isEmpty()
                    ? "Guard enforcement could not be verified"
                    : enforcement.errors().get(0);
            throw new IllegalStateException(detail);
        }
        return read(app);
    }

    /** Ends a window early and re-applies the ordinary policy immediately. */
    public static Snapshot end(Context context, String reason) {
        Context app = context.getApplicationContext();
        boolean hadWindow = readWindow(app) != null;
        clearStored(app);
        cancelAlarm(app);
        if (hadWindow) {
            AuditLog.append(app, "guard-emergency-ended:"
                    + (reason == null || reason.isBlank() ? "administrator" : reason));
        }
        GuardPolicyEnforcer.enforce(app);
        return new Snapshot(EmergencyAccessWindow.Status.MISSING, 0L, 0L);
    }

    /**
     * Removes the exception without re-applying Guard. Used only by the Device
     * Owner release path, which is already withdrawing every policy.
     */
    public static void clearForRelease(Context context) {
        Context app = context.getApplicationContext();
        boolean hadWindow = readWindow(app) != null;
        clearStored(app);
        cancelAlarm(app);
        if (hadWindow) {
            AuditLog.append(app, "guard-emergency-ended:ownership-release");
        }
    }

    static void expireIfDue(Context context) {
        Context app = context.getApplicationContext();
        Snapshot snapshot = read(app);
        if (snapshot.active()) {
            EmergencyAccessWindow.Window window = readWindow(app);
            if (window != null) {
                scheduleAlarm(app, window.startedWallMillis()
                        + (window.expiresElapsedMillis() - window.startedElapsedMillis()));
            }
            return;
        }
        GuardPolicyEnforcer.enforce(app);
    }

    private static void ensureDeviceOwner(Context context) {
        DevicePolicyManager manager = context.getSystemService(DevicePolicyManager.class);
        if (manager == null || !manager.isDeviceOwnerApp(context.getPackageName())) {
            throw new IllegalStateException("device-owner-required");
        }
    }

    private static EmergencyAccessWindow.Window readWindow(Context context) {
        SharedPreferences prefs = prefs(context);
        long startedElapsed = prefs.getLong(KEY_STARTED_ELAPSED, -1L);
        long expiresElapsed = prefs.getLong(KEY_EXPIRES_ELAPSED, -1L);
        long startedWall = prefs.getLong(KEY_STARTED_WALL, -1L);
        if (startedElapsed < 0L || expiresElapsed < 0L || startedWall <= 0L) {
            return null;
        }
        return new EmergencyAccessWindow.Window(
                startedElapsed,
                expiresElapsed,
                startedWall,
                prefs.getInt(KEY_BOOT_COUNT, -1)
        );
    }

    private static void clearStored(Context context) {
        prefs(context).edit().clear().commit();
    }

    private static int currentBootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | RuntimeException unavailable) {
            return -1;
        }
    }

    private static void scheduleAlarm(Context context, long triggerAtWallMillis) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        try {
            alarms.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    Math.max(System.currentTimeMillis() + 1_000L, triggerAtWallMillis),
                    expireIntent(context)
            );
        } catch (RuntimeException ignored) {
            // The visible monitor samples every few seconds and is the fallback.
        }
    }

    private static void cancelAlarm(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        try {
            alarms.cancel(expireIntent(context));
        } catch (RuntimeException ignored) {
        }
    }

    private static PendingIntent expireIntent(Context context) {
        Intent intent = new Intent(context, EmergencyAccessReceiver.class).setAction(ACTION_EXPIRE);
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
