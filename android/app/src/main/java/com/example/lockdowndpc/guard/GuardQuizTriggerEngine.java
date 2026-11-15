package com.example.lockdowndpc.guard;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure evaluation of signed quiz-trigger rules.
 *
 * <p>The monitor supplies a foreground sample and current usage totals. This
 * class owns only deterministic time/session state; it never starts an activity
 * or suspends a package, so every boundary can be tested on the JVM.
 */
public final class GuardQuizTriggerEngine {
    private static final long SCHEDULE_START_GRACE_MILLIS = 30L * 60L * 1000L;

    private GuardQuizTriggerEngine() {}

    public static final class State {
        private String day = "";
        private String foreground = "";
        private final Map<String, Long> sessionSince = new LinkedHashMap<>();
        private final Set<String> sessionFired = new LinkedHashSet<>();
        private final Set<String> firedToday = new LinkedHashSet<>();

        public State() {}

        public String day() { return day; }
        public String foreground() { return foreground; }
        public boolean hasFiredToday(String key) { return firedToday.contains(key); }

        public void markFiredToday(String key) {
            if (key != null && !key.isEmpty()) firedToday.add(key);
        }

        public void prepareDay(String nextDay) {
            if (nextDay == null || nextDay.equals(day)) return;
            day = nextDay;
            foreground = "";
            sessionSince.clear();
            sessionFired.clear();
            firedToday.clear();
        }

