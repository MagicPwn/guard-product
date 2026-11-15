package com.example.lockdowndpc.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.format.DateFormat;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.lockdowndpc.R;
import com.example.lockdowndpc.admin.LockdownAdminReceiver;
import com.example.lockdowndpc.policy.AuditLog;
import com.example.lockdowndpc.policy.PolicyReconciliationCoordinator;
import com.example.lockdowndpc.recovery.EmergencyAccessController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * Polls the usage ledger while the managed device is in use.
 *
 * <p>Android has no callback for "a budget was exhausted". A periodic job is
 * also allowed to run only every 15 minutes, which is too coarse for screen
 * time. While Guard policy is enabled on a provisioned device this foreground
 * service samples every few seconds and re-applies the verified suspension
 * policy. The persistent notification is deliberately visible: enforcement is
 * transparent to the child and to the device owner.
 */
public final class GuardMonitorService extends Service {
    public static final String CHANNEL_ID = "guard_monitor";
    public static final int NOTIFICATION_ID = 8202;
    private static final long SAMPLE_MILLIS = 10_000L;
    private static final long NOTICE_POLL_MILLIS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private long lastNoticePollAt;
    private String lastNotificationText;
    private final Runnable sample = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                PolicyReconciliationCoordinator.runOnPolicyThread(() ->
                        samplePolicyAndTriggers(getApplicationContext()));
            } catch (RuntimeException ignored) {
            }
            refreshNotification();
            try {
                GuardCallCoordinator.wake(getApplicationContext());
            } catch (RuntimeException ignored) {
            }
            long now = System.currentTimeMillis();
            if (now - lastNoticePollAt >= NOTICE_POLL_MILLIS) {
                lastNoticePollAt = now;
                GuardNoticeCoordinator.pollAsync(getApplicationContext());
                GuardManualQuizCoordinator.pollAsync(getApplicationContext());
            }
            handler.postDelayed(this, SAMPLE_MILLIS);
        }
    };

    private static void samplePolicyAndTriggers(Context app) {
        if (EmergencyAccessController.read(app).active()) {
            GuardPolicyEnforcer.enforce(app);
            return;
        }
        String foreground = null;
        GuardQuizRequestPlanner.Plan plan = null;
        try {
            GuardPolicy policy = GuardPolicyStore.read(app);
            GuardQuizRequestCoordinator.prune(app, policy);
            LocalDateTime localTime = LocalDateTime.now(ZoneId.of(policy.timezone()));
            Map<String, Long> usage = policy.enabled()
                    ? UsageLedger.today(app, policy.timezone()) : Collections.emptyMap();
            Set<String> ignored = GuardPolicyEnforcer.protectedPackages(app);
            foreground = UsageLedger.foregroundPackage(app, ignored);
            long now = System.currentTimeMillis();
            GuardUsageReminderEngine.State reminderState = GuardUsageReminderStore.readState(app);
            GuardUsageReminderEngine.Reminder reminder = GuardUsageReminderEngine.evaluate(
                    policy, usage, foreground, localTime, now, reminderState);
            if (!GuardUsageReminderStore.writeState(app, reminderState)) {
                AuditLog.append(app, "guard-usage-reminder-state-failed");
            }
            if (reminder != null) GuardUsageReminderCoordinator.notify(app, reminder);
            GuardQuizTriggerEngine.State state = GuardQuizRequestStore.readState(app);
            Map<String, Long> rewards = GuardRewardStore.active(app);
            plan = GuardQuizRequestPlanner.plan(policy, usage,
                    foreground, localTime, now, rewards.keySet(), state,
                    QuizAttemptStore.canBegin(app, policy));
            if (!GuardQuizRequestStore.writeState(app, state)) {
                AuditLog.append(app, "guard-quiz-trigger-state-failed");
            }
            if (plan.trigger() != null) {
                for (String target : plan.triggerTargets()) {
                    GuardQuizRequestCoordinator.request(app, policy, plan.trigger().id(), target);
                }
            }
            if (plan.budgetTarget() != null) {
                GuardQuizRequestCoordinator.requestBudgetExhausted(
                        app, policy, plan.budgetTarget());
            }
        } catch (RuntimeException exception) {
            AuditLog.append(app, "guard-quiz-trigger-failed:"
                    + exception.getClass().getSimpleName());
        }
        boolean taskRaisedForForeground = foreground != null && plan != null
                && (foreground.equals(plan.budgetTarget())
                || plan.triggerTargets().contains(foreground));
        // Suspending first makes Android replace the task with its admin dialog.
        // Defer for one sample so the task is visible; enforcement then applies
        // even if the child closes the task without completing it.
        if (!taskRaisedForForeground) GuardPolicyEnforcer.enforce(app);
    }

    public static void startIfNeeded(Context context) {
        Context application = context.getApplicationContext();
        boolean shouldRun;
        try {
            DevicePolicyManager manager = application.getSystemService(DevicePolicyManager.class);
            boolean owner = manager != null
                    && manager.isDeviceOwnerApp(application.getPackageName());
            boolean enabled;
            try {
                enabled = GuardPolicyStore.read(application).enabled();
            } catch (RuntimeException exception) {
                enabled = true;
            }
            shouldRun = owner && enabled;
            if (shouldRun) grantNotificationPermission(application, manager);
        } catch (RuntimeException exception) {
            shouldRun = false;
        }
        Intent service = new Intent(application, GuardMonitorService.class);
        try {
            if (shouldRun) {
                ContextCompat.startForegroundService(application, service);
            } else {
                application.stopService(service);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void grantNotificationPermission(Context context, DevicePolicyManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            manager.setPermissionGrantState(LockdownAdminReceiver.componentName(context),
                    context.getPackageName(), "android.permission.POST_NOTIFICATIONS",
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        handler.removeCallbacks(sample);
        refreshNotification();
        GuardQuizRequestCoordinator.ensureVisible(this);
        handler.post(sample);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.guard_monitor_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.guard_monitor_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification notification() {
        Intent open = new Intent(this, GuardActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.guard_monitor_title))
                .setContentText(notificationText())
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private CharSequence notificationText() {
        EmergencyAccessController.Snapshot emergency = EmergencyAccessController.read(this);
        if (emergency.active()) {
            String expiresAt = (String) DateFormat.getTimeFormat(this)
                    .format(new Date(emergency.expiresAtWallMillis()));
            return getString(R.string.guard_monitor_emergency, expiresAt);
        }
        return getString(R.string.guard_monitor_text);
    }

    private void refreshNotification() {
        String text = notificationText().toString();
        if (text.equals(lastNotificationText)) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        lastNotificationText = text;
        try {
            manager.notify(NOTIFICATION_ID, notification());
        } catch (RuntimeException ignored) {
        }
    }
}
