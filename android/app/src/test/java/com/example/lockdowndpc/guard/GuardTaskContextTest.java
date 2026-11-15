package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Instant;

public final class GuardTaskContextTest {
    @Test
    public void budgetReasonWinsOverOtherTriggers() {
        assertEquals(GuardTaskContext.Reason.BUDGET_EXHAUSTED,
                GuardTaskContext.reasonFor(true, true,
                        GuardPolicy.QuizTrigger.Type.TOTAL_USAGE));
        assertEquals(GuardTaskContext.Reason.MANUAL,
                GuardTaskContext.reasonFor(false, true,
                        GuardPolicy.QuizTrigger.Type.APP_ENTER));
        assertEquals(GuardTaskContext.Reason.CONTINUOUS_USE,
                GuardTaskContext.reasonFor(false, false,
                        GuardPolicy.QuizTrigger.Type.CONTINUOUS_USE));
    }

    @Test
    public void cooldownCountsDownAndThenBecomesReady() {
        long now = 1_000_000L;
        GuardTaskContext.Wait wait = GuardTaskContext.evaluateWait(true, false, false, 0L,
                1, 8, 90_000L, "UTC", now);
        assertEquals(GuardTaskContext.WaitKind.COOLDOWN, wait.kind());
        assertEquals(90_000L, wait.remainingMillis());
        assertEquals(90L, GuardTaskContext.remainingSecondsCeil(wait.remainingMillis()));
        assertEquals(45_000L, GuardTaskContext.remaining(wait, now + 45_000L).remainingMillis());
        assertEquals(GuardTaskContext.WaitKind.READY,
                GuardTaskContext.remaining(wait, now + 90_000L).kind());
    }

    @Test
    public void dailyLimitWaitsForLocalMidnight() {
        long now = Instant.parse("2026-11-13T10:00:00Z").toEpochMilli();
        GuardTaskContext.Wait wait = GuardTaskContext.evaluateWait(true, false, false, 0L,
                8, 8, 0L, "Asia/Shanghai", now);
        assertEquals(GuardTaskContext.WaitKind.DAILY_LIMIT, wait.kind());
        assertEquals(6L * 60L * 60L * 1000L, wait.remainingMillis());
    }

    @Test
    public void activeRewardAndInProgressTakePrecedence() {
        GuardTaskContext.Wait reward = GuardTaskContext.evaluateWait(true, false,
                true, 120_000L, 8, 8, 60_000L, "UTC", 5_000L);
        assertEquals(GuardTaskContext.WaitKind.REWARD_ACTIVE, reward.kind());
        assertEquals(120_000L, reward.remainingMillis());

        GuardTaskContext.Wait progress = GuardTaskContext.evaluateWait(true, true,
                false, 0L, 8, 8, 60_000L, "UTC", 5_000L);
        assertEquals(GuardTaskContext.WaitKind.IN_PROGRESS, progress.kind());
    }

    @Test
    public void unavailableAndReadyAreExplicit() {
        assertEquals(GuardTaskContext.WaitKind.UNAVAILABLE,
                GuardTaskContext.evaluateWait(false, false, false, 0L,
                        0, 8, 0L, "UTC", 1L).kind());
        assertEquals(GuardTaskContext.WaitKind.READY,
                GuardTaskContext.evaluateWait(true, false, false, 0L,
                        0, 8, 0L, "UTC", 1L).kind());
    }
}
