package com.liuzheng.rrule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * One concrete occurrence of a recurring event.
 *
 * <p>An occurrence is stored as a <strong>local date plus the originating zone</strong> rather
 * than as a bare {@link Instant}. That choice matters for calendar events: "every weekday at
 * 09:00" means 09:00 <em>on the user's wall clock</em>, so the same event lands on a different
 * instant after a daylight-saving transition. Keeping the local date and zone lets
 * {@link #zonedStart()} resolve the correct offset at render time.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class RRuleOccurrence implements Comparable<RRuleOccurrence> {

    private final LocalDate date;
    private final LocalTime time;
    private final ZoneId zone;

    RRuleOccurrence(LocalDate date, LocalTime time, ZoneId zone) {
        this.date = Objects.requireNonNull(date, "date");
        this.time = Objects.requireNonNull(time, "time");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** @return the local calendar date of this occurrence */
    public LocalDate date() {
        return date;
    }

    /** @return the time-of-day, always inherited from {@code DTSTART} */
    public LocalTime time() {
        return time;
    }

    /** @return the zone the occurrence is expressed in */
    public ZoneId zone() {
        return zone;
    }

    /**
     * Resolves the local date-time to an absolute instant.
     *
     * <p>A local time that does not exist on this date — the hour skipped by a spring-forward
     * daylight-saving transition — is shifted forward by the length of the gap, which is what
     * users expect from "the 02:30 event on the day the clocks move". An ambiguous local time
     * (autumn's repeated hour) resolves to the earlier offset.
     *
     * @return the start instant
     */
    public Instant startInstant() {
        return zonedStart().toInstant();
    }

    /**
     * @return the start as a zoned date-time, with the offset resolved for this exact date
     */
    public ZonedDateTime zonedStart() {
        return date.atTime(time).atZone(zone);
    }

    /**
     * @param durationMinutes the event length in minutes
     * @return the end instant, computed by adding to the local date-time so that an event does
     *         not silently change length across a daylight-saving boundary
     */
    public Instant endInstant(long durationMinutes) {
        return zonedStart().plusMinutes(durationMinutes).toInstant();
    }

    @Override
    public int compareTo(RRuleOccurrence other) {
        int byDate = date.compareTo(other.date);
        return byDate != 0 ? byDate : time.compareTo(other.time);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RRuleOccurrence)) {
            return false;
        }
        RRuleOccurrence other = (RRuleOccurrence) o;
        return date.equals(other.date) && time.equals(other.time) && zone.equals(other.zone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(date, time, zone);
    }

    @Override
    public String toString() {
        return zonedStart().toString();
    }
}
