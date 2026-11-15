package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.HashMap;
import java.util.Map;

public final class GuardRewardStore {
    private static final String PREFS = "guard_rewards";
    private static final String EXPIRY = "expiry:";
    private static final String ELAPSED = "elapsed:";
    private static final String BOOT = "boot:";
    private static final String REVISION = "revision:";
    private static final String TYPE = "type:";
    private static final String GRANT = "grant:";
    private static final String QUIZ = "quiz";
    private static final String PARENT = "parent";

    private GuardRewardStore() {}

    public static synchronized boolean grant(Context context, String packageName, long durationMillis) {
        GuardPolicy policy = GuardPolicyStore.read(context);
        GuardPolicy.requirePackage(packageName);
        boolean managed = policy.dailyBudgets().containsKey(packageName)
                || policy.schedules().containsKey(packageName);
        if (!policy.enabled() || !policy.quizGateEnabled()
                || !managed
                || policy.isAlwaysAllowed(packageName)
                || policy.blockedPackages().contains(packageName)
                || durationMillis <= 0 || durationMillis > policy.rewardMinutes() * 60_000L) {
            return false;
        }
        return write(context, policy, packageName, durationMillis, QUIZ, null);
    }

    /** Parent-issued one-shot allowance, bounded independently from quiz rewards. */
    public static synchronized boolean grantParentOverride(Context context, String packageName,
            long durationMillis, String grantId) {
        if (!validGrantId(grantId)) return false;
        GuardPolicy policy = GuardPolicyStore.read(context);
        GuardPolicy.requirePackage(packageName);
        boolean managed = policy.dailyBudgets().containsKey(packageName)
                || policy.schedules().containsKey(packageName);
        if (!policy.enabled() || !managed || policy.isAlwaysAllowed(packageName)
                || policy.blockedPackages().contains(packageName)
                || durationMillis <= 0 || durationMillis > 1_440L * 60_000L) {
            return false;
        }
        return write(context, policy, packageName, durationMillis, PARENT, grantId);
    }

    private static boolean write(Context context, GuardPolicy policy, String packageName,
            long durationMillis, String type, String grantId) {
        int boot = bootCount(context);
        if (boot < 0) return false;
        SharedPreferences.Editor editor = prefs(context).edit()
                .putLong(EXPIRY + packageName, System.currentTimeMillis() + durationMillis)
                .putLong(ELAPSED + packageName, SystemClock.elapsedRealtime() + durationMillis)
                .putInt(BOOT + packageName, boot)
                .putLong(REVISION + packageName, policy.revision())
                .putString(TYPE + packageName, type);
        if (grantId == null) editor.remove(GRANT + packageName);
        else editor.putString(GRANT + packageName, grantId);
        return editor.commit();
    }

    public static synchronized boolean revoke(Context context, String packageName) {
        return prefs(context).edit().remove(EXPIRY + packageName).remove(ELAPSED + packageName)
                .remove(BOOT + packageName).remove(REVISION + packageName)
                .remove(TYPE + packageName).remove(GRANT + packageName).commit();
    }

    /** Removes the override only when it still belongs to the revoked grant. */
    public static synchronized boolean revokeParentOverride(Context context, String packageName,
            String grantId) {
        if (!validGrantId(grantId) || packageName == null || packageName.isEmpty()) return false;
        SharedPreferences preferences = prefs(context);
        if (!grantId.equals(preferences.getString(GRANT + packageName, null))) return false;
        return revoke(context, packageName);
    }

    public static boolean isActive(Context context, String packageName) {
        return active(context).containsKey(packageName);
    }

    public static synchronized Map<String, Long> active(Context context) {
        Map<String, Long> result = new HashMap<>();
        GuardPolicy policy = GuardPolicyStore.read(context);
        SharedPreferences preferences = prefs(context);
        int boot = bootCount(context);
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(EXPIRY)) continue;
            String packageName = entry.getKey().substring(EXPIRY.length());
            long expiry = entry.getValue() instanceof Long ? (Long) entry.getValue() : 0L;
            String type = preferences.getString(TYPE + packageName, QUIZ);
            boolean typeAllowed = !QUIZ.equals(type) || policy.quizGateEnabled();
            boolean valid = boot >= 0 && preferences.getInt(BOOT + packageName, -1) == boot
                    && preferences.getLong(REVISION + packageName, -1L) == policy.revision()
                    && preferences.getLong(ELAPSED + packageName, 0L) > SystemClock.elapsedRealtime()
                    && expiry > System.currentTimeMillis() && policy.enabled() && typeAllowed
                    && !policy.isAlwaysAllowed(packageName)
                    && !policy.blockedPackages().contains(packageName);
            if (valid) result.put(packageName, expiry);
            else revoke(context, packageName);
        }
        return result;
    }

    private static boolean validGrantId(String grantId) {
        return grantId != null && grantId.matches("^[A-Za-z0-9._:-]{1,128}$");
    }

    public static int bootCount(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
