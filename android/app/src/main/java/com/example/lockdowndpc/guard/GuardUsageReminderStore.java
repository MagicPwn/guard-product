package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable state for local usage reminders so a process restart cannot spam the child. */
public final class GuardUsageReminderStore {
    private static final String PREFS = "guard_usage_reminders";
    private static final String KEY_STATE = "state";

    private GuardUsageReminderStore() {}

    public static synchronized GuardUsageReminderEngine.State readState(Context context) {
        String raw = prefs(context).getString(KEY_STATE, "");
        return GuardUsageReminderEngine.State.fromJson(raw);
    }

    public static synchronized boolean writeState(Context context,
            GuardUsageReminderEngine.State state) {
        if (state == null) return false;
        return prefs(context).edit().putString(KEY_STATE, state.toJson()).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
