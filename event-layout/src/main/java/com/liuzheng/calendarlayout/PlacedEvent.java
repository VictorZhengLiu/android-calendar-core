package com.liuzheng.calendarlayout;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Positioning produced for a single event.
 *
 * <p>Column indices are zero-based and counted from the left edge of the day grid. The horizontal
 * extent of an event is {@code [column, column + columnSpan)}, so a renderer computes the left edge
 * as {@code column / totalColumns} and the width as {@code columnSpan / totalColumns}.
 */
public final class PlacedEvent {

    private final LayoutEvent event;
    private final int column;
    private final int columnSpan;
    private final int totalColumns;
    private final int layer;
    private final boolean spilled;
    private final List<LayoutEvent> conflicts;

    PlacedEvent(LayoutEvent event, int column, int columnSpan, int totalColumns, int layer,
            boolean spilled, List<LayoutEvent> conflicts) {
        this.event = event;
        this.column = column;
        this.columnSpan = columnSpan;
        this.totalColumns = totalColumns;
        this.layer = layer;
        this.spilled = spilled;
        this.conflicts = Collections.unmodifiableList(conflicts);
    }

    /** @return the event this placement describes */
    public LayoutEvent event() {
        return event;
    }

    /** @return zero-based index of the leftmost column */
    public int column() {
        return column;
    }

    /** @return how many columns the event occupies, at least 1 */
    public int columnSpan() {
        return columnSpan;
    }

    /** @return the column count of the enclosing group, used as the denominator for widths */
    public int totalColumns() {
        return totalColumns;
    }

    /** @return zero-based layer index, where a layer is a time bucket inside the group */
    public int layer() {
        return layer;
    }

    /**
     * @return whether the event could not be seated in its own Δt phase's layer and was moved to a
     *         later one, because everything in its phase conflicted with it.
     *
     *         <p>A renderer needs this: it positions an event by the event's own start time, and a
     *         spilled event's layer therefore begins earlier than the event itself. The flag is what
     *         lets a caller tell "this event is alone in this layer" apart from "this event shares
     *         the layer but sits part-way down it".
     */
    public boolean isSpilled() {
        return spilled;
    }

    /** @return every other event in the same group that overlaps this one, in start order */
    public List<LayoutEvent> conflicts() {
        return conflicts;
    }

    /** @return the left edge as a fraction of the grid width, in {@code [0, 1)} */
    public double leftFraction() {
        return (double) column / totalColumns;
    }

    /** @return the width as a fraction of the grid width, in {@code (0, 1]} */
    public double widthFraction() {
        return (double) columnSpan / totalColumns;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlacedEvent)) {
            return false;
        }
        PlacedEvent other = (PlacedEvent) o;
        return event.equals(other.event) && column == other.column
                && columnSpan == other.columnSpan && totalColumns == other.totalColumns;
    }

    @Override
    public int hashCode() {
        return Objects.hash(event, column, columnSpan, totalColumns);
    }

    @Override
    public String toString() {
        return event.title() + " " + event.interval()
                + " -> col " + column + "+" + columnSpan + "/" + totalColumns
                + " layer " + layer + (spilled ? " (spilled)" : "")
                + (conflicts.isEmpty() ? "" : " conflicts=" + conflicts.size());
    }

    /**
     * Builds an index from event key to placement.
     *
     * @param placements the placements to index
     * @return the index, in the iteration order of {@code placements}
     */
    static Map<String, PlacedEvent> indexByKey(List<PlacedEvent> placements) {
        Map<String, PlacedEvent> index = new LinkedHashMap<>();
        for (PlacedEvent placement : placements) {
            index.put(placement.event().key(), placement);
        }
        return index;
    }
}
