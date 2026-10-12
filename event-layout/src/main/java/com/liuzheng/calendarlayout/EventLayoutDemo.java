package com.liuzheng.calendarlayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runnable examples of the layout engine.
 *
 * <p>Run with {@code java -cp build/classes com.liuzheng.calendarlayout.EventLayoutDemo} after
 * {@code bash run-tests.sh}, or from an IDE.
 *
 * <p>Each scenario prints the day as an ASCII grid, because a column assignment is much easier to
 * judge when you can see it. The grid has one column per layout column and one row per 30 minutes.
 */
public final class EventLayoutDemo {

    private EventLayoutDemo() {
    }

    public static void main(String[] args) {
        scenario("两个日程完全重叠",
                day("09:00-10:00 站会", "09:30-10:30 评审"));

        scenario("首尾相连：不算冲突，共用整宽",
                day("09:00-10:00 站会", "10:00-11:00 评审"));

        scenario("传递冲突：首尾不重叠，仍归为一组",
                day("09:00-10:00 站会", "09:30-10:30 评审", "10:15-11:00 复盘"));

        scenario("密集上午 + 空闲下午：下午拿到整宽",
                day("09:00-09:30 站会", "09:20-09:50 评审", "09:25-09:40 电话",
                        "14:00-15:00 一对一"));

        scenario("长日程包住短日程：顺延到下一层，不覆盖",
                day("09:00-11:00 专注开发", "09:30-10:00 电话"));

        scenario("四重叠加：需要四栏",
                day("09:00-10:00 A", "09:05-10:05 B", "09:10-10:10 C", "09:15-10:15 D"));

        scenario("全天日程不参与分栏",
                Arrays.asList(LayoutEvent.allDay("holiday", "公司假日"),
                        LayoutEvent.at("standup", "站会", "09:00", "09:30")));

        System.out.println();
        System.out.println("列数、层数、时间片相位 Δt = "
                + EventLayoutEngine.DEFAULT_DELTA_MINUTES + " 分钟");
    }

    private static List<LayoutEvent> day(String... specs) {
        List<LayoutEvent> events = new ArrayList<>();
        for (int i = 0; i < specs.length; i++) {
            String[] parts = specs[i].split("\\s+", 2);
            String[] range = parts[0].split("-");
            String title = parts.length > 1 ? parts[1] : parts[0];
            events.add(LayoutEvent.at("e" + i, title, range[0], range[1]));
        }
        return events;
    }

    private static void scenario(String title, List<LayoutEvent> events) {
        EventLayoutEngine engine = new EventLayoutEngine();
        EventLayoutEngine.Result result = engine.layout(events);

        System.out.println();
        System.out.println("── " + title);

        if (result.events().isEmpty()) {
            System.out.println("   （没有参与分栏的日程）");
            return;
        }

        int columns = Math.max(1, result.maxColumnsAnywhere());
        int firstMinute = Integer.MAX_VALUE;
        int lastMinute = 0;
        for (LayoutEvent event : result.events()) {
            firstMinute = Math.min(firstMinute, event.interval().startMinute());
            lastMinute = Math.max(lastMinute, event.interval().endMinute());
        }
        int gridStart = (firstMinute / 30) * 30;
        int gridEnd = ((lastMinute + 29) / 30) * 30;

        System.out.println("   列数 = " + columns + "，组数 = " + result.groups().size());

        // One row per 30 minutes; a cell shows the index of the event occupying that slot.
        int slot = 1;
        List<LayoutEvent> indexed = new ArrayList<>(result.events());
        for (LayoutEvent event : indexed) {
            System.out.println("   [" + slot++ + "] " + event.title() + "  " + event.interval()
                    + "  ->  " + describe(result.placementOf(event)));
        }

        for (int minute = gridStart; minute < gridEnd; minute += 30) {
            System.out.print("   " + TimeInterval.format(minute) + " |");
            for (int column = 0; column < columns; column++) {
                String cell = "   ";
                for (int i = 0; i < indexed.size(); i++) {
                    LayoutEvent event = indexed.get(i);
                    PlacedEvent placement = result.placementOf(event);
                    if (placement.column() != column) {
                        continue;
                    }
                    boolean covers = event.interval().startMinute() < minute + 30
                            && minute < event.interval().endMinute();
                    if (covers) {
                        cell = " " + (i + 1) + " ";
                        break;
                    }
                }
                System.out.print(cell + "|");
            }
            System.out.println();
        }
    }

    private static String describe(PlacedEvent placement) {
        if (placement == null) {
            return "（不参与分栏）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("第 ").append(placement.column() + 1).append(" 栏");
        if (placement.columnSpan() > 1) {
            sb.append(" 跨 ").append(placement.columnSpan()).append(" 栏");
        }
        sb.append("，共 ").append(placement.totalColumns()).append(" 栏");
        sb.append("，层 ").append(placement.layer());
        if (placement.isSpilled()) {
            sb.append("（下移一层）");
        }
        if (!placement.conflicts().isEmpty()) {
            sb.append("，冲突 ").append(placement.conflicts().size()).append(" 个");
        }
        return sb.toString();
    }
}
