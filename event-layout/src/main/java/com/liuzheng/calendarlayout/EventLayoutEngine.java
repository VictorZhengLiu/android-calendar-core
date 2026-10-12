package com.liuzheng.calendarlayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Lays out the overlapping events of a day view into columns.
 *
 * <p>The problem: several events may share the same time range, and drawing them all at full width
 * makes them overlap. The engine answers one question — <em>which horizontal band does each event
 * own?</em> — and guarantees that two events overlapping in time never claim overlapping bands.
 *
 * <h2>Pipeline</h2>
 *
 * <pre>
 *  ① 分组 groupEvents          把传递冲突的事件聚成组；组与组之间互不冲突
 *  ② 分层 layer                以 Δt 为相位把开始时间分桶；桶内放不下就顺延到下一层
 *  ③ 层内排序 + 分块            冲突数 → 开始时间 → 时长 → 标题；分下移/冲突/独立三类块
 *  ④ 栏位分配 seatInColumns     全组共用一个栏位池，放入第一个不冲突的栏
 * </pre>
 *
 * {@link #maxConcurrent} computes the peak concurrency used by the tests and by callers that want
 * to size a gutter; the layout itself derives its column count from the columns it actually seats.
 *
 * <h2>Design choices worth knowing</h2>
 *
 * <ul>
 *   <li><strong>Δt is a bucketing device, not a conflict threshold.</strong> Events that overlap are
 *       always separated into different columns, no matter how small the overlap. Δt only decides
 *       which layer an event's start time falls into, and therefore how far down the grid a crowded
 *       phase spills.</li>
 *   <li><strong>Columns come from one pool for the whole group, not one pool per layer.</strong> If
 *       each layer carved up columns on its own, an event in layer 1 could be seated in a column that
 *       a conflicting event from layer 0 already occupies: they would share the horizontal band even
 *       though they do not share the layer, and the renderer would paint one over the other.</li>
 *   <li><strong>The column budget is per group, not per day.</strong> A busy morning does not shrink
 *       an empty afternoon.</li>
 *   <li><strong>Every event occupies exactly one column, and is never widened.</strong> A layout of
 *       column ranges cannot express "wide at the top, narrow at the bottom", so a long event that
 *       shares the grid with shorter ones keeps its own band instead of growing underneath them. The
 *       trade is some whitespace in exchange for a guarantee a renderer can rely on.</li>
 * </ul>
 *
 * <h2>Layering in detail</h2>
 *
 * <p>Layers are the Δt phase buckets: layer {@code L} covers
 * {@code [groupStart + L*Δt, groupStart + (L+1)*Δt)}. An event starts in the layer of its own phase,
 * and when that layer already holds something it conflicts with, it moves <em>down</em> to the next
 * layer that can seat it.
 *
 * <p>Downward-only movement is what keeps the vertical axis honest. Drawing an event at the layer's
 * top edge would be wrong for any event that begins later than its layer, so a renderer positions
 * each event by its own start time; the layer decides only how the horizontal space is divided. A
 * boolean on each placement, {@link PlacedEvent#isSpilled()}, marks the events that could not take
 * their own phase's layer, so a caller can tell why a layer begins before the event inside it.
 *
 * <p>Instances are immutable and safe to share; {@link #layout} is stateless and thread-safe.
 */
public final class EventLayoutEngine {

    /** Default layer phase, in minutes. */
    public static final int DEFAULT_DELTA_MINUTES = 45;

    private final int deltaMinutes;

    /** Creates an engine with the {@link #DEFAULT_DELTA_MINUTES default} layer phase. */
    public EventLayoutEngine() {
        this(DEFAULT_DELTA_MINUTES);
    }

    /**
     * Creates an engine with an explicit layer phase.
     *
     * @param deltaMinutes the Δt bucketing interval in minutes; must be at least 1
     */
    public EventLayoutEngine(int deltaMinutes) {
        if (deltaMinutes < 1) {
            throw new IllegalArgumentException("deltaMinutes must be >= 1, was " + deltaMinutes);
        }
        this.deltaMinutes = deltaMinutes;
    }

    /** @return the layer phase this engine buckets start times with, in minutes */
    public int deltaMinutes() {
        return deltaMinutes;
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /**
     * Lays out one day of events.
     *
     * <p>All-day events are ignored, because they are rendered in their own strip; including them
     * would make every timed event of the day conflict with every other one.
     *
     * @param events the day's events; {@code null} is treated as empty
     * @return the layout, never {@code null}
     */
    public Result layout(List<LayoutEvent> events) {
        List<LayoutEvent> dayEvents = new ArrayList<>();
        if (events != null) {
            for (LayoutEvent event : events) {
                Objects.requireNonNull(event, "events must not contain null");
                if (!event.isAllDay()) {
                    dayEvents.add(event);
                }
            }
        }
        Collections.sort(dayEvents);

        List<LayoutGroup> groups = new ArrayList<>();
        List<PlacedEvent> allPlacements = new ArrayList<>();
        for (List<LayoutEvent> conflictSet : groupEvents(dayEvents)) {
            LayoutGroup group = layoutGroup(conflictSet);
            groups.add(group);
            allPlacements.addAll(group.placements());
        }
        return new Result(dayEvents, groups, allPlacements);
    }

    // ------------------------------------------------------------------
    // ① Grouping
    // ------------------------------------------------------------------

    /**
     * Splits the day into sets of transitively conflicting events.
     *
     * <p>"Transitively" is the operative word: 09:00-10:00, 09:30-10:30 and 10:15-11:00 form a
     * single group even though the first and last never overlap, because the middle event chains
     * them together. Splitting them would let the 10:15 event take the full width while the events
     * around 10:00 only had half, which reads as a layout glitch.
     *
     * @param sortedEvents events in start order
     * @return the groups, each in start order, with the earliest group first
     */
    static List<List<LayoutEvent>> groupEvents(List<LayoutEvent> sortedEvents) {
        List<List<LayoutEvent>> groups = new ArrayList<>();
        List<LayoutEvent> current = new ArrayList<>();
        int currentMaxEnd = Integer.MIN_VALUE;

        for (LayoutEvent event : sortedEvents) {
            if (!current.isEmpty() && event.interval().startMinute() >= currentMaxEnd) {
                groups.add(current);
                current = new ArrayList<>();
                currentMaxEnd = Integer.MIN_VALUE;
            }
            current.add(event);
            currentMaxEnd = Math.max(currentMaxEnd, event.interval().endMinute());
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    // ------------------------------------------------------------------
    // ② Maximum columns
    // ------------------------------------------------------------------

    /**
     * Counts the largest set of events that are all in conflict at the same moment.
     *
     * <p>This is the classic "meeting rooms" sweep: sort by start, keep the active end times in a
     * min-heap, and drop every meeting that has already finished. The peak heap size is both the
     * answer and the number of columns the group will need, because interval graphs are perfect
     * and greedy first-fit reaches that bound exactly.
     *
     * @param group a set of transitively conflicting events
     * @return the peak concurrency; {@code 0} for an empty group
     */
    static int maxConcurrent(List<LayoutEvent> group) {
        if (group.isEmpty()) {
            return 0;
        }
        List<LayoutEvent> sorted = new ArrayList<>(group);
        Collections.sort(sorted, Comparator.comparingInt(e -> e.interval().startMinute()));

        PriorityQueue<Integer> activeEnds = new PriorityQueue<>();
        int peak = 0;
        for (LayoutEvent event : sorted) {
            int start = event.interval().startMinute();
            while (!activeEnds.isEmpty() && activeEnds.peek() <= start) {
                activeEnds.poll();
            }
            activeEnds.add(event.interval().endMinute());
            peak = Math.max(peak, activeEnds.size());
        }
        return peak;
    }

    // ------------------------------------------------------------------
    // ③ Layering
    // ------------------------------------------------------------------

    /** An event together with the layer it was assigned to and its conflict bookkeeping. */
    private static final class Node {
        final LayoutEvent event;
        int layer;
        boolean spilled;
        final List<LayoutEvent> conflicts = new ArrayList<>();

        Node(LayoutEvent event) {
            this.event = event;
        }

        int start() {
            return event.interval().startMinute();
        }

        int end() {
            return event.interval().endMinute();
        }
    }

    /**
     * Assigns every event of a group a layer and records each event's conflict list.
     *
     * @param group the group's events
     * @return one node per event, in start order
     */
    private List<Node> layerEvents(List<LayoutEvent> group) {
        Map<String, Node> byKey = new LinkedHashMap<>();
        for (LayoutEvent event : group) {
            byKey.put(event.key(), new Node(event));
        }

        // Conflict lists, computed pairwise. A day view holds tens of events, so the quadratic pass
        // is cheaper than the bookkeeping needed to make it sub-quadratic, and it keeps the
        // definition of "conflict" in exactly one place.
        for (Node node : byKey.values()) {
            for (Node other : byKey.values()) {
                if (node != other && node.event.interval().conflictsWith(other.event.interval())) {
                    node.conflicts.add(other.event);
                }
            }
            Collections.sort(node.conflicts);
        }

        // Phases are measured from the group's first event, so every layer index corresponds to a
        // real time offset: layer L begins at groupStart + L * Δt.
        int groupStart = Integer.MAX_VALUE;
        for (Node node : byKey.values()) {
            groupStart = Math.min(groupStart, node.start());
        }

        List<Node> ordered = new ArrayList<>(byKey.values());
        Collections.sort(ordered, Comparator.comparingInt(Node::start));

        Map<String, Integer> phaseOf = new LinkedHashMap<>();
        int maxPhase = 0;
        for (Node node : ordered) {
            int phase = (node.start() - groupStart) / deltaMinutes;
            phaseOf.put(node.event.key(), phase);
            maxPhase = Math.max(maxPhase, phase);
        }

        // Layers start out as the Δt phase buckets. An event belongs to its own phase's bucket,
        // because a layer's index maps to a real time offset (layer L begins at groupStart + L*Δt)
        // and placing an event in a layer above its own bucket would draw it above its start time.
        int slotCount = maxPhase + 1;
        List<List<Node>> slots = new ArrayList<>();
        for (int i = 0; i < slotCount; i++) {
            slots.add(new ArrayList<>());
        }

        List<Node> byPriority = new ArrayList<>(ordered);
        Collections.sort(byPriority, nodeComparator());

        // Seat the busiest events first, each in the first layer at or after its own phase that holds
        // nothing it conflicts with. "At or after" is the vertical guarantee; searching forward is
        // what packs a dense phase, since a phase holding more simultaneous events than its own layer
        // can seat simply spills into the layer below instead of overlapping.
        for (Node node : byPriority) {
            int phase = phaseOf.get(node.event.key());
            int assigned = -1;
            for (int candidate = phase; ; candidate++) {
                if (candidate >= slots.size()) {
                    slots.add(new ArrayList<>());
                }
                if (!conflictsWithAny(node, slots.get(candidate))) {
                    assigned = candidate;
                    break;
                }
            }
            node.layer = assigned;
            // Spilled: the event could not take its own phase's layer, so it was moved down. The
            // layer it landed in therefore starts earlier than the event, and a renderer must place
            // it by its own start time rather than at the layer's top.
            node.spilled = assigned > phase;
            slots.get(assigned).add(node);
        }

        Map<Integer, List<Node>> layers = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            if (!slots.get(i).isEmpty()) {
                layers.put(i, slots.get(i));
            }
        }
        for (List<Node> members : layers.values()) {
            Collections.sort(members, nodeComparator());
        }
        return ordered;
    }

    private static boolean conflictsWithAny(Node node, List<Node> members) {
        for (Node member : members) {
            if (node.event.interval().conflictsWith(member.event.interval())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Within one layer, draw the most-conflicted event first, then the earliest, then the longest.
     * Ordering by conflict count is what keeps a dense cluster's columns stable when the caller
     * changes the order of the input list.
     */
    private static Comparator<Node> nodeComparator() {
        return Comparator
                .comparingInt((Node n) -> -n.conflicts.size())
                .thenComparingInt(Node::start)
                .thenComparingInt(n -> -(n.end() - n.start()))
                .thenComparing(n -> n.event.title());
    }

    // ------------------------------------------------------------------
    // ④⑤⑥ Per-group layout
    // ------------------------------------------------------------------

    private LayoutGroup layoutGroup(List<LayoutEvent> groupEvents) {
        List<Node> nodes = layerEvents(groupEvents);

        Map<Integer, List<Node>> byLayer = new LinkedHashMap<>();
        int maxLayer = 0;
        for (Node node : nodes) {
            byLayer.computeIfAbsent(node.layer, key -> new ArrayList<>()).add(node);
            maxLayer = Math.max(maxLayer, node.layer);
        }

        Map<String, Integer> columnOf = new HashMap<>();
        List<LayoutGroup.Layer> layers = new ArrayList<>();

        // One column pool for the whole group. Seating against every event already placed stops an
        // event in a later layer from landing on top of a conflicting event from an earlier one.
        // The pool is sparse: a column index is created only when an event actually needs it, so a
        // layer that seats nothing does not leave a phantom empty column widening everyone else.
        Map<Integer, List<Node>> columns = new HashMap<>();

        for (int layerIndex = 0; layerIndex <= maxLayer; layerIndex++) {
            List<Node> members = byLayer.get(layerIndex);
            if (members == null) {
                continue;
            }
            Collections.sort(members, nodeComparator());
            seatInColumns(members, columns, columnOf);

            int layerStart = Integer.MAX_VALUE;
            for (Node node : members) {
                layerStart = Math.min(layerStart, node.start());
            }
            int layerColumns = 0;
            for (Node node : members) {
                layerColumns = Math.max(layerColumns, columnOf.get(node.event.key()) + 1);
            }
            layers.add(new LayoutGroup.Layer(layerIndex, layerStart, buildBlocks(members),
                    layerColumns));
        }

        // The width denominator is the highest column actually in use. Deriving it from the pool's
        // size would count sparse gaps and hand every event a narrower width than the layout uses.
        int totalColumns = 1;
        for (Node node : nodes) {
            totalColumns = Math.max(totalColumns, columnOf.get(node.event.key()) + 1);
        }

        List<PlacedEvent> placements = new ArrayList<>();
        for (Node node : nodes) {
            int column = columnOf.get(node.event.key());
            placements.add(new PlacedEvent(node.event, column, 1, totalColumns, node.layer,
                    node.spilled, node.conflicts));
        }
        return new LayoutGroup(new ArrayList<>(groupEvents), layers, placements, totalColumns);
    }

    /**
     * Seats one layer's events in the first column that holds no conflicting event.
     *
     * @param members  the layer's events, already in priority order
     * @param columns  the group-wide column pool, grown in place as needed
     * @param columnOf receives the chosen column per event key
     */
    private static void seatInColumns(List<Node> members, Map<Integer, List<Node>> columns,
            Map<String, Integer> columnOf) {
        for (Node node : members) {
            int target = -1;
            int probe = 0;
            while (true) {
                List<Node> seated = columns.get(probe);
                if (seated == null) {
                    target = probe;
                    break;
                }
                boolean free = true;
                for (Node other : seated) {
                    if (node.event.interval().conflictsWith(other.event.interval())) {
                        free = false;
                        break;
                    }
                }
                if (free) {
                    target = probe;
                    break;
                }
                probe++;
            }
            columns.computeIfAbsent(target, key -> new ArrayList<>()).add(node);
            columnOf.put(node.event.key(), target);
        }
    }

    /**
     * Splits a layer's events into the three block kinds, in draw order: events that had to be
     * moved down out of their own phase first, then the events that make the layer need several
     * columns, then the ones sitting alone in the layer.
     */
    private static List<LayoutGroup.Block> buildBlocks(List<Node> members) {
        List<LayoutEvent> spilled = new ArrayList<>();
        List<LayoutEvent> conflicting = new ArrayList<>();
        List<LayoutEvent> free = new ArrayList<>();

        for (Node node : members) {
            if (node.spilled) {
                spilled.add(node.event);
            } else if (node.conflicts.isEmpty()) {
                free.add(node.event);
            } else {
                conflicting.add(node.event);
            }
        }

        List<LayoutGroup.Block> blocks = new ArrayList<>();
        if (!spilled.isEmpty()) {
            blocks.add(new LayoutGroup.Block(LayoutGroup.BlockKind.SPILLED, spilled));
        }
        if (!conflicting.isEmpty()) {
            blocks.add(new LayoutGroup.Block(LayoutGroup.BlockKind.CONFLICTING, conflicting));
        }
        if (!free.isEmpty()) {
            blocks.add(new LayoutGroup.Block(LayoutGroup.BlockKind.FREE, free));
        }
        return blocks;
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    /** The layout of one day. */
    public static final class Result {

        private final List<LayoutEvent> events;
        private final List<LayoutGroup> groups;
        private final List<PlacedEvent> placements;
        private final Map<String, PlacedEvent> byKey;
        private final Map<String, Integer> groupIndexOfKey;

        Result(List<LayoutEvent> events, List<LayoutGroup> groups, List<PlacedEvent> placements) {
            this.events = Collections.unmodifiableList(events);
            this.groups = Collections.unmodifiableList(groups);
            this.placements = Collections.unmodifiableList(placements);
            this.byKey = Collections.unmodifiableMap(PlacedEvent.indexByKey(placements));

            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < groups.size(); i++) {
                for (LayoutEvent event : groups.get(i).events()) {
                    index.put(event.key(), i);
                }
            }
            this.groupIndexOfKey = Collections.unmodifiableMap(index);
        }

        /** @return the timed events that were laid out, in start order */
        public List<LayoutEvent> events() {
            return events;
        }

        /** @return the conflict groups, earliest first */
        public List<LayoutGroup> groups() {
            return groups;
        }

        /** @return every placement, in the order positions were assigned */
        public List<PlacedEvent> placements() {
            return placements;
        }

        /**
         * @param event an event that was passed to {@link #layout}
         * @return its placement, or {@code null} when it was all-day and therefore skipped
         */
        public PlacedEvent placementOf(LayoutEvent event) {
            return byKey.get(event.key());
        }

        /** @return the number of columns used by the busiest group; {@code 0} for an empty day */
        public int maxColumnsAnywhere() {
            int max = 0;
            for (LayoutGroup group : groups) {
                max = Math.max(max, group.columnCount());
            }
            return max;
        }

        /** @return the index of the group an event belongs to, or {@code -1} when absent */
        int groupIndexOf(LayoutEvent event) {
            Integer index = groupIndexOfKey.get(event.key());
            return index == null ? -1 : index;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("LayoutResult[").append(events.size())
                    .append(" events, ").append(groups.size()).append(" groups]\n");
            for (LayoutGroup group : groups) {
                sb.append(group);
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /** One way a layout violates its own guarantee. */
    public static final class Violation {

        private final String message;

        Violation(String message) {
            this.message = message;
        }

        /** @return a description of the two events that were drawn on top of each other */
        public String message() {
            return message;
        }

        @Override
        public String toString() {
            return message;
        }
    }

    /**
     * Checks the layout's central guarantee: two events that overlap in time must never claim
     * overlapping horizontal space.
     *
     * <p>This is the property the whole engine exists to provide, so callers — and the tests — can
     * assert it directly instead of comparing column numbers by hand on every scenario.
     *
     * @param result a result produced by {@link #layout}
     * @return the violations found; empty when the layout is sound
     */
    public static List<Violation> validate(Result result) {
        List<Violation> violations = new ArrayList<>();
        List<PlacedEvent> placements = result.placements();

        for (int i = 0; i < placements.size(); i++) {
            PlacedEvent a = placements.get(i);
            for (int j = i + 1; j < placements.size(); j++) {
                PlacedEvent b = placements.get(j);
                if (!a.event().interval().conflictsWith(b.event().interval())) {
                    continue;
                }
                // Events in different groups never conflict, by the way grouping is defined.
                if (result.groupIndexOf(a.event()) != result.groupIndexOf(b.event())) {
                    continue;
                }
                int aEnd = a.column() + a.columnSpan();
                int bEnd = b.column() + b.columnSpan();
                if (a.column() < bEnd && b.column() < aEnd) {
                    violations.add(new Violation("\"" + a.event().title() + "\" "
                            + a.event().interval() + " (col " + a.column() + "+" + a.columnSpan()
                            + ") is drawn over \"" + b.event().title() + "\" "
                            + b.event().interval() + " (col " + b.column() + "+" + b.columnSpan()
                            + ")"));
                }
            }
        }
        return violations;
    }
}
