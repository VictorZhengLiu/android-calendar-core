package com.liuzheng.rrule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Expands a {@link RRule} into concrete dates inside a query window.
 *
 * <p>The expander walks the rule period by period — a day, a week, a month or a year, depending
 * on {@code FREQ} — and within each period applies the {@code BY*} parts in the order RFC 5545
 * section 3.3.10 mandates: month, then day-of-month, then day-of-week, then {@code BYSETPOS}.
 * Applying them out of order is the classic way to produce a rule that looks right on simple
 * inputs and silently drifts on real calendars.
 *
 * <h2>Two behaviours worth calling out</h2>
 *
 * <ul>
 *   <li><strong>{@code COUNT} counts occurrences, not periods.</strong>
 *       {@code FREQ=MONTHLY;BYDAY=MO;COUNT=5} yields five Mondays, spanning five weeks, not five
 *       months.</li>
 *   <li><strong>The window is a filter, not a starting point.</strong> Walking always begins at
 *       {@code DTSTART}, so a query for next March still respects the {@code COUNT} and
 *       {@code UNTIL} limits of the rule as a whole.</li>
 * </ul>
 *
 * <p>Instances are not thread-safe; {@link RRule} creates and caches one per rule for its own
 * single-threaded use.
 */
final class RRuleExpander {

    private final RRule rule;

    RRuleExpander(RRule rule) {
        this.rule = rule;
    }

    /**
     * Expands {@link #rule} over an inclusive window.
     *
     * @param windowStart inclusive lower bound
     * @param windowEnd   inclusive upper bound
     * @return occurrences in ascending date order
     */
    List<RRuleOccurrence> expand(LocalDate windowStart, LocalDate windowEnd) {
        LocalDate startDate = rule.getStart().toLocalDate();
        Integer count = rule.getCount();
        LocalDate until = rule.getUntil();

        List<RRuleOccurrence> result = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();

        int emitted = 0;
        int startYear = startDate.getYear();

        // DTSTART is the first instance of the set even when it does not satisfy the BY* parts:
        // an event created on a Monday with a "every Tuesday" rule still occupies the Monday it
        // was created on. This matches Google Calendar, and it is what a user who just created the
        // event expects to see. The rule only decides the instances that follow.
        if (!startDate.isBefore(windowStart) && !startDate.isAfter(windowEnd)
                && (until == null || !startDate.isAfter(until))) {
            result.add(new RRuleOccurrence(startDate, rule.getStart().toLocalTime(),
                    rule.getStart().getZone()));
            seen.add(startDate);
            emitted = 1;
        }

        for (int periodIndex = 0; ; periodIndex++) {
            // Guard against a rule whose period step overflows the calendar; a caller asking for
            // dates far outside LocalDate's range is a bug, not a recurrence.
            if (periodIndex > MAX_PERIODS) {
                break;
            }

            LocalDate periodStart = periodStart(startDate, periodIndex);
            if (periodStart.getYear() > startYear + MAX_YEAR_SPAN) {
                break;
            }
            // Once the period has moved past the window there is nothing left to find.
            if (periodStart.isAfter(windowEnd)) {
                break;
            }

            List<LocalDate> candidates = candidatesOf(periodStart);
            if (candidates.isEmpty()) {
                continue;
            }

            for (LocalDate candidate : candidates) {
                if (count != null && emitted >= count) {
                    return result;
                }
                // Nothing before DTSTART belongs to the recurrence, and DTSTART itself was
                // already emitted above.
                if (!candidate.isAfter(startDate)) {
                    continue;
                }
                if (until != null && candidate.isAfter(until)) {
                    return result;
                }
                // COUNT limits the instances of the series, not the instances this particular
                // query returns. Every generated instance consumes one unit of the budget, even
                // when the window discards it — otherwise a window opened later in the series
                // would hand back more instances than COUNT allows.
                emitted++;
                if (!candidate.isBefore(windowStart) && !candidate.isAfter(windowEnd)
                        && seen.add(candidate)) {
                    result.add(new RRuleOccurrence(candidate, rule.getStart().toLocalTime(),
                            rule.getStart().getZone()));
                }
            }

            if (count != null && emitted >= count) {
                return result;
            }
            if (until != null && periodStart.isAfter(until)) {
                return result;
            }
        }

        Collections.sort(result);
        return result;
    }

    /** Hard ceiling on periods walked, so a pathological rule cannot spin forever. */
    private static final int MAX_PERIODS = 200_000;

    /** Hard ceiling on years spanned, well beyond any realistic calendar query. */
    private static final int MAX_YEAR_SPAN = 300;

