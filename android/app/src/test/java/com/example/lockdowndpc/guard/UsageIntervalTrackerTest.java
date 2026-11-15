package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public final class UsageIntervalTrackerTest {
    private static final long MINUTE = 60_000L;
    private static final UsageIntervalTracker.Kind RESUMED = UsageIntervalTracker.Kind.RESUMED;
    private static final UsageIntervalTracker.Kind PAUSED = UsageIntervalTracker.Kind.PAUSED;
    private static final UsageIntervalTracker.Kind STOPPED = UsageIntervalTracker.Kind.STOPPED;

    @Test
    public void interleavedActivitiesCountOneContinuousInterval() {
        UsageIntervalTracker tracker = new UsageIntervalTracker(0L, 10 * MINUTE);
        tracker.event(RESUMED, "com.example.game", "Main", 0L);
        tracker.event(RESUMED, "com.example.game", "Dialog", 1 * MINUTE);
        tracker.event(PAUSED, "com.example.game", "Main", 2 * MINUTE);
        tracker.event(STOPPED, "com.example.game", "Main", 2 * MINUTE);
        tracker.event(PAUSED, "com.example.game", "Dialog", 5 * MINUTE);
        assertEquals(5 * MINUTE, tracker.finish().get("com.example.game").longValue());
    }

    @Test
    public void sessionStartedBeforeMidnightCountsOnlyAfterMidnight() {
        UsageIntervalTracker tracker = new UsageIntervalTracker(0L, 60 * MINUTE);
        tracker.event(RESUMED, "com.example.video", "Player", -30 * MINUTE);
        tracker.event(PAUSED, "com.example.video", "Player", 10 * MINUTE);
        assertEquals(10 * MINUTE, tracker.finish().get("com.example.video").longValue());
    }

    @Test
    public void screenOffClosesOpenActivityWithoutPauseEvent() {
        UsageIntervalTracker tracker = new UsageIntervalTracker(0L, 60 * MINUTE);
        tracker.event(RESUMED, "com.example.game", "Main", 10 * MINUTE);
        tracker.event(UsageIntervalTracker.Kind.SCREEN_OFF, null, null, 20 * MINUTE);
        tracker.event(RESUMED, "com.example.game", "Main", 30 * MINUTE);
        tracker.event(PAUSED, "com.example.game", "Main", 40 * MINUTE);
        assertEquals(20 * MINUTE, tracker.finish().get("com.example.game").longValue());
    }

    @Test
    public void stoppedAfterPausedIsIdempotent() {
        UsageIntervalTracker tracker = new UsageIntervalTracker(0L, 60 * MINUTE);
        tracker.event(RESUMED, "com.example.app", "Main", 0L);
        tracker.event(PAUSED, "com.example.app", "Main", 5 * MINUTE);
        tracker.event(STOPPED, "com.example.app", "Main", 5 * MINUTE);
        tracker.event(RESUMED, "com.example.app", "Main", 10 * MINUTE);
        tracker.event(PAUSED, "com.example.app", "Main", 12 * MINUTE);
        assertEquals(7 * MINUTE, tracker.finish().get("com.example.app").longValue());
    }

    @Test
    public void unfinishedSessionIsCountedToWindowEnd() {
        UsageIntervalTracker tracker = new UsageIntervalTracker(0L, 30 * MINUTE);
        tracker.event(RESUMED, "com.example.app", "Main", 5 * MINUTE);
        assertEquals(25 * MINUTE, tracker.finish().get("com.example.app").longValue());
    }

    @Test
    public void unknownPackagesAndEmptyStreamsProduceNoUsage() {
        UsageIntervalTracker tracker = new UsageIntervalTracker(0L, 60 * MINUTE);
        tracker.event(PAUSED, "com.example.never-seen", "Main", 5 * MINUTE);
        tracker.event(RESUMED, null, "Main", 6 * MINUTE);
        assertTrue(tracker.finish().isEmpty());
    }
}
