package com.liuzheng.rrule;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Runnable examples of the API.
 *
 * <p>Run with {@code java -cp build/classes com.liuzheng.rrule.RRuleDemo} after
 * {@code bash run-tests.sh}, or from an IDE.
 *
 * <p>This class exists because a README that only shows a signature does not tell a reader what
 * the output looks like. Every example below prints the dates it produces.
 */
public final class RRuleDemo {

    private RRuleDemo() {
    }

    public static void main(String[] args) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.of(2026, 10, 12);

        show("每天，共 5 次", "FREQ=DAILY;COUNT=5", today, zone);
        show("每周一、三、五", "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=6", today, zone);
        show("每两周的周二和周四", "FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH;COUNT=4", today, zone);
        show("每月 1 日和 15 日", "FREQ=MONTHLY;BYMONTHDAY=1,15;COUNT=4",
                LocalDate.of(2026, 10, 1), zone);
        // DTSTART here is itself the last weekday of its month, so the series reads cleanly.
        show("每月最后一个工作日", "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=4",
                LocalDate.of(2026, 10, 30), zone);
        show("每月第二个周二", "FREQ=MONTHLY;BYDAY=2TU;COUNT=3",
                LocalDate.of(2026, 10, 13), zone);
        show("每年 12 月 25 日", "FREQ=YEARLY;BYMONTH=12;BYMONTHDAY=25;COUNT=3", today, zone);
        show("每年 1 月的第一个周五", "FREQ=YEARLY;BYMONTH=1;BYDAY=1FR;COUNT=3",
                LocalDate.of(2026, 1, 2), zone);
        show("2 月 29 日（只在闰年发生）", "FREQ=YEARLY;COUNT=3",
                LocalDate.of(2024, 2, 29), zone);
        show("每月 31 日（没有 31 日的月份跳过）", "FREQ=MONTHLY;COUNT=5",
                LocalDate.of(2026, 1, 31), zone);

        System.out.println();
        System.out.println("== 与时间相关的行为 ==");
        occurrenceDetails(zone);
        formatError();
    }

    private static void show(String title, String ruleText, LocalDate startDate, ZoneId zone) {
        ZonedDateTime start = startDate.atTime(9, 0).atZone(zone);
        RRule rule = RRule.parse(ruleText, start);
        List<RRuleOccurrence> occurrences = rule.expand(startDate, startDate.plusDays(800));

        System.out.println();
        System.out.println("── " + title);
        System.out.println("   RRULE      : " + ruleText);
        System.out.println("   DTSTART    : " + startDate + " 09:00 " + zone);
        System.out.println("   描述       : " + rule.humanReadable(java.util.Locale.SIMPLIFIED_CHINESE));
        System.out.print("   结果       :");
        occurrences.forEach(o -> System.out.print("  " + o.date()));
        System.out.println();
    }

    /** Shows what an occurrence carries, including how a DST gap is resolved. */
    private static void occurrenceDetails(ZoneId zone) {
        RRule rule = RRule.parse("FREQ=WEEKLY;BYDAY=MO;COUNT=1",
                LocalDate.of(2026, 10, 12).atTime(9, 30).atZone(zone));
        RRuleOccurrence occurrence = rule.expand(LocalDate.of(2026, 10, 12),
                LocalDate.of(2026, 10, 12)).get(0);

        System.out.println("   本地时间   : " + occurrence.date() + " " + occurrence.time());
        System.out.println("   时区       : " + occurrence.zone());
        System.out.println("   起始瞬间   : " + occurrence.startInstant());
        System.out.println("   90 分钟后的结束瞬间: " + occurrence.endInstant(90));

        // New York skips 02:00-03:00 on 8 March 2026, so the 02:30 instance moves to 03:30.
        ZoneId newYork = ZoneId.of("America/New_York");
        RRule daily = RRule.parse("FREQ=DAILY;COUNT=2",
                LocalDate.of(2026, 3, 7).atTime(2, 30).atZone(newYork));
        System.out.println("   夏令时跳变 : " + daily.expand(LocalDate.of(2026, 3, 7),
                LocalDate.of(2026, 3, 9)));
    }

    /** Shows the failure mode for a rule part this implementation refuses to guess at. */
    private static void formatError() {
        try {
            RRule.parse("FREQ=HOURLY", LocalDate.of(2026, 10, 12).atStartOfDay(
                    ZoneId.of("Asia/Shanghai")));
        } catch (RRuleException e) {
            System.out.println("   拒绝 HOURLY: " + e.getMessage());
        }
    }
}