    // ------------------------------------------------------------------
    // Period iteration
    // ------------------------------------------------------------------

    /**
     * @param startDate   the {@code DTSTART} date, which anchors period zero
     * @param periodIndex how many periods after the anchor to compute
     * @return the first date of that period, per {@code FREQ} and {@code INTERVAL}
     */
    private LocalDate periodStart(LocalDate startDate, int periodIndex) {
        int interval = rule.getInterval();
        switch (rule.getFrequency()) {
            case DAILY:
                return startDate.plusDays((long) interval * periodIndex);
            case WEEKLY:
                return weekStartOf(startDate).plusWeeks((long) interval * periodIndex);
            case MONTHLY:
                return startDate.withDayOfMonth(1).plusMonths((long) interval * periodIndex);
            case YEARLY:
                // Yearly periods are anchored on DTSTART's own month and day, not on 1 January.
                // Anchoring on the year boundary would put a rule such as
                // FREQ=YEARLY;BYMONTH=1;BYDAY=1FR into a period that starts before its own
                // DTSTART, and the first Friday of the following January would then be attributed
                // to the wrong period and skipped.
                return startDate.plusYears((long) interval * periodIndex);
            default:
                throw new RRuleException("unsupported frequency " + rule.getFrequency());
        }
    }

    /** @return the first day of the week containing {@code date}, honouring {@code WKST} */
    private LocalDate weekStartOf(LocalDate date) {
        DayOfWeek weekStart = rule.getWeekStart();
        int delta = (7 + date.getDayOfWeek().getValue() - weekStart.getValue()) % 7;
        return date.minusDays(delta);
    }

    // ------------------------------------------------------------------
    // Candidate generation
    // ------------------------------------------------------------------

    /**
     * Generates the candidate dates of one period, sorted and de-duplicated.
     *
     * @param periodStart the period's first date
     * @return candidates in ascending order, after {@code BY*} filtering and {@code BYSETPOS}
     */
    /** Test hook: exposes one period's candidates so a failing expectation can be located. */
    List<LocalDate> rawCandidatesForTest(LocalDate periodStart) {
        return new ArrayList<>(collectRawCandidates(periodStart));
    }

    private List<LocalDate> candidatesOf(LocalDate periodStart) {
        List<LocalDate> ordered = new ArrayList<>(collectRawCandidates(periodStart));
        ordered.sort(null);

        if (!rule.hasBySetPos()) {
            return ordered;
        }
        return applySetPos(ordered);
    }

    /** Collects the dates a period can produce, before {@code BYSETPOS} trims them. */
    private Set<LocalDate> collectRawCandidates(LocalDate periodStart) {
        Set<LocalDate> dates = new TreeSet<>();
        switch (rule.getFrequency()) {
            case DAILY:
                // A daily period is a single day; BYmonth/BYmonthday/BYday act as filters on it.
                if (acceptedByMonthDayAndDay(periodStart)) {
                    dates.add(periodStart);
                }
                break;
            case WEEKLY:
                addWeeklyCandidates(periodStart, dates);
                break;
            case MONTHLY:
                // With BYDAY present the weekday (and its ordinal, if any) picks the days, so
                // the start day-of-month is not a constraint. Without it, the anchor is the
                // start day and months lacking that day contribute nothing.
                int monthlyAnchor = rule.hasByDay() ? 0 : startDayOfMonthAnchor();
                addDailyCandidatesOfMonth(periodStart.getYear(), periodStart.getMonthValue(),
                        monthlyAnchor, dates);
                break;
            case YEARLY:
                addYearlyCandidates(periodStart.getYear(), dates);
                break;
            default:
                throw new RRuleException("unsupported frequency " + rule.getFrequency());
        }
        return dates;
    }

