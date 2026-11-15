package com.example.lockdowndpc.guard;

import android.app.admin.DevicePolicyManager;
import android.content.Context;

import com.example.lockdowndpc.BuildConfig;
import com.example.lockdowndpc.policy.AuditLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Coordinates pairing, signed policy sync, usage/audit upload, and call discovery. */
public final class GuardSyncManager {
    public enum Status {
        DISABLED, NOT_ENROLLED, KEY_NOT_PINNED, UNPAIRED, APPLIED, UP_TO_DATE, BUSY, ERROR
    }

    public static final class Outcome {
        public final Status status;
        public final String message;
        public final String pairingCode;
        public final long pairingExpiresAt;

        Outcome(Status status, String message, String pairingCode, long pairingExpiresAt) {
            this.status = status;
            this.message = message;
            this.pairingCode = pairingCode;
            this.pairingExpiresAt = pairingExpiresAt;
        }
    }

    private static final ReentrantLock LOCK = new ReentrantLock();

    private GuardSyncManager() {}

    public static Outcome enroll(Context context) {
        Context app = context.getApplicationContext();
        GuardRemoteClient client = new GuardRemoteClient(app);
        if (!client.isConfigured()) return outcome(Status.DISABLED, "Guard server is not configured");
        if (!LOCK.tryLock()) return outcome(Status.BUSY, "Another Guard sync is running");
        try {
            GuardRemoteClient.PairingInfo info = client.registerDevice();
            GuardRemoteClient.Metadata metadata = client.me();
            pinAndValidate(app, metadata);
            GuardRemoteStore.setPairingState(app, metadata.paired, metadata.confirmed,
                    metadata.parentEmail);
            GuardRemoteStore.markSynced(app);
            AuditLog.append(app, "guard-remote-enrolled");
            return new Outcome(Status.APPLIED, "Device registered; enter the pairing code in the parent app",
                    info.code, info.expiresAt);
        } catch (Exception exception) {
            return failure(app, exception);
        } finally {
            LOCK.unlock();
        }
    }

