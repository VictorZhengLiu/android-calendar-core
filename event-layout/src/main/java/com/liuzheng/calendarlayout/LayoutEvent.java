package com.liuzheng.calendarlayout;

import java.util.Objects;

/**
 * One event to be laid out in a day view.
 *
 * <p>Instances are compared by <em>identity-like</em> semantics via {@link #key()}, not by their
 * times. Two events may legitimately share a name and a time slot — a calendar can hold two
 * separate 09:00 stand-ups — and the layout engine must keep them apart rather than collapsing
 * them into one column assignment.
 */
public final class LayoutEvent implements Comparable<LayoutEvent> {

    private final String id;
    private final String title;
    private final TimeInterval interval;
    private final boolean allDay;

    private LayoutEvent(String id, String title, TimeInterval interval, boolean allDay) {
        this.id = id;
        this.title = title;
        this.interval = interval;
        this.allDay = allDay;
    }

    /**
     * Creates a timed event that participates in column layout.
     *
     * @param id       a stable identifier, unique within one day
     * @param title    display text; may be empty but not {@code null}
     * @param interval the half-open time span within the day
     * @return the event
     */
    public static LayoutEvent timed(String id, String title, TimeInterval interval) {
        return new LayoutEvent(requireId(id), requireTitle(title), Objects.requireNonNull(interval,
                "interval"), false);
    }

    /**
     * Creates a timed event from {@code "HH:mm"} strings, for callers that build fixtures by hand.
     *
     * @param id    a stable identifier
     * @param title display text
     * @param start start time of day, such as {@code "09:30"}
     * @param end   end time of day, later than {@code start}
     * @return the event
     */
    public static LayoutEvent at(String id, String title, String start, String end) {
        return timed(id, title, TimeInterval.between(start, end));
    }

    /**
     * Creates an all-day event. All-day events are excluded from column layout entirely: they are
     * rendered in a separate strip above the timed grid, and mixing them into the columns would
     * make every timed event of the day conflict with every other one.
     *
     * @param id    a stable identifier
     * @param title display text
     * @return the event
     */
    public static LayoutEvent allDay(String id, String title) {
        return new LayoutEvent(requireId(id), requireTitle(title),
                TimeInterval.ofMinutes(0, TimeInterval.MINUTES_PER_DAY), true);
    }

    private static String requireId(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("event id must not be null or empty");
        }
        return id;
    }

    private static String requireTitle(String title) {
        if (title == null) {
            throw new IllegalArgumentException("event title must not be null");
        }
        return title;
    }

    /** @return the stable identifier */
    public String id() {
        return id;
    }

    /** @return the display text */
    public String title() {
        return title;
    }

    /** @return the half-open time span within the day */
    public TimeInterval interval() {
        return interval;
    }

    /** @return whether this is an all-day event, which the layout engine skips */
    public boolean isAllDay() {
        return allDay;
    }

    /**
     * @return the value used to identify this event in maps and sets. Two events with the same id
     *         are the same event; the title and the times are not part of the identity.
     */
    public String key() {
        return id;
    }

    /**
     * Orders events the way a reader scans a column: earlier first, then longer first (a long
     * meeting reads better at the top of the stack), then by id so the result is deterministic
     * regardless of the order the caller supplied.
     */
    @Override
    public int compareTo(LayoutEvent other) {
        int byStart = Integer.compare(interval.startMinute(), other.interval.startMinute());
        if (byStart != 0) {
            return byStart;
        }
        int byDuration = Integer.compare(other.interval.durationMinutes(),
                interval.durationMinutes());
        if (byDuration != 0) {
            return byDuration;
        }
        return id.compareTo(other.id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LayoutEvent)) {
            return false;
        }
        return id.equals(((LayoutEvent) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return (allDay ? "[all-day] " : "") + title + " " + interval;
    }
}
