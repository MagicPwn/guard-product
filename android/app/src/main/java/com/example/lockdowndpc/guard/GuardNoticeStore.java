package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Locally cached parent notices and the visible notifications already raised. */
public final class GuardNoticeStore {
    private static final String PREFS = "guard_notices";
    private static final String KEY_PENDING = "pending";
    private static final String KEY_SHOWN = "shown";
    private static final int MAX_NOTICES = 50;

    private GuardNoticeStore() {}

    public static final class Notice {
        private final String id;
        private final String kind;
        private final String message;
        private final String expiresAt;

        public Notice(String id, String kind, String message, String expiresAt) {
            if (id == null || !id.matches("^[A-Za-z0-9._:-]{1,128}$")
                    || kind == null || !kind.matches("^[a-z]{1,32}$")) {
                throw new IllegalArgumentException("Notice is invalid");
            }
            this.id = id;
            this.kind = kind;
            this.message = message == null ? "" : message;
            this.expiresAt = expiresAt == null ? "" : expiresAt;
        }

        public String id() { return id; }
        public String kind() { return kind; }
        public String message() { return message; }
        public String expiresAt() { return expiresAt; }

        private JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("kind", kind)
                    .put("message", message).put("expires_at", expiresAt);
        }

        private static Notice fromJson(JSONObject json) throws JSONException {
            return new Notice(json.getString("id"), json.getString("kind"),
                    json.optString("message", ""), json.optString("expires_at", ""));
        }
    }

    public static synchronized List<Notice> pending(Context context) {
        List<Notice> result = new ArrayList<>();
        String raw = prefs(context).getString(KEY_PENDING, "");
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length() && index < MAX_NOTICES; index++) {
                try {
                    result.add(Notice.fromJson(array.getJSONObject(index)));
                } catch (IllegalArgumentException | JSONException ignored) {
                }
            }
        } catch (JSONException ignored) {
        }
        return Collections.unmodifiableList(result);
    }

    public static synchronized Notice find(Context context, String id) {
        for (Notice notice : pending(context)) {
            if (notice.id().equals(id)) return notice;
        }
        return null;
    }

    public static synchronized boolean replace(Context context, List<Notice> notices) {
        try {
            JSONArray array = new JSONArray();
            int start = Math.max(0, notices.size() - MAX_NOTICES);
            for (int index = start; index < notices.size(); index++) {
                array.put(notices.get(index).toJson());
            }
            return prefs(context).edit().putString(KEY_PENDING, array.toString()).commit();
        } catch (JSONException exception) {
            return false;
        }
    }

    public static synchronized Set<String> shown(Context context) {
        return new LinkedHashSet<>(prefs(context).getStringSet(KEY_SHOWN,
                Collections.emptySet()));
    }

    public static synchronized boolean markShown(Context context, String id) {
        Set<String> shown = shown(context);
        shown.add(id);
        return prefs(context).edit().putStringSet(KEY_SHOWN, shown).commit();
    }

    public static synchronized boolean clearShown(Context context, String id) {
        Set<String> shown = shown(context);
        boolean changed = shown.remove(id);
        return changed && prefs(context).edit().putStringSet(KEY_SHOWN, shown).commit();
    }

    public static synchronized boolean remove(Context context, String id) {
        List<Notice> notices = new ArrayList<>(pending(context));
        boolean removed = notices.removeIf(notice -> notice.id().equals(id));
        return removed && replace(context, notices);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
