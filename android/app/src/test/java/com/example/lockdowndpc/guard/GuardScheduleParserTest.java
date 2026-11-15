package com.example.lockdowndpc.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.time.DayOfWeek;
import java.util.Set;

public final class GuardScheduleParserTest {
    @Test
    public void parsesAnOvernightWeekdayWindow() {
        GuardPolicy.GuardSchedule schedule = GuardScheduleParser.parse("22:30-02:00", "MON,WED,FRI");
        assertEquals(22 * 60 + 30, schedule.startMinute());
        assertEquals(210, schedule.durationMinutes());
        assertEquals(Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), schedule.days());
    }

    @Test
    public void starAndEverydaySelectTheWholeWeek() {
        assertEquals(7, GuardScheduleParser.parse("08:00-09:00", "*").days().size());
        assertEquals(7, GuardScheduleParser.parse("08:00-09:00", "everyday").days().size());
    }

    @Test
    public void identicalStartAndEndMeansAllDay() {
        assertEquals(1440, GuardScheduleParser.parse("00:00-00:00", "MON").durationMinutes());
    }

    @Test
    public void invalidWindowsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GuardScheduleParser.parse("25:00-26:00", "MON"));
        assertThrows(IllegalArgumentException.class, () -> GuardScheduleParser.parse("10:70-12:00", "MON"));
        assertThrows(IllegalArgumentException.class, () -> GuardScheduleParser.parse("10:00", "MON"));
    }

    @Test
    public void invalidDaysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GuardScheduleParser.parse("10:00-11:00", "FUNDAY"));
        assertThrows(IllegalArgumentException.class, () -> GuardScheduleParser.parse("10:00-11:00", ","));
    }

    @Test
    public void descriptionRoundTripsReadableHours() {
        assertEquals("22:30-02:00 MON",
                GuardScheduleParser.describe(GuardScheduleParser.parse("22:30-02:00", "MON")));
    }
}
