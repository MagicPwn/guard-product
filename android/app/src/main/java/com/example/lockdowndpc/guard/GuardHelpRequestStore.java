package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Durable offline outbox and reply cache for child-initiated help requests. */
public final class GuardHelpRequestStore {
    private static final String PREFS = "guard_help_requests";
    private static final String KEY_OUTBOX = "outbox";
    private static final String KEY_REQUESTS = "requests";
    private static final String KEY_LAST_SIGNATURE = "last_signature";
    private static final String KEY_LAST_AT = "last_at";
    private static final int MAX_OUTBOX = 8;
    private static final int MAX_REQUESTS = 32;
    private static final long DEDUPE_MILLIS = 2L * 60L * 1000L;
    private static final String CLIENT_PREFIX = "help-client-";

    private GuardHelpRequestStore() {}

    public static final class Pending {
        public final String clientRequestId;
        public final String targetPackage;
        public final String triggerId;
        public final String reason;
        public final String message;
        public final long createdAt;

        Pending(String clientRequestId, String targetPackage, String triggerId, String reason,
                String message, long createdAt) {
            this.clientRequestId = clientRequestId;
            this.targetPackage = targetPackage;
            this.triggerId = triggerId;
            this.reason = reason;
            this.message = message;
            this.createdAt = createdAt;
        }
    }

    public static final class Reply {
        public final String requestId;
        public final String targetPackage;
        public final String reason;
        public final String message;
        public final String parentReply;
        public final String status;
        public final boolean read;

        Reply(String requestId, String targetPackage, String reason, String message,
                String parentReply, String status, boolean read) {
            this.requestId = requestId;
            this.targetPackage = targetPackage;
            this.reason = reason;
            this.message = message;
            this.parentReply = parentReply;
            this.status = status;
            this.read = read;
        }
    }

    /** Returns a stable local id so a double tap cannot create two requests. */
    public static synchronized String enqueue(Context context, String targetPackage,
            String triggerId, String reason, String message) {
        String target = targetPackage == null ? "" : targetPackage.trim();
        if (!target.isEmpty()) GuardPolicy.requirePackage(target);
        String trigger = triggerId == null ? "" : triggerId.trim();
        if (!trigger.isEmpty()
                && !trigger.matches("(quiz-trigger|quiz-manual|quiz-budget)-[a-f0-9]{24}")) {
            throw new IllegalArgumentException("Help request trigger is invalid");
        }
        String normalizedReason = reason == null ? "blocked" : reason.trim().toLowerCase();
        if (!normalizedReason.matches("^[a-z][a-z0-9_]{0,31}$")) {
            throw new IllegalArgumentException("Help request reason is invalid");
        }
        String text = message == null ? "" : message.trim();
        if (text.length() > 500) {
            throw new IllegalArgumentException("Help message must be at most 500 characters");
        }
        SharedPreferences preferences = prefs(context);
        long now = System.currentTimeMillis();
        String signature = target + "\n" + trigger + "\n" + normalizedReason + "\n" + text;
        if (signature.equals(preferences.getString(KEY_LAST_SIGNATURE, ""))
                && now - preferences.getLong(KEY_LAST_AT, 0L) < DEDUPE_MILLIS) {
            List<Pending> pending = readOutbox(preferences);
            for (int index = pending.size() - 1; index >= 0; index--) {
                Pending item = pending.get(index);
                if ((item.targetPackage + "\n" + item.triggerId + "\n" + item.reason + "\n"
                        + item.message).equals(signature)) return item.clientRequestId;
            }
        }
        List<Pending> pending = readOutbox(preferences);
        if (pending.size() >= MAX_OUTBOX) {
            throw new IllegalStateException("Too many help requests are waiting to be sent");
        }
        String clientRequestId = CLIENT_PREFIX + UUID.randomUUID();
        pending.add(new Pending(clientRequestId, target, trigger, normalizedReason, text, now));
        boolean saved = writeOutbox(preferences, pending) && preferences.edit()
                .putString(KEY_LAST_SIGNATURE, signature).putLong(KEY_LAST_AT, now).commit();
        if (!saved) throw new IllegalStateException("Cannot persist the help request");
        return clientRequestId;
    }

    public static synchronized List<Pending> pending(Context context) {
        return Collections.unmodifiableList(readOutbox(prefs(context)));
    }

    public static synchronized boolean hasPending(Context context) {
        return !readOutbox(prefs(context)).isEmpty();
    }

    public static synchronized void markSent(Context context, String clientRequestId,
            GuardRemoteClient.HelpRequestInfo request) {
        SharedPreferences preferences = prefs(context);
        List<Pending> pending = readOutbox(preferences);
        pending.removeIf(item -> item.clientRequestId.equals(clientRequestId));
        if (!writeOutbox(preferences, pending)) {
            throw new IllegalStateException("Cannot update the help request outbox");
        }
        record(preferences, request);
    }

    public static synchronized void recordServer(Context context,
            List<GuardRemoteClient.HelpRequestInfo> requests) {
        if (requests == null) return;
        SharedPreferences preferences = prefs(context);
        for (GuardRemoteClient.HelpRequestInfo request : requests) record(preferences, request);
    }

