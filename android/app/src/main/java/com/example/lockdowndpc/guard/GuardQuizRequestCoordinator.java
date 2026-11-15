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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Raises a visible, lock-screen-capable prompt for a persisted quiz request. */
public final class GuardQuizRequestCoordinator {
    public static final String EXTRA_REQUEST_ID = "guard_quiz_request_id";
    public static final String CHANNEL_ID = "guard_quiz_prompts";

    private GuardQuizRequestCoordinator() {}

    public static void ensureVisible(Context context) {
        Context app = context.getApplicationContext();
        for (GuardQuizRequestStore.Request request : GuardQuizRequestStore.active(app)) {
            if (!request.notified()) {
                notifyPrompt(app, request);
            }
        }
    }

    /** Drops prompts whose signed rule or policy revision is no longer current. */
    public static void prune(Context context, GuardPolicy policy) {
        if (policy == null) return;
        for (GuardQuizRequestStore.Request request : GuardQuizRequestStore.active(context)) {
            GuardPolicy.QuizTrigger trigger = null;
            for (GuardPolicy.QuizTrigger candidate : policy.quizTriggers()) {
                if (candidate.id().equals(request.triggerId())) {
                    trigger = candidate;
                    break;
                }
            }
            boolean validTarget;
            if (request.budgetExhausted()) {
                validTarget = policy.dailyBudgets().containsKey(request.targetPackage())
                        && !policy.blockedPackages().contains(request.targetPackage());
            } else if (request.manual()) {
                validTarget = policy.managesPackage(request.targetPackage())
                        && !policy.blockedPackages().contains(request.targetPackage());
            } else {
                validTarget = trigger != null
                        && trigger.packages().contains(request.targetPackage());
            }
            if (!policy.enabled() || !policy.quizGateEnabled()
                    || policy.revision() != request.policyRevision()
                    || !validTarget) {
                cancel(context, request.id());
            }
        }
    }

    public static GuardQuizRequestStore.Request request(Context context, GuardPolicy policy,
            String triggerId, String targetPackage) {
        GuardQuizRequestStore.Request request = GuardQuizRequestStore.create(
                context, triggerId, targetPackage, policy.revision());
        notifyPrompt(context, request);
        launch(context, request);
        AuditLog.append(context, "guard-quiz-request:"
                + (request.manual() ? "manual:" : "") + triggerId + ":" + targetPackage);
        return request;
    }

    /** Raises the full-screen task page once when a signed daily budget is exhausted. */
    public static GuardQuizRequestStore.Request requestBudgetExhausted(Context context,
            GuardPolicy policy, String targetPackage) {
        GuardPolicy.requirePackage(targetPackage);
        Integer budget = policy.dailyBudgets().get(targetPackage);
        if (budget == null) {
            throw new IllegalArgumentException("Package has no daily budget");
        }
        String triggerId = budgetTriggerId(policy.revision(), targetPackage, budget);
        GuardQuizRequestStore.Request request = GuardQuizRequestStore.create(
                context, triggerId, targetPackage, policy.revision());
        notifyPrompt(context, request);
        launch(context, request);
        AuditLog.append(context, "guard-quiz-request:budget:" + targetPackage);
        return request;
    }

    private static String budgetTriggerId(long revision, String targetPackage, int budgetMinutes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (revision + "\n" + targetPackage + "\n" + budgetMinutes)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(24);
            for (int index = 0; index < 12; index++) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", digest[index]));
            }
            return "quiz-budget-" + result;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static Intent intent(Context context, String requestId) {
        return new Intent(context, GuardQuizActivity.class)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    public static boolean cancel(Context context, String requestId) {
        GuardQuizRequestStore.remove(context, requestId);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(notificationId(requestId));
        return true;
    }

    private static void notifyPrompt(Context context, GuardQuizRequestStore.Request request) {
        createChannel(context);
        Intent open = intent(context, request.id());
        PendingIntent pending = PendingIntent.getActivity(context, notificationId(request.id()),
                open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(context.getString(R.string.guard_quiz_prompt_title))
                .setContentText(context.getString(R.string.guard_quiz_prompt_text))
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setTimeoutAfter(GuardQuizRequestStore.MAX_AGE_MILLIS)
                .build();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.notify(notificationId(request.id()), notification);
        GuardQuizRequestStore.markNotified(context, request.id());
    }

    private static void launch(Context context, GuardQuizRequestStore.Request request) {
        if (request.started()) return;
        try {
            context.startActivity(intent(context, request.id()));
        } catch (RuntimeException exception) {
            AuditLog.append(context, "guard-quiz-launch-blocked:"
                    + exception.getClass().getSimpleName());
        }
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.guard_quiz_prompt_channel),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.guard_quiz_prompt_channel_description));
        channel.setShowBadge(true);
        manager.createNotificationChannel(channel);
    }

    private static int notificationId(String requestId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(requestId.getBytes(StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
                    | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
            return 0x50000000 | (value & 0x0fffffff);
        } catch (Exception exception) {
            return 0x50000001;
        }
    }
}