        public String toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("day", day);
                json.put("foreground", foreground);
                JSONObject since = new JSONObject();
                for (Map.Entry<String, Long> entry : sessionSince.entrySet()) {
                    since.put(entry.getKey(), entry.getValue());
                }
                json.put("session_since", since);
                json.put("session_fired", array(sessionFired));
                json.put("fired_today", array(firedToday));
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
                JSONObject since = json.optJSONObject("session_since");
                if (since != null) {
                    java.util.Iterator<String> keys = since.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        state.sessionSince.put(key, since.getLong(key));
                    }
                }
                readStrings(json.optJSONArray("session_fired"), state.sessionFired);
                readStrings(json.optJSONArray("fired_today"), state.firedToday);
                return state;
            } catch (JSONException exception) {
                return new State();
            }
        }

        private static void readStrings(JSONArray source, Set<String> target) throws JSONException {
            if (source == null) return;
            for (int index = 0; index < source.length(); index++) {
                target.add(source.getString(index));
            }
        }

        private static JSONArray array(Set<String> values) {
            JSONArray array = new JSONArray();
            for (String value : values) array.put(value);
            return array;
        }
    }

    public static final class Decision {
        private final GuardPolicy.QuizTrigger trigger;
        private final List<String> targets;

        private Decision(GuardPolicy.QuizTrigger trigger, List<String> targets) {
            this.trigger = trigger;
            this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
        }

        public GuardPolicy.QuizTrigger trigger() { return trigger; }
        public List<String> targets() { return targets; }
    }

    public static Decision evaluate(GuardPolicy policy, Map<String, Long> usageMillis,
            String foregroundPackage, LocalDateTime localTime, long nowMillis,
            Set<String> rewardedPackages, State state) {
        if (policy == null || state == null || !policy.enabled() || !policy.quizGateEnabled()) {
            return null;
        }
        Map<String, Long> usage = usageMillis == null ? Collections.emptyMap() : usageMillis;
        Set<String> rewarded = rewardedPackages == null
                ? Collections.emptySet() : rewardedPackages;
        String today = localTime.toLocalDate().toString();
        state.prepareDay(today);
        updateForegroundSession(state, foregroundPackage, nowMillis);

        for (GuardPolicy.QuizTrigger trigger : policy.quizTriggers()) {
            List<String> targets = null;
            switch (trigger.type()) {
                case TOTAL_USAGE:
                    targets = totalUsageTargets(policy, trigger, usage, rewarded, state, today);
                    break;
                case APP_ENTER:
                    targets = appEnterTargets(policy, trigger, foregroundPackage, localTime, nowMillis,
                            rewarded, state);
                    break;
                case CONTINUOUS_USE:
                    targets = continuousTargets(policy, trigger, foregroundPackage, nowMillis,
                            rewarded, state);
                    break;
                case SCHEDULE_START:
                    targets = scheduleStartTargets(policy, trigger, localTime, nowMillis,
                            rewarded, state, today);
                    break;
                default:
                    break;
            }
            if (targets != null && !targets.isEmpty()) {
                return new Decision(trigger, targets);
            }
        }
        return null;
    }

    private static List<String> totalUsageTargets(GuardPolicy policy, GuardPolicy.QuizTrigger trigger,
            Map<String, Long> usage, Set<String> rewarded, State state, String today) {
        String dayKey = trigger.id() + "@" + today;
        if (state.firedToday.contains(dayKey)) return null;
        long total = 0L;
        for (String packageName : trigger.packages()) {
            total += Math.max(0L, usage.getOrDefault(packageName, 0L));
        }
        if (total < trigger.minutes() * 60_000L) return null;
        List<String> targets = notRewarded(policy, trigger.packages(), rewarded);
        if (targets.isEmpty()) return null;
        state.firedToday.add(dayKey);
        return targets;
    }

    private static List<String> appEnterTargets(GuardPolicy policy, GuardPolicy.QuizTrigger trigger,
            String foregroundPackage, LocalDateTime localTime, long nowMillis,
            Set<String> rewarded, State state) {
        if (foregroundPackage == null || !trigger.packages().contains(foregroundPackage)) return null;
        if (rewarded.contains(foregroundPackage)
                || policy.isAlwaysAllowed(foregroundPackage)) return null;
        String sessionKey = trigger.id() + "|" + foregroundPackage;
        if (state.sessionFired.contains(sessionKey)) return null;
        Long since = state.sessionSince.get(sessionKey);
        if (since == null) {
            state.sessionSince.put(sessionKey, nowMillis);
            since = nowMillis;
        }
        if (nowMillis - since < trigger.delaySeconds() * 1000L) return null;
        state.sessionFired.add(sessionKey);
        return Collections.singletonList(foregroundPackage);
    }

    private static List<String> continuousTargets(GuardPolicy policy, GuardPolicy.QuizTrigger trigger,
            String foregroundPackage, long nowMillis, Set<String> rewarded, State state) {
        if (foregroundPackage == null || !trigger.packages().contains(foregroundPackage)) return null;
        if (rewarded.contains(foregroundPackage)
                || policy.isAlwaysAllowed(foregroundPackage)) return null;
        String sessionKey = trigger.id() + "|" + foregroundPackage;
        if (state.sessionFired.contains(sessionKey)) return null;
        Long since = state.sessionSince.get(sessionKey);
        if (since == null) {
            state.sessionSince.put(sessionKey, nowMillis);
            since = nowMillis;
        }
        if (nowMillis - since < trigger.minutes() * 60_000L) return null;
        state.sessionFired.add(sessionKey);
        return Collections.singletonList(foregroundPackage);
    }

    private static List<String> scheduleStartTargets(GuardPolicy policy,
            GuardPolicy.QuizTrigger trigger, LocalDateTime localTime, long nowMillis,
            Set<String> rewarded, State state, String today) {
        if (!trigger.days().contains(localTime.getDayOfWeek())) return null;
        int minuteOfDay = localTime.getHour() * 60 + localTime.getMinute();
        if (minuteOfDay < trigger.startMinute()) return null;
        long elapsedSinceStart = ((long) minuteOfDay - trigger.startMinute()) * 60_000L
                + localTime.getSecond() * 1000L;
        if (elapsedSinceStart > SCHEDULE_START_GRACE_MILLIS) return null;

        List<String> candidates = new ArrayList<>();
        for (String packageName : trigger.packages()) {
            String dayKey = trigger.id() + "|" + packageName + "@" + today;
            if (state.firedToday.contains(dayKey) || rewarded.contains(packageName)) continue;
            if (policy.isAlwaysAllowed(packageName)) continue;
            if (!policy.isWithinSchedule(packageName, localTime)) continue;
            candidates.add(packageName);
        }
        if (candidates.isEmpty()) return null;
        for (String packageName : candidates) {
            state.firedToday.add(trigger.id() + "|" + packageName + "@" + today);
        }
        return candidates;
    }

    private static List<String> notRewarded(GuardPolicy policy, List<String> packages,
            Set<String> rewarded) {
        List<String> result = new ArrayList<>();
        for (String packageName : packages) {
            if (!rewarded.contains(packageName) && !policy.isAlwaysAllowed(packageName)) {
                result.add(packageName);
            }
        }
        return result;
    }

    private static void updateForegroundSession(State state, String foregroundPackage,
            long nowMillis) {
        String next = foregroundPackage == null ? "" : foregroundPackage;
        if (next.equals(state.foreground)) return;
        state.foreground = next;
        state.sessionSince.clear();
        state.sessionFired.clear();
    }
}
