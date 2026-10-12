package com.liuzheng.calendarlayout;

/**
 * An immutable half-open local time span, measured in minutes from midnight.
 *
 * <p>Half-open means {@code [start, end)}: an event ending at 10:00 does not conflict with one
 * starting at 10:00. Back-to-back events are the common case in a calendar and must be allowed to
 * share a column.
 *
 * <p>The range is bounded to a single day, matching the day view this engine lays out. A caller
 * that renders a multi-day grid clips the event to the day it is drawing before handing it over.
 */
public final class TimeInterval implements Comparable<TimeInterval> {

    /** Minutes in one day; the exclusive upper bound of any interval. */
    public static final int MINUTES_PER_DAY = 24 * 60;

    private final int startMinute;
    private final int endMinute;

    private TimeInterval(int startMinute, int endMinute) {
        this.startMinute = startMinute;
        this.endMinute = endMinute;
    }

    /**
     * Creates an interval from minute offsets.
     *
     * @param startMinute minutes from midnight, in {@code [0, 1440]}
     * @param endMinute   minutes from midnight, in {@code (startMinute, 1440]}
     * @return the interval
     * @throws IllegalArgumentException if the range is outside the day or is empty
     */
    public static TimeInterval ofMinutes(int startMinute, int endMinute) {
        if (startMinute < 0 || startMinute > MINUTES_PER_DAY) {
            throw new IllegalArgumentException(
                    "startMinute out of range [0, 1440]: " + startMinute);
        }
        if (endMinute < 0 || endMinute > MINUTES_PER_DAY) {
            throw new IllegalArgumentException(
                    "endMinute out of range [0, 1440]: " + endMinute);
        }
        if (endMinute <= startMinute) {
            throw new IllegalArgumentException(
                    "endMinute (" + endMinute + ") must be after startMinute (" + startMinute + ")");
        }
        return new TimeInterval(startMinute, endMinute);
    }

    /**
     * Creates an interval from hours and minutes.
     *
     * @param startHour   hour of the start, 0-23
     * @param startMinute minute of the start, 0-59
     * @param endHour     hour of the end, 0-24
     * @param endMinute   minute of the end, 0-59
     * @return the interval
     */
    public static TimeInterval of(int startHour, int startMinute, int endHour, int endMinute) {
        return ofMinutes(startHour * 60 + startMinute, endHour * 60 + endMinute);
    }

    /**
     * Creates an interval of a given length starting at an hour and minute.
     *
     * @param startHour     hour of the start, 0-23
     * @param startMinute   minute of the start, 0-59
     * @param durationMins  length in minutes, at least 1
     * @return the interval
     */
    public static TimeInterval startingAt(int startHour, int startMinute, int durationMins) {
        int start = startHour * 60 + startMinute;
        return ofMinutes(start, start + durationMins);
    }

    /**
     * Creates an interval from {@code "HH:mm"} strings, which keeps layout fixtures readable.
     *
     * <p>Accepts both {@code "9:05"} and {@code "09:05"}. Midnight at the end of the day is written
     * {@code "24:00"} so that an event running to the end of the day has somewhere to land.
     *
     * @param start a time of day, such as {@code "09:30"}
     * @param end   a time of day later than {@code start}
     * @return the interval
     * @throws IllegalArgumentException if either string is not a valid time of day
     */
    public static TimeInterval between(String start, String end) {
        return ofMinutes(parseMinuteOfDay(start), parseMinuteOfDay(end));
    }

    /**
     * Parses {@code "H:mm"} or {@code "HH:mm"} into minutes from midnight.
     *
     * @param text a time of day, in {@code [00:00, 24:00]}
     * @return minutes from midnight
     * @throws IllegalArgumentException if the text is not a valid time of day
     */
    public static int parseMinuteOfDay(String text) {
        if (text == null) {
            throw new IllegalArgumentException("time text must not be null");
        }
        String trimmed = text.trim();
        int colon = trimmed.indexOf(':');
        if (colon < 1 || colon == trimmed.length() - 1) {
            throw new IllegalArgumentException("time must look like H:mm or HH:mm: \"" + text + "\"");
        }
        int hour;
        int minute;
        try {
            hour = Integer.parseInt(trimmed.substring(0, colon).trim());
            minute = Integer.parseInt(trimmed.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("time must look like H:mm or HH:mm: \"" + text + "\"",
                    e);
        }
        if (hour == 24 && minute == 0) {
            return MINUTES_PER_DAY;
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("time out of range: \"" + text + "\"");
        }
        return hour * 60 + minute;
    }

    /** @return minutes from midnight of the start, inclusive */
    public int startMinute() {
        return startMinute;
    }

    /** @return minutes from midnight of the end, exclusive */
    public int endMinute() {
        return endMinute;
    }

    /** @return the length in minutes, always at least 1 */
    public int durationMinutes() {
        return endMinute - startMinute;
    }

    /**
     * Tests whether two intervals share any real time.
     *
     * <p>Uses the half-open rule, so touching intervals do not conflict:
     * {@code [9:00, 10:00)} and {@code [10:00, 11:00)} are not a conflict.
     *
     * @param other the interval to test against
     * @return {@code true} when the intervals overlap
     */
    public boolean conflictsWith(TimeInterval other) {
        return startMinute < other.endMinute && other.startMinute < endMinute;
    }

    /**
     * @param other the interval to test against
     * @return {@code true} when this interval starts within 0 exclusive and {@code minutes}
     *         inclusive of {@code other}'s start, and the two overlap
     */
    boolean overlapsWithin(TimeInterval other, int minutes) {
        if (!conflictsWith(other)) {
            return false;
        }
        return Math.abs(startMinute - other.startMinute) <= minutes;
    }

    @Override
    public int compareTo(TimeInterval other) {
        int byStart = Integer.compare(startMinute, other.startMinute);
        if (byStart != 0) {
            return byStart;
        }
        return Integer.compare(endMinute, other.endMinute);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TimeInterval)) {
            return false;
        }
        TimeInterval other = (TimeInterval) o;
        return startMinute == other.startMinute && endMinute == other.endMinute;
    }

    @Override
    public int hashCode() {
        return startMinute * 31 + endMinute;
    }

    @Override
    public String toString() {
        return format(startMinute) + "-" + format(endMinute);
    }

    /** @return a {@code HH:mm} rendering of a minute offset, for messages and tests */
    public static String format(int minute) {
        return String.format("%02d:%02d", minute / 60, minute % 60);
    }
}
