package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable idempotency markers for parent-issued temporary grants. */
public final class GuardGrantStore {
    private static final String PREFS = "guard_parent_grants";
    private static final String APPLIED = "applied:";
    private static final int MAX_RECORDS = 200;

    private GuardGrantStore() {}

    public static boolean isApplied(Context context, String grantId) {
        if (!validId(grantId)) return false;
        return prefs(context).getBoolean(APPLIED + grantId, false);
    }

    public static synchronized boolean markApplied(Context context, String grantId) {
        if (!validId(grantId)) return false;
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(APPLIED + grantId, true);
        String[] keys = preferences.getAll().keySet().toArray(new String[0]);
        if (keys.length >= MAX_RECORDS) {
            int remove = Math.max(1, keys.length - MAX_RECORDS + 1);
            for (int index = 0; index < keys.length && index < remove; index++) {
                if (keys[index].startsWith(APPLIED)) editor.remove(keys[index]);
            }
        }
        return editor.commit();
    }

    private static boolean validId(String grantId) {
        return grantId != null && grantId.matches("^[A-Za-z0-9._:-]{1,128}$");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
