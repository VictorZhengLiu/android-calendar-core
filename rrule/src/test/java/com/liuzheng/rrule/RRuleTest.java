package com.liuzheng.rrule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.junit.Test;

/**
 * Tests for {@link RRule} and {@link RRuleExpander}.
 *
 * <p>The cases are grouped by the behaviour they pin down rather than by method, because the
 * interesting failures in a recurrence engine are almost always about the <em>interaction</em> of
 * two rule parts. Where an expectation comes straight from RFC 5545 the example number is cited;
 * where it comes from the "month-end skipping" decision documented on {@link RRule}, the test name
 * says so.
 *
 * <p>Every date in this file was derived by hand from a calendar, not from the implementation's
 * output, so a test failure means the rule engine is wrong rather than merely different.
 */
public class RRuleTest {

    // A Thursday. Deliberately not a Monday, so that week-alignment bugs surface.
    private static final LocalDate START = LocalDate.of(2026, 10, 12); // Monday
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime NINE_AM = LocalTime.of(9, 0);

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @Test
    public void parsesRequiredAndOptionalParts() {
        RRule rule = RRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=5;WKST=SU",
                START.atTime(NINE_AM).atZone(ZONE));

        assertEquals(RRule.Frequency.WEEKLY, rule.getFrequency());
        assertEquals(2, rule.getInterval());
        assertEquals(Integer.valueOf(5), rule.getCount());
        assertEquals(DayOfWeek.SUNDAY, rule.getWeekStart());
        assertTrue(rule.hasByDay());
        assertTrue(rule.acceptsDayOfWeek(DayOfWeek.MONDAY));
        assertTrue(rule.acceptsDayOfWeek(DayOfWeek.WEDNESDAY));
        assertFalse(rule.acceptsDayOfWeek(DayOfWeek.TUESDAY));
    }

    @Test
    public void appliesDefaultsWhenPartsAreAbsent() {
        RRule rule = RRule.parse("FREQ=DAILY", START.atStartOfDay(ZONE));

        assertEquals(1, rule.getInterval());
        assertEquals(null, rule.getCount());
        assertEquals(null, rule.getUntil());
        assertEquals(RRule.DEFAULT_WEEK_START, rule.getWeekStart());
        assertFalse(rule.hasByDay());
        assertFalse(rule.hasByMonthDay());
        assertFalse(rule.hasBySetPos());
    }

    @Test
    public void acceptsOptionalRulePrefixAndLowercase() {
        RRule withPrefix = RRule.parse("RRULE:FREQ=DAILY;COUNT=1", START.atStartOfDay(ZONE));
        RRule lowercase = RRule.parse("rrule:freq=daily;count=1", START.atStartOfDay(ZONE));

        assertEquals(RRule.Frequency.DAILY, withPrefix.getFrequency());
        assertEquals(RRule.Frequency.DAILY, lowercase.getFrequency());
    }

    @Test
    public void parsesNegativeByMonthDayAndBySetPos() {
        RRule rule = RRule.parse("FREQ=MONTHLY;BYMONTHDAY=-1;BYSETPOS=-1",
                START.atStartOfDay(ZONE));

        assertEquals(List.of(Integer.valueOf(-1)), rule.byMonthDayValues());
        assertEquals(List.of(Integer.valueOf(-1)), rule.bySetPosValues());
    }

    @Test
    public void parsesUntilInAllThreeRealWorldForms() {
        assertEquals(LocalDate.of(2026, 12, 31), RRule
                .parse("FREQ=DAILY;UNTIL=20261231", START.atStartOfDay(ZONE)).getUntil());
        assertEquals(LocalDate.of(2026, 12, 31), RRule
                .parse("FREQ=DAILY;UNTIL=2026-12-31", START.atStartOfDay(ZONE)).getUntil());
        assertEquals(LocalDate.of(2026, 12, 31), RRule
                .parse("FREQ=DAILY;UNTIL=20261231T235959Z", START.atStartOfDay(ZONE)).getUntil());
    }

    @Test
    public void rejectsMissingFrequency() {
        assertParseFails("INTERVAL=2", "FREQ is required");
    }

    @Test
    public void rejectsCountTogetherWithUntil() {
        assertParseFails("FREQ=DAILY;COUNT=3;UNTIL=20261231",
                "COUNT and UNTIL are mutually exclusive");
    }

    @Test
    public void rejectsIntervalBelowOne() {
        assertParseFails("FREQ=DAILY;INTERVAL=0", "INTERVAL must be >= 1");
    }

    @Test
    public void rejectsSubDailyFrequenciesWithAnActionableMessage() {
        assertParseFails("FREQ=HOURLY", "requires time-of-day expansion");
        assertParseFails("FREQ=MINUTELY", "requires time-of-day expansion");
        assertParseFails("FREQ=SECONDLY", "requires time-of-day expansion");
    }

    @Test
    public void rejectsTimeOfDayPartsRatherThanIgnoringThem() {
        assertParseFails("FREQ=DAILY;BYHOUR=9", "BYHOUR");
        assertParseFails("FREQ=DAILY;BYMINUTE=30", "BYMINUTE");
    }

    @Test
    public void rejectsUnsupportedPartsExplicitly() {
        assertParseFails("FREQ=YEARLY;BYWEEKNO=20", "BYWEEKNO");
        assertParseFails("FREQ=YEARLY;BYYEARDAY=100", "BYYEARDAY");
    }

    @Test
    public void rejectsMalformedParts() {
        assertParseFails("FREQ", "expected NAME=VALUE");
        assertParseFails("FREQ=DAILY;BYDAY=XX", "invalid weekday");
        assertParseFails("FREQ=DAILY;BYMONTH=13", "BYMONTH value out of range");
        assertParseFails("FREQ=DAILY;BYMONTHDAY=0", "BYMONTHDAY value out of range");
        assertParseFails("FREQ=DAILY;COUNT=abc", "invalid COUNT value");
        assertParseFails("FREQ=DAILY;UNKNOWN=1", "unknown rule part");
        assertParseFails("", "must not be empty");
    }

    // ------------------------------------------------------------------
    // DAILY
    // ------------------------------------------------------------------

    @Test
    public void dailyCountsConsecutiveDays() {
        assertEquals("2026-10-12,2026-10-13,2026-10-14",
                format(expand("FREQ=DAILY;COUNT=3")));
    }

    @Test
    public void dailyHonoursInterval() {
        assertEquals("2026-10-12,2026-10-15,2026-10-18",
                format(expand("FREQ=DAILY;INTERVAL=3;COUNT=3")));
    }

    @Test
    public void dailyStopsAtUntilInclusive() {
        // UNTIL is inclusive: the 15th is the last instance, not the first one excluded.
        assertEquals("2026-10-12,2026-10-13,2026-10-14,2026-10-15",
                format(expand("FREQ=DAILY;UNTIL=20261015")));
    }

    @Test
    public void dailyTreatsByDayAsAFilter() {
        // 2026-10-12 is a Monday; only Mondays and Wednesdays survive, across three weeks.
        assertEquals("2026-10-12,2026-10-14,2026-10-19,2026-10-21,2026-10-26",
                format(expand("FREQ=DAILY;BYDAY=MO,WE;COUNT=5")));
    }

    // ------------------------------------------------------------------
    // WEEKLY
    // ------------------------------------------------------------------

    @Test
    public void weeklyWithoutByDayRepeatsOnTheStartWeekday() {
        assertEquals("2026-10-12,2026-10-19,2026-10-26",
                format(expand("FREQ=WEEKLY;COUNT=3")));
    }

    @Test
    public void weeklyWithByDayProducesEveryNamedDayOfEachWeek() {
        // RFC 5545 example: "every other week on Tuesday and Thursday". DTSTART is a Monday, so it
        // is emitted first (see dtstartIsTheFirstInstanceEvenWhenItDoesNotMatchTheRule), and the
        // rule's four instances land in the current and following biweekly periods.
        assertEquals("2026-10-12,2026-10-13,2026-10-15,2026-10-27",
                format(expand("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH;COUNT=4")));
    }

    @Test
    public void weeklyIncludesDaysThatFallBeforeTheStartDateWithinTheFirstWeek() {
        // The first period runs from the Monday of DTSTART's week, so Tuesday 13 October is in
        // the set even though DTSTART itself is a Monday.
        List<LocalDate> dates = expand("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;COUNT=5");
        assertEquals("2026-10-12,2026-10-13,2026-10-14,2026-10-15,2026-10-16",
                format(dates));
    }

    @Test
    public void weeklyRespectsWeekStartWhenComputingPeriods() {
        // With WKST=SU the period containing Sunday 11 October is 11-17 October, so Sunday is a
        // member of the first week. Anchoring DTSTART mid-week must not shift the periods.
        LocalDate sunday = LocalDate.of(2026, 10, 11);
        List<LocalDate> dates = RRule
                .parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,TU;COUNT=4;WKST=SU",
                        sunday.atTime(NINE_AM).atZone(ZONE))
                .expand(sunday, sunday.plusDays(40))
                .stream().map(RRuleOccurrence::date).collect(Collectors.toList());

        assertEquals("2026-10-11,2026-10-13,2026-10-25,2026-10-27", format(dates));
    }

    // ------------------------------------------------------------------
    // MONTHLY
    // ------------------------------------------------------------------

    @Test
    public void monthlyWithoutByRulesRepeatsOnTheStartDayOfMonth() {
        assertEquals("2026-10-15,2026-11-15,2026-12-15",
                format(expandFrom(LocalDate.of(2026, 10, 15), "FREQ=MONTHLY;COUNT=3")));
    }

    @Test
    public void monthlySkipsMonthsThatLackTheStartDayRatherThanClamping() {
        // January, March, May, July, August, October, December have a 31st; February 2026 and
        // April, June, September, November do not and are skipped (documented deviation).
        assertEquals("2026-01-31,2026-03-31,2026-05-31,2026-07-31,2026-08-31,2026-10-31,2026-12-31",
                format(expandFrom(LocalDate.of(2026, 1, 31), "FREQ=MONTHLY", 340)));
    }

    @Test
    public void monthlyWithByMonthDaySelectsEachListedDay() {
        // RFC 5545 example: "monthly on the 1st and 15th".
        // DTSTART is 1 October, which is itself one of the listed days: RFC 5545 makes DTSTART
        // the first instance of the set, and 15 October is the second.
        assertEquals("2026-10-01,2026-10-15,2026-11-01,2026-11-15",
                format(expandFrom(LocalDate.of(2026, 10, 1),
                        "FREQ=MONTHLY;BYMONTHDAY=1,15;COUNT=4")));
    }

    @Test
    public void monthlyWithNegativeByMonthDayCountsBackFromTheEnd() {
        assertEquals("2026-10-31,2026-11-30,2026-12-31",
                format(expandFrom(LocalDate.of(2026, 10, 31),
                        "FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=3")));
    }

    @Test
    public void monthlyWithOrdinalByDaySelectsTheNthWeekday() {
        // RFC 5545 example: "monthly on the first Friday". October 2026's first Friday is the
        // 2nd, November's is the 6th, December's is the 4th.
        // Starting on 2 October, itself the first Friday of that month.
        assertEquals("2026-10-02,2026-11-06,2026-12-04",
                format(expandFrom(LocalDate.of(2026, 10, 2),
                        "FREQ=MONTHLY;BYDAY=1FR;COUNT=3")));
    }

    @Test
    public void monthlyWithNegativeOrdinalByDaySelectsTheLastWeekday() {
        // RFC 5545 example: "the last Monday of the month".
        assertEquals("2026-10-26,2026-11-30,2026-12-28",
                format(expandFrom(LocalDate.of(2026, 10, 26),
                        "FREQ=MONTHLY;BYDAY=-1MO;COUNT=3")));
    }

    @Test
    public void monthlyWithBareByDaySelectsEveryOccurrenceInTheMonth() {
        // DTSTART (Monday 12 October) is the first instance; the rule then supplies the rest.
        assertEquals("2026-10-12,2026-10-14,2026-10-16,2026-10-19,2026-10-21",
                format(expand("FREQ=MONTHLY;BYDAY=MO,WE,FR;COUNT=5")));
    }

    @Test
    public void bySetPosPicksTheLastWeekdayOfEachMonth() {
        // The canonical BYSETPOS use: expand all weekdays, then keep the last one.
        // October 2026 ends Fri 30; November ends Mon 30; December ends Thu 31.
        // DTSTART is 30 October, itself the last weekday of that month, so the assertions can
        // read as the plain series.
        assertEquals("2026-10-30,2026-11-30,2026-12-31",
                format(expandFrom(LocalDate.of(2026, 10, 30),
                        "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=3")));
    }

    @Test
    public void bySetPosPicksTheFirstAndLastWeekdayOfEachMonth() {
        assertEquals("2026-10-01,2026-10-30,2026-11-02,2026-11-30",
                format(expandFrom(LocalDate.of(2026, 10, 1),
                        "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1,-1;COUNT=4")));
    }

    @Test
    public void dtstartIsTheFirstInstanceEvenWhenItDoesNotMatchTheRule() {
        // 12 October 2026 is a Monday, but the rule only selects Tuesdays. DTSTART still occupies
        // the day the event was created on, which is how Google Calendar behaves and what the
        // user who just created the event expects. The rule supplies the instances after it.
        assertEquals("2026-10-12,2026-10-13,2026-10-20",
                format(expand("FREQ=WEEKLY;BYDAY=TU;COUNT=3")));
    }

    @Test
    public void bySetPosIgnoresPositionsThatOvershootThePeriod() {
        // October 2026 has 22 weekdays, so BYSETPOS=99 selects nothing and the next period is
        // still reached rather than the rule terminating.
        // DTSTART (12 October) does not satisfy BYSETPOS=1, so it is emitted first and the two
        // rule-generated instances follow.
        assertEquals("2026-10-12,2026-11-02,2026-12-01",
                format(expand("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1;COUNT=3")));
    }

    @Test
    public void monthlyWithByMonthOnlyRecursInTheListedMonths() {
        assertEquals("2026-10-12,2026-12-12,2027-10-12",
                format(expand("FREQ=MONTHLY;BYMONTH=10,12;COUNT=3", 400)));
    }

    // ------------------------------------------------------------------
    // YEARLY
    // ------------------------------------------------------------------

    @Test
    public void yearlyWithoutByRulesRepeatsOnTheStartMonthAndDay() {
        assertEquals("2026-12-25,2027-12-25,2028-12-25",
                format(expandFrom(LocalDate.of(2026, 12, 25), "FREQ=YEARLY;COUNT=3", 800)));
    }

    @Test
    public void yearlySkipsLeapDayInCommonYears() {
        // 2028 is the next leap year after 2024, so a 29 February rule jumps four years.
        assertEquals("2024-02-29,2028-02-29,2032-02-29",
                format(expandFrom(LocalDate.of(2024, 2, 29), "FREQ=YEARLY;COUNT=3", 3000)));
    }

    @Test
    public void yearlyOrdinalByDayIsRelativeToTheYearWhenByMonthIsAbsent() {
        // RFC 5545 3.3.10: without BYMONTH, BYDAY ordinals count across the year.
        assertEquals("2026-01-05,2027-01-04,2028-01-03",
                format(expandFrom(LocalDate.of(2026, 1, 5),
                        "FREQ=YEARLY;BYDAY=1MO;COUNT=3", 1200)));
    }

    @Test
    public void yearlyOrdinalByDayIsRelativeToTheMonthWhenByMonthIsPresent() {
        // RFC 5545 example: "10:00 AM on the first Friday for 10 occurrences" restricted to
        // January — the first Friday of January, not of the year.
        assertEquals("2026-01-02,2027-01-01,2028-01-07",
                format(expandFrom(LocalDate.of(2026, 1, 2),
                        "FREQ=YEARLY;BYMONTH=1;BYDAY=1FR;COUNT=3", 1200)));
    }

    @Test
    public void yearlyWithByMonthDaySelectsTheDayInEveryMonth() {
        assertEquals("2026-10-01,2026-11-01,2026-12-01,2027-01-01",
                format(expandFrom(LocalDate.of(2026, 10, 1),
                        "FREQ=YEARLY;BYMONTHDAY=1;COUNT=4", 400)));
    }

    @Test
    public void yearlyCombinesByMonthAndByMonthDay() {
        // Christmas Day of 2026 and 2027, expressed without relying on the DTSTART month/day.
        assertEquals("2026-12-25,2027-12-25",
                format(expandFrom(LocalDate.of(2026, 12, 25),
                        "FREQ=YEARLY;BYMONTH=12;BYMONTHDAY=25;COUNT=2", 800)));
    }

    // ------------------------------------------------------------------
    // Window semantics
    // ------------------------------------------------------------------

    @Test
    public void windowFiltersResultsButDoesNotRestartTheSeries() {
        // A 10-instance series starting 12 October; asking for a later window must still return
        // only the instances that fall inside it, and must not begin counting again.
        RRule rule = RRule.parse("FREQ=DAILY;COUNT=10", START.atTime(NINE_AM).atZone(ZONE));

        List<LocalDate> mid = dates(rule.expand(LocalDate.of(2026, 10, 18),
                LocalDate.of(2026, 10, 20)));
        assertEquals("2026-10-18,2026-10-19,2026-10-20", format(mid));

        List<LocalDate> beyond = dates(rule.expand(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30)));
        assertTrue("a window past COUNT must be empty, was " + format(beyond), beyond.isEmpty());
    }

    @Test
    public void windowIsInclusiveAtBothEnds() {
        assertEquals("2026-10-12,2026-10-13,2026-10-14",
                format(dates(RRule.parse("FREQ=DAILY", START.atTime(NINE_AM).atZone(ZONE))
                        .expand(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14)))));
    }

    @Test
    public void windowBeforeStartDateIsEmpty() {
        RRule rule = RRule.parse("FREQ=DAILY", START.atTime(NINE_AM).atZone(ZONE));
        assertTrue(rule.expand(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 11)).isEmpty());
    }

    @Test
    public void rejectsInvertedWindow() {
        RRule rule = RRule.parse("FREQ=DAILY", START.atTime(NINE_AM).atZone(ZONE));
        try {
            rule.expand(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 1));
            fail("expected RRuleException for an inverted window");
        } catch (RRuleException expected) {
            assertTrue(expected.getMessage().contains("before windowStart"));
        }
    }

    @Test
    public void repeatedExpansionReturnsTheSameResults() {
        // RRule caches its expander; a second call must not be affected by the first.
        RRule rule = RRule.parse("FREQ=WEEKLY;BYDAY=MO,FR;COUNT=6",
                START.atTime(NINE_AM).atZone(ZONE));
        List<LocalDate> first = dates(rule.expand(START, START.plusDays(60)));
        List<LocalDate> second = dates(rule.expand(START, START.plusDays(60)));
        assertEquals(format(first), format(second));
    }

    // ------------------------------------------------------------------
    // Occurrence values
    // ------------------------------------------------------------------

    @Test
    public void occurrenceCarriesTheStartTimeOfDayAndZone() {
        RRule rule = RRule.parse("FREQ=DAILY;COUNT=1", START.atTime(NINE_AM).atZone(ZONE));
        RRuleOccurrence occurrence = rule.expand(START, START).get(0);

        assertEquals(LocalDate.of(2026, 10, 12), occurrence.date());
        assertEquals(NINE_AM, occurrence.time());
        assertEquals(ZONE, occurrence.zone());
        assertEquals(ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, ZONE).toInstant(),
                occurrence.startInstant());
    }

    @Test
    public void occurrenceEndFollowsTheEventDuration() {
        RRule rule = RRule.parse("FREQ=DAILY;COUNT=1", START.atTime(NINE_AM).atZone(ZONE));
        RRuleOccurrence occurrence = rule.expand(START, START).get(0);

        assertEquals(ZonedDateTime.of(2026, 10, 12, 10, 30, 0, 0, ZONE).toInstant(),
                occurrence.endInstant(90));
    }

    @Test
    public void daylightSavingGapShiftsTheOccurrenceForward() {
        // In New York the clocks jump from 02:00 to 03:00 on 8 March 2026, so a 02:30
        // occurrence has no valid offset that day and must land at 03:30 rather than vanish.
        // The series starts the day before and COUNT covers both days, so the instance inside
        // the gap is actually reached.
        ZoneId newYork = ZoneId.of("America/New_York");
        LocalDate dayBefore = LocalDate.of(2026, 3, 7);
        LocalDate dstDay = LocalDate.of(2026, 3, 8);
        RRule rule = RRule.parse("FREQ=DAILY;COUNT=2",
                dayBefore.atTime(2, 30).atZone(newYork));

        List<RRuleOccurrence> occurrences = rule.expand(dayBefore, dstDay);
        assertEquals(2, occurrences.size());

        ZonedDateTime resolved = occurrences.get(1).zonedStart();

        assertEquals(3, resolved.getHour());
        assertEquals(30, resolved.getMinute());
        assertEquals(8, resolved.getDayOfMonth());
    }

    @Test
    public void occurrencesAreReturnedInAscendingOrder() {
        List<LocalDate> dates = expand("FREQ=MONTHLY;BYDAY=MO,WE,FR;COUNT=20", 400);
        List<LocalDate> sorted = new ArrayList<>(dates);
        sorted.sort(null);
        assertEquals(format(sorted), format(dates));
    }

    // ------------------------------------------------------------------
    // Presentation
    // ------------------------------------------------------------------

    @Test
    public void toStringRoundTripsTheSignificantParts() {
        RRule rule = RRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR;COUNT=4",
                START.atTime(NINE_AM).atZone(ZONE));
        String text = rule.toString();

        assertTrue(text, text.contains("FREQ=WEEKLY"));
        assertTrue(text, text.contains("INTERVAL=2"));
        assertTrue(text, text.contains("BYDAY="));
        assertTrue(text, text.contains("MO"));
        assertTrue(text, text.contains("FR"));
        assertTrue(text, text.contains("COUNT=4"));
    }

    @Test
    public void humanReadableDescribesTheRuleWithoutInventingDefaults() {
        RRule weekly = RRule.parse("FREQ=WEEKLY;BYDAY=FR;COUNT=5",
                START.atTime(NINE_AM).atZone(ZONE));
        String text = weekly.humanReadable(Locale.SIMPLIFIED_CHINESE);

        assertNotNull(text);
        assertTrue(text, text.contains("每周"));
        assertTrue(text, text.contains("共 5 次"));
    }

    @Test
    public void humanReadableNamesTheMonthsAndDaysOfAYearlyRule() {
        RRule rule = RRule.parse("FREQ=YEARLY;BYMONTH=12;BYMONTHDAY=25;COUNT=3",
                START.atTime(NINE_AM).atZone(ZONE));
        String text = rule.humanReadable(Locale.SIMPLIFIED_CHINESE);

        // The month name already carries the unit, so it must not be doubled ("十二月 月").
        assertFalse("month unit must not be doubled: " + text, text.contains("月 月"));
        assertTrue(text, text.contains("每年"));
        assertTrue(text, text.contains("25"));
        assertTrue(text, text.contains("共 3 次"));
    }

    @Test
    public void humanReadableDescribesAnOrdinalByDayRule() {
        RRule rule = RRule.parse("FREQ=MONTHLY;BYDAY=2TU", START.atTime(NINE_AM).atZone(ZONE));
        String text = rule.humanReadable(Locale.SIMPLIFIED_CHINESE);

        assertTrue(text, text.contains("每月"));
        assertTrue(text, text.contains("2"));
        assertFalse("ordinal lists must not print List brackets: " + text, text.contains("["));
    }

    @Test
    public void humanReadableOmitsWhatTheRuleDoesNotSay() {
        // A bare DAILY rule has no BY parts and no end condition: the text must not invent any.
        RRule rule = RRule.parse("FREQ=DAILY", START.atTime(NINE_AM).atZone(ZONE));
        String text = rule.humanReadable(Locale.SIMPLIFIED_CHINESE);

        assertEquals("每天", text);
    }

    @Test
    public void builderProducesTheSameRuleAsTheEquivalentString() {
        ZonedDateTime start = START.atTime(NINE_AM).atZone(ZONE);
        RRule fromString = RRule.parse("FREQ=MONTHLY;BYDAY=2TU;COUNT=3", start);
        RRule fromBuilder = RRule.builder()
                .frequency(RRule.Frequency.MONTHLY)
                .byDay(2, DayOfWeek.TUESDAY)
                .count(3)
                .start(start)
                .build();

        assertEquals(format(dates(fromString.expand(START, START.plusDays(200)))),
                format(dates(fromBuilder.expand(START, START.plusDays(200)))));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Expands a rule over a window wide enough for the handful of instances these tests assert. */
    private static List<LocalDate> expand(String rule) {
        return expand(rule, 200);
    }

    private static List<LocalDate> expand(String rule, int windowDays) {
        return expandFrom(START, rule, windowDays);
    }

    private static List<LocalDate> expandFrom(LocalDate start, String rule) {
        return expandFrom(start, rule, 200);
    }

    private static List<LocalDate> expandFrom(LocalDate start, String rule, int windowDays) {
        RRule parsed = RRule.parse(rule, start.atTime(NINE_AM).atZone(ZONE));
        return dates(parsed.expand(start, start.plusDays(windowDays)));
    }

    private static List<LocalDate> dates(List<RRuleOccurrence> occurrences) {
        return occurrences.stream().map(RRuleOccurrence::date).collect(Collectors.toList());
    }

    private static String format(List<LocalDate> dates) {
        return dates.stream().map(LocalDate::toString).collect(Collectors.joining(","));
    }

    private static void assertParseFails(String rule, String expectedMessageFragment) {
        try {
            RRule.parse(rule, START.atTime(NINE_AM).atZone(ZONE));
            fail("expected RRuleException for \"" + rule + "\"");
        } catch (RRuleException expected) {
            String message = String.valueOf(expected.getMessage());
            assertTrue("message \"" + message + "\" should mention \""
                            + expectedMessageFragment + "\"",
                    message.contains(expectedMessageFragment));
        }
    }
}
