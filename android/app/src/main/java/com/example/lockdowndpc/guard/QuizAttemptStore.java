package com.example.lockdowndpc.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import java.time.LocalDate;
import java.time.ZoneId;

public final class QuizAttemptStore {
    private static final String PREFS = "guard_quiz_attempts";

    private QuizAttemptStore() {}

    public static synchronized int countToday(Context context) {
        return countToday(context, GuardPolicyStore.read(context).timezone());
    }

    public static synchronized int countToday(Context context, String timezone) {
        resetIfNeeded(context, timezone);
        return prefs(context).getInt("count", 0);
    }

    public static synchronized boolean beginAttempt(Context context, GuardPolicy policy) {
        if (!canBegin(context, policy)) return false;
        int count = countToday(context, policy.timezone());
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0) return false;
        return prefs(context).edit().putInt("count", count + 1).putInt("boot", boot).commit();
    }

    /**
     * Whether an attempt could begin now without consuming the daily counter.
     * Trigger creation uses this so a cooldown does not open an unusable page.
     */
    public static synchronized boolean canBegin(Context context, GuardPolicy policy) {
        return allowsAttempt(countToday(context, policy.timezone()),
                policy.maxAttemptsPerDay(), cooldownRemaining(context, policy.cooldownSeconds()));
    }

    static boolean allowsAttempt(int countToday, int maxAttemptsPerDay, long cooldownMillis) {
        return countToday < maxAttemptsPerDay && cooldownMillis <= 0L;
    }

    /** Starts the cooldown only when an answer is actually wrong. */
    public static synchronized boolean recordWrong(Context context) {
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0) return false;
        return prefs(context).edit().putInt("boot", boot)
                .putLong("last_wrong", System.currentTimeMillis())
                .putLong("last_elapsed", SystemClock.elapsedRealtime()).commit();
    }

    public static synchronized long cooldownRemaining(Context context, int seconds) {
        SharedPreferences preferences = prefs(context);
        long lastWall = preferences.getLong("last_wrong", 0L);
        if (lastWall <= 0L || seconds <= 0) return 0L;
        long wallRemaining = seconds * 1000L - (System.currentTimeMillis() - lastWall);
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        long elapsedRemaining = 0L;
        if (boot >= 0 && preferences.getInt("boot", -2) == boot) {
            elapsedRemaining = seconds * 1000L
                    - (SystemClock.elapsedRealtime() - preferences.getLong("last_elapsed", 0L));
        }
        return Math.max(0L, Math.min(seconds * 1000L, Math.max(wallRemaining, elapsedRemaining)));
    }

    private static void resetIfNeeded(Context context, String timezone) {
        String day = LocalDate.now(ZoneId.of(timezone)).toString();
        SharedPreferences preferences = prefs(context);
        String stored = preferences.getString("day", "");
        if (stored.isEmpty() || day.compareTo(stored) > 0) {
            if (!preferences.edit().putString("day", day).putInt("count", 0).commit()) {
                throw new IllegalStateException("Cannot persist daily quiz counter");
            }
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
