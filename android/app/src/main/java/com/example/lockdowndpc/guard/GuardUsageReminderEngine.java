package com.example.lockdowndpc.guard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Pure evaluation of child-facing well-being reminders from signed policy limits. */
public final class GuardUsageReminderEngine {
    public enum Reason {
        BUDGET_WARNING,
        CONTINUOUS_USE
    }

    public static final class Reminder {
        private final Reason reason;
        private final String packageName;
        private final int minutes;

        private Reminder(Reason reason, String packageName, int minutes) {
            this.reason = reason;
            this.packageName = packageName;
            this.minutes = minutes;
        }

        public Reason reason() { return reason; }
        public String packageName() { return packageName; }
        public int minutes() { return minutes; }
    }

    /** Persistent per-day and per-foreground-session reminder de-duplication. */
    public static final class State {
        private String day = "";
        private String foreground = "";
        private long foregroundSince;
        private boolean continuousFired;
        private final Set<String> budgetWarnedToday = new LinkedHashSet<>();

        public State() {}

        public String day() { return day; }
        public String foreground() { return foreground; }
        public long foregroundSince() { return foregroundSince; }
        public boolean continuousFired() { return continuousFired; }
        public Set<String> budgetWarnedToday() { return budgetWarnedToday; }

        private void prepareDay(String nextDay) {
            if (nextDay == null || nextDay.isEmpty() || nextDay.equals(day)) return;
            day = nextDay;
            resetForeground();
            budgetWarnedToday.clear();
        }

        private void updateForeground(String nextForeground, long nowMillis) {
            String next = nextForeground == null ? "" : nextForeground;
            if (next.equals(foreground) && foregroundSince > 0 && nowMillis >= foregroundSince) {
                return;
            }
            foreground = next;
            foregroundSince = next.isEmpty() ? 0L : Math.max(1L, nowMillis);
            continuousFired = false;
        }

        private void resetForeground() {
            foreground = "";
            foregroundSince = 0L;
            continuousFired = false;
        }

        public String toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("day", day);
                json.put("foreground", foreground);
                json.put("foreground_since", foregroundSince);
                json.put("continuous_fired", continuousFired);
                JSONArray warned = new JSONArray();
                for (String packageName : budgetWarnedToday) warned.put(packageName);
                json.put("budget_warned_today", warned);
                return json.toString();
            } catch (JSONException exception) {
                throw new IllegalStateException(exception);
            }
        }

        public static State fromJson(String raw) {
            State state = new State();
            if (raw == null || raw.trim().isEmpty()) return state;
            try {
                JSONObject json = new JSONObject(raw);
                state.day = json.optString("day", "");
                state.foreground = json.optString("foreground", "");
                state.foregroundSince = Math.max(0L, json.optLong("foreground_since", 0L));
                state.continuousFired = json.optBoolean("continuous_fired", false);
                JSONArray warned = json.optJSONArray("budget_warned_today");
                if (warned != null) {
                    for (int index = 0; index < warned.length(); index++) {
                        String packageName = warned.optString(index, "");
                        if (!packageName.isEmpty()) state.budgetWarnedToday.add(packageName);
                    }
                }
                return state;
            } catch (JSONException exception) {
                return new State();
            }
        }
    }

    private GuardUsageReminderEngine() {}

    public static Reminder evaluate(GuardPolicy policy, Map<String, Long> usageMillis,
            String foregroundPackage, LocalDateTime localTime, long nowMillis, State state) {
        if (policy == null || state == null) return null;
        if (!policy.enabled()) {
            state.resetForeground();
            return null;
        }
        state.prepareDay(localTime.toLocalDate().toString());
        String foreground = foregroundPackage == null ? "" : foregroundPackage;
        state.updateForeground(foreground, nowMillis);
        if (foreground.isEmpty()
                || policy.blockedPackages().contains(foreground)
                || !policy.managesPackage(foreground)
                || !policy.isWithinSchedule(foreground, localTime)) {
            state.resetForeground();
            return null;
        }

        Map<String, Long> usage = usageMillis == null ? java.util.Collections.emptyMap() : usageMillis;
        Integer budgetMinutes = policy.dailyBudgets().get(foreground);
        long usedMillis = Math.max(0L, usage.getOrDefault(foreground, 0L));
        long remainingMillis = budgetMinutes == null ? Long.MAX_VALUE
                : budgetMinutes * 60_000L - usedMillis;
        if (budgetMinutes != null && remainingMillis <= 0L) {
            state.resetForeground();
            return null;
        }

        if (budgetMinutes != null && policy.budgetWarningMinutes() > 0
                && remainingMillis <= policy.budgetWarningMinutes() * 60_000L
                && state.budgetWarnedToday.add(foreground)) {
            return new Reminder(Reason.BUDGET_WARNING, foreground,
                    Math.max(1, minutesCeiling(remainingMillis)));
        }

        if (policy.continuousUseReminderMinutes() > 0 && !state.continuousFired
                && state.foregroundSince > 0 && nowMillis >= state.foregroundSince
                && nowMillis - state.foregroundSince
                        >= policy.continuousUseReminderMinutes() * 60_000L) {
            state.continuousFired = true;
            return new Reminder(Reason.CONTINUOUS_USE, foreground,
                    Math.max(1, (int) ((nowMillis - state.foregroundSince) / 60_000L)));
        }
        return null;
    }

    private static int minutesCeiling(long millis) {
        long minutes = (Math.max(0L, millis) + 59_999L) / 60_000L;
        return minutes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) minutes;
    }
}
