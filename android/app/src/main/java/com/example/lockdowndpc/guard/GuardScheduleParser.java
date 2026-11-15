package com.example.lockdowndpc.guard;

import java.time.DayOfWeek;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the compact administrator syntax used by the Guard console. */
public final class GuardScheduleParser {
    private static final Pattern WINDOW = Pattern.compile(
            "^\\s*(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\s*$");

    private GuardScheduleParser() {}

    public static GuardPolicy.GuardSchedule parse(String window, String days) {
        Matcher matcher = WINDOW.matcher(window == null ? "" : window);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Use HH:mm-HH:mm, for example 16:00-18:00");
        }
        int startHour = Integer.parseInt(matcher.group(1));
        int startMinute = Integer.parseInt(matcher.group(2));
        int endHour = Integer.parseInt(matcher.group(3));
        int endMinute = Integer.parseInt(matcher.group(4));
        if (startHour > 23 || endHour > 23 || startMinute > 59 || endMinute > 59) {
            throw new IllegalArgumentException("Time is outside 00:00-23:59");
        }
        int start = startHour * 60 + startMinute;
        int end = endHour * 60 + endMinute;
        int duration = end == start ? 1440 : Math.floorMod(end - start, 1440);
        return new GuardPolicy.GuardSchedule(parseDays(days), start, duration);
    }

    public static Set<DayOfWeek> parseDays(String days) {
        String source = days == null ? "" : days.trim().toUpperCase(Locale.ROOT);
        Set<DayOfWeek> result = new LinkedHashSet<>();
        if (source.isEmpty() || source.equals("*") || source.equals("EVERYDAY")
                || source.equals("ALL")) {
            result.addAll(java.util.EnumSet.allOf(DayOfWeek.class));
            return result;
        }
        for (String token : source.split("[,\\s]+")) {
            if (token.isEmpty()) continue;
            String normalized = token.length() >= 3 ? token.substring(0, 3) : token;
            switch (normalized) {
                case "MON" -> result.add(DayOfWeek.MONDAY);
                case "TUE" -> result.add(DayOfWeek.TUESDAY);
                case "WED" -> result.add(DayOfWeek.WEDNESDAY);
                case "THU" -> result.add(DayOfWeek.THURSDAY);
                case "FRI" -> result.add(DayOfWeek.FRIDAY);
                case "SAT" -> result.add(DayOfWeek.SATURDAY);
                case "SUN" -> result.add(DayOfWeek.SUNDAY);
                default -> throw new IllegalArgumentException("Unknown day: " + token);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Choose at least one day");
        return result;
    }

    public static String describe(GuardPolicy.GuardSchedule schedule) {
        StringBuilder days = new StringBuilder();
        for (DayOfWeek day : schedule.days()) {
            if (days.length() > 0) days.append(',');
            days.append(day.name(), 0, 3);
        }
        int end = Math.floorMod(schedule.startMinute() + schedule.durationMinutes(), 1440);
        return String.format(Locale.ROOT, "%02d:%02d-%02d:%02d %s",
                schedule.startMinute() / 60, schedule.startMinute() % 60,
                end / 60, end % 60, days);
    }
}
