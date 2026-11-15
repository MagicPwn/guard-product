package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GuardUsageReminderEngineTest {
    private static final String GAME = "com.example.game";
    private static final String VIDEO = "com.example.video";
    private static final String BLOCKED = "com.example.blocked";
    private static final String ALLOWED = "com.example.phone";

    private GuardPolicy policy(int budgetWarning, int continuousReminder,
            Map<String, List<GuardPolicy.GuardSchedule>> schedules) {
        return new GuardPolicy(20, "UTC", true, false, true, 10, 8, 60,
                Map.of(GAME, 60, VIDEO, 60, BLOCKED, 60), Set.of(BLOCKED), schedules,
                Collections.emptyList(), GuardPolicy.QuizSettings.defaults(),
                Collections.emptyList(), Set.of(ALLOWED), budgetWarning, continuousReminder);
    }

    private static long millis(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Test
    public void budgetWarningFiresOncePerAppPerDayAtTheRemainingThreshold() {
        GuardPolicy policy = policy(15, 0, Collections.emptyMap());
        GuardUsageReminderEngine.State state = new GuardUsageReminderEngine.State();
        LocalDateTime now = LocalDateTime.of(2026, 11, 16, 18, 0);

        assertNull(GuardUsageReminderEngine.evaluate(policy,
                Map.of(GAME, 44 * 60_000L), GAME, now, millis(now), state));
        GuardUsageReminderEngine.Reminder reminder = GuardUsageReminderEngine.evaluate(
                policy, Map.of(GAME, 46 * 60_000L), GAME, now, millis(now), state);
        assertNotNull(reminder);
        assertEquals(GuardUsageReminderEngine.Reason.BUDGET_WARNING, reminder.reason());
        assertEquals(GAME, reminder.packageName());
        assertEquals(14, reminder.minutes());
        assertNull(GuardUsageReminderEngine.evaluate(policy,
                Map.of(GAME, 50 * 60_000L), GAME, now, millis(now), state));

        GuardUsageReminderEngine.State nextDay = GuardUsageReminderEngine.State.fromJson(
                state.toJson());
        LocalDateTime tomorrow = now.plusDays(1);
        assertNotNull(GuardUsageReminderEngine.evaluate(policy,
                Map.of(GAME, 50 * 60_000L), GAME, tomorrow, millis(tomorrow), nextDay));
    }

    @Test
    public void exhaustedOrUnmanagedAppsNeverGetABudgetWarning() {
        GuardPolicy policy = policy(15, 0, Collections.emptyMap());
        GuardUsageReminderEngine.State state = new GuardUsageReminderEngine.State();
        LocalDateTime now = LocalDateTime.of(2026, 11, 16, 18, 0);

        assertNull(GuardUsageReminderEngine.evaluate(policy,
                Map.of(GAME, 60 * 60_000L), GAME, now, millis(now), state));
        assertNull(GuardUsageReminderEngine.evaluate(policy,
                Map.of(ALLOWED, 60 * 60_000L), ALLOWED, now, millis(now), state));
        assertNull(GuardUsageReminderEngine.evaluate(policy,
                Map.of(BLOCKED, 60 * 60_000L), BLOCKED, now, millis(now), state));
        assertNull(GuardUsageReminderEngine.evaluate(policy,
                Map.of("com.example.unmanaged", 60 * 60_000L), "com.example.unmanaged",
                now, millis(now), state));
    }

    @Test
    public void continuousUseFiresOncePerForegroundSessionAndRoundTrips() {
        GuardPolicy policy = policy(0, 30, Collections.emptyMap());
        GuardUsageReminderEngine.State state = new GuardUsageReminderEngine.State();
        LocalDateTime start = LocalDateTime.of(2026, 11, 16, 18, 0);

        assertNull(GuardUsageReminderEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start, millis(start), state));
        assertNull(GuardUsageReminderEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusMinutes(29), millis(start.plusMinutes(29)), state));

        GuardUsageReminderEngine.State restored = GuardUsageReminderEngine.State.fromJson(
                state.toJson());
        GuardUsageReminderEngine.Reminder reminder = GuardUsageReminderEngine.evaluate(policy,
                Collections.emptyMap(), GAME, start.plusMinutes(31),
                millis(start.plusMinutes(31)), restored);
        assertNotNull(reminder);
        assertEquals(GuardUsageReminderEngine.Reason.CONTINUOUS_USE, reminder.reason());
        assertEquals(31, reminder.minutes());
        assertTrue(restored.continuousFired());
        assertNull(GuardUsageReminderEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusMinutes(40), millis(start.plusMinutes(40)), restored));

        assertNull(GuardUsageReminderEngine.evaluate(policy, Collections.emptyMap(), VIDEO,
                start.plusMinutes(41), millis(start.plusMinutes(41)), restored));
        assertNull(GuardUsageReminderEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusMinutes(45), millis(start.plusMinutes(45)), restored));
        assertNotNull(GuardUsageReminderEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusMinutes(76), millis(start.plusMinutes(76)), restored));
    }

    @Test
    public void outsideScheduleAndDisabledPolicyResetTheContinuousSession() {
        GuardPolicy.GuardSchedule mondayMorning = new GuardPolicy.GuardSchedule(
                Set.of(java.time.DayOfWeek.MONDAY), 9 * 60, 60);
        GuardPolicy policy = policy(0, 30, Map.of(GAME, List.of(mondayMorning)));
        GuardUsageReminderEngine.State state = new GuardUsageReminderEngine.State();
        LocalDateTime monday = LocalDateTime.of(2026, 11, 16, 10, 0);

        assertNull(GuardUsageReminderEngine.evaluate(policy, Collections.emptyMap(), GAME,
                monday, millis(monday), state));
        LocalDateTime outside = monday.plusHours(2);
        assertNull(GuardUsageReminderEngine.evaluate(policy, Collections.emptyMap(), GAME,
                outside, millis(outside), state));
        assertEquals("", state.foreground());

        GuardPolicy disabled = new GuardPolicy(21, "UTC", false, false, true, 10, 8, 60,
                Map.of(GAME, 60), Collections.emptySet(), Collections.emptyMap(),
                Collections.emptyList(), GuardPolicy.QuizSettings.defaults(),
                Collections.emptyList(), Collections.emptySet(), 0, 30);
        assertNull(GuardUsageReminderEngine.evaluate(disabled, Collections.emptyMap(), GAME,
                monday, millis(monday), state));
    }
}
