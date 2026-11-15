package com.example.lockdowndpc.guard;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class GuardPolicyEngine {
    private GuardPolicyEngine() {}

    public static Set<String> blockedPackages(GuardPolicy policy, Map<String, Long> usageMillis,
            Set<String> rewardedPackages) {
        return blockedPackages(policy, usageMillis, rewardedPackages,
                ZonedDateTime.now(ZoneId.of(policy.timezone())).toLocalDateTime());
    }

    public static Set<String> blockedPackages(GuardPolicy policy, Map<String, Long> usageMillis,
            Set<String> rewardedPackages, LocalDateTime localTime) {
        Set<String> result = new HashSet<>();
        if (!policy.enabled()) {
            return result;
        }
        result.addAll(policy.blockedPackages());
        Set<String> scheduled = new HashSet<>(policy.schedules().keySet());
        scheduled.addAll(policy.dailyBudgets().keySet());
        for (String packageName : scheduled) {
            if (policy.blockedPackages().contains(packageName)) continue;
            if (policy.isAlwaysAllowed(packageName)) continue;
            boolean outsideWindow = !policy.isWithinSchedule(packageName, localTime);
            boolean rewarded = rewardedPackages != null && rewardedPackages.contains(packageName);
            if (outsideWindow) {
                result.add(packageName);
            } else if (!rewarded && policy.dailyBudgets().containsKey(packageName)) {
                long budget = policy.dailyBudgets().get(packageName) * 60_000L;
                if (usageMillis.getOrDefault(packageName, 0L) >= budget) {
                    result.add(packageName);
                }
            }
        }
        return result;
    }
}
