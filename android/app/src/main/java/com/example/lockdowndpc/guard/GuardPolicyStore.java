package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class GuardPolicyStore {
    private static final String PREFS = "guard_policy";
    private static final String KEY_BACKUP = "verified_policy_backup";
    private static final String KEY_POLICY = "policy_json";
    private static final String KEY_SUSPENDED = "suspended_packages";
    private static final String KEY_LAST_ENFORCED = "last_enforced_at";

    private GuardPolicyStore() {}

    public static GuardPolicy read(Context context) {
        String raw = prefs(context).getString(KEY_POLICY, null);
        if (raw == null) {
            String backup = prefs(context).getString(KEY_BACKUP, null);
            if (backup != null) return GuardPolicy.fromJson(backup);
            if (!readSuspendedPackages(context).isEmpty()) {
                throw new IllegalStateException("Policy missing while restrictions remain");
            }
            return GuardPolicy.disabled(java.util.TimeZone.getDefault().getID());
        }
        try {
            return GuardPolicy.fromJson(raw);
        } catch (IllegalArgumentException exception) {
            String backup = prefs(context).getString(KEY_BACKUP, null);
            if (backup != null) return GuardPolicy.fromJson(backup);
            throw new IllegalStateException("Saved policy is corrupt; restrictions retained", exception);
        }
    }

    public static synchronized boolean save(Context context, GuardPolicy policy) {
        String serialized = policy.toJson().toString();
        GuardPolicy.fromJson(serialized);
        return prefs(context).edit().putString(KEY_POLICY, serialized)
                .putString(KEY_BACKUP, serialized).commit();
    }

    public static Set<String> readSuspendedPackages(Context context) {
        Set<String> stored = prefs(context).getStringSet(KEY_SUSPENDED, Collections.emptySet());
        return new HashSet<>(stored);
    }

    public static boolean writeSuspendedPackages(Context context, Set<String> packages) {
        return prefs(context).edit().putStringSet(KEY_SUSPENDED, new HashSet<>(packages)).commit();
    }

    public static long lastEnforcedAt(Context context) {
        return prefs(context).getLong(KEY_LAST_ENFORCED, 0L);
    }

    public static boolean markEnforced(Context context) {
        return prefs(context).edit().putLong(KEY_LAST_ENFORCED, System.currentTimeMillis()).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
