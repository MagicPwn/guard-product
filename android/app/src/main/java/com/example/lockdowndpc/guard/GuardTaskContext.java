package com.example.lockdowndpc.guard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Pure task-page context rules shared by the Android screen and JVM tests. */
public final class GuardTaskContext {
    public enum Reason {
        CHOOSE,
        BUDGET_EXHAUSTED,
        TOTAL_USAGE,
        CONTINUOUS_USE,
        APP_ENTER,
        SCHEDULE_START,
        MANUAL,
        UNAVAILABLE
    }

    public enum WaitKind {
        READY,
        COOLDOWN,
        DAILY_LIMIT,
        REWARD_ACTIVE,
        IN_PROGRESS,
        UNAVAILABLE
    }

    public static final class Wait {
        private final WaitKind kind;
        private final long remainingMillis;
        private final long deadlineMillis;

        private Wait(WaitKind kind, long remainingMillis, long deadlineMillis) {
            this.kind = Objects.requireNonNull(kind);
            this.remainingMillis = Math.max(0L, remainingMillis);
            this.deadlineMillis = Math.max(0L, deadlineMillis);
        }

        public WaitKind kind() { return kind; }
        public long remainingMillis() { return remainingMillis; }
        public long deadlineMillis() { return deadlineMillis; }
    }

    private GuardTaskContext() {}

    public static Reason reasonFor(boolean budgetExhausted, boolean manual,
            GuardPolicy.QuizTrigger.Type triggerType) {
        if (budgetExhausted) return Reason.BUDGET_EXHAUSTED;
        if (manual) return Reason.MANUAL;
        if (triggerType == null) return Reason.UNAVAILABLE;
        switch (triggerType) {
            case TOTAL_USAGE:
                return Reason.TOTAL_USAGE;
            case CONTINUOUS_USE:
                return Reason.CONTINUOUS_USE;
            case APP_ENTER:
                return Reason.APP_ENTER;
            case SCHEDULE_START:
                return Reason.SCHEDULE_START;
            default:
                return Reason.UNAVAILABLE;
        }
    }

    public static Wait evaluateWait(boolean available, boolean attemptInProgress,
            boolean rewardActive, long rewardRemainingMillis, int attemptsToday,
            int maxAttemptsPerDay, long cooldownRemainingMillis, String timezone,
            long nowMillis) {
        if (!available) return new Wait(WaitKind.UNAVAILABLE, 0L, 0L);
        if (attemptInProgress) return new Wait(WaitKind.IN_PROGRESS, 0L, 0L);
        if (rewardActive) {
            long remaining = Math.max(0L, rewardRemainingMillis);
            return new Wait(WaitKind.REWARD_ACTIVE, remaining,
                    remaining == 0L ? 0L : safeAdd(nowMillis, remaining));
        }
        if (attemptsToday >= maxAttemptsPerDay) {
            long deadline = nextDailyResetMillis(timezone, nowMillis);
            return new Wait(WaitKind.DAILY_LIMIT, Math.max(0L, deadline - nowMillis), deadline);
        }
        if (cooldownRemainingMillis > 0L) {
            return new Wait(WaitKind.COOLDOWN, cooldownRemainingMillis,
                    safeAdd(nowMillis, cooldownRemainingMillis));
        }
        return new Wait(WaitKind.READY, 0L, 0L);
    }

    public static Wait remaining(Wait original, long nowMillis) {
        if (original == null) return new Wait(WaitKind.UNAVAILABLE, 0L, 0L);
        if (original.kind() == WaitKind.IN_PROGRESS
                || original.kind() == WaitKind.READY
                || original.kind() == WaitKind.UNAVAILABLE) {
            return original;
        }
        long remaining = original.deadlineMillis() - nowMillis;
        if (remaining <= 0L) return new Wait(WaitKind.READY, 0L, 0L);
        return new Wait(original.kind(), remaining, original.deadlineMillis());
    }

    public static long remainingSecondsCeil(long millis) {
        if (millis <= 0L) return 0L;
        return (millis + 999L) / 1000L;
    }

    static long nextDailyResetMillis(String timezone, long nowMillis) {
        ZoneId zone = ZoneId.of(timezone);
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        return today.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static long safeAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
    }
}
