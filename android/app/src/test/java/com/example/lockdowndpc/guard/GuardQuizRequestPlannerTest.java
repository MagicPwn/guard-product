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

public final class GuardQuizRequestPlannerTest {
    private static final String GAME = "com.example.game";
    private static final String VIDEO = "com.example.video";

    private static long millis(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private GuardPolicy policy(Map<String, Integer> budgets,
            List<GuardPolicy.QuizTrigger> triggers) {
        return new GuardPolicy(11, "UTC", true, false, true, 10, 8, 60,
                budgets, Collections.emptySet(), Collections.emptyMap(), triggers,
                GuardPolicy.QuizSettings.defaults());
    }

    @Test
    public void exhaustedBudgetSuppressesTheSameAppsAppEnterRequest() {
        GuardPolicy.QuizTrigger appEnter = GuardPolicy.QuizTrigger.create(
                GuardPolicy.QuizTrigger.Type.APP_ENTER, List.of(GAME),
                0, 0, Collections.emptySet(), 0);
        GuardPolicy policy = policy(Map.of(GAME, 1), List.of(appEnter));
        GuardQuizTriggerEngine.State state = new GuardQuizTriggerEngine.State();
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 18, 0);

        GuardQuizRequestPlanner.Plan plan = GuardQuizRequestPlanner.plan(policy,
                Map.of(GAME, 60_000L), GAME, now, millis(now), Collections.emptySet(),
                state, true);

        assertEquals(GAME, plan.budgetTarget());
        assertNotNull(plan.trigger());
        assertTrue(plan.triggerTargets().isEmpty());
    }

    @Test
    public void exhaustedBudgetKeepsOtherTriggerTargetsEligible() {
        GuardPolicy.QuizTrigger totalUsage = GuardPolicy.QuizTrigger.create(
                GuardPolicy.QuizTrigger.Type.TOTAL_USAGE, List.of(GAME, VIDEO),
                1, 0, Collections.emptySet(), 0);
        GuardPolicy policy = policy(Map.of(GAME, 0, VIDEO, 60), List.of(totalUsage));
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 18, 0);

        GuardQuizRequestPlanner.Plan plan = GuardQuizRequestPlanner.plan(policy,
                Map.of(VIDEO, 60_000L), GAME, now, millis(now), Collections.emptySet(),
                new GuardQuizTriggerEngine.State(), true);

        assertEquals(GAME, plan.budgetTarget());
        assertNotNull(plan.trigger());
        assertEquals(List.of(VIDEO), plan.triggerTargets());
    }

    @Test
    public void budgetRequestStillAppearsWhenQuizAttemptsAreUnavailable() {
        GuardPolicy policy = policy(Map.of(GAME, 0), Collections.emptyList());
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 18, 0);

        GuardQuizRequestPlanner.Plan plan = GuardQuizRequestPlanner.plan(policy,
                Collections.emptyMap(), GAME, now, millis(now), Collections.emptySet(),
                new GuardQuizTriggerEngine.State(), false);

        assertEquals(GAME, plan.budgetTarget());
        assertNull(plan.trigger());
    }

    @Test
    public void budgetPromptIsDeduplicatedPerRevisionAndLocalDay() {
        GuardPolicy policy = policy(Map.of(GAME, 0), Collections.emptyList());
        GuardQuizTriggerEngine.State state = new GuardQuizTriggerEngine.State();
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 18, 0);

        assertEquals(GAME, GuardQuizRequestPlanner.plan(policy, Collections.emptyMap(),
                GAME, now, millis(now), Collections.emptySet(), state, false).budgetTarget());
        assertNull(GuardQuizRequestPlanner.plan(policy, Collections.emptyMap(),
                GAME, now.plusHours(1), millis(now.plusHours(1)), Collections.emptySet(),
                state, true).budgetTarget());
        assertEquals(GAME, GuardQuizRequestPlanner.plan(policy, Collections.emptyMap(),
                GAME, now.plusDays(1), millis(now.plusDays(1)), Collections.emptySet(),
                state, true).budgetTarget());
    }

    @Test
    public void activeRewardSuppressesTheBudgetRequest() {
        GuardPolicy policy = policy(Map.of(GAME, 0), Collections.emptyList());
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 18, 0);

        assertNull(GuardQuizRequestPlanner.plan(policy, Collections.emptyMap(), GAME,
                now, millis(now), Set.of(GAME), new GuardQuizTriggerEngine.State(),
                false).budgetTarget());
    }

    @Test
    public void alwaysAllowedSuppressesTheBudgetRequest() {
        GuardPolicy policy = new GuardPolicy(12, "UTC", true, false, true, 10, 8, 60,
                Map.of(GAME, 0), Collections.emptySet(), Collections.emptyMap(),
                Collections.emptyList(), GuardPolicy.QuizSettings.defaults(), Collections.emptyList(),
                Set.of(GAME));
        LocalDateTime now = LocalDateTime.of(2026, 11, 13, 18, 0);

        assertNull(GuardQuizRequestPlanner.plan(policy, Collections.emptyMap(), GAME,
                now, millis(now), Collections.emptySet(), new GuardQuizTriggerEngine.State(),
                true).budgetTarget());
    }
}
