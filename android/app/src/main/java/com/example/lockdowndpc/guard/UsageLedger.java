package com.example.lockdowndpc.guard;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class UsageLedger {
    private static final long LOOKBACK_MILLIS = 2L * 24L * 60L * 60L * 1000L;
    private static final long FOREGROUND_LOOKBACK_MILLIS = 2L * 60L * 60L * 1000L;

    private UsageLedger() {}

    public static boolean hasUsageAccess(Context context) {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        if (appOps == null) return false;
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    public static Map<String, Long> today(Context context) {
        return today(context, ZoneId.systemDefault().getId());
    }

    public static Map<String, Long> today(Context context, String timezone) {
        if (!hasUsageAccess(context)) return Collections.emptyMap();
        UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
        if (manager == null) return Collections.emptyMap();
        ZoneId zone = ZoneId.of(timezone);
        long start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        long end = System.currentTimeMillis();
        UsageEvents events = manager.queryEvents(Math.max(0L, start - LOOKBACK_MILLIS), end);
        UsageIntervalTracker tracker = new UsageIntervalTracker(start, end);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            UsageIntervalTracker.Kind kind = kind(event.getEventType());
            if (kind == null) continue;
            String packageName = event.getPackageName();
            if (packageName == null || packageName.equals(context.getPackageName())) continue;
            tracker.event(kind, packageName, event.getClassName(), event.getTimeStamp());
        }
        return tracker.finish();
    }

    /**
     * Returns the most recently resumed non-ignored package.
     *
     * <p>Guard, HOME, the dialer and essential system surfaces are deliberately
     * ignored. Resuming one of them closes the managed-app foreground session,
     * so an app-enter or continuous-use rule cannot keep counting behind a
     * visible Guard prompt or the lock screen.
     */
    public static String foregroundPackage(Context context, Set<String> ignored) {
        if (!hasUsageAccess(context)) return null;
        UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
        if (manager == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents events = manager.queryEvents(now - FOREGROUND_LOOKBACK_MILLIS, now);
        if (events == null) return null;
        Set<String> ignoredPackages = ignored == null ? Collections.emptySet() : ignored;
        Map<String, Set<String>> activeClasses = new HashMap<>();
        Map<String, Long> lastResumed = new LinkedHashMap<>();
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            String packageName = event.getPackageName();
            if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                    || type == UsageEvents.Event.KEYGUARD_SHOWN
                    || type == UsageEvents.Event.DEVICE_SHUTDOWN) {
                activeClasses.clear();
                lastResumed.clear();
                continue;
            }
            if (packageName == null || packageName.isEmpty()
                    || packageName.equals(context.getPackageName())) {
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    activeClasses.clear();
                    lastResumed.clear();
                }
                continue;
            }
            String className = event.getClassName();
            String activity = className == null || className.isEmpty() ? "default" : className;
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (ignoredPackages.contains(packageName)) {
                    activeClasses.clear();
                    lastResumed.clear();
                    continue;
                }
                activeClasses.computeIfAbsent(packageName, key -> new HashSet<>()).add(activity);
                lastResumed.put(packageName, event.getTimeStamp());
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED
                    || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                Set<String> classes = activeClasses.get(packageName);
                if (classes == null) continue;
                classes.remove(activity);
                if (classes.isEmpty()) {
                    activeClasses.remove(packageName);
                    lastResumed.remove(packageName);
                }
            }
        }
        String selected = null;
        long latest = Long.MIN_VALUE;
        for (Map.Entry<String, Long> entry : lastResumed.entrySet()) {
            Set<String> classes = activeClasses.get(entry.getKey());
            if (classes == null || classes.isEmpty()) continue;
            if (entry.getValue() >= latest) {
                latest = entry.getValue();
                selected = entry.getKey();
            }
        }
        return selected;
    }

    private static UsageIntervalTracker.Kind kind(int eventType) {
        if (eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
            return UsageIntervalTracker.Kind.RESUMED;
        }
        if (eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
            return UsageIntervalTracker.Kind.PAUSED;
        }
        if (eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
            return UsageIntervalTracker.Kind.STOPPED;
        }
        if (eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                || eventType == UsageEvents.Event.KEYGUARD_SHOWN) {
            return UsageIntervalTracker.Kind.SCREEN_OFF;
        }
        if (eventType == UsageEvents.Event.DEVICE_SHUTDOWN) {
            return UsageIntervalTracker.Kind.DEVICE_SHUTDOWN;
        }
        return null;
    }
}
