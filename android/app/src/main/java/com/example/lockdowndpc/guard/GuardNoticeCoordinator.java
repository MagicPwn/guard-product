package com.example.lockdowndpc.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.example.lockdowndpc.R;
import com.example.lockdowndpc.policy.AuditLog;

import org.json.JSONException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polls parent notices and keeps one visible, acknowledgeable notification per notice. */
public final class GuardNoticeCoordinator {
    public static final String CHANNEL_ID = "guard_notices";
    public static final String EXTRA_NOTICE_ID = "guard_notice_id";
    public static final String ACTION_ACK = "com.example.lockdowndpc.guard.ACK_NOTICE";
    private static final AtomicBoolean POLLING = new AtomicBoolean();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "guard-notice-poll");
        thread.setDaemon(true);
        return thread;
    });

    private GuardNoticeCoordinator() {}

    public static void pollAsync(Context context) {
        Context app = context.getApplicationContext();
        if (!POLLING.compareAndSet(false, true)) return;
        EXECUTOR.execute(() -> {
            try {
                poll(app);
            } catch (IOException | JSONException | RuntimeException exception) {
                AuditLog.append(app, "guard-notice-poll-failed:"
                        + exception.getClass().getSimpleName());
            } finally {
                POLLING.set(false);
            }
        });
    }

    public static synchronized void poll(Context context) throws IOException, JSONException {
        if (!GuardRemoteStore.isConfirmed(context)) return;
        GuardRemoteClient client = new GuardRemoteClient(context);
        if (!client.isConfigured()) return;
        List<GuardRemoteClient.NoticeInfo> remote = client.pendingNotices();
        List<GuardNoticeStore.Notice> notices = new ArrayList<>();
        Set<String> pendingIds = new HashSet<>();
        for (GuardRemoteClient.NoticeInfo info : remote) {
            GuardNoticeStore.Notice notice = new GuardNoticeStore.Notice(info.id,
                    info.kind, info.message, info.expiresAt);
            notices.add(notice);
            pendingIds.add(notice.id());
        }
        Set<String> previouslyShown = GuardNoticeStore.shown(context);
        if (!GuardNoticeStore.replace(context, notices)) {
            throw new IOException("Unable to persist parent notices");
        }
        for (String shownId : previouslyShown) {
            if (!pendingIds.contains(shownId)) {
                cancel(context, shownId);
                GuardNoticeStore.clearShown(context, shownId);
            }
        }
        for (GuardNoticeStore.Notice notice : notices) {
            if (previouslyShown.contains(notice.id())) continue;
            notifyNotice(context, notice);
            GuardNoticeStore.markShown(context, notice.id());
        }
    }

    public static synchronized boolean acknowledge(Context context, String noticeId) {
        if (noticeId == null || !noticeId.matches("^[A-Za-z0-9._:-]{1,128}$")) return false;
        try {
            new GuardRemoteClient(context).acknowledgeNotice(noticeId);
            GuardNoticeStore.remove(context, noticeId);
            GuardNoticeStore.clearShown(context, noticeId);
            cancel(context, noticeId);
            AuditLog.append(context, "guard-notice-acknowledged:" + noticeId);
            return true;
        } catch (IOException exception) {
            AuditLog.append(context, "guard-notice-ack-failed:"
                    + exception.getClass().getSimpleName());
            return false;
        }
    }

    public static void acknowledgeAsync(Context context, String noticeId, Runnable completion) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            boolean acknowledged = acknowledge(app, noticeId);
            if (completion != null) completion.run();
        });
    }

    private static void notifyNotice(Context context, GuardNoticeStore.Notice notice) {
        createChannel(context);
        Intent open = new Intent(context, GuardNoticeActivity.class)
                .putExtra(EXTRA_NOTICE_ID, notice.id())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(context, notificationId(notice.id()),
                open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent ack = new Intent(context, GuardNoticeActionReceiver.class)
                .setAction(ACTION_ACK).putExtra(EXTRA_NOTICE_ID, notice.id());
        PendingIntent ackPending = PendingIntent.getBroadcast(context,
                notificationId(notice.id()) + 1, ack,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean callback = "callback".equals(notice.kind());
        String body = notice.message().isEmpty()
                ? context.getString(callback ? R.string.guard_notice_callback_body
                        : R.string.guard_notice_task_body)
                : notice.message();
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(callback
                        ? R.string.guard_notice_callback_title
                        : R.string.guard_notice_task_title))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.guard_notice_ack), ackPending)
                .build();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(notificationId(notice.id()), notification);
    }

    private static void cancel(Context context, String noticeId) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(notificationId(noticeId));
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.guard_notice_channel),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.guard_notice_channel_description));
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);
    }

    private static int notificationId(String noticeId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(noticeId.getBytes(StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
            return 0x60000000 | (value & 0x0fffffff);
        } catch (Exception exception) {
            return 0x60000001;
        }
    }
}
