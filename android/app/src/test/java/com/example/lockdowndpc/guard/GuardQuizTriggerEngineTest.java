package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GuardQuizTriggerEngineTest {
    private static final String GAME = "com.example.game";
    private static final String VIDEO = "com.example.video";

    private GuardPolicy policy(List<GuardPolicy.QuizTrigger> triggers,
            Map<String, List<GuardPolicy.GuardSchedule>> schedules) {
        return new GuardPolicy(9, "UTC", true, false, true, 10, 8, 60,
                Map.of(GAME, 60, VIDEO, 60), Collections.emptySet(), schedules, triggers,
                GuardPolicy.QuizSettings.defaults());
    }

    private static long millis(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Test
    public void totalUsageFiresOncePerDayAndResetsAtMidnight() {
        GuardPolicy.QuizTrigger trigger = GuardPolicy.QuizTrigger.create(
                GuardPolicy.QuizTrigger.Type.TOTAL_USAGE, List.of(GAME),
                30, 0, Collections.emptySet(), 0);
        GuardPolicy policy = policy(List.of(trigger), Collections.emptyMap());
        GuardQuizTriggerEngine.State state = new GuardQuizTriggerEngine.State();
        LocalDateTime firstDay = LocalDateTime.of(2026, 11, 16, 18, 0);

        assertNull(GuardQuizTriggerEngine.evaluate(policy,
                Map.of(GAME, 29 * 60_000L), null, firstDay, millis(firstDay),
                Collections.emptySet(), state));
        GuardQuizTriggerEngine.Decision decision = GuardQuizTriggerEngine.evaluate(policy,
                Map.of(GAME, 30 * 60_000L), null, firstDay, millis(firstDay),
                Collections.emptySet(), state);
        assertNotNull(decision);
        assertEquals(List.of(GAME), decision.targets());
        assertNull(GuardQuizTriggerEngine.evaluate(policy,
                Map.of(GAME, 90 * 60_000L), null, firstDay.plusHours(1),
                millis(firstDay.plusHours(1)), Collections.emptySet(), state));

        LocalDateTime nextDay = firstDay.plusDays(1);
        assertNotNull(GuardQuizTriggerEngine.evaluate(policy,
                Map.of(GAME, 90 * 60_000L), null, nextDay, millis(nextDay),
                Collections.emptySet(), state));
    }

    @Test
    public void appEnterHonorsDelayAndOnlyFiresOncePerForegroundSession() {
        GuardPolicy.QuizTrigger trigger = GuardPolicy.QuizTrigger.create(
                GuardPolicy.QuizTrigger.Type.APP_ENTER, List.of(GAME),
                0, 10, Collections.emptySet(), 0);
        GuardPolicy policy = policy(List.of(trigger), Collections.emptyMap());
        GuardQuizTriggerEngine.State state = new GuardQuizTriggerEngine.State();
        LocalDateTime start = LocalDateTime.of(2026, 11, 16, 18, 0);

        assertNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start, millis(start), Collections.emptySet(), state));
        assertNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusSeconds(9), millis(start.plusSeconds(9)),
                Collections.emptySet(), state));
        assertNotNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusSeconds(10), millis(start.plusSeconds(10)),
                Collections.emptySet(), state));
        assertNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusSeconds(20), millis(start.plusSeconds(20)),
                Collections.emptySet(), state));

        assertNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), VIDEO,
                start.plusSeconds(21), millis(start.plusSeconds(21)),
                Collections.emptySet(), state));
        assertNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusSeconds(22), millis(start.plusSeconds(22)),
                Collections.emptySet(), state));
        assertNotNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusSeconds(32), millis(start.plusSeconds(32)),
                Collections.emptySet(), state));
    }

    @Test
    public void continuousUseMeasuresTheCurrentForegroundSession() {
        GuardPolicy.QuizTrigger trigger = GuardPolicy.QuizTrigger.create(
                GuardPolicy.QuizTrigger.Type.CONTINUOUS_USE, List.of(GAME),
                1, 0, Collections.emptySet(), 0);
        GuardPolicy policy = policy(List.of(trigger), Collections.emptyMap());
        GuardQuizTriggerEngine.State state = new GuardQuizTriggerEngine.State();
        LocalDateTime start = LocalDateTime.of(2026, 11, 16, 18, 0);

        assertNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start, millis(start), Collections.emptySet(), state));
        assertNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusSeconds(59), millis(start.plusSeconds(59)),
                Collections.emptySet(), state));
        assertNotNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusMinutes(1), millis(start.plusMinutes(1)),
                Collections.emptySet(), state));

        assertNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), VIDEO,
                start.plusMinutes(1).plusSeconds(10), millis(start.plusMinutes(1).plusSeconds(10)),
                Collections.emptySet(), state));
        assertNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), GAME,
                start.plusMinutes(1).plusSeconds(20), millis(start.plusMinutes(1).plusSeconds(20)),
                Collections.emptySet(), state));
    }

    @Test
    public void scheduleStartFiresOnceInsideItsGraceWindow() {
        GuardPolicy.GuardSchedule window = new GuardPolicy.GuardSchedule(
                Set.of(DayOfWeek.MONDAY), 9 * 60, 120);
        GuardPolicy.QuizTrigger trigger = GuardPolicy.QuizTrigger.create(
                GuardPolicy.QuizTrigger.Type.SCHEDULE_START, List.of(GAME),
                0, 0, Set.of(DayOfWeek.MONDAY), 9 * 60);
        GuardPolicy policy = policy(List.of(trigger), Map.of(GAME, List.of(window)));
        GuardQuizTriggerEngine.State state = new GuardQuizTriggerEngine.State();
        LocalDateTime monday = LocalDateTime.of(2026, 11, 16, 9, 1);

        GuardQuizTriggerEngine.Decision decision = GuardQuizTriggerEngine.evaluate(policy,
                Collections.emptyMap(), null, monday, millis(monday),
                Collections.emptySet(), state);
        assertNotNull(decision);
        assertEquals(List.of(GAME), decision.targets());
        assertNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), null,
                monday.plusMinutes(1), millis(monday.plusMinutes(1)),
                Collections.emptySet(), state));

        GuardQuizTriggerEngine.State lateState = new GuardQuizTriggerEngine.State();
        LocalDateTime late = LocalDateTime.of(2026, 11, 16, 9, 45);
        assertNull(GuardQuizTriggerEngine.evaluate(policy, Collections.emptyMap(), null,
                late, millis(late), Collections.emptySet(), lateState));
    }

    @Test
    public void rewardedPackagesAreExcludedAndTriggerStateRoundTrips() {
        GuardPolicy.QuizTrigger trigger = GuardPolicy.QuizTrigger.create(
                GuardPolicy.QuizTrigger.Type.TOTAL_USAGE, List.of(GAME, VIDEO),
                10, 0, Collections.emptySet(), 0);
        GuardPolicy policy = policy(List.of(trigger), Collections.emptyMap());
        GuardQuizTriggerEngine.State state = new GuardQuizTriggerEngine.State();
        LocalDateTime now = LocalDateTime.of(2026, 11, 16, 18, 0);

        GuardQuizTriggerEngine.Decision decision = GuardQuizTriggerEngine.evaluate(policy,
                Map.of(GAME, 10 * 60_000L, VIDEO, 10 * 60_000L), null, now, millis(now),
                Set.of(GAME), state);
        assertNotNull(decision);
        assertEquals(List.of(VIDEO), decision.targets());

        GuardQuizTriggerEngine.State restored = GuardQuizTriggerEngine.State.fromJson(state.toJson());
        assertEquals(state.day(), restored.day());
        assertNull(GuardQuizTriggerEngine.evaluate(policy,
                Map.of(GAME, 10 * 60_000L, VIDEO, 10 * 60_000L), null, now, millis(now),
                Collections.emptySet(), restored));
    }

    @Test
    public void alwaysAllowedPackagesAreNeverQuizTriggerTargets() {
        GuardPolicy.QuizTrigger trigger = GuardPolicy.QuizTrigger.create(
                GuardPolicy.QuizTrigger.Type.TOTAL_USAGE, List.of(GAME, VIDEO),
                1, 0, Collections.emptySet(), 0);
        GuardPolicy policy = new GuardPolicy(10, "UTC", true, false, true, 10, 8, 60,
                Map.of(GAME, 60, VIDEO, 60), Collections.emptySet(), Collections.emptyMap(),
                List.of(trigger), GuardPolicy.QuizSettings.defaults(), Collections.emptyList(),
                Set.of(GAME));
        GuardQuizTriggerEngine.State state = new GuardQuizTriggerEngine.State();
        LocalDateTime now = LocalDateTime.of(2026, 11, 16, 18, 0);
        GuardQuizTriggerEngine.Decision decision = GuardQuizTriggerEngine.evaluate(policy,
                Map.of(GAME, 60 * 60_000L, VIDEO, 60 * 60_000L), null, now, millis(now),
                Collections.emptySet(), state);
        assertNotNull(decision);
        assertEquals(List.of(VIDEO), decision.targets());
    }

    @Test
    public void policyJsonRoundTripPreservesTriggers() {
        GuardPolicy.QuizTrigger trigger = GuardPolicy.QuizTrigger.create(
                GuardPolicy.QuizTrigger.Type.APP_ENTER, List.of(GAME),
                0, 30, Collections.emptySet(), 0);
        GuardPolicy policy = policy(List.of(trigger), Collections.emptyMap());
        GuardPolicy decoded = GuardPolicy.fromJson(policy.toJson().toString());
        assertEquals(1, decoded.quizTriggers().size());
        assertEquals(trigger.id(), decoded.quizTriggers().get(0).id());
        assertEquals(trigger.computedId(), decoded.quizTriggers().get(0).computedId());
    }
}
