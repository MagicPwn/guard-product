package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class QuizAttemptEligibilityTest {
    @Test
    public void rejectsDailyLimitAndPositiveCooldown() {
        assertFalse(QuizAttemptStore.allowsAttempt(8, 8, 0L));
        assertFalse(QuizAttemptStore.allowsAttempt(0, 8, 1L));
    }

    @Test
    public void allowsAttemptBeforeLimitAndAfterCooldown() {
        assertTrue(QuizAttemptStore.allowsAttempt(7, 8, 0L));
        assertTrue(QuizAttemptStore.allowsAttempt(0, 8, -1L));
    }
}
