package com.example.lockdowndpc.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import com.example.lockdowndpc.R;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Polls only for visible, child-consented call invitations. */
public final class GuardCallCoordinator {
    public static final String ACTION_ACCEPT = "com.example.lockdowndpc.guard.CALL_ACCEPT";
    public static final String ACTION_DECLINE = "com.example.lockdowndpc.guard.CALL_DECLINE";
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_CALL_KIND = "call_kind";
    public static final String EXTRA_AUTO_ACCEPT = "auto_accept";
    private static final String CHANNEL = "guard_calls";
    private static final long POLL_INTERVAL_MS = 7_000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean POLLING = new AtomicBoolean();
    private static final Map<String, Integer> NOTIFICATIONS = new HashMap<>();
    private static volatile long lastPollElapsed;
    private static volatile String activeCallId;

    private GuardCallCoordinator() {}

    public static void wake(Context context) {
        Context app = context.getApplicationContext();
        if (!GuardRemoteStore.isConfirmed(app)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastPollElapsed < POLL_INTERVAL_MS) return;
        if (!POLLING.compareAndSet(false, true)) return;
        lastPollElapsed = now;
        EXECUTOR.execute(() -> {
            try {
                poll(app);
            } catch (Exception exception) {
                GuardRemoteStore.recordError(app,
                        "Call polling failed: " + exception.getClass().getSimpleName());
            } finally {
                POLLING.set(false);
            }
        });
    }

    public static void respond(Context context, String callId, boolean accept, Runnable completion) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                new GuardRemoteClient(app).respondToCall(callId, accept);
                if (!accept) cancel(app, callId);
                GuardRemoteStore.markSynced(app);
            } catch (Exception exception) {
                GuardRemoteStore.recordError(app,
                        "Call response failed: " + exception.getMessage());
            } finally {
                if (completion != null) completion.run();
            }
        });
    }

    public static void setActiveCall(String callId) {
        activeCallId = callId;
    }

    public static void clearActiveCall(String callId) {
        if (callId != null && callId.equals(activeCallId)) activeCallId = null;
    }

    public static void cancel(Context context, String callId) {
        Integer id = NOTIFICATIONS.remove(callId);
        if (id != null) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(id);
        }
    }

    public static Intent callIntent(Context context, String callId, String kind,
            boolean autoAccept) {
        Class<?> target = "screenshot".equals(kind)
                ? GuardScreenshotActivity.class : GuardCallActivity.class;
        return new Intent(context, target)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_CALL_KIND, kind)
                .putExtra(EXTRA_AUTO_ACCEPT, autoAccept)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private static void poll(Context context) {
        if (!GuardRemoteStore.isConfirmed(context)) return;
        GuardRemoteClient client = new GuardRemoteClient(context);
        if (!client.isConfigured()) return;
        try {
            List<GuardRemoteClient.CallInfo> calls = client.pendingCalls();
            Set<String> pending = new HashSet<>();
            for (GuardRemoteClient.CallInfo call : calls) {
                pending.add(call.id);
                if (!call.id.equals(activeCallId)) show(context, call);
            }
            for (String known : new HashSet<>(NOTIFICATIONS.keySet())) {
                if (!pending.contains(known)) cancel(context, known);
            }
            GuardRemoteStore.markSynced(context);
        } catch (Exception exception) {
            GuardRemoteStore.recordError(context,
                    "Call polling failed: " + exception.getMessage());
        }
    }

    private static void show(Context context, GuardRemoteClient.CallInfo call) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        createChannel(manager, context);
        int notificationId = notificationId(call.id);
        NOTIFICATIONS.put(call.id, notificationId);

        Intent decline = new Intent(context, GuardCallActionReceiver.class)
                .setAction(ACTION_DECLINE)
                .putExtra(EXTRA_CALL_ID, call.id)
                .putExtra(EXTRA_CALL_KIND, call.kind);
        PendingIntent declineIntent = PendingIntent.getBroadcast(context,
                notificationId + 1, decline, PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        Intent accept = new Intent(context, GuardCallActionReceiver.class)
                .setAction(ACTION_ACCEPT)
                .putExtra(EXTRA_CALL_ID, call.id)
                .putExtra(EXTRA_CALL_KIND, call.kind);
        PendingIntent acceptIntent = PendingIntent.getBroadcast(context,
                notificationId + 2, accept, PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent open = PendingIntent.getActivity(context, notificationId,
                callIntent(context, call.id, call.kind, false),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(R.string.guard_call_incoming_title))
                .setContentText(context.getString(R.string.guard_call_incoming_text,
                        kindLabel(context, call.kind)))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOngoing(true)
                .setTimeoutAfter(120_000L)
                .addAction(0, context.getString(R.string.guard_call_accept), acceptIntent)
                .addAction(0, context.getString(R.string.guard_call_reject), declineIntent)
                .build();
        manager.notify(notificationId, notification);
    }

    private static void createChannel(NotificationManager manager, Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.guard_call_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.guard_call_channel_description));
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);
    }

    private static String kindLabel(Context context, String kind) {
        if ("video".equals(kind)) return context.getString(R.string.guard_call_kind_video);
        if ("screen".equals(kind)) return context.getString(R.string.guard_call_kind_screen);
        if ("screenshot".equals(kind)) return context.getString(R.string.guard_call_kind_screenshot);
        return context.getString(R.string.guard_call_kind_audio);
    }

    private static int notificationId(String callId) {
        return 0x21000000 | (callId.hashCode() & 0x00ffffff);
    }
}
