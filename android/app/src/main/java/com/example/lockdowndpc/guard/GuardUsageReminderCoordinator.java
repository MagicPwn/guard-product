package com.example.lockdowndpc.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.example.lockdowndpc.R;
import com.example.lockdowndpc.policy.AuditLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Creates child-visible local reminders that never change signed policy enforcement. */
public final class GuardUsageReminderCoordinator {
    public static final String CHANNEL_ID = "guard_usage_reminders";
    public static final String EXTRA_REASON = "guard_usage_reminder_reason";
    public static final String EXTRA_PACKAGE = "guard_usage_reminder_package";
    public static final String EXTRA_LABEL = "guard_usage_reminder_label";
    public static final String EXTRA_MINUTES = "guard_usage_reminder_minutes";

    private GuardUsageReminderCoordinator() {}

    public static void notify(Context context, GuardUsageReminderEngine.Reminder reminder) {
        if (reminder == null) return;
        Context app = context.getApplicationContext();
        createChannel(app);
        String label = appLabel(app, reminder.packageName());
        GuardUsageReminderEngine.Reason reason = reminder.reason();
        String title = reason == GuardUsageReminderEngine.Reason.BUDGET_WARNING
                ? app.getString(R.string.guard_usage_budget_title, label)
                : app.getString(R.string.guard_usage_continuous_title);
        String body = reason == GuardUsageReminderEngine.Reason.BUDGET_WARNING
                ? app.getString(R.string.guard_usage_budget_body, label, reminder.minutes())
                : app.getString(R.string.guard_usage_continuous_body, label, reminder.minutes());

        Intent open = new Intent(app, GuardUsageReminderActivity.class)
                .putExtra(EXTRA_REASON, reason.name())
                .putExtra(EXTRA_PACKAGE, reminder.packageName())
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_MINUTES, reminder.minutes())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int id = notificationId(reason.name(), reminder.packageName());
        PendingIntent pending = PendingIntent.getActivity(app, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build();
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.notify(id, notification);
        AuditLog.append(app, "guard-usage-reminder:" + reason.name().toLowerCase()
                + ":" + reminder.packageName());
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.guard_usage_reminder_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(
                R.string.guard_usage_reminder_channel_description));
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);
    }

    private static String appLabel(Context context, String packageName) {
        try {
            PackageManager manager = context.getPackageManager();
            CharSequence label = manager.getApplicationLabel(
                    manager.getApplicationInfo(packageName, 0));
            if (label != null && label.length() > 0) return label.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return packageName;
    }

    private static int notificationId(String reason, String packageName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((reason + "|" + packageName).getBytes(StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
            return 0x61000000 | (value & 0x0fffffff);
        } catch (Exception exception) {
            return 0x61000001;
        }
    }
}