    public static Outcome sync(Context context) {
        Context app = context.getApplicationContext();
        GuardRemoteClient client = new GuardRemoteClient(app);
        if (!client.isConfigured()) return outcome(Status.DISABLED, "Guard server is not configured");
        String pinnedId = GuardRemoteStore.pinnedKeyId(app);
        String pinnedKey = GuardRemoteStore.pinnedPublicKey(app);
        if (pinnedId.isEmpty() || pinnedKey.isEmpty()) {
            return outcome(Status.KEY_NOT_PINNED, "Register this device or pin the policy key first");
        }
        if (!LOCK.tryLock()) return outcome(Status.BUSY, "Another Guard sync is running");
        try {
            GuardRemoteClient.Metadata metadata = client.me();
            GuardRemoteStore.setPairingState(app, metadata.paired, metadata.confirmed,
                    metadata.parentEmail);
            if (!metadata.paired) {
                return outcome(Status.UNPAIRED, "Waiting for a parent to claim this device");
            }
            if (!metadata.confirmed) {
                client.confirmPairing();
                GuardRemoteStore.setPairingState(app, true, true, metadata.parentEmail);
                AuditLog.append(app, "guard-remote-pairing-confirmed");
            }
            client.uploadApps(GuardAppInventory.collect(app));

            long accepted = GuardRemoteStore.acceptedCloudRevision(app);
            String envelope = client.fetchPolicy(accepted);
            Status status = Status.UP_TO_DATE;
            String message = metadata.latestRevision == accepted
                    ? "Policy is current" : "No newer signed policy is available";
            if (envelope != null) {
                GuardPolicyVerifier.Result verified;
                try {
                    verified = GuardPolicyVerifier.verify(envelope, pinnedId, pinnedKey, accepted);
                } catch (IllegalArgumentException exception) {
                    client.acknowledgePolicy(metadata.latestRevision, false,
                            java.util.Collections.singletonList(exception.getMessage()));
                    throw exception;
                }
                if (!GuardPolicyStore.save(app, verified.policy)) {
                    throw new IOException("Unable to persist the signed policy");
                }
                GuardRemoteStore.setAcceptedCloudRevision(app, verified.revision);
                GuardPolicyEnforcer.Result enforcement = GuardPolicyEnforcer.enforce(app);
                client.acknowledgePolicy(verified.revision, enforcement.verified(), enforcement.errors());
                AuditLog.append(app, "guard-remote-policy:" + verified.revision
                        + ":" + enforcement.verified());
                status = Status.APPLIED;
                message = enforcement.verified()
                        ? "Signed policy revision " + verified.revision + " applied"
                        : "Signed policy saved; enforcement needs attention";
            }

            try {
                long quizVersion = syncCloudQuizPack(app, client);
                if (quizVersion > 0) {
                    if (status == Status.UP_TO_DATE) status = Status.APPLIED;
                    message += "; signed quiz pack " + quizVersion + " applied";
                }
            } catch (Exception exception) {
                AuditLog.append(app, "guard-quiz-sync-failed:"
                        + exception.getClass().getSimpleName());
                message += "; quiz pack sync needs attention: " + safeMessage(exception);
            }

            if (applyParentGrants(app, client)) {
                GuardScheduler.schedule(app);
                GuardPolicyEnforcer.enforce(app);
                if (status == Status.UP_TO_DATE) status = Status.APPLIED;
                message = "Parent-issued temporary allowance updated";
            }

            uploadUsage(app, client);
            uploadAudit(app, client);
            try {
                GuardQuizResultOutbox.flush(app);
            } catch (Exception exception) {
                AuditLog.append(app, "guard-quiz-result-sync-failed:"
                        + exception.getClass().getSimpleName());
                message += "; quiz results remain queued: " + safeMessage(exception);
            }
            try {
                GuardHelpCoordinator.flush(app);
                GuardHelpCoordinator.poll(app);
            } catch (Exception exception) {
                AuditLog.append(app, "guard-help-sync-failed:"
                        + exception.getClass().getSimpleName());
                message += "; help requests need attention: " + safeMessage(exception);
            }
            try {
                GuardNoticeCoordinator.poll(app);
            } catch (Exception exception) {
                AuditLog.append(app, "guard-notice-sync-failed:"
                        + exception.getClass().getSimpleName());
                message += "; parent notices need attention: " + safeMessage(exception);
            }
            try {
                GuardManualQuizCoordinator.poll(app);
            } catch (Exception exception) {
                AuditLog.append(app, "guard-manual-quiz-sync-failed:"
                        + exception.getClass().getSimpleName());
                message += "; parent quiz requests need attention: " + safeMessage(exception);
            }
            GuardRemoteStore.markSynced(app);
            GuardCallCoordinator.wake(app);
            return outcome(status, message);
        } catch (Exception exception) {
            return failure(app, exception);
        } finally {
            LOCK.unlock();
        }
    }

    public static Outcome pinCurrentServerKey(Context context) {
        Context app = context.getApplicationContext();
        GuardRemoteClient client = new GuardRemoteClient(app);
        if (!client.isConfigured()) return outcome(Status.DISABLED, "Guard server is not configured");
        if (!LOCK.tryLock()) return outcome(Status.BUSY, "Another Guard sync is running");
        try {
            pinAndValidate(app, client.me());
            return outcome(Status.APPLIED, "Policy signing key pinned");
        } catch (Exception exception) {
            return failure(app, exception);
        } finally {
            LOCK.unlock();
        }
    }

    private static void pinAndValidate(Context context, GuardRemoteClient.Metadata metadata)
            throws IOException {
        if (metadata.policyKeyId.isEmpty() || metadata.policyPublicKey.isEmpty()) {
            throw new IOException("Server did not publish a policy signing key");
        }
        String configuredId = BuildConfig.GUARD_POLICY_KEY_ID;
        String configuredKey = BuildConfig.GUARD_POLICY_PUBLIC_KEY;
        if (!configuredId.isEmpty() || !configuredKey.isEmpty()) {
            if (!configuredId.equals(metadata.policyKeyId)
                    || !configuredKey.equals(metadata.policyPublicKey)) {
                throw new IOException("Server policy key does not match this Guard build");
            }
        }
        String existingId = GuardRemoteStore.pinnedKeyId(context);
        String existingKey = GuardRemoteStore.pinnedPublicKey(context);
        if (!existingId.isEmpty() && !existingKey.isEmpty()) {
            if (!existingId.equals(metadata.policyKeyId)
                    || !existingKey.equals(metadata.policyPublicKey)) {
                throw new IOException("Server policy key changed; an administrator must re-pin it");
            }
            return;
        }
        if (!GuardRemoteStore.pinPolicyKey(context, metadata.policyKeyId, metadata.policyPublicKey)) {
            throw new IOException("Policy signing key is invalid");
        }
    }

