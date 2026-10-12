package com.liuzheng.calendarlayout;

import java.util.Collections;
import java.util.List;

/**
 * A set of events laid out together: the events that transitively conflict with each other.
 *
 * <p>Grouping matters because the column budget is per group, not per day. A day with a busy
 * morning and an empty afternoon should give the afternoon events the full width of the grid,
 * rather than shrinking every event to the width demanded by the morning.
 */
public final class LayoutGroup {

    /** Why a block of events was put where it was, which the UI can surface as a "+N" affordance. */
    public enum BlockKind {
        /** Events in this layer that could not fit in their own phase's layer. */
        SPILLED,
        /** Events that share the layer and conflict with each other. */
        CONFLICTING,
        /** Events that share the layer without conflicting. */
        FREE
    }

    /** A contiguous run of same-kind events inside one layer, in display order. */
    public static final class Block {

        private final BlockKind kind;
        private final List<LayoutEvent> events;

        Block(BlockKind kind, List<LayoutEvent> events) {
            this.kind = kind;
            this.events = Collections.unmodifiableList(events);
        }

        /** @return why this block exists */
        public BlockKind kind() {
            return kind;
        }

        /** @return the events of the block, in the order they should be drawn */
        public List<LayoutEvent> events() {
            return events;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(kind + "[");
            for (int i = 0; i < events.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(events.get(i).title());
            }
            return sb.append(']').toString();
        }
    }

    /** One time bucket of a group: the events whose start times fall within one Δt phase. */
    public static final class Layer {

        private final int index;
        private final int startMinute;
        private final List<Block> blocks;
        private final int columnCount;

        Layer(int index, int startMinute, List<Block> blocks, int columnCount) {
            this.index = index;
            this.startMinute = startMinute;
            this.blocks = Collections.unmodifiableList(blocks);
            this.columnCount = columnCount;
        }

        /** @return zero-based layer index */
        public int index() {
            return index;
        }

        /** @return minutes from midnight where this layer's Δt phase begins */
        public int startMinute() {
            return startMinute;
        }

        /** @return the blocks of this layer, in draw order: parallel, then conflicting, then free */
        public List<Block> blocks() {
            return blocks;
        }

        /** @return how many columns this layer needed */
        public int columnCount() {
            return columnCount;
        }

        @Override
        public String toString() {
            return "layer " + index + " @" + TimeInterval.format(startMinute)
                    + " columns=" + columnCount + " " + blocks;
        }
    }

    private final List<LayoutEvent> events;
    private final List<Layer> layers;
    private final List<PlacedEvent> placements;
    private final int columnCount;

    LayoutGroup(List<LayoutEvent> events, List<Layer> layers, List<PlacedEvent> placements,
            int columnCount) {
        this.events = Collections.unmodifiableList(events);
        this.layers = Collections.unmodifiableList(layers);
        this.placements = Collections.unmodifiableList(placements);
        this.columnCount = columnCount;
    }

    /** @return the events of this group, in start order */
    public List<LayoutEvent> events() {
        return events;
    }

    /** @return the layers, lowest index first, with their block structure preserved */
    public List<Layer> layers() {
        return layers;
    }

    /** @return the placements of this group, in the order positions were assigned */
    public List<PlacedEvent> placements() {
        return placements;
    }

    /** @return the number of columns this group needed, i.e. the largest conflict set size */
    public int columnCount() {
        return columnCount;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("LayoutGroup[").append(events.size())
                .append(" events, ").append(columnCount).append(" columns]\n");
        for (Layer layer : layers) {
            sb.append("  ").append(layer).append('\n');
        }
        for (PlacedEvent placement : placements) {
            sb.append("  ").append(placement).append('\n');
        }
        return sb.toString();
    }
}
