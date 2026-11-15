package com.example.lockdowndpc.guard;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses the persisted task requests raised by one monitor sample.
 *
 * <p>A signed daily-budget exhaustion has priority over quiz triggers. When it
 * applies to the foreground package, that package is removed from the trigger
 * targets so the task page reports the budget reason instead of being replaced
 * by an app-entry rule. Other trigger targets remain eligible.
 */
public final class GuardQuizRequestPlanner {
    private GuardQuizRequestPlanner() {}

    public static final class Plan {
        private final String budgetTarget;
        private final GuardPolicy.QuizTrigger trigger;
        private final List<String> triggerTargets;

        private Plan(String budgetTarget, GuardPolicy.QuizTrigger trigger,
                List<String> triggerTargets) {
            this.budgetTarget = budgetTarget;
            this.trigger = trigger;
            this.triggerTargets = Collections.unmodifiableList(
                    new ArrayList<>(triggerTargets));
        }

        public String budgetTarget() { return budgetTarget; }
        public GuardPolicy.QuizTrigger trigger() { return trigger; }
        public List<String> triggerTargets() { return triggerTargets; }
    }

    public static Plan plan(GuardPolicy policy, Map<String, Long> usageMillis,
            String foregroundPackage, LocalDateTime localTime, long nowMillis,
            Set<String> rewardedPackages, GuardQuizTriggerEngine.State state,
            boolean triggersAllowed) {
        if (policy == null || localTime == null) {
            return new Plan(null, null, Collections.emptyList());
        }
        if (state != null) state.prepareDay(localTime.toLocalDate().toString());
        GuardQuizTriggerEngine.Decision decision = triggersAllowed
                ? GuardQuizTriggerEngine.evaluate(policy, usageMillis, foregroundPackage,
                        localTime, nowMillis, rewardedPackages, state)
                : null;

        String budgetTarget = exhaustedBudgetTarget(policy, usageMillis, foregroundPackage,
                localTime, rewardedPackages, state);
        if (budgetTarget != null) {
            state.markFiredToday(budgetStateKey(policy, foregroundPackage, localTime));
        }

        if (decision == null) {
            return new Plan(budgetTarget, null, Collections.emptyList());
        }
        List<String> targets = new ArrayList<>();
        for (String target : decision.targets()) {
            if (!target.equals(budgetTarget)) targets.add(target);
        }
        return new Plan(budgetTarget, decision.trigger(), targets);
    }

    private static String exhaustedBudgetTarget(GuardPolicy policy,
            Map<String, Long> usageMillis, String foregroundPackage,
            LocalDateTime localTime, Set<String> rewardedPackages,
            GuardQuizTriggerEngine.State state) {
        if (!policy.enabled() || !policy.quizGateEnabled() || policy.rewardMinutes() <= 0
                || foregroundPackage == null || state == null) {
            return null;
        }
        Integer budgetMinutes = policy.dailyBudgets().get(foregroundPackage);
        if (budgetMinutes == null || policy.isAlwaysAllowed(foregroundPackage)
                || policy.blockedPackages().contains(foregroundPackage)) {
            return null;
        }
        if (rewardedPackages != null && rewardedPackages.contains(foregroundPackage)) {
            return null;
        }
        long usedMillis = usageMillis == null
                ? 0L : Math.max(0L, usageMillis.getOrDefault(foregroundPackage, 0L));
        if (usedMillis < budgetMinutes * 60_000L) return null;
        String key = budgetStateKey(policy, foregroundPackage, localTime);
        return state.hasFiredToday(key) ? null : foregroundPackage;
    }

    private static String budgetStateKey(GuardPolicy policy, String targetPackage,
            LocalDateTime localTime) {
        return "budget:" + policy.revision() + ":" + targetPackage
                + "@" + localTime.toLocalDate();
    }
}