    private static long syncCloudQuizPack(Context context, GuardRemoteClient client)
            throws IOException, JSONException {
        try {
            QuizPackStore.TransitionResult transitions = QuizPackStore.applyKeyTransitions(
                    context, client.quizKeyTransitions());
            if (transitions.changed()) {
                AuditLog.append(context, "guard-quiz-key-transitions:"
                        + transitions.applied() + ":" + transitions.revoked()
                        + ":" + transitions.ignored());
            }
        } catch (Exception exception) {
            // A stale or malformed transition must not replace a valid local
            // pack. If the cloud key really changed, the pack import below will
            // fail closed and the next sync can retry the transition.
            AuditLog.append(context, "guard-quiz-key-transition-failed:"
                    + exception.getClass().getSimpleName());
        }

        QuizPackVerifier.VerifiedPack active = QuizPackStore.activePack(context);
        long currentVersion = active == null ? 0L : active.version();
        GuardRemoteClient.QuizPackBundle bundle = client.fetchQuizPack(currentVersion);
        if (bundle == null || bundle.version <= currentVersion) return 0L;
        ensureQuizTrust(context, bundle);

        if (bundle.deltaEnvelope != null && !bundle.deltaEnvelope.isEmpty()
                && bundle.baseVersion != null && bundle.baseVersion == currentVersion
                && active != null) {
            QuizPackStore.ImportResult delta = QuizPackStore.importDelta(
                    context, bundle.deltaEnvelope);
            AuditLog.append(context, "guard-quiz-delta-sync:" + delta.status()
                    + (delta.pack() == null ? "" : ":" + delta.pack().version()));
            if (delta.imported()) return delta.pack().version();
        }

        if (bundle.envelope != null && !bundle.envelope.isEmpty()) {
            QuizPackStore.ImportResult full = QuizPackStore.importSigned(context, bundle.envelope);
            AuditLog.append(context, "guard-quiz-sync:" + full.status()
                    + (full.pack() == null ? "" : ":" + full.pack().version()));
            if (full.imported()) return full.pack().version();
            if (full.status() != QuizPackStore.Status.NOT_NEWER) {
                throw new IOException("Cloud quiz pack was rejected: " + full.message());
            }
            return 0L;
        }

        // The server may return a delta-only response when it believes our base
        // version matches. If verification, trust or ordering rejects it, retry
        // without a base hint so the full signed pack remains an explicit
        // recovery path rather than leaving the device stranded.
        GuardRemoteClient.QuizPackBundle fallback = client.fetchQuizPack(0L);
        if (fallback == null || fallback.envelope == null || fallback.envelope.isEmpty()) {
            throw new IOException("Cloud quiz delta could not be applied and no full pack was available");
        }
        ensureQuizTrust(context, fallback);
        QuizPackStore.ImportResult full = QuizPackStore.importSigned(context, fallback.envelope);
        AuditLog.append(context, "guard-quiz-sync-fallback:" + full.status()
                + (full.pack() == null ? "" : ":" + full.pack().version()));
        if (full.imported()) return full.pack().version();
        if (full.status() == QuizPackStore.Status.NOT_NEWER) return 0L;
        throw new IOException("Cloud quiz pack was rejected: " + full.message());
    }

