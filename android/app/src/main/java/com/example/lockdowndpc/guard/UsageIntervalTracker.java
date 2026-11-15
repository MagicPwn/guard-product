package com.example.lockdowndpc.guard;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Converts a stream of usage events into foreground intervals.
 *
 * <p>Pure Java so the accounting rules are unit-testable. An app can have more
 * than one resumed activity (split screen, a dialog activity, a second task), so
 * a single package timestamp undercounts or resets the interval when activities
 * interleave. This tracker counts resumed activity classes per package and only
 * closes the interval when the last one pauses or stops. Screen-off and shutdown
 * flush every open interval because those transitions do not always emit a
 * lifecycle event for each activity.
 */
public final class UsageIntervalTracker {
    public enum Kind { RESUMED, PAUSED, STOPPED, SCREEN_OFF, DEVICE_SHUTDOWN }

    private final long windowStart;
    private final long windowEnd;
    private final Map<String, Set<String>> activeClasses = new HashMap<>();
    private final Map<String, Long> activeSince = new HashMap<>();
    private final Map<String, Long> totals = new HashMap<>();

    public UsageIntervalTracker(long windowStart, long windowEnd) {
        if (windowEnd < windowStart) {
            throw new IllegalArgumentException("Window end precedes start");
        }
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    public void event(Kind kind, String packageName, String className, long timestamp) {
        if (kind == null) return;
        if (kind == Kind.SCREEN_OFF || kind == Kind.DEVICE_SHUTDOWN) {
            flush(timestamp);
            return;
        }
        if (packageName == null || packageName.isEmpty()) return;
        String activity = className == null || className.isEmpty() ? "default" : className;
        if (kind == Kind.RESUMED) {
            Set<String> classes = activeClasses.computeIfAbsent(packageName, key -> new HashSet<>());
            if (classes.add(activity) && classes.size() == 1) {
                activeSince.put(packageName, timestamp);
            }
        } else {
            Set<String> classes = activeClasses.get(packageName);
            if (classes == null) return;
            if (classes.remove(activity) && classes.isEmpty()) {
                close(packageName, timestamp);
                activeClasses.remove(packageName);
            }
        }
    }

    public Map<String, Long> finish() {
        flush(windowEnd);
        return Collections.unmodifiableMap(new HashMap<>(totals));
    }

    private void flush(long timestamp) {
        for (String packageName : new HashSet<>(activeSince.keySet())) {
            close(packageName, timestamp);
        }
        activeClasses.clear();
        activeSince.clear();
    }

    private void close(String packageName, long timestamp) {
        Long started = activeSince.remove(packageName);
        if (started == null) return;
        long from = Math.max(windowStart, started);
        long to = Math.min(windowEnd, Math.max(from, timestamp));
        if (to > from) {
            totals.merge(packageName, to - from, Long::sum);
        }
    }
}