    public static synchronized List<Reply> unreadReplies(Context context, String targetPackage) {
        List<Reply> replies = new ArrayList<>();
        for (Reply reply : readReplies(prefs(context))) {
            if (reply.read || reply.parentReply.isEmpty()) continue;
            if (!reply.targetPackage.isEmpty() && targetPackage != null
                    && !reply.targetPackage.equals(targetPackage)) continue;
            replies.add(reply);
        }
        return Collections.unmodifiableList(replies);
    }

    public static synchronized List<Reply> allReplies(Context context) {
        return Collections.unmodifiableList(readReplies(prefs(context)));
    }

    public static synchronized boolean markRead(Context context, String requestId) {
        SharedPreferences preferences = prefs(context);
        JSONArray array = readArray(preferences.getString(KEY_REQUESTS, "[]"));
        boolean changed = false;
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null || !requestId.equals(item.optString("id", ""))) continue;
            try {
                item.put("read", true);
                changed = true;
            } catch (JSONException ignored) {
            }
        }
        return changed && preferences.edit().putString(KEY_REQUESTS, array.toString()).commit();
    }

    private static void record(SharedPreferences preferences,
            GuardRemoteClient.HelpRequestInfo request) {
        JSONArray array = readArray(preferences.getString(KEY_REQUESTS, "[]"));
        JSONArray next = new JSONArray();
        boolean read = false;
        String previousReply = "";
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null || request.id.equals(item.optString("id", ""))) {
                if (item != null) {
                    read = item.optBoolean("read", false);
                    previousReply = item.optString("parent_reply", "");
                }
                continue;
            }
            if (next.length() < MAX_REQUESTS - 1) next.put(item);
        }
        if (!request.parentReply.equals(previousReply)) read = false;
        try {
            next.put(new JSONObject()
                    .put("id", request.id)
                    .put("client_request_id", request.clientRequestId)
                    .put("target_package", request.targetPackage)
                    .put("trigger_id", request.triggerId)
                    .put("reason", request.reason)
                    .put("message", request.message)
                    .put("status", request.status)
                    .put("parent_reply", request.parentReply)
                    .put("created_at", request.createdAt)
                    .put("expires_at", request.expiresAt)
                    .put("read", read));
            preferences.edit().putString(KEY_REQUESTS, next.toString()).commit();
        } catch (JSONException ignored) {
        }
    }

    private static List<Pending> readOutbox(SharedPreferences preferences) {
        List<Pending> result = new ArrayList<>();
        JSONArray array = readArray(preferences.getString(KEY_OUTBOX, "[]"));
        for (int index = 0; index < array.length() && index < MAX_OUTBOX; index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) continue;
            String clientId = item.optString("client_request_id", "");
            String target = item.optString("target_package", "");
            String trigger = item.optString("trigger_id", "");
            String reason = item.optString("reason", "");
            String message = item.optString("message", "");
            if (!clientId.matches("^help-client-[a-f0-9-]{36}$")
                    || !reason.matches("^[a-z][a-z0-9_]{0,31}$")
                    || message.length() > 500) continue;
            try {
                if (!target.isEmpty()) GuardPolicy.requirePackage(target);
            } catch (IllegalArgumentException exception) {
                continue;
            }
            result.add(new Pending(clientId, target, trigger, reason, message,
                    item.optLong("created_at", 0L)));
        }
        return result;
    }

    private static List<Reply> readReplies(SharedPreferences preferences) {
        List<Reply> result = new ArrayList<>();
        JSONArray array = readArray(preferences.getString(KEY_REQUESTS, "[]"));
        for (int index = 0; index < array.length() && index < MAX_REQUESTS; index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) continue;
            String id = item.optString("id", "");
            String status = item.optString("status", "");
            if (!id.matches("^help-[a-f0-9-]{36}$")
                    || !status.matches("^(pending|answered|acknowledged|expired)$")) continue;
            result.add(new Reply(id, item.optString("target_package", ""),
                    item.optString("reason", ""), item.optString("message", ""),
                    item.optString("parent_reply", ""), status, item.optBoolean("read", false)));
        }
        return result;
    }

    private static boolean writeOutbox(SharedPreferences preferences, List<Pending> pending) {
        JSONArray array = new JSONArray();
        for (Pending item : pending) {
            try {
                array.put(new JSONObject()
                        .put("client_request_id", item.clientRequestId)
                        .put("target_package", item.targetPackage)
                        .put("trigger_id", item.triggerId)
                        .put("reason", item.reason)
                        .put("message", item.message)
                        .put("created_at", item.createdAt));
            } catch (JSONException exception) {
                return false;
            }
        }
        return preferences.edit().putString(KEY_OUTBOX, array.toString()).commit();
    }

    private static JSONArray readArray(String value) {
        try {
            return value == null || value.trim().isEmpty() ? new JSONArray() : new JSONArray(value);
        } catch (JSONException exception) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
