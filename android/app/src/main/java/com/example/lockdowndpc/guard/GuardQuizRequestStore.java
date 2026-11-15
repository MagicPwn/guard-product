package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Durable authorization for an automatically raised quiz gate.
 *
 * <p>An activity extra is not an authorization boundary. A request is created
 * only after the signed policy evaluates to a trigger, then survives process
 * death and can be reopened from the visible prompt notification.
 */
public final class GuardQuizRequestStore {
    private static final String PREFS = "guard_quiz_requests";
    private static final String KEY_STATE = "trigger_state";
    private static final String KEY_REQUESTS = "requests";
    private static final int MAX_REQUESTS = 32;
    private static final String TRIGGER_PATTERN = "(quiz-trigger|quiz-manual|quiz-budget)-[a-f0-9]{24}";
    private static final String MANUAL_PREFIX = "quiz-manual-";
    private static final String BUDGET_PREFIX = "quiz-budget-";

    /** A stale prompt must not turn into an indefinite bypass. */
    public static final long MAX_AGE_MILLIS = 30L * 60L * 1000L;

    private GuardQuizRequestStore() {}

    public static final class Request {
        private final String id;
        private final String triggerId;
        private final String targetPackage;
        private final long policyRevision;
        private final long createdAt;
        private final long expiresAt;
        private final boolean started;
        private final boolean notified;
        private final String attemptId;

        private Request(String id, String triggerId, String targetPackage, long policyRevision,
                long createdAt, long expiresAt, boolean started, boolean notified,
                String attemptId) {
            this.id = id;
            this.triggerId = triggerId;
            this.targetPackage = targetPackage;
            this.policyRevision = policyRevision;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.started = started;
            this.notified = notified;
            this.attemptId = attemptId;
        }

        public String id() { return id; }
        public String triggerId() { return triggerId; }
        public String targetPackage() { return targetPackage; }
        public long policyRevision() { return policyRevision; }
        public long createdAt() { return createdAt; }
        public long expiresAt() { return expiresAt; }
        public boolean started() { return started; }
        public boolean notified() { return notified; }
        public String attemptId() { return attemptId; }

        public boolean isExpired(long now) {
            return expiresAt <= now;
        }

        /** Manual requests come from an authenticated parent, not a signed rule. */
        public boolean manual() { return isManual(triggerId); }

        /** The signed daily budget was exhausted while this app was in the foreground. */
        public boolean budgetExhausted() { return isBudgetExhausted(triggerId); }

        private Request started(String attemptId, long revision) {
            return new Request(id, triggerId, targetPackage, revision, createdAt, expiresAt,
                    true, true, attemptId);
        }

        private Request withNotified() {
            return new Request(id, triggerId, targetPackage, policyRevision, createdAt, expiresAt,
                    started, true, attemptId);
        }

        private JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("trigger_id", triggerId)
                    .put("target_package", targetPackage).put("policy_revision", policyRevision)
                    .put("created_at", createdAt).put("expires_at", expiresAt)
                    .put("started", started).put("notified", notified)
                    .put("attempt_id", attemptId == null ? "" : attemptId);
        }

