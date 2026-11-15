package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.lockdowndpc.policy.AuditLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Durable at-least-once delivery for quiz results, idempotent at the server. */
public final class GuardQuizResultOutbox {
    private static final String PREFS = "guard_quiz_result_outbox";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 100;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "guard-quiz-result-upload");
        thread.setDaemon(true);
        return thread;
    });

    private GuardQuizResultOutbox() {}

    public static synchronized boolean enqueue(Context context, JSONObject payload) {
        if (payload == null) return false;
        try {
            List<JSONObject> items = read(context);
            String attemptId = payload.getString("attempt_id");
            for (JSONObject item : items) {
                if (attemptId.equals(item.optString("attempt_id", ""))) return true;
            }
            items.add(new JSONObject(payload.toString()));
            while (items.size() > MAX_ITEMS) items.remove(0);
            return write(context, items);
        } catch (JSONException exception) {
            return false;
        }
    }

    public static void flushAsync(Context context) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                flush(app);
            } catch (IOException | JSONException exception) {
                AuditLog.append(app, "guard-quiz-result-upload-failed:"
                        + exception.getClass().getSimpleName());
            }
        });
    }

    /** Returns the number delivered; failures keep the corresponding item queued. */
    public static int flush(Context context) throws IOException, JSONException {
        if (!GuardRemoteStore.isConfirmed(context)) return 0;
        GuardRemoteClient client = new GuardRemoteClient(context);
        if (!client.isConfigured()) return 0;
        int delivered = 0;
        for (JSONObject item : pending(context)) {
            String attemptId = item.getString("attempt_id");
            try {
                client.uploadQuizResult(item);
                remove(context, attemptId);
                delivered++;
            } catch (GuardRemoteClient.RemoteException exception) {
                boolean permanent = exception.status >= 400 && exception.status < 500
                        && exception.status != 401 && exception.status != 403
                        && exception.status != 429;
                if (!permanent) throw exception;
                remove(context, attemptId);
                AuditLog.append(context, "guard-quiz-result-rejected:"
                        + exception.status + ":" + attemptId);
            }
        }
        if (delivered > 0) AuditLog.append(context, "guard-quiz-result-uploaded:" + delivered);
        return delivered;
    }

    public static synchronized List<JSONObject> pending(Context context) {
        return read(context);
    }

    public static synchronized boolean remove(Context context, String attemptId) {
        List<JSONObject> items = read(context);
        boolean removed = items.removeIf(item -> attemptId.equals(item.optString("attempt_id", "")));
        return removed && write(context, items);
    }

    private static List<JSONObject> read(Context context) {
        List<JSONObject> result = new ArrayList<>();
        String raw = prefs(context).getString(KEY_ITEMS, "");
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length() && index < MAX_ITEMS; index++) {
                result.add(new JSONObject(array.getJSONObject(index).toString()));
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    private static boolean write(Context context, List<JSONObject> items) {
        JSONArray array = new JSONArray();
        for (JSONObject item : items) array.put(item);
        return prefs(context).edit().putString(KEY_ITEMS, array.toString()).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
