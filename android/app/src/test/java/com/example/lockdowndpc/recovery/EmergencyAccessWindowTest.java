package com.example.lockdowndpc.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EmergencyAccessWindowTest {
    @Test
    public void activeWindowCountsDownInsideTheSameBoot() {
        EmergencyAccessWindow.Window window = EmergencyAccessWindow.start(
                10_000L, 1_700_000_000_000L, 7, 15L * 60_000L);

        EmergencyAccessWindow.Evaluation evaluation = EmergencyAccessWindow.evaluate(
                window, 10_000L + 60_000L, 1_700_000_060_000L, 7);

        assertTrue(evaluation.active());
        assertEquals(14L * 60_000L, evaluation.remainingMillis());
    }

    @Test
    public void expiryIsFailClosedAtTheExactDeadline() {
        EmergencyAccessWindow.Window window = EmergencyAccessWindow.start(
                10_000L, 1_700_000_000_000L, 7, 60_000L);

        EmergencyAccessWindow.Evaluation evaluation = EmergencyAccessWindow.evaluate(
                window, 70_000L, 1_700_000_060_000L, 7);

        assertEquals(EmergencyAccessWindow.Status.EXPIRED, evaluation.status());
        assertFalse(evaluation.active());
    }

    @Test
    public void restartEndsTheExceptionEvenWhenTheWallClockStillLooksValid() {
        EmergencyAccessWindow.Window window = EmergencyAccessWindow.start(
                10_000L, 1_700_000_000_000L, 7, 15L * 60_000L);

        EmergencyAccessWindow.Evaluation evaluation = EmergencyAccessWindow.evaluate(
                window, 12_000L, 1_700_000_030_000L, 8);

        assertEquals(EmergencyAccessWindow.Status.REBOOTED, evaluation.status());
    }

    @Test
    public void unreadableBootIdentityEndsTheException() {
        EmergencyAccessWindow.Window window = EmergencyAccessWindow.start(
                10_000L, 1_700_000_000_000L, 7, 15L * 60_000L);

        EmergencyAccessWindow.Evaluation evaluation = EmergencyAccessWindow.evaluate(
                window, 12_000L, 1_700_000_030_000L, -1);

        assertEquals(EmergencyAccessWindow.Status.INVALID, evaluation.status());
    }

    @Test
    public void movingTheWallClockBackwardsEndsTheException() {
        EmergencyAccessWindow.Window window = EmergencyAccessWindow.start(
                10_000L, 1_700_000_000_000L, 7, 15L * 60_000L);

        EmergencyAccessWindow.Evaluation evaluation = EmergencyAccessWindow.evaluate(
                window, 20_000L, 1_700_000_000_000L - 5L * 60_000L, 7);

        assertEquals(EmergencyAccessWindow.Status.CLOCK_ANOMALY, evaluation.status());
    }

    @Test
    public void monotonicClockGoingBackwardsIsTreatedAsARestart() {
        EmergencyAccessWindow.Window window = EmergencyAccessWindow.start(
                100_000L, 1_700_000_000_000L, 7, 15L * 60_000L);

        EmergencyAccessWindow.Evaluation evaluation = EmergencyAccessWindow.evaluate(
                window, 10_000L, 1_700_000_100_000L, 7);

        assertEquals(EmergencyAccessWindow.Status.REBOOTED, evaluation.status());
    }

    @Test
    public void invalidDurationsAreNeverStored() {
        assertThrows(IllegalArgumentException.class, () -> EmergencyAccessWindow.start(
                1L, 2L, 3, 0L));
        assertThrows(IllegalArgumentException.class, () -> EmergencyAccessWindow.start(
                1L, 2L, 3, EmergencyAccessWindow.MAX_DURATION_MILLIS + 1L));
        assertEquals(30L * 60_000L, EmergencyAccessWindow.start(
                1L, 2L, 3, EmergencyAccessWindow.MAX_DURATION_MILLIS)
                .expiresElapsedMillis() - 1L);
    }

    @Test
    public void missingWindowIsInactive() {
        EmergencyAccessWindow.Evaluation evaluation = EmergencyAccessWindow.evaluate(
                null, 1L, 2L, 3);
        assertEquals(EmergencyAccessWindow.Status.MISSING, evaluation.status());
        assertFalse(evaluation.active());
    }
}