    private static void ensureQuizTrust(Context context, GuardRemoteClient.QuizPackBundle bundle)
            throws IOException {
        java.util.List<String> trusted = QuizPackStore.trustedKeys(context);
        if (trusted.isEmpty()) {
            String installed = QuizPackStore.installTrustAnchor(context, bundle.publicKey);
            if (!installed.equalsIgnoreCase(bundle.keyId)) {
                throw new IOException("Cloud quiz trust anchor does not match its key id");
            }
            AuditLog.append(context, "guard-quiz-trust-sync:" + installed);
        } else if (!QuizPackVerifier.isKeyTrusted(trusted, bundle.keyId)) {
            throw new IOException("Cloud quiz signing key is not trusted; "
                    + "add the new public key before rolling the signing key");
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }

    private static void uploadUsage(Context context, GuardRemoteClient client) throws IOException {
        GuardPolicy policy;
        try {
            policy = GuardPolicyStore.read(context);
        } catch (RuntimeException exception) {
            policy = GuardPolicy.disabled(ZoneId.systemDefault().getId());
        }
        Map<String, Long> usage = UsageLedger.today(context, policy.timezone());
        Map<String, Integer> minutes = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : usage.entrySet()) {
            long value = Math.max(0L, entry.getValue());
            minutes.put(entry.getKey(), (int) Math.min(1440L, (value + 59_999L) / 60_000L));
        }
        JSONObject health = new JSONObject();
        try {
            DevicePolicyManager manager = context.getSystemService(DevicePolicyManager.class);
            health.put("device_owner", manager != null
                    && manager.isDeviceOwnerApp(context.getPackageName()));
            health.put("usage_access", UsageLedger.hasUsageAccess(context));
            health.put("policy_enabled", policy.enabled());
            health.put("policy_revision", policy.revision());
            health.put("last_enforced_at", GuardPolicyStore.lastEnforcedAt(context));
            health.put("boot_count", GuardRewardStore.bootCount(context));
            health.put("suspended_packages", GuardPolicyStore.readSuspendedPackages(context).size());
        } catch (JSONException exception) {
            throw new IOException("Unable to encode device health", exception);
        }
        client.uploadUsage(LocalDate.now(ZoneId.of(policy.timezone())), minutes, health);
    }

    private static boolean applyParentGrants(Context context, GuardRemoteClient client)
            throws IOException, JSONException {
        boolean changed = false;
        for (GuardRemoteClient.GrantInfo grant : client.pendingGrants()) {
            if (GuardGrantStore.isApplied(context, grant.id)) {
                client.applyGrant(grant.id);
                continue;
            }
            boolean granted = GuardRewardStore.grantParentOverride(context, grant.packageName,
                    grant.minutes * 60_000L, grant.id);
            if (!GuardGrantStore.markApplied(context, grant.id)) {
                throw new IOException("Unable to persist parent grant receipt");
            }
            client.applyGrant(grant.id);
            AuditLog.append(context, "guard-parent-grant:" + grant.packageName + ":"
                    + grant.minutes + ":" + granted);
            changed = changed || granted;
        }
        for (GuardRemoteClient.GrantInfo grant : client.revokedGrants()) {
            if (!GuardRewardStore.revokeParentOverride(context, grant.packageName, grant.id)) {
                continue;
            }
            AuditLog.append(context, "guard-parent-grant-revoked:" + grant.packageName
                    + ":" + grant.id);
            changed = true;
        }
        return changed;
    }

    private static void uploadAudit(Context context, GuardRemoteClient client) throws IOException {
        long watermark = GuardRemoteStore.lastAuditUploadAt(context);
        List<AuditLog.Entry> entries = AuditLog.since(context, watermark);
        int start = Math.max(0, entries.size() - 20);
        long newest = watermark;
        for (int index = start; index < entries.size(); index++) {
            AuditLog.Entry entry = entries.get(index);
            client.uploadAudit("device.audit", entry.event);
            newest = Math.max(newest, entry.timestamp);
        }
        if (newest > watermark) GuardRemoteStore.markAuditUploaded(context, newest);
    }

    private static Outcome failure(Context context, Exception exception) {
        String message = exception instanceof GuardRemoteClient.RemoteException
                ? exception.getMessage()
                : exception.getClass().getSimpleName() + ": "
                    + (exception.getMessage() == null ? "sync failed" : exception.getMessage());
        GuardRemoteStore.recordError(context, message);
        AuditLog.append(context, "guard-remote-sync-failed:" + exception.getClass().getSimpleName());
        return outcome(Status.ERROR, message);
    }

    private static Outcome outcome(Status status, String message) {
        return new Outcome(status, message, "", 0L);
    }
}