        private static Request fromJson(JSONObject json) throws JSONException {
            String id = json.getString("id");
            String triggerId = json.getString("trigger_id");
            String targetPackage = json.getString("target_package");
            if (!id.matches("quiz-request-[a-f0-9-]{36}")
                    || !triggerId.matches(TRIGGER_PATTERN)) {
                throw new IllegalArgumentException("Stored quiz request id is invalid");
            }
            GuardPolicy.requirePackage(targetPackage);
            String attemptId = json.optString("attempt_id", "");
            if (!attemptId.isEmpty() && !attemptId.matches("^[A-Za-z0-9._:-]{8,128}$")) {
                throw new IllegalArgumentException("Stored quiz attempt id is invalid");
            }
            return new Request(id, triggerId, targetPackage, json.getLong("policy_revision"),
                    json.getLong("created_at"), json.getLong("expires_at"),
                    json.optBoolean("started", false), json.optBoolean("notified", false),
                    attemptId);
        }
    }

    public static synchronized GuardQuizTriggerEngine.State readState(Context context) {
        return GuardQuizTriggerEngine.State.fromJson(
                prefs(context).getString(KEY_STATE, ""));
    }

    public static synchronized boolean writeState(Context context,
            GuardQuizTriggerEngine.State state) {
        if (state == null) return false;
        return prefs(context).edit().putString(KEY_STATE, state.toJson()).commit();
    }

    public static synchronized List<Request> active(Context context) {
        long now = System.currentTimeMillis();
        List<Request> requests = readRequests(context);
        boolean changed = requests.removeIf(request -> request.isExpired(now));
        if (changed) writeRequests(context, requests);
        return Collections.unmodifiableList(requests);
    }

    public static synchronized Request findActive(Context context, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Request request : active(context)) {
            if (request.id().equals(id)) return request;
        }
        return null;
    }

    public static synchronized Request create(Context context, String triggerId,
            String targetPackage, long policyRevision) {
        GuardPolicy.requirePackage(targetPackage);
        if (triggerId == null || !triggerId.matches(TRIGGER_PATTERN)) {
            throw new IllegalArgumentException("Quiz trigger id is invalid");
        }
        long now = System.currentTimeMillis();
        List<Request> requests = readRequests(context);
        requests.removeIf(request -> request.isExpired(now));
        for (Request existing : requests) {
            if (existing.triggerId().equals(triggerId)
                    && existing.targetPackage().equals(targetPackage)
                    && existing.policyRevision() == policyRevision) {
                return existing;
            }
        }
        Request request = new Request("quiz-request-" + UUID.randomUUID(),
                triggerId, targetPackage, policyRevision, now, now + MAX_AGE_MILLIS,
                false, false, "");
        requests.add(request);
        requests.sort(Comparator.comparingLong(Request::createdAt));
        while (requests.size() > MAX_REQUESTS) requests.remove(0);
        if (!writeRequests(context, requests)) {
            throw new IllegalStateException("Unable to persist quiz request");
        }
        return request;
    }

    public static synchronized boolean markStarted(Context context, String id,
            String attemptId, long policyRevision) {
        if (attemptId == null || !attemptId.matches("^[A-Za-z0-9._:-]{8,128}$")) return false;
        List<Request> requests = readRequests(context);
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (int index = 0; index < requests.size(); index++) {
            Request request = requests.get(index);
            if (!request.id().equals(id) || request.isExpired(now)) continue;
            requests.set(index, request.started(attemptId, policyRevision));
            changed = true;
        }
        return changed && writeRequests(context, requests);
    }

    public static synchronized boolean markNotified(Context context, String id) {
        List<Request> requests = readRequests(context);
        boolean changed = false;
        for (int index = 0; index < requests.size(); index++) {
            Request request = requests.get(index);
            if (!request.id().equals(id)) continue;
            requests.set(index, request.withNotified());
            changed = true;
        }
        return changed && writeRequests(context, requests);
    }

    public static synchronized boolean remove(Context context, String id) {
        List<Request> requests = readRequests(context);
        boolean removed = requests.removeIf(request -> request.id().equals(id));
        return removed && writeRequests(context, requests);
    }

    private static List<Request> readRequests(Context context) {
        List<Request> result = new ArrayList<>();
        String raw = prefs(context).getString(KEY_REQUESTS, "");
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length() && index < MAX_REQUESTS; index++) {
                try {
                    result.add(Request.fromJson(array.getJSONObject(index)));
                } catch (IllegalArgumentException | JSONException ignored) {
                }
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    private static boolean writeRequests(Context context, List<Request> requests) {
        try {
            JSONArray array = new JSONArray();
            for (Request request : requests) array.put(request.toJson());
            return prefs(context).edit().putString(KEY_REQUESTS, array.toString()).commit();
        } catch (JSONException exception) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isManual(String triggerId) {
        return triggerId != null && triggerId.startsWith(MANUAL_PREFIX);
    }

    public static boolean isBudgetExhausted(String triggerId) {
        return triggerId != null && triggerId.startsWith(BUDGET_PREFIX);
    }
}
