package com.example.lockdowndpc.guard;

import android.content.Context;

import com.example.lockdowndpc.policy.AuditLog;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls authenticated parent-initiated quiz requests.
 *
 * <p>The server can only ask for a prompt. The device still evaluates its
 * locally verified policy, reward, cooldown, daily-attempt and quiz-pack state
 * before creating the same durable request used by automatic triggers.
 */
public final class GuardManualQuizCoordinator {
    private static final AtomicBoolean POLLING = new AtomicBoolean();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "guard-manual-quiz-poll");
        thread.setDaemon(true);
        return thread;
    });

    private GuardManualQuizCoordinator() {}

    public static void pollAsync(Context context) {
        Context app = context.getApplicationContext();
        if (!POLLING.compareAndSet(false, true)) return;
        EXECUTOR.execute(() -> {
            try {
                poll(app);
            } catch (IOException | JSONException | RuntimeException exception) {
                AuditLog.append(app, "guard-manual-quiz-poll-failed:"
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
        List<GuardRemoteClient.ManualQuizTriggerInfo> pending =
                client.pendingManualQuizTriggers();
        if (pending.isEmpty()) return;

        GuardPolicy policy = null;
        String policyError = null;
        try {
            policy = GuardPolicyStore.read(context);
        } catch (RuntimeException exception) {
            policyError = "policy_unavailable";
        }
        Set<String> protectedPackages = GuardPolicyEnforcer.protectedPackages(context);
        for (GuardRemoteClient.ManualQuizTriggerInfo trigger : pending) {
            try {
                String rejection = rejection(context, policy, policyError, protectedPackages,
                        trigger.packageName);
                if (rejection != null) {
                    client.acknowledgeManualQuizTrigger(trigger.id, "rejected", rejection);
                    AuditLog.append(context, "guard-manual-quiz-rejected:"
                            + trigger.packageName + ":" + rejection);
                    continue;
                }
                GuardQuizRequestCoordinator.request(context, policy, trigger.id,
                        trigger.packageName);
                client.acknowledgeManualQuizTrigger(trigger.id, "accepted", "");
                AuditLog.append(context, "guard-manual-quiz-accepted:" + trigger.packageName);
            } catch (RuntimeException exception) {
                AuditLog.append(context, "guard-manual-quiz-failed:"
                        + exception.getClass().getSimpleName());
                try {
                    client.acknowledgeManualQuizTrigger(trigger.id, "rejected", "device_error");
                } catch (IOException | JSONException ignored) {
                    // The next poll retries the same idempotent request.
                }
            }
        }
    }

    private static String rejection(Context context, GuardPolicy policy, String policyError,
            Set<String> protectedPackages, String packageName) {
        if (policyError != null) return policyError;
        if (policy == null || !policy.enabled() || !policy.quizGateEnabled()) {
            return "quiz_gate_disabled";
        }
        if (policy.isAlwaysAllowed(packageName)) return "package_always_allowed";
        if (!policy.managesPackage(packageName)) return "package_not_managed";
        if (policy.blockedPackages().contains(packageName)
                || protectedPackages.contains(packageName)) return "package_protected";
        if (GuardRewardStore.isActive(context, packageName)) return "reward_active";
        if (!QuizAttemptStore.canBegin(context, policy)) return "quiz_limit_or_cooldown";
        try {
            if (QuizPackStore.questionsFor(context, policy).isEmpty()) {
                return "quiz_pack_unavailable";
            }
        } catch (RuntimeException exception) {
            return "quiz_pack_unavailable";
        }
        return null;
    }
}
