package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class GuardPolicyEngineTest {
    private GuardPolicy policy(boolean enabled, Map<String, Integer> budgets, Set<String> blocked) {
        return new GuardPolicy(1, "Asia/Shanghai", enabled, false, true, 10, 8, 60, budgets, blocked);
    }

    @Test
    public void budgetBlocksAtTheExactBoundary() {
        GuardPolicy policy = policy(true, Collections.singletonMap("com.example.game", 1), Collections.emptySet());
        assertTrue(GuardPolicyEngine.blockedPackages(policy,
                Collections.singletonMap("com.example.game", 60_000L), Collections.emptySet())
                .contains("com.example.game"));
        assertFalse(GuardPolicyEngine.blockedPackages(policy,
                Collections.singletonMap("com.example.game", 59_999L), Collections.emptySet())
                .contains("com.example.game"));
    }

    @Test
    public void rewardsCanRelaxBudgetsButNeverPermanentBlocks() {
        GuardPolicy policy = policy(true, Collections.singletonMap("com.example.game", 0),
                Collections.singleton("com.example.blocked"));
        Set<String> result = GuardPolicyEngine.blockedPackages(policy, Collections.emptyMap(),
                Set.of("com.example.game", "com.example.blocked"));
        assertEquals(Collections.singleton("com.example.blocked"), result);
    }

    @Test
    public void rewardCannotBypassPermanentBlockOnTheSamePackage() {
        GuardPolicy policy = policy(true, Collections.singletonMap("com.example.game", 0),
                Collections.singleton("com.example.game"));
        assertEquals(Collections.singleton("com.example.game"), GuardPolicyEngine.blockedPackages(
                policy, Collections.emptyMap(), Collections.singleton("com.example.game")));
    }

    @Test
    public void disabledPolicyDoesNotBlockAnything() {
        GuardPolicy policy = policy(false, Collections.singletonMap("com.example.game", 0),
                Collections.singleton("com.example.blocked"));
        assertTrue(GuardPolicyEngine.blockedPackages(policy, Collections.emptyMap(), Collections.emptySet()).isEmpty());
    }

    @Test
    public void appIsBlockedOutsideItsConfiguredWindow() {
        GuardPolicy.GuardSchedule window = new GuardPolicy.GuardSchedule(
                java.util.Set.of(java.time.DayOfWeek.MONDAY), 16 * 60, 120);
        GuardPolicy policy = new GuardPolicy(1, "UTC", true, false, true, 10, 8, 60,
                Collections.emptyMap(), Collections.emptySet(),
                Collections.singletonMap("com.example.game", java.util.List.of(window)));
        java.time.LocalDateTime monday = java.time.LocalDateTime.of(2026, 11, 9, 17, 0);
        java.time.LocalDateTime sunday = java.time.LocalDateTime.of(2026, 11, 8, 17, 0);
        assertFalse(GuardPolicyEngine.blockedPackages(policy, Collections.emptyMap(),
                Collections.emptySet(), monday).contains("com.example.game"));
        assertTrue(GuardPolicyEngine.blockedPackages(policy, Collections.emptyMap(),
                Collections.emptySet(), sunday).contains("com.example.game"));
    }

    @Test
    public void overnightWindowContinuesIntoTheNextDay() {
        GuardPolicy.GuardSchedule window = new GuardPolicy.GuardSchedule(
                java.util.Set.of(java.time.DayOfWeek.FRIDAY), 22 * 60, 240);
        GuardPolicy policy = new GuardPolicy(1, "UTC", true, false, true, 10, 8, 60,
                Collections.emptyMap(), Collections.emptySet(),
                Collections.singletonMap("com.example.game", java.util.List.of(window)));
        java.time.LocalDateTime saturdayEarly = java.time.LocalDateTime.of(2026, 11, 14, 1, 0);
        java.time.LocalDateTime saturdayLate = java.time.LocalDateTime.of(2026, 11, 14, 3, 0);
        assertFalse(GuardPolicyEngine.blockedPackages(policy, Collections.emptyMap(),
                Collections.emptySet(), saturdayEarly).contains("com.example.game"));
        assertTrue(GuardPolicyEngine.blockedPackages(policy, Collections.emptyMap(),
                Collections.emptySet(), saturdayLate).contains("com.example.game"));
    }

    @Test
    public void rewardCannotBypassAScheduleWindow() {
        GuardPolicy.GuardSchedule window = new GuardPolicy.GuardSchedule(
                java.util.Set.of(java.time.DayOfWeek.MONDAY), 16 * 60, 60);
        GuardPolicy policy = new GuardPolicy(1, "UTC", true, false, true, 10, 8, 60,
                Collections.emptyMap(), Collections.emptySet(),
                Collections.singletonMap("com.example.game", java.util.List.of(window)));
        java.time.LocalDateTime outside = java.time.LocalDateTime.of(2026, 11, 9, 18, 0);
        assertTrue(GuardPolicyEngine.blockedPackages(policy, Collections.emptyMap(),
                Collections.singleton("com.example.game"), outside).contains("com.example.game"));
    }

    @Test
    public void multipleBudgetsAreEvaluatedIndependently() {
        Map<String, Integer> budgets = new LinkedHashMap<>();
        budgets.put("com.example.first", 1);
        budgets.put("com.example.second", 2);
        Map<String, Long> usage = Map.of("com.example.first", 60_000L, "com.example.second", 90_000L);
        assertEquals(Collections.singleton("com.example.first"), GuardPolicyEngine.blockedPackages(
                policy(true, budgets, Collections.emptySet()), usage, Collections.emptySet()));
    }

    @Test
    public void alwaysAllowedBypassesBudgetAndScheduleButNotPermanentBlocks() {
        GuardPolicy.GuardSchedule window = new GuardPolicy.GuardSchedule(
                java.util.Set.of(java.time.DayOfWeek.MONDAY), 16 * 60, 60);
        GuardPolicy policy = new GuardPolicy(2, "UTC", true, false, true, 10, 8, 60,
                Collections.singletonMap("com.example.phone", 0), Collections.emptySet(),
                Collections.singletonMap("com.example.phone", java.util.List.of(window)),
                Collections.emptyList(), GuardPolicy.QuizSettings.defaults(), Collections.emptyList(),
                java.util.Set.of("com.example.phone"));
        java.time.LocalDateTime outside = java.time.LocalDateTime.of(2026, 11, 9, 18, 0);
        assertTrue(GuardPolicyEngine.blockedPackages(policy,
                Collections.singletonMap("com.example.phone", 60_000L), Collections.emptySet(),
                outside).isEmpty());
    }
}