    private void addWeeklyCandidates(LocalDate periodStart, Set<LocalDate> dates) {
        boolean anchorWeekday = startDayOfMonthAnchor() != 0 && !rule.hasByDay();
        LocalDate startDate = rule.getStart().toLocalDate();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate day = periodStart.plusDays(offset);
            if (rule.hasByDay()) {
                if (!rule.acceptsDayOfWeek(day.getDayOfWeek())) {
                    continue;
                }
            } else if (anchorWeekday && day.getDayOfWeek() != startDate.getDayOfWeek()) {
                // A WEEKLY rule names no day: it repeats on the weekday of DTSTART.
                continue;
            }
            if (!rule.acceptsMonth(day.getMonthValue())) {
                continue;
            }
            if (rule.hasByMonthDay() && !matchByMonthDay(day)) {
                continue;
            }
            dates.add(day);
        }
    }

    /**
     * Adds every day of one month that survives the {@code BYMONTH}, {@code BYMONTHDAY},
     * {@code BYDAY} and anchor-day filters.
     *
     * @param year      the calendar year
     * @param month     1-12
     * @param anchorDay the day-of-month every candidate must fall on, or {@code 0} to accept any
     *                  day. Callers pass {@code DTSTART}'s day for {@code MONTHLY} periods, where
     *                  the period is a whole month; the {@code YEARLY} path passes the start
     *                  date's month so that the period behaves like a monthly one.
     * @param dates     the accumulator
     */
    private void addDailyCandidatesOfMonth(int year, int month, int anchorDay,
            Set<LocalDate> dates) {
        if (!rule.acceptsMonth(month)) {
            return;
        }
        LocalDate day = LocalDate.of(year, month, 1);
        int lengthOfMonth = day.lengthOfMonth();
        for (int d = 1; d <= lengthOfMonth; d++, day = day.plusDays(1)) {
            if (anchorDay != 0 && d != anchorDay) {
                // Nothing in the rule picks the day, so the anchor does. A month without that
                // day contributes no instance (never a clamped one).
                continue;
            }
            if (!acceptedByMonthDayAndDay(day)) {
                continue;
            }
            dates.add(day);
        }
    }

    /**
     * @return the day-of-month of {@code DTSTART}, or {@code 0} when {@code BYMONTHDAY},
     *         {@code BYDAY} or an ordinal {@code BYDAY} already selects the days. In those cases
     *         the start day is not an additional constraint, only the anchor of the first period.
     */
    private int startDayOfMonthAnchor() {
        if (rule.hasByMonthDay()) {
            return 0;
        }
        if (rule.hasByDay() && rule.ordinalByDayMask() == 0) {
            // A bare BYDAY names the weekdays outright.
            return 0;
        }
        return rule.getStart().toLocalDate().getDayOfMonth();
    }

    /**
     * The anchor to use inside a month when {@code BYMONTH} is present.
     *
     * <p>Unlike {@link #startDayOfMonthAnchor()}, an <em>ordinal</em> {@code BYDAY} does not
     * disqualify the anchor here: {@code FREQ=YEARLY;BYMONTH=1;BYDAY=1FR} means "the first Friday
     * of January, every year", so the day-of-month of {@code DTSTART} says nothing about which day
     * the rule picks. Applying the anchor would pin the rule to the start day and drop every year
     * whose January does not happen to contain it.
     *
     * @return the anchor day, or {@code 0} to accept any day the other parts select
     */
    private int staticMonthAnchor() {
        if (rule.hasByMonthDay() || rule.hasByDay()) {
            return 0;
        }
        return rule.getStart().toLocalDate().getDayOfMonth();
    }

    private boolean acceptedByMonthDayAndDay(LocalDate day) {
        if (!rule.acceptsMonth(day.getMonthValue())) {
            return false;
        }
        if (rule.hasByMonthDay() && !matchByMonthDay(day)) {
            return false;
        }
        return isSelectedByDay(day);
    }

    /**
     * @param day a date to test against {@code BYDAY}
     * @return {@code true} when the day is named by a bare weekday token, or matches an
     *         ordinal token relative to {@code day}'s month
     */
    private boolean isSelectedByDay(LocalDate day) {
        if (!rule.hasByDay()) {
            return true;
        }
        if ((rule.bareByDayMask() & RRule.maskFor(day.getDayOfWeek())) != 0) {
            return true;
        }
        if (!rule.hasOrdinalFor(day.getDayOfWeek())) {
            return false;
        }
        int occurrence = (day.getDayOfMonth() - 1) / 7 + 1;
        int fromEnd = -((day.lengthOfMonth() - day.getDayOfMonth()) / 7 + 1);
        for (int ordinal : rule.ordinalsFor(day.getDayOfWeek())) {
            if (ordinal == occurrence || ordinal == fromEnd) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param day a date to test against {@code BYMONTHDAY}
     * @return {@code true} when the day's position (or its position counting back from the
     *         month end) appears in {@code BYMONTHDAY}
     */
    private boolean matchByMonthDay(LocalDate day) {
        if (rule.hasByMonthDay(day.getDayOfMonth())) {
            return true;
        }
        int fromEnd = day.getDayOfMonth() - day.lengthOfMonth() - 1;
        return rule.hasByMonthDay(fromEnd);
    }

    // ------------------------------------------------------------------
    // Yearly candidates
    // ------------------------------------------------------------------

    private void addYearlyCandidates(int year, Set<LocalDate> dates) {
        LocalDate startDate = rule.getStart().toLocalDate();
        boolean byDayHasOrdinals = rule.ordinalByDayMask() != 0;
        boolean byMonthConstrains = rule.byMonthMask() != 0;

        // RFC 5545 3.3.10: without BYMONTH, an ordinal BYDAY in a YEARLY rule is relative to the
        // year ("the first Monday of 2026"), not to a month. With BYMONTH it is month-relative,
        // which the per-month loop below already handles.
        if (byDayHasOrdinals && !byMonthConstrains && !rule.hasByMonthDay()) {
            addYearRelativeOrdinalDays(year, dates);
            return;
        }

        if (byMonthConstrains) {
            // BYMONTH is present, so this is "the Nth Friday of January" style: the period behaves
            // like a monthly one and the anchor is decided the same way.
            int anchor = staticMonthAnchor();
            for (int month = 1; month <= 12; month++) {
                if (rule.acceptsMonth(month)) {
                    addDailyCandidatesOfMonth(year, month, anchor, dates);
                }
            }
        } else if (rule.hasByMonthDay()) {
            // BYMONTHDAY names the days outright; the start day is not a constraint.
            for (int month = 1; month <= 12; month++) {
                addDailyCandidatesOfMonth(year, month, 0, dates);
            }
        } else {
            addDailyCandidatesOfMonth(year, startDate.getMonthValue(),
                    startDate.getDayOfMonth(), dates);
        }
    }

    /** Adds the dates selected by {@code BYDAY} ordinals counted across the whole year. */
    private void addYearRelativeOrdinalDays(int year, Set<LocalDate> dates) {
        for (int weekday = 1; weekday <= 7; weekday++) {
            DayOfWeek day = DayOfWeek.of(weekday);
            for (int ordinal : rule.ordinalsFor(day)) {
                LocalDate resolved = nthWeekdayOfYear(year, day, ordinal);
                if (resolved != null && rule.acceptsMonth(resolved.getMonthValue())) {
                    dates.add(resolved);
                }
            }
        }
    }

    /**
     * @param year    the calendar year
     * @param day     the weekday to find
     * @param ordinal which occurrence, 1-based from the start or negative from the end
     * @return the matching date, or {@code null} when the ordinal overshoots the year
     */
    private static LocalDate nthWeekdayOfYear(int year, DayOfWeek day, int ordinal) {
        if (ordinal > 0) {
            LocalDate first = LocalDate.of(year, 1, 1);
            int delta = (7 + day.getValue() - first.getDayOfWeek().getValue()) % 7;
            LocalDate candidate = first.plusDays(delta + 7L * (ordinal - 1));
            return candidate.getYear() == year ? candidate : null;
        }
        LocalDate last = LocalDate.of(year, 12, 31);
        int delta = (7 + last.getDayOfWeek().getValue() - day.getValue()) % 7;
        LocalDate candidate = last.minusDays(delta + 7L * (-ordinal - 1));
        return candidate.getYear() == year ? candidate : null;
    }

    // ------------------------------------------------------------------
    // BYSETPOS
    // ------------------------------------------------------------------

    /**
     * Applies {@code BYSETPOS} to one period's sorted candidates.
     *
     * <p>Positions are 1-based and negative values count back from the end, so
     * {@code FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1} selects the month's last weekday.
     *
     * @param ordered the period's candidates, ascending
     * @return the selected subset, ascending and without duplicates
     */
    private List<LocalDate> applySetPos(List<LocalDate> ordered) {
        int size = ordered.size();
        List<LocalDate> selected = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();
        for (int position : rule.bySetPosValues()) {
            int index = position > 0 ? position - 1 : size + position;
            if (index < 0 || index >= size) {
                continue;
            }
            LocalDate date = ordered.get(index);
            if (seen.add(date)) {
                selected.add(date);
            }
        }
        Collections.sort(selected);
        return selected;
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    /**
     * Counts the occurrences in a window without materialising them.
     *
     * @param windowStart inclusive lower bound
     * @param windowEnd   inclusive upper bound
     * @return the number of occurrences
     */
    long count(LocalDate windowStart, LocalDate windowEnd) {
        return expand(windowStart, windowEnd).size();
    }

    /**
     * @param from the date to measure from
     * @param to   the date to measure to
     * @return whole days between two dates, for logging and tests
     */
    static long daysBetween(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to);
    }
}
