package com.liuzheng.rrule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * A parsed RFC 5545 {@code RRULE}, together with the {@code DTSTART} it applies to.
 *
 * <p>Instances are created through {@link #parse(String, ZonedDateTime)} or
 * {@link #builder()}, and turned into concrete dates through {@link #expand(LocalDate, LocalDate)}
 * or {@link #expand(LocalDate)}.
 *
 * <h2>What this implementation covers</h2>
 *
 * <p>Recurrence is expanded at <strong>day granularity</strong>: every occurrence shares the
 * time-of-day of {@code DTSTART}. This is the correct model for calendar events such as
 * "every weekday at 09:00", and it keeps the rule engine free of the ambiguity that
 * time-of-day expansion introduces.
 *
 * <table border="1">
 *   <caption>Supported and unsupported RRULE parts</caption>
 *   <tr><th>Part</th><th>Status</th><th>Notes</th></tr>
 *   <tr><td>{@code FREQ}</td><td>Supported</td>
 *       <td>{@code SECONDLY}, {@code MINUTELY} and {@code HOURLY} are rejected: they need
 *           time-of-day expansion.</td></tr>
 *   <tr><td>{@code INTERVAL}</td><td>Supported</td><td>Must be &gt;= 1.</td></tr>
 *   <tr><td>{@code COUNT}</td><td>Supported</td>
 *       <td>Counts occurrences, not periods. Mutually exclusive with {@code UNTIL}.</td></tr>
 *   <tr><td>{@code UNTIL}</td><td>Supported</td>
 *       <td>Compared as an inclusive local-date bound. Mutually exclusive with {@code COUNT}.</td></tr>
 *   <tr><td>{@code WKST}</td><td>Supported</td>
 *       <td>Only affects which day starts a {@code WEEKLY} period in which {@code BYDAY}
 *           carries no day of the {@code DTSTART} week.</td></tr>
 *   <tr><td>{@code BYMONTH}</td><td>Supported</td></tr>
 *   <tr><td>{@code BYMONTHDAY}</td><td>Supported</td>
 *       <td>Negative values count back from the end of the month ({@code -1} = last day).
 *           Days that do not exist in a month are skipped, never clamped.</td></tr>
 *   <tr><td>{@code BYDAY}</td><td>Supported</td>
 *       <td>Ordinals are interpreted as "nth weekday in the period"; within
 *           {@code FREQ=MONTHLY} the period is the month, within {@code FREQ=YEARLY} it is
 *           the month when {@code BYMONTH} is present and the year otherwise.</td></tr>
 *   <tr><td>{@code BYSETPOS}</td><td>Supported</td>
 *       <td>Applied after every other {@code BY*} part, to the candidates of one period.</td></tr>
 *   <tr><td>{@code BYHOUR}, {@code BYMINUTE}, {@code BYSECOND}</td>
 *       <td>Rejected</td><td>Require time-of-day expansion. The time-of-day always comes
 *           from {@code DTSTART}.</td></tr>
 *   <tr><td>{@code BYWEEKNO}, {@code BYYEARDAY}, {@code BYSETPOS}-with-{@code BYWEEKNO}</td>
 *       <td>Rejected</td><td>Explicitly unsupported rather than silently ignored.</td></tr>
 * </table>
 *
 * <h2>Deviations from RFC 5545 worth knowing</h2>
 *
 * <ul>
 *   <li><strong>{@code FREQ=MONTHLY} on month-ends is skipped, not clamped.</strong>
 *       An event on the 31st recurs in January and March but not in February. RFC 5545 leaves
 *       this to the producer; skipping matches user expectation for calendar UIs and matches
 *       what most CalDAV servers store.</li>
 *   <li><strong>{@code WKST} defaults to {@link DayOfWeek#MONDAY}</strong>, the RFC 5545
 *       default, rather than to the locale's first day of week.</li>
 *   <li><strong>No expansion limit.</strong> The caller supplies the query window, so an
 *       unbounded rule ({@code FREQ=DAILY} with no {@code COUNT}/{@code UNTIL}) is bounded by
 *       construction. There is therefore no {@code MAX_INSTANCES} guard to trip over.</li>
 * </ul>
 *
 * @see RRuleExpander
 */
public final class RRule {

    // ------------------------------------------------------------------
    // Public constants
    // ------------------------------------------------------------------

    /**
     * Bit mask for Monday, matching RFC 5545's {@code MO} weekday code.
     * Combine masks with {@code |} to describe a day set.
     */
    public static final int MO = 1;
    /** Bit mask for Tuesday ({@code TU}). */
    public static final int TU = 1 << 1;
    /** Bit mask for Wednesday ({@code WE}). */
    public static final int WE = 1 << 2;
    /** Bit mask for Thursday ({@code TH}). */
    public static final int TH = 1 << 3;
    /** Bit mask for Friday ({@code FR}). */
    public static final int FR = 1 << 4;
    /** Bit mask for Saturday ({@code SA}). */
    public static final int SA = 1 << 5;
    /** Bit mask for Sunday ({@code SU}). */
    public static final int SU = 1 << 6;

    /** The RFC 5545 default week start. */
    public static final DayOfWeek DEFAULT_WEEK_START = DayOfWeek.MONDAY;

    /** Short names indexed like RFC 5545 weekday codes; {@code DAY_SHORT_NAMES[0]} is Monday. */
    static final String[] DAY_SHORT_NAMES = {"MO", "TU", "WE", "TH", "FR", "SA", "SU"};

    /** How often the rule repeats. */
    public enum Frequency {
        DAILY, WEEKLY, MONTHLY, YEARLY
    }

    // ------------------------------------------------------------------
    // Builder state
    // ------------------------------------------------------------------

    private final Frequency frequency;
    private final int interval;
    private final Integer count;
    private final LocalDate until;
    private final DayOfWeek weekStart;
    private final int byMonthMask;
    private final boolean[] byMonthDay;
    private final int byDayMask;
    private final int[][] byDayOrdinalDays;
    private final boolean[] byDayOrdinalUsed;
    private final boolean[] bySetPos;
    private final ZonedDateTime start;

    private RRuleExpander expander;

    private RRule(Builder b) {
        this.frequency = b.frequency;
        this.interval = b.interval;
        this.count = b.count;
        this.until = b.until;
        this.weekStart = b.weekStart;
        this.byMonthMask = b.byMonthMask;
        this.byMonthDay = b.byMonthDay == null ? null : b.byMonthDay.clone();
        this.byDayMask = b.byDayMask;
        this.byDayOrdinalDays = new int[8][];
        for (int i = 1; i <= 7; i++) {
            this.byDayOrdinalDays[i] = b.byDayOrdinalDays[i] == null
                    ? null
                    : b.byDayOrdinalDays[i].clone();
        }
        this.byDayOrdinalUsed = b.byDayOrdinalUsed.clone();
        this.bySetPos = b.bySetPos == null ? null : b.bySetPos.clone();
        this.start = b.start;
    }

    // ------------------------------------------------------------------
    // Parsing / construction
    // ------------------------------------------------------------------

    /** Starts a fluent {@link Builder} with {@link #DEFAULT_WEEK_START}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Parses an RRULE string and binds it to a start instant.
     *
     * <p>The rule string may be given with or without a leading {@code RRULE:} prefix; keys are
     * case-insensitive, which matches the real-world variation produced by CalDAV servers.
     *
     * @param rule  an RFC 5545 recurrence rule such as {@code "FREQ=WEEKLY;BYDAY=MO,WE,FR"}
     * @param start the {@code DTSTART} instant; its time-of-day and zone are applied to every
     *              occurrence and its date anchors the first period
     * @return the parsed rule
     * @throws RRuleException if the string is malformed or uses an unsupported part
     */
    public static RRule parse(String rule, ZonedDateTime start) {
        if (rule == null) {
            throw new RRuleException("rule must not be null");
        }
        if (start == null) {
            throw new RRuleException("start must not be null");
        }
        Builder b = parseInto(builder().start(start), rule);
        return b.build();
    }

    /** Convenience overload of {@link #parse(String, ZonedDateTime)} starting at midnight. */
    public static RRule parse(String rule, LocalDate startDate) {
        return parse(rule, startDate.atStartOfDay(java.time.ZoneOffset.UTC));
    }

    /**
     * Parses an RRULE string into an existing builder.
     *
     * @param b    the builder to fill
     * @param rule the rule text
     * @return {@code b}, for chaining
     */
    static Builder parseInto(Builder b, String rule) {
        String text = rule.trim();
        if (text.regionMatches(true, 0, "RRULE:", 0, 6)) {
            text = text.substring(6);
        }
        if (text.isEmpty()) {
            throw new RRuleException("rule must not be empty");
        }
        for (String part : text.split(";")) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            int eq = piece.indexOf('=');
            if (eq <= 0 || eq == piece.length() - 1) {
                throw new RRuleException("malformed rule part: \"" + piece
                        + "\" (expected NAME=VALUE)");
            }
            String name = piece.substring(0, eq).trim().toUpperCase(Locale.ROOT);
            String value = piece.substring(eq + 1).trim();
            b.applyPart(name, value, piece);
        }
        b.validate();
        return b;
    }

    // ------------------------------------------------------------------
    // Expansion
    // ------------------------------------------------------------------

    /**
     * Expands this rule into the occurrences whose start date falls inside
     * {@code [windowStart, windowEnd]}, both ends inclusive.
     *
     * <p>The window is a <em>filter</em>, never a performance hint: occurrences in the window are
     * returned even if thousands of earlier occurrences were skipped over to reach it, and
     * {@code COUNT}/{@code UNTIL} are still honoured as if the whole rule had been walked from
     * {@code DTSTART}.
     *
     * @param windowStart inclusive lower bound, compared against the occurrence's local date
     * @param windowEnd   inclusive upper bound
     * @return occurrences in ascending date order; empty when nothing matches
     * @throws RRuleException if {@code windowEnd} is before {@code windowStart}
     */
    public List<RRuleOccurrence> expand(LocalDate windowStart, LocalDate windowEnd) {
        if (windowStart == null || windowEnd == null) {
            throw new RRuleException("window bounds must not be null");
        }
        if (windowEnd.isBefore(windowStart)) {
            throw new RRuleException("windowEnd (" + windowEnd + ") is before windowStart ("
                    + windowStart + ")");
        }
        if (expander == null) {
            expander = new RRuleExpander(this);
        }
        return expander.expand(windowStart, windowEnd);
    }

    /**
     * Expands this rule over the window that starts on {@code windowStart} and ends
     * {@link #defaultWindowDays()} days later.
     *
     * @param windowStart inclusive lower bound
     * @return occurrences in ascending date order
     */
    public List<RRuleOccurrence> expand(LocalDate windowStart) {
        return expand(windowStart, windowStart.plusDays(defaultWindowDays()));
    }

    /**
     * The window length used by {@link #expand(LocalDate)}: long enough to cover a month view
     * plus its leading and trailing days from the neighbouring months.
     *
     * @return the default window length in days
     */
    public int defaultWindowDays() {
        return 62;
    }

    // ------------------------------------------------------------------
    // Rule parts used by the expander
    // ------------------------------------------------------------------

    /** @return how often the rule repeats */
    public Frequency getFrequency() {
        return frequency;
    }

    /** @return the period step, always &gt;= 1; {@code 1} when {@code INTERVAL} was absent */
    public int getInterval() {
        return interval;
    }

    /**
     * @return the {@code COUNT} cap, or {@code null} when the rule is unbounded by count
     */
    public Integer getCount() {
        return count;
    }

    /**
     * @return the inclusive {@code UNTIL} local-date bound, or {@code null} when absent
     */
    public LocalDate getUntil() {
        return until;
    }

    /** @return the week start used to align {@code WEEKLY} periods */
    public DayOfWeek getWeekStart() {
        return weekStart;
    }

    /** @return the {@code DTSTART} instant, carrying the time-of-day and zone of every result */
    public ZonedDateTime getStart() {
        return start;
    }

    /**
     * @return bit mask of months selected by {@code BYMONTH}, bit {@code 0} = January;
     *         {@code 0} when unset
     */
    int byMonthMask() {
        return byMonthMask;
    }

    /**
     * @param month 1-12
     * @return whether {@code BYMONTH} constrains this month; {@code true} when unset
     */
    boolean acceptsMonth(int month) {
        return byMonthMask == 0 || (byMonthMask & (1 << (month - 1))) != 0;
    }

    /**
     * @return whether {@code BYMONTHDAY} is present.
     *         <p>Presence is decided by looking for a set bit rather than by a {@code null} check:
     *         the backing array is allocated on first append, so a rule such as
     *         {@code BYMONTHDAY=} would otherwise look "present but empty" and filter every day
     *         out of the result.
     */
    boolean hasByMonthDay() {
        return anySet(byMonthDay);
    }

    private static boolean anySet(boolean[] flags) {
        if (flags == null) {
            return false;
        }
        for (boolean flag : flags) {
            if (flag) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param day a day-of-month value, possibly negative
     * @return whether {@code BYMONTHDAY} lists {@code day}
     */
    boolean hasByMonthDay(int day) {
        return byMonthDay != null && day >= -31 && day <= 31 && day != 0 && byMonthDay[day + 31];
    }

    /** @return {@code BYMONTHDAY} values in declaration order */
    List<Integer> byMonthDayValues() {
        List<Integer> values = new ArrayList<>();
        if (byMonthDay == null) {
            return values;
        }
        for (int i = 0; i < byMonthDay.length; i++) {
            if (byMonthDay[i]) {
                values.add(i - 31);
            }
        }
        return values;
    }

    /** @return whether {@code BYDAY} is present */
    boolean hasByDay() {
        return byDayMask != 0;
    }

    /**
     * @param day a day of week
     * @return whether {@code BYDAY} selects this weekday, regardless of any ordinal
     */
    boolean acceptsDayOfWeek(DayOfWeek day) {
        if (byDayMask == 0) {
            return true;
        }
        return (byDayMask & maskFor(day)) != 0;
    }

    /**
     * @return bit mask of weekdays named by a <em>bare</em> {@code BYDAY} token
     *         (no ordinal), or {@code 0} when every {@code BYDAY} token carries an ordinal
     */
    int bareByDayMask() {
        int mask = 0;
        for (int i = 1; i <= 7; i++) {
            if (!byDayOrdinalUsed[i] && (byDayMask & maskForIndex(i)) != 0) {
                mask |= maskForIndex(i);
            }
        }
        return mask;
    }

    /**
     * @return bit mask of weekdays named by an {@code ORDINAL}{@code DAY} token,
     *         or {@code 0} when none does
     */
    int ordinalByDayMask() {
        int mask = 0;
        for (int i = 1; i <= 7; i++) {
            if (byDayOrdinalUsed[i]) {
                mask |= maskForIndex(i);
            }
        }
        return mask;
    }

    /**
     * @param day a day of week
     * @return the ordinals listed for {@code day} inside {@code BYDAY}, in declaration order;
     *         empty when the token was bare
     */
    List<Integer> ordinalsFor(DayOfWeek day) {
        int index = indexOf(day);
        if (!byDayOrdinalUsed[index] || byDayOrdinalDays[index] == null) {
            return Collections.emptyList();
        }
        List<Integer> ordinals = new ArrayList<>();
        for (int slot = 1; slot <= 7; slot++) {
            int packed = byDayOrdinalDays[index][slot];
            if (packed != 0) {
                ordinals.add(packed);
            }
        }
        return ordinals;
    }

    /**
     * @return whether {@code BYSETPOS} is present; presence is decided by
     *         {@link #anySet(boolean[])}, for the same reason as {@link #hasByMonthDay()}
     */
    boolean hasBySetPos() {
        return anySet(bySetPos);
    }

    /**
     * @param day a day of week
     * @return whether {@code BYDAY} lists {@code day} with an explicit ordinal
     */
    boolean hasOrdinalFor(DayOfWeek day) {
        return byDayOrdinalUsed[indexOf(day)];
    }

    /**
     * @return the {@code BYSETPOS} values in declaration order; empty when absent
     */
    List<Integer> bySetPosValues() {
        List<Integer> values = new ArrayList<>();
        if (bySetPos == null) {
            return values;
        }
        for (int i = 0; i < bySetPos.length; i++) {
            if (bySetPos[i]) {
                values.add(i - 31);
            }
        }
        return values;
    }

    static int maskFor(DayOfWeek day) {
        return maskForIndex(indexOf(day));
    }

    static int indexOf(DayOfWeek day) {
        return day.getValue(); // MONDAY = 1 ... SUNDAY = 7
    }

    static int maskForIndex(int index) {
        return 1 << (index - 1);
    }

    static String dayName(DayOfWeek day) {
        return DAY_SHORT_NAMES[indexOf(day) - 1];
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("FREQ=").append(frequency);
        if (interval != 1) {
            sb.append(";INTERVAL=").append(interval);
        }
        if (count != null) {
            sb.append(";COUNT=").append(count);
        }
        if (until != null) {
            sb.append(";UNTIL=").append(until);
        }
        if (weekStart != DEFAULT_WEEK_START) {
            sb.append(";WKST=").append(dayName(weekStart));
        }
        if (byMonthMask != 0) {
            sb.append(";BYMONTH=");
            for (int m = 1; m <= 12; m++) {
                if ((byMonthMask & (1 << (m - 1))) != 0) {
                    sb.append(m).append(',');
                }
            }
            sb.setLength(sb.length() - 1);
        }
        if (byMonthDay != null) {
            sb.append(";BYMONTHDAY=");
            for (int d : byMonthDayValues()) {
                sb.append(d).append(',');
            }
            sb.setLength(sb.length() - 1);
        }
        if (byDayMask != 0) {
            sb.append(";BYDAY=");
            Set<String> tokens = new TreeSet<>();
            for (int i = 1; i <= 7; i++) {
                if ((byDayMask & maskForIndex(i)) == 0) {
                    continue;
                }
                if (byDayOrdinalUsed[i]) {
                    for (int ordinal : ordinalsFor(DayOfWeek.of(i))) {
                        tokens.add(ordinal + DAY_SHORT_NAMES[i - 1]);
                    }
                } else {
                    tokens.add(DAY_SHORT_NAMES[i - 1]);
                }
            }
            sb.append(String.join(",", tokens));
        }
        if (bySetPos != null) {
            sb.append(";BYSETPOS=");
            for (int p : bySetPosValues()) {
                sb.append(p).append(',');
            }
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * Renders this rule as a display string for a given locale, e.g. {@code "每周五"}.
     *
     * <p>Only the parts this implementation supports are described; unset parts are omitted
     * rather than filled in with defaults, so the output never claims more than the rule says.
     *
     * @param locale the locale used for weekday and month names
     * @return a human-readable summary
     */
    public String humanReadable(Locale locale) {
        StringBuilder sb = new StringBuilder();
        String every = interval == 1 ? "每" : "每 " + interval + " ";
        switch (frequency) {
            case DAILY:
                sb.append(every).append("天");
                break;
            case WEEKLY:
                sb.append(every).append("周");
                if (byDayMask != 0) {
                    sb.append("的 ").append(joinWeekdays(locale));
                }
                break;
            case MONTHLY:
                sb.append(every).append("月");
                if (byMonthDay != null) {
                    sb.append(" ").append(joinInts(byMonthDayValues())).append(" 日");
                } else if (byDayMask != 0) {
                    sb.append("的 ").append(joinWeekdaysWithOrdinal(locale));
                } else {
                    sb.append(" ").append(dayOfMonthOfStart()).append(" 日");
                }
                break;
            case YEARLY:
                sb.append(every).append("年");
                if (byMonthMask != 0) {
                    sb.append(" ").append(joinMonths(locale));
                }
                if (byMonthDay != null) {
                    sb.append(" ").append(joinInts(byMonthDayValues())).append(" 日");
                } else if (byDayMask != 0) {
                    sb.append(" 的").append(joinWeekdaysWithOrdinal(locale));
                } else if (byMonthMask == 0) {
                    // Nothing picks the date, so DTSTART's month and day anchor the rule.
                    sb.append(" 同月同日");
                }
                break;
            default:
                sb.append(frequency);
        }
        if (bySetPos != null) {
            sb.append("（取第 ").append(joinInts(bySetPosValues())).append(" 个）");
        }
        if (count != null) {
            sb.append("，共 ").append(count).append(" 次");
        } else if (until != null) {
            sb.append("，直到 ").append(until);
        }
        return sb.toString();
    }

    /** @return the day-of-month of {@code DTSTART}, for describing an unconstrained monthly rule */
    private int dayOfMonthOfStart() {
        return start.toLocalDate().getDayOfMonth();
    }

    /** @return the weekdays of {@code BYDAY}, in calendar order, without ordinals */
    private String joinWeekdays(Locale locale) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            if ((byDayMask & maskForIndex(i)) != 0) {
                names.add(DayOfWeek.of(i).getDisplayName(TextStyle.FULL, locale));
            }
        }
        return String.join("、", names);
    }

    /** @return the {@code BYDAY} tokens, rendering ordinal tokens as "第 N 个<weekday>" */
    private String joinWeekdaysWithOrdinal(Locale locale) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            if ((byDayMask & maskForIndex(i)) == 0) {
                continue;
            }
            String day = DayOfWeek.of(i).getDisplayName(TextStyle.FULL, locale);
            if (byDayOrdinalUsed[i]) {
                names.add("第 " + joinInts(ordinalsFor(DayOfWeek.of(i))) + " 个" + day);
            } else {
                names.add(day);
            }
        }
        return String.join("、", names);
    }

    /** @return the months of {@code BYMONTH}, in calendar order, with a single 月 suffix */
    private String joinMonths(Locale locale) {
        List<String> names = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            if ((byMonthMask & (1 << (m - 1))) != 0) {
                names.add(java.time.Month.of(m).getDisplayName(TextStyle.FULL, locale));
            }
        }
        // Month.getDisplayName already carries the locale's month suffix ("十二月", "December"),
        // so no additional unit text is appended here.
        return String.join("、", names);
    }

    /** @return values joined for display, without the brackets {@code List.toString} adds */
    private static String joinInts(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    public static final class Builder {

        private Frequency frequency;
        private int interval = 1;
        private Integer count;
        private LocalDate until;
        private DayOfWeek weekStart = DEFAULT_WEEK_START;
        private int byMonthMask;
        private boolean[] byMonthDay;
        private int byDayMask;
        private final int[][] byDayOrdinalDays = new int[8][8];
        private final boolean[] byDayOrdinalUsed = new boolean[8];
        private boolean[] bySetPos;
        private ZonedDateTime start;

        private Builder() {
        }

        public Builder frequency(Frequency frequency) {
            this.frequency = frequency;
            return this;
        }

        public Builder interval(int interval) {
            this.interval = interval;
            return this;
        }

        public Builder count(int count) {
            this.count = count;
            return this;
        }

        public Builder until(LocalDate until) {
            this.until = until;
            return this;
        }

        public Builder weekStart(DayOfWeek weekStart) {
            this.weekStart = weekStart;
            return this;
        }

        /** @param months months to select, each 1-12 */
        public Builder byMonth(int... months) {
            int mask = 0;
            for (int m : months) {
                if (m < 1 || m > 12) {
                    throw new RRuleException("BYMONTH value out of range (1-12): " + m);
                }
                mask |= 1 << (m - 1);
            }
            this.byMonthMask |= mask;
            return this;
        }

        /** @param days days of month to select; negative counts back from the month end */
        public Builder byMonthDay(int... days) {
            if (byMonthDay == null) {
                byMonthDay = new boolean[63];
            }
            for (int d : days) {
                if (d == 0 || d < -31 || d > 31) {
                    throw new RRuleException(
                            "BYMONTHDAY value out of range (-31..-1, 1..31): " + d);
                }
                byMonthDay[d + 31] = true;
            }
            return this;
        }

        /** @param days weekdays to select, without ordinals */
        public Builder byDay(DayOfWeek... days) {
            for (DayOfWeek d : days) {
                int index = indexOf(d);
                byDayMask |= maskForIndex(index);
                byDayOrdinalUsed[index] = false;
            }
            return this;
        }

        /** @param day the weekday; @param ordinal e.g. {@code -1} for "last" */
        public Builder byDay(int ordinal, DayOfWeek day) {
            if (ordinal == 0 || ordinal < -53 || ordinal > 53) {
                throw new RRuleException("BYDAY ordinal out of range (-53..-1, 1..53): "
                        + ordinal);
            }
            int index = indexOf(day);
            byDayMask |= maskForIndex(index);
            byDayOrdinalUsed[index] = true;
            for (int slot = 1; slot <= 7; slot++) {
                if (byDayOrdinalDays[index][slot] == 0) {
                    byDayOrdinalDays[index][slot] = ordinal;
                    return this;
                }
            }
            throw new RRuleException("too many BYDAY ordinals for " + dayName(day));
        }

        /** @param positions positions within a period, 1-based, negative counts from the end */
        public Builder bySetPos(int... positions) {
            if (bySetPos == null) {
                bySetPos = new boolean[63];
            }
            for (int p : positions) {
                if (p == 0 || p < -31 || p > 31) {
                    throw new RRuleException(
                            "BYSETPOS value out of range (-31..-1, 1..31): " + p);
                }
                bySetPos[p + 31] = true;
            }
            return this;
        }

        public Builder start(ZonedDateTime start) {
            this.start = start;
            return this;
        }

        /**
         * Validates and freezes the builder.
         *
         * @return an immutable rule
         * @throws RRuleException if {@code FREQ} or {@code start} is missing, if both
         *         {@code COUNT} and {@code UNTIL} are set, or if {@code INTERVAL} is below 1
         */
        public RRule build() {
            validateComplete();
            return new RRule(this);
        }

        /**
         * Validates everything a rule can be validated for while it is being assembled.
         *
         * <p>{@code DTSTART} is checked by {@link #build()} rather than here, because a rule is
         * parsed before its start instant is attached: {@link RRule#parse} fills the builder from
         * the rule text first and binds {@code DTSTART} afterwards.
         */
        void validate() {
            if (frequency == null) {
                throw new RRuleException("FREQ is required");
            }
            if (interval < 1) {
                throw new RRuleException("INTERVAL must be >= 1, was " + interval);
            }
            if (count != null && until != null) {
                throw new RRuleException("COUNT and UNTIL are mutually exclusive");
            }
            if (count != null && count < 1) {
                throw new RRuleException("COUNT must be >= 1, was " + count);
            }
        }

        /** Validates the parts that only {@link #build()} can see. */
        private void validateComplete() {
            validate();
            if (start == null) {
                throw new RRuleException("start is required");
            }
        }

        void applyPart(String name, String value, String original) {
            switch (name) {
                case "FREQ":
                    frequency = parseFrequency(value, original);
                    break;
                case "INTERVAL":
                    interval = positiveInt(value, "INTERVAL", original);
                    break;
                case "COUNT":
                    count = positiveInt(value, "COUNT", original);
                    break;
                case "UNTIL":
                    until = parseUntil(value, original);
                    break;
                case "WKST":
                    weekStart = parseWeekday(value, original);
                    break;
                case "BYMONTH":
                    for (String token : value.split(",")) {
                        byMonth(monthInt(token.trim(), original));
                    }
                    break;
                case "BYMONTHDAY":
                    for (String token : value.split(",")) {
                        byMonthDay(monthDayInt(token.trim(), original));
                    }
                    break;
                case "BYDAY":
                    for (String token : value.split(",")) {
                        applyByDayToken(token.trim(), original);
                    }
                    break;
                case "BYSETPOS":
                    for (String token : value.split(",")) {
                        bySetPos(monthDayInt(token.trim(), original));
                    }
                    break;
                case "BYHOUR":
                case "BYMINUTE":
                case "BYSECOND":
                    throw new RRuleException("rule part " + name
                            + " requires time-of-day expansion, which this implementation "
                            + "does not support; the time-of-day always comes from DTSTART "
                            + "(in \"" + original + "\")");
                case "BYWEEKNO":
                case "BYYEARDAY":
                    throw new RRuleException("rule part " + name
                            + " is not supported by this implementation (in \""
                            + original + "\")");
                default:
                    throw new RRuleException("unknown rule part \"" + name + "\"");
            }
        }

        private void applyByDayToken(String token, String original) {
            if (token.isEmpty()) {
                throw new RRuleException("empty BYDAY token in \"" + original + "\"");
            }
            int split = 0;
            while (split < token.length()
                    && (Character.isDigit(token.charAt(split))
                        || (split == 0 && (token.charAt(split) == '+' || token.charAt(split) == '-')))) {
                split++;
            }
            String dayPart = token.substring(split).trim().toUpperCase(Locale.ROOT);
            String ordinalPart = token.substring(0, split).trim();
            DayOfWeek day = parseWeekday(dayPart, original);
            if (ordinalPart.isEmpty()) {
                byDay(day);
            } else {
                int ordinal;
                try {
                    ordinal = Integer.parseInt(ordinalPart.startsWith("+")
                            ? ordinalPart.substring(1)
                            : ordinalPart);
                } catch (NumberFormatException e) {
                    throw new RRuleException("invalid BYDAY ordinal \"" + ordinalPart
                            + "\" in \"" + original + "\"", e);
                }
                byDay(ordinal, day);
            }
        }

        private static Frequency parseFrequency(String value, String original) {
            String f = value.trim().toUpperCase(Locale.ROOT);
            switch (f) {
                case "DAILY":
                    return Frequency.DAILY;
                case "WEEKLY":
                    return Frequency.WEEKLY;
                case "MONTHLY":
                    return Frequency.MONTHLY;
                case "YEARLY":
                    return Frequency.YEARLY;
                case "SECONDLY":
                case "MINUTELY":
                case "HOURLY":
                    throw new RRuleException("FREQ=" + f
                            + " requires time-of-day expansion, which this implementation does "
                            + "not support (in \"" + original + "\")");
                default:
                    throw new RRuleException("unknown FREQ value \"" + value + "\"");
            }
        }

        private static int positiveInt(String value, String part, String original) {
            try {
                int v = Integer.parseInt(value.trim());
                if (v < 1) {
                    throw new RRuleException(part + " must be >= 1, was " + v
                            + " (in \"" + original + "\")");
                }
                return v;
            } catch (NumberFormatException e) {
                throw new RRuleException("invalid " + part + " value \"" + value
                        + "\" (in \"" + original + "\")", e);
            }
        }

        private static LocalDate parseUntil(String value, String original) {
            String v = value.trim();
            // Accept the three forms seen in the wild: date, local date-time, UTC date-time.
            int t = v.indexOf('T');
            if (t >= 0) {
                v = v.substring(0, t);
            }
            v = v.replace("-", "");
            if (v.length() != 8) {
                throw new RRuleException("invalid UNTIL value \"" + value
                        + "\" (expected YYYYMMDD, YYYY-MM-DD or an ISO date-time)");
            }
            try {
                return LocalDate.of(
                        Integer.parseInt(v.substring(0, 4)),
                        Integer.parseInt(v.substring(4, 6)),
                        Integer.parseInt(v.substring(6, 8)));
            } catch (RuntimeException e) {
                throw new RRuleException("invalid UNTIL date \"" + value + "\" in \""
                        + original + "\"", e);
            }
        }

        private static DayOfWeek parseWeekday(String value, String original) {
            String v = value.trim().toUpperCase(Locale.ROOT);
            for (int i = 0; i < DAY_SHORT_NAMES.length; i++) {
                if (DAY_SHORT_NAMES[i].equals(v)) {
                    return DayOfWeek.of(i + 1);
                }
            }
            throw new RRuleException("invalid weekday \"" + value + "\" in \"" + original
                    + "\" (expected one of MO,TU,WE,TH,FR,SA,SU)");
        }

        private static int monthInt(String token, String original) {
            return boundedInt(token, original, "BYMONTH");
        }

        private static int monthDayInt(String token, String original) {
            return boundedInt(token, original, "BYMONTHDAY/BYSETPOS");
        }

        private static int boundedInt(String token, String original, String part) {
            if (token.isEmpty()) {
                throw new RRuleException("empty value in " + part + " (in \"" + original + "\")");
            }
            try {
                return Integer.parseInt(token.startsWith("+") ? token.substring(1) : token);
            } catch (NumberFormatException e) {
                throw new RRuleException("invalid " + part + " value \"" + token
                        + "\" (in \"" + original + "\")", e);
            }
        }
    }
}
