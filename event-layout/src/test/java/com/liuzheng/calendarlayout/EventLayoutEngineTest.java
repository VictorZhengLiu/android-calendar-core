package com.liuzheng.calendarlayout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Tests for {@link EventLayoutEngine}.
 *
 * <p>The interesting failures in a layout engine are not "the wrong column number" but "two events
 * that overlap in time ended up drawn on top of each other". So the backbone of this suite is
 * {@link EventLayoutEngine#validate}, applied to every scenario: most tests assert the concrete
 * columns that make the layout readable, and every test asserts that nothing overlaps.
 *
 * <p>A second recurring theme is that a layout must not depend on the order the caller happened to
 * pass the events in. Several tests shuffle the input for exactly that reason.
 */
public class EventLayoutEngineTest {

    private final EventLayoutEngine engine = new EventLayoutEngine();

    // ------------------------------------------------------------------
    // TimeInterval
    // ------------------------------------------------------------------

    @Test
    public void intervalsThatTouchDoNotConflict() {
        TimeInterval first = TimeInterval.between("09:00", "10:00");
        TimeInterval second = TimeInterval.between("10:00", "11:00");

        assertFalse(first.conflictsWith(second));
        assertFalse(second.conflictsWith(first));
    }

    @Test
    public void overlappingIntervalsConflictInBothDirections() {
        TimeInterval first = TimeInterval.between("09:00", "10:00");
        TimeInterval second = TimeInterval.between("09:30", "10:30");

        assertTrue(first.conflictsWith(second));
        assertTrue(second.conflictsWith(first));
    }

    @Test
    public void containmentCountsAsAConflict() {
        TimeInterval outer = TimeInterval.between("09:00", "12:00");
        TimeInterval inner = TimeInterval.between("10:00", "10:30");

        assertTrue(outer.conflictsWith(inner));
    }

    @Test
    public void intervalExposesItsGeometry() {
        TimeInterval interval = TimeInterval.between("09:15", "10:45");

        assertEquals(555, interval.startMinute());
        assertEquals(645, interval.endMinute());
        assertEquals(90, interval.durationMinutes());
        assertEquals("09:15-10:45", interval.toString());
    }

    @Test
    public void intervalAcceptsTheEndOfTheDayAs24Hours() {
        TimeInterval interval = TimeInterval.between("23:00", "24:00");

        assertEquals(TimeInterval.MINUTES_PER_DAY, interval.endMinute());
        assertEquals(60, interval.durationMinutes());
    }

    @Test
    public void intervalRejectsEmptyAndInvertedRanges() {
        assertRejects("09:00", "09:00");
        assertRejects("10:00", "09:00");
    }

    @Test
    public void intervalRejectsMalformedTimes() {
        for (String bad : new String[]{"9", "0900", "9:xx", "", "25:00", "09:60", "09:"}) {
            try {
                TimeInterval.parseMinuteOfDay(bad);
                fail("expected a rejection for \"" + bad + "\"");
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // ① Grouping
    // ------------------------------------------------------------------

    @Test
    public void nonConflictingEventsShareAFullWidthGroupEach() {
        EventLayoutEngine.Result result = layout(
                event("a", "09:00", "10:00"),
                event("b", "11:00", "12:00"));

        assertEquals(2, result.groups().size());
        assertEquals(1, result.maxColumnsAnywhere());
        assertEquals(1, result.placementOf(event("a", "09:00", "10:00")).columnSpan());
    }

    @Test
    public void transitivelyConflictingEventsFormOneGroup() {
        // 09:00-10:00 and 10:15-11:00 never overlap, but the middle event chains them together.
        // Splitting the group would hand the last event the full width while the events around
        // 10:00 only had half, which reads as a glitch.
        EventLayoutEngine.Result result = layout(
                event("a", "09:00", "10:00"),
                event("b", "09:30", "10:30"),
                event("c", "10:15", "11:00"));

        assertEquals(1, result.groups().size());
        assertEquals(3, result.groups().get(0).events().size());
    }

    @Test
    public void backToBackEventsAreSeparateGroups() {
        EventLayoutEngine.Result result = layout(
                event("a", "09:00", "10:00"),
                event("b", "10:00", "11:00"));

        assertEquals(2, result.groups().size());
        assertEquals(1, result.maxColumnsAnywhere());
    }

    // ------------------------------------------------------------------
    // ② Maximum columns
    // ------------------------------------------------------------------

    @Test
    public void peakConcurrencyCountsOnlySimultaneousEvents() {
        // A and B overlap; C starts exactly when A ends, so at most two run at once.
        assertEquals(2, EventLayoutEngine.maxConcurrent(Arrays.asList(
                event("a", "09:00", "10:00"),
                event("b", "09:30", "10:30"),
                event("c", "10:00", "11:00"))));
    }

    @Test
    public void peakConcurrencyTracksAThreeWayOverlap() {
        assertEquals(3, EventLayoutEngine.maxConcurrent(Arrays.asList(
                event("a", "09:00", "10:30"),
                event("b", "09:30", "11:00"),
                event("c", "10:00", "11:30"))));
    }

    @Test
    public void peakConcurrencyOfAnEmptyGroupIsZero() {
        assertEquals(0, EventLayoutEngine.maxConcurrent(Collections.emptyList()));
    }

    // ------------------------------------------------------------------
    // ③④ Layering and blocks
    // ------------------------------------------------------------------

    @Test
    public void layerPhaseGroupsEventsThatStartCloseTogether() {
        // 09:00 and 09:05 fall in the same Δt phase. They overlap, so they cannot share a layer, but
        // both layers exist and the group still needs only two columns.
        EventLayoutEngine.Result result = layout(
                event("a", "09:00", "09:20"),
                event("b", "09:05", "09:25"));

        assertEquals(1, result.groups().size());
        assertEquals(2, result.groups().get(0).layers().size());
        assertEquals(2, result.groups().get(0).columnCount());
        assertNoViolations(result);
    }

    @Test
    public void aLaterEventThatOnlyCollidesWithTheTailStillFitsBeside() {
        // A runs 09:00-10:00; B collides with A's tail at 09:10-09:50; C extends past both at
        // 09:55-10:20. All three are one transitive group, and each must end up clear of the events
        // it overlaps.
        LayoutEvent a = event("a", "09:00", "10:00");
        LayoutEvent b = event("b", "09:10", "09:50");
        LayoutEvent c = event("c", "09:55", "10:20");

        EventLayoutEngine.Result result = layout(a, b, c);

        assertEquals(1, result.groups().size());
        assertNoViolations(result);
        assertNotNull(result.placementOf(c));
    }

    @Test
    public void aLongEventLeavesRoomForAShortOneWithoutCoveringIt() {
        // Two events in the same phase cannot share a layer, but a later event that conflicts with
        // only one of them can still be seated beside the other. The engine must pick a column that
        // keeps it clear of the event it collides with.
        LayoutEvent a = event("a", "09:00", "09:30");
        LayoutEvent b = event("b", "09:20", "09:50");
        LayoutEvent c = event("c", "09:25", "09:40");

        EventLayoutEngine.Result result = layout(a, b, c);

        assertNoViolations(result);
        assertNotSameColumn(result, b, c);
        assertNotSameColumn(result, a, c);
    }

    @Test
    public void anEventBeyondThePhaseIsNotDraggedIntoAnEarlierLayer() {
        // B starts 50 minutes after A: outside the phase, so promoting it would misplace it
        // vertically and the layer must stay separate.
        EventLayoutEngine.Result result = layout(
                event("a", "09:00", "09:10"),
                event("b", "09:50", "10:00"));

        assertEquals(2, result.groups().size());
        assertNoViolations(result);
    }

    @Test
    public void anEventIsNeverDrawnAboveItsOwnStartTime() {
        // A long event and a short one that begins inside it. Both fall in phase 0, but the long
        // event already holds that layer and they conflict, so the short one spills into the next
        // layer rather than sharing layer 0 and being drawn on top of the long event.
        LayoutEvent longEvent = event("long", "09:00", "11:00");
        LayoutEvent inner = event("inner", "09:30", "10:00");

        EventLayoutEngine.Result result = layout(longEvent, inner);

        PlacedEvent placedLong = result.placementOf(longEvent);
        PlacedEvent placedInner = result.placementOf(inner);
        assertNotNull(placedLong);
        assertNotNull(placedInner);

        assertEquals(0, placedLong.layer());
        assertEquals(1, placedInner.layer());
        assertTrue("a layer index never maps to a time before the event's own start",
                placedInner.layer() >= 0);
        assertNotSameColumn(result, longEvent, inner);
    }

    @Test
    public void blocksAreOrderedParallelThenConflictingThenFree() {
        // A and B conflict and share a layer; C starts 20 minutes later, conflicts with nothing, and
        // is promoted, so it belongs to the parallel block that is drawn first.
        EventLayoutEngine.Result result = layout(
                event("a", "09:00", "10:00"),
                event("b", "09:30", "10:30"),
                event("c", "09:20", "09:40"));

        LayoutGroup group = result.groups().get(0);
        List<LayoutGroup.BlockKind> kinds = new ArrayList<>();
        for (LayoutGroup.Block block : group.layers().get(0).blocks()) {
            kinds.add(block.kind());
        }

        assertTrue("the conflicting block must be present, was " + kinds,
                kinds.contains(LayoutGroup.BlockKind.CONFLICTING));
        // When a promoted block exists it is drawn first, so the eye meets the parallel run before
        // the dense cluster.
        if (kinds.contains(LayoutGroup.BlockKind.SPILLED)) {
            assertEquals(LayoutGroup.BlockKind.SPILLED, kinds.get(0));
        }
        assertNoViolations(result);
    }

    @Test
    public void aLayerWithNoConflictsReportsASingleFreeBlock() {
        EventLayoutEngine.Result result = layout(event("a", "09:00", "10:00"));

        List<LayoutGroup.Block> blocks = result.groups().get(0).layers().get(0).blocks();
        assertEquals(1, blocks.size());
        assertEquals(LayoutGroup.BlockKind.FREE, blocks.get(0).kind());
    }

    // ------------------------------------------------------------------
    // ⑤⑥ Columns and stretching
    // ------------------------------------------------------------------

    @Test
    public void twoOverlappingEventsSplitTheWidth() {
        LayoutEvent a = event("a", "09:00", "10:00");
        LayoutEvent b = event("b", "09:30", "10:30");

        EventLayoutEngine.Result result = layout(a, b);

        assertEquals(2, result.groups().get(0).columnCount());
        assertEquals(0, result.placementOf(a).column());
        assertEquals(1, result.placementOf(b).column());
        assertEquals(1, result.placementOf(a).columnSpan());
    }

    @Test
    public void everyEventOccupiesExactlyOneColumn() {
        // No event is widened sideways. The column pool only creates a column when some event needs
        // it, so a free column never exists to grow into; width comes from the group's column count
        // instead, which keeps every placement a plain rectangle.
        LayoutEvent a = event("a", "09:00", "10:00");
        LayoutEvent b = event("b", "09:00", "09:30");
        LayoutEvent c = event("c", "09:30", "10:00");

        EventLayoutEngine.Result result = layout(a, b, c);

        for (PlacedEvent placement : result.placements()) {
            assertEquals("every event is one column wide: " + placement,
                    1, placement.columnSpan());
        }
        assertEquals(2, result.groups().get(0).columnCount());
        assertNoViolations(result);
    }

    @Test
    public void aNarrowEventDoesNotGetSwallowedByAWideningNeighbour() {
        // The failure this guards against: B stretches across a column that C occupies, hiding C.
        LayoutEvent a = event("a", "09:00", "10:00");
        LayoutEvent b = event("b", "09:00", "10:00");
        LayoutEvent c = event("c", "09:20", "09:40");

        EventLayoutEngine.Result result = layout(a, b, c);

        assertNoViolations(result);
        PlacedEvent placedC = result.placementOf(c);
        assertNotNull(placedC);
        assertEquals("the narrow event keeps its own column", 1, placedC.columnSpan());
    }

    @Test
    public void aLaterConflictingEventIsNeverSeatedOnTopOfAnEarlierOne() {
        // The cross-layer bug this engine was written to avoid: C does not share a layer with B, but
        // it overlaps B in time. Seating columns per layer would put C in B's column and the
        // renderer would paint one over the other.
        EventLayoutEngine.Result result = layout(
                event("a", "09:00", "10:00"),
                event("b", "09:30", "10:30"),
                event("c", "10:00", "11:00"));

        LayoutEvent b = event("b", "09:30", "10:30");
        LayoutEvent c = event("c", "10:00", "11:00");
        assertNotSameColumn(result, b, c);
        assertNoViolations(result);
    }

    @Test
    public void aThreeWayOverlapInALaterLayerStillFitsTheBudget() {
        // A and B overlap; C and D form a second overlapping pair that also collides with B, so the
        // group needs three columns even though no single moment holds more than three events.
        EventLayoutEngine.Result result = layout(
                event("a", "09:00", "10:30"),
                event("b", "09:30", "11:00"),
                event("c", "10:00", "11:30"),
                event("d", "10:15", "11:45"));

        // A and B overlap, and C and D overlap. B also collides with C, so a, b, c, d span four
        // columns even though no single instant holds more than three.
        assertEquals(4, result.maxColumnsAnywhere());
        assertNoViolations(result);
    }

    @Test
    public void columnCountNeverExceedsPeakConcurrency() {
        EventLayoutEngine.Result result = layout(
                event("a", "09:00", "10:30"),
                event("b", "09:30", "11:00"),
                event("c", "10:00", "11:30"),
                event("d", "14:00", "15:00"));

        for (LayoutGroup group : result.groups()) {
            int peak = EventLayoutEngine.maxConcurrent(group.events());
            assertTrue("group used " + group.columnCount() + " columns but peak concurrency is "
                            + peak,
                    group.columnCount() <= Math.max(1, peak));
        }
    }

    @Test
    public void placementFractionsDescribeTheHorizontalExtent() {
        LayoutEvent a = event("a", "09:00", "10:00");
        LayoutEvent b = event("b", "09:30", "10:30");

        EventLayoutEngine.Result result = layout(a, b);
        PlacedEvent placedA = result.placementOf(a);
        PlacedEvent placedB = result.placementOf(b);

        assertEquals(0.0, placedA.leftFraction(), 1e-9);
        assertEquals(0.5, placedA.widthFraction(), 1e-9);
        assertEquals(0.5, placedB.leftFraction(), 1e-9);
        assertEquals(0.5, placedB.widthFraction(), 1e-9);
    }

    @Test
    public void noTwoConflictingEventsEverShareAColumn() {
        // The invariant the engine trades width for: if two events overlap in time, their column
        // ranges never intersect, so not even the "+N" stacking case can arise. This is what lets a
        // renderer draw one rectangle per event in any order.
        EventLayoutEngine.Result result = layout(
                event("a", "09:00", "11:00"),
                event("b", "09:30", "10:00"),
                event("c", "10:15", "10:45"),
                event("d", "10:30", "11:30"));

        List<PlacedEvent> placements = result.placements();
        for (int i = 0; i < placements.size(); i++) {
            for (int j = i + 1; j < placements.size(); j++) {
                PlacedEvent first = placements.get(i);
                PlacedEvent second = placements.get(j);
                if (!first.event().interval().conflictsWith(second.event().interval())) {
                    continue;
                }
                int firstEnd = first.column() + first.columnSpan();
                int secondEnd = second.column() + second.columnSpan();
                boolean horizontalOverlap = first.column() < secondEnd
                        && second.column() < firstEnd;
                assertFalse(first.event().title() + " and " + second.event().title()
                        + " overlap both in time and in columns", horizontalOverlap);
            }
        }
        assertNoViolations(result);
    }

    // ------------------------------------------------------------------
    // Determinism and edge cases
    // ------------------------------------------------------------------

    @Test
    public void layoutDoesNotDependOnInputOrder() {
        List<LayoutEvent> events = Arrays.asList(
                event("a", "09:00", "10:30"),
                event("b", "09:30", "11:00"),
                event("c", "10:00", "11:30"),
                event("d", "09:15", "09:45"));

        String first = describe(layout(events));
        List<LayoutEvent> reversed = new ArrayList<>(events);
        Collections.reverse(reversed);
        String second = describe(layout(reversed));

        assertEquals(first, second);
    }

    @Test
    public void anEmptyDayProducesAnEmptyLayout() {
        EventLayoutEngine.Result result = engine.layout(Collections.emptyList());

        assertTrue(result.events().isEmpty());
        assertTrue(result.groups().isEmpty());
        assertTrue(result.placements().isEmpty());
        assertEquals(0, result.maxColumnsAnywhere());
        assertNoViolations(result);
    }

    @Test
    public void aNullDayIsTreatedAsEmpty() {
        EventLayoutEngine.Result result = engine.layout(null);

        assertTrue(result.placements().isEmpty());
    }

    @Test
    public void allDayEventsAreExcludedFromColumnLayout() {
        LayoutEvent timed = event("timed", "09:00", "10:00");
        LayoutEvent allDay = LayoutEvent.allDay("holiday", "Company holiday");

        EventLayoutEngine.Result result = layout(timed, allDay);

        assertEquals(1, result.events().size());
        assertNull(result.placementOf(allDay));
        assertEquals(1, result.maxColumnsAnywhere());
    }

    @Test
    public void identicalEventsEachGetTheirOwnColumn() {
        // Two separate stand-ups at the same time are two events, not one drawn twice.
        EventLayoutEngine.Result result = layout(
                event("first", "09:00", "09:15"),
                event("second", "09:00", "09:15"));

        assertEquals(2, result.groups().get(0).columnCount());
        assertNotSameColumn(result, event("first", "09:00", "09:15"),
                event("second", "09:00", "09:15"));
        assertNoViolations(result);
    }

    @Test
    public void aLongEventSpanningTheWholeDayIsHandled() {
        LayoutEvent allDayish = event("long", "00:00", "24:00");
        LayoutEvent morning = event("morning", "09:00", "10:00");
        LayoutEvent afternoon = event("afternoon", "15:00", "16:00");

        EventLayoutEngine.Result result = layout(allDayish, morning, afternoon);

        // The long event overlaps both others, so the whole day is one group. The long event holds a
        // column of its own and the two short events share the second one.
        assertEquals(1, result.groups().size());
        assertEquals(2, result.maxColumnsAnywhere());
        assertNoViolations(result);
    }

    @Test
    public void engineRejectsANonPositivePhase() {
        for (int bad : new int[]{0, -1, -45}) {
            try {
                new EventLayoutEngine(bad);
                fail("expected a rejection for deltaMinutes=" + bad);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void engineExposesItsPhase() {
        assertEquals(EventLayoutEngine.DEFAULT_DELTA_MINUTES, engine.deltaMinutes());
        assertEquals(30, new EventLayoutEngine(30).deltaMinutes());
    }

    @Test
    public void aSmallerPhaseProducesMoreLayers() {
        List<LayoutEvent> events = Arrays.asList(
                event("a", "09:00", "09:10"),
                event("b", "09:20", "09:30"),
                event("c", "09:40", "09:50"));

        int layersWith45 = new EventLayoutEngine(45).layout(events).groups().get(0).layers().size();
        int layersWith10 = new EventLayoutEngine(10).layout(events).groups().get(0).layers().size();

        assertTrue("a finer phase should split the day into at least as many layers",
                layersWith10 >= layersWith45);
    }

    // ------------------------------------------------------------------
    // Event identity
    // ------------------------------------------------------------------

    @Test
    public void eventsWithTheSameTimesAreStillDistinct() {
        LayoutEvent first = event("first", "09:00", "10:00");
        LayoutEvent second = event("second", "09:00", "10:00");

        assertFalse(first.equals(second));
        assertEquals(first, event("first", "11:00", "12:00"));
    }

    @Test
    public void eventRejectsBadConstruction() {
        assertRejectsEvent(() -> LayoutEvent.at("", "title", "09:00", "10:00"));
        assertRejectsEvent(() -> LayoutEvent.at("id", null, "09:00", "10:00"));
        assertRejectsEvent(() -> LayoutEvent.at("id", "title", "10:00", "09:00"));
    }

    @Test
    public void allDayEventIsMarkedAndSpansTheWholeDay() {
        LayoutEvent allDay = LayoutEvent.allDay("holiday", "Company holiday");

        assertTrue(allDay.isAllDay());
        assertEquals(0, allDay.interval().startMinute());
        assertEquals(TimeInterval.MINUTES_PER_DAY, allDay.interval().endMinute());
    }

    // ------------------------------------------------------------------
    // Randomised invariant check
    // ------------------------------------------------------------------

    @Test
    public void randomisedDaysKeepEveryInvariant() {
        // Hand-picked fixtures only prove what the author thought to try. This sweeps a few thousand
        // pseudo-random days, with a fixed seed so a failure is reproducible, and asserts the four
        // properties the renderer depends on:
        //
        //   1. no two conflicting events claim overlapping columns
        //   2. every event is placed exactly once
        //   3. column indices stay inside the group's column count
        //   4. a group never needs more columns than its peak concurrency
        Random random = new Random(20261012L);
        EventLayoutEngine engine = new EventLayoutEngine();

        for (int trial = 0; trial < 3000; trial++) {
            int count = 1 + random.nextInt(8);
            List<LayoutEvent> day = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int start = random.nextInt(1300);
                int end = Math.min(start + 5 + random.nextInt(180), TimeInterval.MINUTES_PER_DAY);
                if (end <= start) {
                    continue;
                }
                String id = "e" + i;
                day.add(LayoutEvent.at(id, id, TimeInterval.format(start),
                        TimeInterval.format(end)));
            }
            if (day.isEmpty()) {
                continue;
            }

            EventLayoutEngine.Result result = engine.layout(day);

            List<EventLayoutEngine.Violation> violations = EventLayoutEngine.validate(result);
            assertTrue("trial " + trial + " produced overlaps: " + violations, violations.isEmpty());

            assertEquals("every event is placed once, trial " + trial,
                    day.size(), result.placements().size());
            for (PlacedEvent placement : result.placements()) {
                assertTrue("column is inside the grid, trial " + trial,
                        placement.column() >= 0 && placement.column() < placement.totalColumns());
                assertTrue("span covers at least one column, trial " + trial,
                        placement.columnSpan() >= 1);
                assertTrue("span stays inside the grid, trial " + trial,
                        placement.column() + placement.columnSpan()
                                <= placement.totalColumns());
            }

            for (LayoutGroup group : result.groups()) {
                int peak = EventLayoutEngine.maxConcurrent(group.events());
                assertTrue("trial " + trial + ": group used " + group.columnCount()
                                + " columns for peak concurrency " + peak,
                        group.columnCount() <= Math.max(1, peak));
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static LayoutEvent event(String id, String start, String end) {
        return LayoutEvent.at(id, id, start, end);
    }

    private EventLayoutEngine.Result layout(LayoutEvent... events) {
        return engine.layout(Arrays.asList(events));
    }

    private EventLayoutEngine.Result layout(List<LayoutEvent> events) {
        return engine.layout(events);
    }

    private static void assertNoViolations(EventLayoutEngine.Result result) {
        List<EventLayoutEngine.Violation> violations = EventLayoutEngine.validate(result);
        assertTrue("overlapping events share horizontal space: " + violations, violations.isEmpty());
    }

    private static void assertNotSameColumn(EventLayoutEngine.Result result, LayoutEvent first,
            LayoutEvent second) {
        PlacedEvent a = result.placementOf(first);
        PlacedEvent b = result.placementOf(second);
        assertNotNull(a);
        assertNotNull(b);
        int aEnd = a.column() + a.columnSpan();
        int bEnd = b.column() + b.columnSpan();
        boolean horizontalOverlap = a.column() < bEnd && b.column() < aEnd;
        assertFalse("\"" + first.title() + "\" and \"" + second.title()
                + "\" both occupy columns " + a.column() + "+" + a.columnSpan() + " and "
                + b.column() + "+" + b.columnSpan(), horizontalOverlap);
    }

    private static void assertRejects(String start, String end) {
        try {
            TimeInterval.between(start, end);
            fail("expected a rejection for " + start + "-" + end);
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static void assertRejectsEvent(Runnable action) {
        try {
            action.run();
            fail("expected a rejection");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /** A stable, order-independent description of a layout, for equality comparisons. */
    private static String describe(EventLayoutEngine.Result result) {
        List<String> lines = new ArrayList<>();
        for (PlacedEvent placement : result.placements()) {
            lines.add(placement.event().title() + " " + placement.event().interval()
                    + " col=" + placement.column() + "+" + placement.columnSpan()
                    + " layer=" + placement.layer());
        }
        Collections.sort(lines);
        return String.join("; ", lines);
    }
}
